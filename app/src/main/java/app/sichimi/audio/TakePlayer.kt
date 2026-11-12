package app.sichimi.audio

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.sichimi.gate.Gear
import app.sichimi.songs.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** 녹음 듣기 상태. [key]: 무엇을 듣는가("mine:곡" 또는 "곡/uid"). */
data class TakePlayUi(val key: String? = null, val phase: Phase = Phase.IDLE, val message: String? = null) {
    enum class Phase { IDLE, LOADING, PLAYING, FAILED }
}

/** 녹음을 틀어도 되는 때: 기어 P, 주행 규제 없음, 혼자. */
data class TakeAllowance(val gear: Gear, val requiresDo: Boolean, val solo: Boolean) {
    val allowed: Boolean get() = gear == Gear.PARK && !requiresDo && solo
}

/**
 * 녹음 듣기. 앱에 든 곡 파일을 따로 하나 더 열어 반주 채널만 쓰고, 그 위에 녹음된 목소리를 곡 시각에 맞춰 섞는다
 * (노래방처럼 들린다). 원곡 가수 목소리와 박자 소리는 섞지 않는다.
 *
 * - 사용자가 「듣기」를 눌렀을 때만 시작한다. 스스로 시작하지 않는다.
 * - 기어가 P가 아니거나, 주행 규제가 걸리거나, 혼자가 아니게 되면 바로 멈춘다.
 * - 목소리가 끝나고 [TAIL_MS] 뒤에 멈춘다(긴 후주를 기다리지 않게).
 * - 메인 재생(문지기, 채점)과는 다른 플레이어라 기록과 점수에 섞이지 않는다.
 */
@OptIn(UnstableApi::class)
class TakePlayer(private val context: Context, private val scope: CoroutineScope, private val allowance: Flow<TakeAllowance>) {
    private val _state = MutableStateFlow(TakePlayUi())
    val state: StateFlow<TakePlayUi> = _state.asStateFlow()

    @Volatile private var allowed = false
    private var player: ExoPlayer? = null
    private var mixer: TakeMixProcessor? = null
    private var job: Job? = null
    private var watch: Job? = null

    fun start() {
        scope.launch {
            allowance.collect { a ->
                allowed = a.allowed
                if (!a.allowed && _state.value.phase != TakePlayUi.Phase.IDLE) {
                    Log.i(TAG, "녹음 듣기 멈춤: 기어=${a.gear} 주행규제=${a.requiresDo} 혼자=${a.solo}")
                    stop()
                }
            }
        }
    }

    /**
     * [key]의 녹음을 튼다. [load]는 AAC 바이트와 그 녹음의 곡 시작 시각을 돌려준다(서버에서 받거나 차 안 파일).
     * 메인 재생은 부르는 쪽이 먼저 멈춘다.
     */
    fun play(key: String, song: Song, load: suspend () -> Pair<ByteArray, Long>) {
        if (!allowed) { Log.w(TAG, "주차·혼자가 아니라 녹음을 틀지 않음"); return }
        stop()
        _state.value = TakePlayUi(key, TakePlayUi.Phase.LOADING)
        job = scope.launch {
            try {
                val (bytes, startMs) = load()
                val voice = withContext(Dispatchers.Default) { TakeCodec.decode(bytes) }
                if (!allowed || _state.value.key != key) return@launch
                begin(key, song, voice, startMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "녹음 불러오기 실패: $e")
                if (_state.value.key == key) _state.value = TakePlayUi(key, TakePlayUi.Phase.FAILED, "녹음을 불러오지 못했습니다")
            }
        }
    }

