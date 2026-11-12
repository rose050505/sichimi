package app.sichimi.audio

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 스템 한 파일을 2채널로 섞으면서 보컬에만 음량을 곱한다.
 *
 * - 5채널 입력: 4채널과 같고 박자 세기(카운트인) 드럼 스틱 소리 한 채널이 더 있다. 채널 순서는 [Ch5]
 * - 4채널 입력: 0·1 = 반주 좌·우, 2·3 = 보컬 좌·우
 * - 2채널 입력: 0 = 반주(모노), 1 = 보컬(모노)  (에뮬레이터가 4채널을 못 풀 때의 대안)
 *
 * 음량은 표본마다 조금씩 옮겨 가므로 딸깍 소리가 나지 않는다.
 * [setVocalGain]은 아무 스레드에서나 부를 수 있다. 오디오 스레드가 다음 버퍼에서 반영한다.
 *
 * 박자 세기 소리는 [setCountInAudible]이 켜 둔 동안만 섞는다(혼자만의 노래방일 때만). 끌 때는 [CLICK_RAMP_MS] 안에 사라진다.
 * 박자 소리가 곡 파일 안에 들어 있어서 곡의 박자와 표본 단위로 맞는다.
 */
@OptIn(UnstableApi::class)
class StemMixProcessor : BaseAudioProcessor() {

    /** 요청: 목표 음량, 걸리는 시간, 요청 번호. 한 객체로 바꿔 끼워 원자적으로 넘긴다. */
    private class Request(val target: Float, val durationMs: Long, val seq: Long)

    @Volatile private var request = Request(1f, 0, 0)
    private var appliedSeq = 0L

    // 오디오 스레드 전용
    private var gain = 1f
    private var step = 0f
    private var target = 1f
    private var inChannels = 0
    private var sampleRate = 44_100

    /** 마지막 요청을 오디오 스레드가 처음 반영한 시각(elapsedRealtime)과 그 요청 번호. */
    @Volatile var lastAppliedAtMs: Long = 0L
        private set
    @Volatile var lastAppliedSeq: Long = 0L
        private set

    /** 지금 오디오 스레드가 곱하고 있는 보컬 음량(화면 표시용). */
    @Volatile var currentGain: Float = 1f
        private set

    private var seqCounter = 0L

    // ---------- 고스트 목소리(v2) ----------
    /** 고스트: 다른 사람이 올린 목소리(16kHz 모노)와 그 첫 표본의 곡 시각. 한 객체로 바꿔 끼운다. */
    private class Ghost(val pcm: ShortArray, val startMs: Long, val level: Float, val guideGain: Float)
    @Volatile private var ghost: Ghost? = null
    /** 메인 스레드가 정한 곡 위치(이동·곡 바뀜 뒤 다시 맞춤). */
    private class Anchor(val posMs: Long, val seq: Long)
    @Volatile private var anchor = Anchor(0, 0)
    private var anchorSeq = 0L // 오디오 스레드
    private var framePos = -1L // 오디오 스레드: 지금 처리하는 곡 위치(출력 표본). -1 = 모름(고스트 소리 안 냄)
    @Volatile var processedPosMs: Long = -1L
        private set
    /** 지금 고스트에 곱하는 음량(로그·시험용). */
    @Volatile var currentGhostGain: Float = 0f
        private set

    /**
     * 고스트 목소리를 건다. 실제 음량 = [level] × (1 − 지금 가수 음량)/(1 − [guideGain]).
     * 가수가 100%로 돌아오는 램프와 같은 표본에서 고스트도 0이 된다(동승자가 타면 즉시 사라짐).
     */
    fun setGhost(pcm: ShortArray, startMs: Long, level: Float, guideGain: Float) { ghost = Ghost(pcm, startMs, level, guideGain) }
    fun clearGhost() { ghost = null }
    val hasGhost: Boolean get() = ghost != null

    /** 곡 위치를 다시 맞춘다(메인 스레드가 재생 위치를 보고 부른다). */
    @Synchronized
    fun reanchor(posMs: Long) { anchor = Anchor(posMs, anchor.seq + 1) }

    @Volatile private var clickWanted = false
    private var clickGain = 0f // 오디오 스레드 전용
    private val frame5 = IntArray(5) // 오디오 스레드 전용

    /** 박자 세기 소리를 섞을지. 아무 스레드에서나 부를 수 있다. */
    fun setCountInAudible(on: Boolean) {
        clickWanted = on
    }

