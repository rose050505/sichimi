package app.sichimi.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import app.sichimi.gate.TakeBudget
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** 압축한 녹음. [startMs]: 압축된 소리의 첫 표본이 곡의 몇 ms인가(인코더 앞 지연을 뺀 값). */
class EncodedTake(val bytes: ByteArray, val startMs: Long, val lengthMs: Long, val bitrate: Int, val cut: Boolean)

/**
 * 목소리 녹음을 AAC-LC(16kHz 모노, ADTS)로 압축하고 푼다. Android에 든 MediaCodec만 쓴다.
 *
 * - ADTS는 프레임마다 7바이트 머리말이 붙은 날 AAC라, 담는 그릇(mp4) 없이 바로 이어 붙이고 바로 풀 수 있다.
 * - 크기는 [TakeBudget]이 정한다(900,000바이트 이하). 넘치면 비트레이트를 낮춰 다시 한다.
 * - AAC 인코더는 앞에 지연(프라이밍)을 넣는다. 압축한 것을 바로 다시 풀어 원래 소리와 맞대 보고 그만큼을 시작 시각에서 뺀다.
 */
object TakeCodec {
    private const val TAG = "SC.Take"
    const val SAMPLE_RATE = 16_000
    private const val SR = SAMPLE_RATE
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val TIMEOUT_US = 10_000L
    /** AudioSpecificConfig: AAC-LC(2), 16kHz(색인 8), 모노(1). */
    private val CSD = byteArrayOf(0x14, 0x08)

    /** [pcm]의 [from]..[to] 표본을 압축한다. [startMs]는 [from] 표본의 곡 시각. */
    fun encode(pcm: ShortArray, from: Int, to: Int, startMs: Long): EncodedTake {
        val gain = normalizeGain(pcm, from, to)
        var plan = TakeBudget.plan((to - from) * 1000L / SR)
        repeat(5) {
            val end = from + (plan.keepMs * SR / 1000).toInt().coerceAtMost(to - from)
            val t0 = System.currentTimeMillis()
            val bytes = encodeOnce(pcm, from, end, plan.bitrate, gain)
            Log.i(TAG, "압축 ${plan.bitrate / 1000}kbps, ${(end - from) * 1000L / SR}ms -> ${bytes.size}바이트 (${System.currentTimeMillis() - t0}ms)")
            if (bytes.size <= TakeBudget.MAX_BYTES) {
                val delay = runCatching { measureDelay(pcm, from, end, gain, decode(bytes)) }.getOrNull() ?: DEFAULT_DELAY
                Log.i(TAG, "인코더 앞 지연 ${delay}표본(${delay * 1000 / SR}ms)")
                return EncodedTake(bytes, startMs - delay * 1000L / SR, (end - from) * 1000L / SR, plan.bitrate, plan.cut)
            }
            plan = TakeBudget.shrink(plan)
        }
        error("녹음을 ${TakeBudget.MAX_BYTES}바이트 안에 넣지 못함")
    }

    /** 가장 큰 소리가 최대의 약 90%가 되게 키운다(마이크 소리가 작을 때). 최대 8배. */
    private fun normalizeGain(pcm: ShortArray, from: Int, to: Int): Float {
        var peak = 1
        for (i in from until to) { val a = abs(pcm[i].toInt()); if (a > peak) peak = a }
        return (0.9f * 32767 / peak).coerceIn(1f, 8f)
    }

    private fun encodeOnce(pcm: ShortArray, from: Int, to: Int, bitrate: Int, gain: Float): ByteArray {
        val fmt = MediaFormat.createAudioFormat(MIME, SR, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        val out = ByteArrayOutputStream((to - from) / SR * bitrate / 8 + 4096)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = from
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.clear()
                        val n = minOf(buf.capacity() / 2, to - pos, 2048)
                        for (k in 0 until n) buf.putShort((pcm[pos + k] * gain).toInt().coerceIn(-32768, 32767).toShort())
                        val pts = (pos - from) * 1_000_000L / SR
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n * 2, pts, 0)
                            pos += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val b = codec.getOutputBuffer(o)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        out.write(adtsHeader(info.size))
                        val tmp = ByteArray(info.size); b.get(tmp); out.write(tmp)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return out.toByteArray()
    }