    private fun begin(key: String, song: Song, voice: ShortArray, startMs: Long) {
        val m = TakeMixProcessor()
        val voiceEndMs = startMs + voice.size * 1000L / TakeCodec.SAMPLE_RATE
        // 목소리 3초 앞부터(전주를 다 기다리지 않게). Ogg Opus 이동은 패킷(20ms) 단위라 그만큼 어긋날 수 있다.
        val from = (startMs - LEAD_MS).coerceAtLeast(0)
        m.setVoice(voice, startMs, from)
        val p = build(m)
        player = p; mixer = m
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_ENDED) stop()
            }
            override fun onPlayWhenReadyChanged(pwr: Boolean, reason: Int) {
                // 다른 소리(메인 재생, 내비 안내 등)가 오디오 포커스를 가져가면 멈춘다
                if (!pwr && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) { Log.i(TAG, "오디오 포커스 잃음 -> 멈춤"); stop() }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "녹음 재생 오류: $error"); stop()
                _state.value = TakePlayUi(key, TakePlayUi.Phase.FAILED, "녹음을 틀지 못했습니다")
            }
        })
        p.setMediaItem(MediaItem.fromUri(song.uri), from)
        p.prepare()
        p.play()
        _state.value = TakePlayUi(key, TakePlayUi.Phase.PLAYING)
        Log.i(TAG, "녹음 듣기 시작: $key 곡 ${from}ms부터, 목소리 ${startMs}~${voiceEndMs}ms")
        watch = scope.launch {
            while (true) {
                delay(500)
                val pos = player?.currentPosition ?: break
                if (pos > voiceEndMs + TAIL_MS) { Log.i(TAG, "목소리 끝 -> 멈춤"); stop(); break }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        watch?.cancel(); watch = null
        player?.let { it.stop(); it.release() }
        player = null; mixer = null
        _state.value = TakePlayUi()
    }

    private fun build(m: TakeMixProcessor): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(m)).build()
        }
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        return ExoPlayer.Builder(context, renderers).setAudioAttributes(attrs, true).setHandleAudioBecomingNoisy(true).build()
    }

    companion object {
        const val TAG = "SC.TakePlay"
        const val LEAD_MS = 3_000L
        const val TAIL_MS = 3_000L
    }
}

/**
 * 곡 파일(5·4·2채널 스템)의 반주만 2채널로 내보내고, 녹음된 목소리(16kHz 모노)를 같은 곡 시각에 더한다.
 * 곡 위치는 재생을 시작한(또는 옮긴) 자리에서부터 처리한 표본 수로 센다. 표본 단위로 맞는다.
 */
@OptIn(UnstableApi::class)
class TakeMixProcessor : BaseAudioProcessor() {
    @Volatile private var voice = ShortArray(0)
    @Volatile private var voiceStartMs = 0L
    @Volatile private var baseMs = 0L
    @Volatile private var voiceGain = 1f

    private var inChannels = 0
    private var sampleRate = 48_000
    private var frame = 0L // 오디오 스레드 전용: 곡의 지금 표본 위치(출력 표본률)
    private val ch = IntArray(5)

    fun setVoice(v: ShortArray, startMs: Long, playFromMs: Long) {
        voice = v; voiceStartMs = startMs; baseMs = playFromMs
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(inputAudioFormat)
        return when (inputAudioFormat.channelCount) {
            5, 4, 2 -> {
                inChannels = inputAudioFormat.channelCount
                sampleRate = inputAudioFormat.sampleRate
                AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)
            }
            else -> AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        frame = baseMs * sampleRate / 1000
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (inChannels * 2)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 4).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val v = voice
        val vsr = TakeCodec.SAMPLE_RATE.toDouble()
        val startSample = voiceStartMs * vsr / 1000
        repeat(frames) {
            for (i in 0 until inChannels) ch[i] = input.short.toInt()
            val accL: Int
            val accR: Int
            when (inChannels) {
                5 -> { accL = ch[StemMixProcessor.Ch5.ACC_L]; accR = ch[StemMixProcessor.Ch5.ACC_R] }
                4 -> { accL = ch[0]; accR = ch[1] }
                else -> { accL = ch[0]; accR = ch[0] }
            }
            // 목소리: 곡 시각을 16kHz 표본 위치로 바꿔 선형 보간
            val t = frame * vsr / sampleRate - startSample
            var vo = 0f
            if (t >= 0) {
                val k = t.toInt()
                if (k + 1 < v.size) {
                    val f = (t - k).toFloat()
                    vo = (v[k] * (1 - f) + v[k + 1] * f) * voiceGain
                }
            }
            out.putShort(clip(accL * ACC_GAIN + vo))
            out.putShort(clip(accR * ACC_GAIN + vo))
            frame++
        }
        out.flip()
    }

    override fun onReset() { inChannels = 0 }

    private fun clip(x: Float): Short = x.roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        /** 반주를 조금 줄여 목소리가 묻히지 않게 한다. 목소리는 압축할 때 이미 크기를 맞췄다. */
        const val ACC_GAIN = 0.7f
    }
}
