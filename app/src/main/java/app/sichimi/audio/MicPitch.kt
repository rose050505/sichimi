package app.sichimi.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import app.sichimi.gate.SingScore
import app.sichimi.gate.Yin
import kotlin.math.sqrt

/**
 * 마이크에서 20ms마다 음높이를 찾는다. 소리는 어디에도 저장하지 않고 음높이 숫자만 넘긴다.
 *
 * - 16kHz 모노. 40ms 창을 20ms씩 옮기며 YIN으로 찾는다.
 * - 너무 작은 소리(약 -45dBFS 아래)는 부르지 않은 것으로 본다.
 * - 버퍼가 모두 0이면(다른 앱이 앞에 있어 시스템이 마이크를 막았거나 입력 장치가 없음) "들을 수 없음"으로 알린다.
 *   이때는 채점하지 않는다(부르지 않은 것과 구별).
 * [onFrame]은 마이크 스레드에서 불린다: (그 소리가 들어온 시각 System.nanoTime, 찾은 음 MIDI 또는 null, 들을 수 있었는가).
 */
class MicPitch(private val onFrame: (atNanos: Long, midi: Float?, heard: Boolean) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // 부르는 쪽(SingCoach)이 권한을 확인한다
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "SC.Mic").apply { start() }
        Log.i(TAG, "마이크 켬")
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.join(500)
        thread = null
        Log.i(TAG, "마이크 끔")
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, HOP * 8) * 2)
        }.getOrElse { Log.w(TAG, "AudioRecord 만들기 실패: $it"); running = false; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord 초기화 실패"); rec.release(); running = false; return
        }
        val yin = Yin(SR)
        val hop = ShortArray(HOP)
        val win = FloatArray(WIN)
        var filled = 0
        var frames = 0L
        var heardFrames = 0L
        var pitched = 0L
        rec.startRecording()
        val startNanos = System.nanoTime()
        try {
            while (running) {
                var got = 0
                while (got < HOP && running) {
                    val n = rec.read(hop, got, HOP - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < HOP) break
                // 이 토막의 마지막 표본이 들어온 시각: 지금에서 AudioRecord 안에 쌓인 만큼을 뺀다(대략).
                val at = System.nanoTime() - HOP_NANOS / 2
                // 창을 한 토막 밀고 새 토막을 붙인다
                System.arraycopy(win, HOP, win, 0, WIN - HOP)
                var sum = 0.0
                var allZero = true
                for (i in 0 until HOP) {
                    val v = hop[i] / 32768f
                    win[WIN - HOP + i] = v
                    sum += v * v
                    if (hop[i].toInt() != 0) allZero = false
                }
                filled = minOf(WIN, filled + HOP)
                frames++
                val heard = !allZero
                if (heard) heardFrames++
                val rms = sqrt(sum / HOP).toFloat()
                val midi = if (heard && filled == WIN && rms > MIN_RMS) yin.pitchHz(win)?.let { SingScore.hzToMidi(it) } else null
                if (midi != null) pitched++
                onFrame(at, midi, heard)
                if (frames % 250 == 0L) {
                    Log.i(TAG, "${(System.nanoTime() - startNanos) / 1_000_000_000}초: 토막 $frames, 들림 $heardFrames, 음 찾음 $pitched")
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    companion object {
        const val TAG = "SC.Mic"
        const val SR = 16_000
        const val HOP = 320 // 20ms
        const val WIN = 640 // 40ms
        const val HOP_NANOS = 20_000_000L
        const val MIN_RMS = 0.0056f // 약 -45dBFS
    }
}