    /** 보컬 음량을 [target]까지 [durationMs]에 걸쳐 옮긴다. 요청 번호를 돌려준다. */
    @Synchronized
    fun setVocalGain(target: Float, durationMs: Long): Long {
        val seq = ++seqCounter
        request = Request(target.coerceIn(0f, 1f), durationMs.coerceAtLeast(0), seq)
        return seq
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        android.util.Log.i("SC.Mixer", "입력 ${inputAudioFormat.channelCount}채널 ${inputAudioFormat.sampleRate}Hz enc=${inputAudioFormat.encoding}")
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(inputAudioFormat)
        return when (inputAudioFormat.channelCount) {
            5, 4, 2 -> {
                inChannels = inputAudioFormat.channelCount
                sampleRate = inputAudioFormat.sampleRate
                AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
            }
            else -> AudioFormat.NOT_SET // 스템 파일이 아니면 건드리지 않는다
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = inChannels * 2
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        pickUpRequest()
        val a = anchor
        if (a.seq != anchorSeq) { anchorSeq = a.seq; framePos = a.posMs * sampleRate / 1000 }
        val g = ghost
        val gsr = 16_000.0
        val gStart = if (g != null) g.startMs * gsr / 1000 else 0.0
        var ghostGain = 0f
        val clickTarget = if (clickWanted) 1f else 0f
        val clickStep = 1000f / (CLICK_RAMP_MS * sampleRate)
        val out = replaceOutputBuffer(frames * 4).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        repeat(frames) {
            val accL: Int
            val accR: Int
            val vocL: Int
            val vocR: Int
            var click = 0
            if (inChannels == 5) {
                val ch = frame5
                for (i in 0 until 5) ch[i] = input.short.toInt()
                accL = ch[Ch5.ACC_L]; accR = ch[Ch5.ACC_R]
                vocL = ch[Ch5.VOC_L]; vocR = ch[Ch5.VOC_R]
                click = ch[Ch5.CLICK]
            } else if (inChannels == 4) {
                accL = input.short.toInt(); accR = input.short.toInt()
                vocL = input.short.toInt(); vocR = input.short.toInt()
            } else {
                accL = input.short.toInt(); accR = accL
                vocL = input.short.toInt(); vocR = vocL
            }
            if (gain != target) {
                gain += step
                if ((step > 0 && gain >= target) || (step < 0 && gain <= target) || step == 0f) gain = target
            }
            if (clickGain != clickTarget) {
                clickGain = if (clickGain < clickTarget) minOf(clickTarget, clickGain + clickStep) else maxOf(clickTarget, clickGain - clickStep)
            }
            val c = click * clickGain
            var gv = 0f
            if (g != null && framePos >= 0) {
                ghostGain = app.sichimi.gate.GhostGain.follow(gain, g.guideGain, g.level)
                if (ghostGain > 0f) {
                    val t = framePos * gsr / sampleRate - gStart
                    if (t >= 0) {
                        val k = t.toInt()
                        if (k + 1 < g.pcm.size) {
                            val f = (t - k).toFloat()
                            gv = (g.pcm[k] * (1 - f) + g.pcm[k + 1] * f) * ghostGain
                        }
                    }
                }
            }
            if (framePos >= 0) framePos++
            out.putShort(clip(accL + vocL * gain + c + gv))
            out.putShort(clip(accR + vocR * gain + c + gv))
        }
        currentGain = gain
        currentGhostGain = ghostGain
        processedPosMs = if (framePos >= 0) framePos * 1000 / sampleRate else -1
        out.flip()
    }

    private fun pickUpRequest() {
        val r = request
        if (r.seq == appliedSeq) return
        appliedSeq = r.seq
        target = r.target
        val samples = (r.durationMs * sampleRate / 1000f).coerceAtLeast(1f)
        step = (target - gain) / samples
        if (abs(step) < 1e-7f) gain = target
        lastAppliedAtMs = SystemClock.elapsedRealtime()
        lastAppliedSeq = r.seq
    }

    override fun onFlush() {
        // 이동했으니 곡 위치를 모른다: 메인 스레드가 다시 맞출 때까지 고스트는 쉰다
        framePos = -1L
        processedPosMs = -1L
        // 위치를 옮겨도 음량은 이어진다. 남은 이동은 바로 끝낸다.
        val r = request
        gain = r.target
        target = gain
        step = 0f
        if (r.seq != appliedSeq) {
            appliedSeq = r.seq
            lastAppliedAtMs = SystemClock.elapsedRealtime()
            lastAppliedSeq = r.seq
        }
    }

    override fun onReset() {
        inChannels = 0
        clickGain = 0f
    }

    /**
     * 5채널 파일의 채널 순서. tools/pack_stems.py가 5.0 배치(FL FR FC BL BR)로 묶은 Opus(매핑 1)를
     * 에뮬레이터가 이 순서로 풀어 준다(검증기록 "박자 세기", 채널마다 다른 높이 소리로 확인).
     */
    object Ch5 {
        const val ACC_L = 0
        const val ACC_R = 1
        const val CLICK = 2
        const val VOC_L = 3
        const val VOC_R = 4
    }

    companion object {
        const val CLICK_RAMP_MS = 10f
    }

    private fun clip(v: Float): Short = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