    private fun adtsHeader(payload: Int): ByteArray {
        val len = payload + 7
        val profile = 2 // AAC-LC
        val freqIdx = 8 // 16kHz
        val chan = 1
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((profile - 1) shl 6) or (freqIdx shl 2) or (chan shr 2)).toByte(),
            (((chan and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /** ADTS AAC를 16kHz 모노 PCM으로 푼다. */
    fun decode(adts: ByteArray): ShortArray {
        val frames = ArrayList<IntArray>() // (시작, 길이) 머리말 뺀 본문
        var p = 0
        while (p + 7 <= adts.size) {
            if (adts[p] != 0xFF.toByte() || (adts[p + 1].toInt() and 0xF0) != 0xF0) error("ADTS 머리말이 아님: $p")
            val noCrc = adts[p + 1].toInt() and 1 == 1
            val len = ((adts[p + 3].toInt() and 3) shl 11) or ((adts[p + 4].toInt() and 0xFF) shl 3) or ((adts[p + 5].toInt() and 0xFF) shr 5)
            val head = if (noCrc) 7 else 9
            if (len < head || p + len > adts.size) break
            frames.add(intArrayOf(p + head, len - head))
            p += len
        }
        val fmt = MediaFormat.createAudioFormat(MIME, SR, 1).apply {
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(CSD))
        }
        val codec = MediaCodec.createDecoderByType(MIME)
        var out = ShortArray(frames.size * 1024 + 4096)
        var n = 0
        var channels = 1
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fi = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        if (fi >= frames.size) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val (s, l) = frames[fi].let { it[0] to it[1] }
                            buf.put(adts, s, l)
                            codec.queueInputBuffer(i, 0, l, fi * 1024L * 1_000_000 / SR, 0)
                            fi++
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val sb = b.asShortBuffer()
                    val count = sb.remaining() / channels
                    if (n + count > out.size) out = out.copyOf(maxOf(out.size * 2, n + count))
                    for (k in 0 until count) out[n + k] = sb.get(k * channels)
                    n += count
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return out.copyOf(n)
    }

    /**
     * 압축 전 소리와 푼 소리를 가장 큰 소리가 있는 0.5초 구간에서 맞대 보아 몇 표본 늦게 나오는지 찾는다.
     * 맞는 정도가 낮으면(0.5 미만) null.
     */
    private fun measureDelay(pcm: ShortArray, from: Int, to: Int, gain: Float, dec: ShortArray): Int? {
        val win = SR / 2
        val maxLag = 4096
        if (to - from < win * 2) return null
        // 가장 큰 구간
        var bestStart = from
        var bestE = -1.0
        var s = from
        while (s + win + maxLag < to) {
            var e = 0.0
            for (i in s until s + win step 4) { val v = pcm[i].toDouble(); e += v * v }
            if (e > bestE) { bestE = e; bestStart = s }
            s += win
        }
        if (bestE <= 0) return null
        val a = bestStart - from
        var best = 0
        var bestC = -1.0
        var ea = 0.0
        for (i in 0 until win) { val v = pcm[bestStart + i] * gain.toDouble(); ea += v * v }
        for (lag in 0..maxLag) {
            if (a + lag + win > dec.size) break
            var c = 0.0
            var eb = 0.0
            for (i in 0 until win) {
                val x = pcm[bestStart + i].toDouble() * gain
                val y = dec[a + lag + i].toDouble()
                c += x * y; eb += y * y
            }
            val nc = c / sqrt(ea * eb + 1e-9)
            if (nc > bestC) { bestC = nc; best = lag }
        }
        Log.i(TAG, "지연 맞대기: ${best}표본, 상관 ${"%.2f".format(bestC)}")
        return if (bestC >= 0.5) best else null
    }

    /** 맞대기가 안 될 때 쓰는 값(Android AAC-LC 인코더의 흔한 앞 지연, 1024표본 두 개). */
    private const val DEFAULT_DELAY = 2048
}
