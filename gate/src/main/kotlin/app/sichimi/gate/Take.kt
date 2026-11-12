package app.sichimi.gate

import kotlin.math.abs

/**
 * 한 곡을 부른 목소리 녹음을 곡 시각에 맞춰 모으는 버퍼(16kHz 모노 PCM).
 *
 * 마이크 토막(20ms)마다 "이 토막 가운데가 곡의 몇 ms인가"를 함께 받아 그 자리에 쓴다.
 * - 바로 앞 토막에 이어지는 자리(±[RESYNC_MS] 안)면 빈틈 없이 이어 쓴다(마이크 시계와 곡 시계의 작은 흔들림을 흡수).
 * - 그보다 멀면(일시정지, 노래방이 잠깐 풀려 마이크가 꺼졌다 켜짐) 새 자리로 건너뛴다. 사이는 무음(0)으로 남는다.
 * 녹음은 혼자만의 노래방에서 마이크가 켜져 있던 동안의 소리뿐이다. 반주나 원곡은 들어가지 않는다.
 */
class TakeTimeline(durationMs: Long, val sampleRate: Int = SR) {
    val pcm = ShortArray(((durationMs + 2_000) * sampleRate / 1000).toInt())
    private var writePos = -1
    /** 처음 쓴 표본, 마지막으로 쓴 표본 다음. 아무것도 안 썼으면 -1. */
    var first = -1
        private set
    var end = -1
        private set
    var jumps = 0
        private set

    /** [songMs]: 이 토막 가운데의 곡 시각. */
    fun write(songMs: Long, hop: ShortArray, n: Int = hop.size) {
        if (n <= 0) return
        val want = (songMs * sampleRate / 1000).toInt() - n / 2
        val tol = RESYNC_MS * sampleRate / 1000
        if (writePos < 0 || abs(want - writePos) > tol) {
            if (writePos >= 0) jumps++
            writePos = want
        }
        var src = 0
        var dst = writePos
        var len = n
        if (dst < 0) { src = -dst; len -= -dst; dst = 0 }
        if (dst + len > pcm.size) len = pcm.size - dst
        if (len > 0) {
            System.arraycopy(hop, src, pcm, dst, len)
            if (first < 0 || dst < first) first = dst
            if (dst + len > end) end = dst + len
        }
        writePos += n
    }

    val isEmpty: Boolean get() = first < 0 || end <= first

    /** 녹음이 시작되는 곡 시각(ms). */
    val startMs: Long get() = if (isEmpty) 0 else first * 1000L / sampleRate

    /** 녹음 길이(ms, 처음 쓴 곳부터 마지막 쓴 곳까지). */
    val lengthMs: Long get() = if (isEmpty) 0 else (end - first) * 1000L / sampleRate

    /**
     * 실제로 소리가 있는 구간(표본, 끝은 포함 안 함). 20ms 묶음의 크기(RMS)가 가장 큰 묶음의 [ratio]배를 넘는
     * 첫 묶음부터 마지막 묶음까지, 앞뒤로 [padMs] 여유를 둔다. 앞뒤의 작은 잡음(전주 동안 들어온 소리)을 뺀다.
     */
    fun voicedRange(ratio: Double = 0.1, padMs: Int = 500): IntRange? {
        if (isEmpty) return null
        val block = sampleRate / 50
        val n = (end - first) / block
        if (n == 0) return null
        val rms = DoubleArray(n) { b ->
            var sum = 0.0
            val s0 = first + b * block
            for (i in s0 until s0 + block) { val v = pcm[i].toDouble(); sum += v * v }
            kotlin.math.sqrt(sum / block)
        }
        val top = rms.max()
        if (top <= 0) return null
        val on = rms.indexOfFirst { it > top * ratio }
        val off = rms.indexOfLast { it > top * ratio }
        val pad = padMs * sampleRate / 1000
        val from = maxOf(first, first + on * block - pad)
        val to = minOf(end, first + (off + 1) * block + pad)
        return from until to
    }

    companion object {
        const val SR = 16_000
        const val RESYNC_MS = 60
    }
}

/**
 * 녹음 한 개를 Firestore 문서 한 개(상한 1 MiB)에 넣기 위한 크기 예산.
 *
 * - 보안 규칙의 녹음 상한은 [MAX_BYTES](900,000바이트). 인코더가 조금 넘칠 수 있어 [TARGET_BYTES]를 목표로 비트레이트를 고른다.
 * - 비트레이트는 [MAX_BPS](32kbps)에서 시작해 길이가 길면 낮춘다. [MIN_BPS](16kbps) 아래로는 내리지 않고, 그래도 넘으면 앞부분 [MAX_MS](7분)만 남긴다.
 *   앱에 든 세 곡은 모두 4분 미만이라 곡 전체가 들어간다(가장 긴 곡 3분 49초 → 약 29kbps).
 */
object TakeBudget {
    const val MAX_BYTES = 900_000
    const val TARGET_BYTES = 840_000
    const val MAX_BPS = 32_000
    const val MIN_BPS = 16_000
    const val MAX_MS = 420_000L

    data class Plan(val bitrate: Int, val keepMs: Long, val cut: Boolean)

    fun plan(lengthMs: Long): Plan {
        val keep = minOf(lengthMs, MAX_MS).coerceAtLeast(1)
        val bps = (TARGET_BYTES * 8L * 1000 / keep).coerceIn(MIN_BPS.toLong(), MAX_BPS.toLong()).toInt() / 1000 * 1000
        return Plan(bps, keep, lengthMs > MAX_MS)
    }

    /** 인코딩 결과가 [MAX_BYTES]를 넘었을 때 다음 시도: 비트레이트를 20% 내리고, 바닥이면 길이를 15% 줄인다. */
    fun shrink(p: Plan): Plan =
        if (p.bitrate > MIN_BPS) p.copy(bitrate = maxOf(MIN_BPS, (p.bitrate * 0.8).toInt() / 1000 * 1000))
        else p.copy(keepMs = (p.keepMs * 0.85).toLong(), cut = true)
}
