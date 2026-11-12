package app.sichimi.gate

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 원곡 보컬의 음높이 흐름(tools/make_pitch.py가 만든 pitch.u8). 한 프레임 = [hopMs]ms, 값 0 = 채점 안 함,
 * 그 밖 = MIDI 음 번호 × 2.
 */
class PitchRef(private val frames: ByteArray, val hopMs: Int = 20) {
    /** [songMs] 시각의 기준 음(MIDI). 채점하지 않는 곳이면 null. */
    fun noteAt(songMs: Long): Float? {
        val i = (songMs / hopMs).toInt()
        if (i < 0 || i >= frames.size) return null
        val v = frames[i].toInt() and 0xFF
        return if (v == 0) null else v / 2f
    }

    /** 곡 전체의 음표 프레임 수. */
    val noteFrames: Int = frames.count { it.toInt() != 0 }
}

/**
 * 노래 점수(노래방 점수처럼 100점 만점).
 *
 * - 마이크에서 찾은 음높이를 같은 순간의 원곡 음과 비교한다. 옥타브는 따지지 않는다(남녀가 한 옥타브 낮거나 높게 불러도 맞음).
 * - 박자 여유: 기준 시각 앞뒤 [slackMs] 안의 원곡 음 가운데 가장 가까운 것과 비교한다.
 * - 원곡에 음표가 있는 프레임마다: 반음 안이면 1점, 2반음 안이면 0.5점, 그 밖이나 부르지 않았으면 0점.
 *   점수 = 받은 점 ÷ 채점한 프레임 × 100.
 * - 채점한 프레임은 마이크가 켜져 있던 동안(혼자만의 노래방)의 원곡 음표 프레임뿐이다. 곡의 절반 넘게 채점해야 완주로 본다.
 */
class SingScore(private val ref: PitchRef, private val slackMs: Int = 200) {
    private var lastFrame = -1
    private var judged = 0
    private var credit = 0f

    /**
     * 마이크 한 토막. [songMs]는 그 소리가 불린 순간의 곡 위치, [sungMidi]는 찾은 음(없으면 부르지 않음).
     * 같은 프레임이 두 번 오면 한 번만 센다.
     */
    fun feed(songMs: Long, sungMidi: Float?) {
        val frame = (songMs / ref.hopMs).toInt()
        if (frame <= lastFrame) return
        lastFrame = frame
        val target = ref.noteAt(songMs) ?: return
        judged++
        if (sungMidi == null) return
        var best = semitoneDistance(sungMidi, target)
        var d = -slackMs
        while (d <= slackMs) {
            ref.noteAt(songMs + d)?.let { best = minOf(best, semitoneDistance(sungMidi, it)) }
            d += ref.hopMs
        }
        credit += when {
            best <= 1f -> 1f
            best <= 2f -> 0.5f
            else -> 0f
        }
    }

    /** 곡을 넘기거나 처음으로 돌아갔을 때(위치가 뒤로 감) 새로 센다. */
    fun reset() {
        lastFrame = -1; judged = 0; credit = 0f
    }

    val judgedFrames: Int get() = judged

    /** 지금까지의 점수(0~100). 채점한 프레임이 없으면 null. */
    val score: Int? get() = if (judged == 0) null else (credit / judged * 100).roundToInt()

    /** 곡의 음표 가운데 채점한 비율. */
    val coverage: Float get() = if (ref.noteFrames == 0) 0f else judged.toFloat() / ref.noteFrames

    /** 끝까지(절반 넘게) 불렀는가. 랭킹과 최고 기록은 완주한 곡만 센다. */
    val finished: Boolean get() = coverage >= 0.5f

    companion object {
        /** 옥타브를 무시한 두 음의 거리(반음, 0~6). */
        fun semitoneDistance(a: Float, b: Float): Float {
            val d = abs(a - b) % 12f
            return minOf(d, 12f - d)
        }

        fun hzToMidi(hz: Float): Float = (69 + 12 * ln(hz / 440.0) / ln(2.0)).toFloat()
    }
}

/**
 * YIN 음높이 검출(de Cheveigné & Kawahara 2002). 마이크 한 토막에서 기본 주파수를 찾는다.
 * 목소리 범위 65~1050Hz. 소리가 작거나 주기가 뚜렷하지 않으면 null. 기준 0.25: 원곡 보컬 스템으로 0.15는 87점, 0.25는 94점(검증기록 G5).
 */
class Yin(private val sampleRate: Int, private val threshold: Float = 0.25f) {
    private val minLag = sampleRate / 1050
    private val maxLag = sampleRate / 65

    /** [x]는 -1~1 실수 표본. 길이는 [maxLag]의 두 배 이상이어야 한다(16kHz면 약 500표본, 32ms). */
    fun pitchHz(x: FloatArray): Float? {
        val w = x.size - maxLag
        if (w <= minLag) return null
        val d = FloatArray(maxLag + 1)
        for (tau in 1..maxLag) {
            var s = 0f
            for (j in 0 until w) {
                val diff = x[j] - x[j + tau]
                s += diff * diff
            }
            d[tau] = s
        }
        // 누적 평균으로 정규화
        var running = 0f
        val cmnd = FloatArray(maxLag + 1)
        cmnd[0] = 1f
        for (tau in 1..maxLag) {
            running += d[tau]
            cmnd[tau] = if (running == 0f) 1f else d[tau] * tau / running
        }
        var tau = minLag
        while (tau <= maxLag) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 <= maxLag && cmnd[tau + 1] < cmnd[tau]) tau++
                // 포물선 보간
                val better = if (tau in 1 until maxLag) {
                    val a = cmnd[tau - 1]; val b = cmnd[tau]; val c = cmnd[tau + 1]
                    val den = a + c - 2 * b
                    if (den != 0f) tau + (a - c) / (2 * den) else tau.toFloat()
                } else tau.toFloat()
                return sampleRate / better
            }
            tau++
        }
        return null
    }
}
