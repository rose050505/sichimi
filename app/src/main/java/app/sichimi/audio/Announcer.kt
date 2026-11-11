package app.sichimi.audio

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 한 줄 음성(TextToSpeech SDK 3.3.1, ON_DEVICE).
 * ON_DEVICE는 Playground 앱 등록이 필요 없어 클라이언트 ID·시크릿을 앱에 넣지 않는다.
 * 내비 안내 음성이 나오는 중이면 끝난 뒤에 말한다(겹치지 않게). 최대 [MAX_WAIT_MS]까지 기다린다.
 */
class Announcer(context: Context) {
    private val tag = "SC.Tts"
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var waiting: String? = null
    private val giveUp = Runnable { waiting?.let { Log.i(tag, "내비 안내가 길어 더 기다리지 않음"); speakNow(it) } }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val line = waiting ?: return
            if (!naviSpeaking(configs)) main.postDelayed({ if (waiting == line && !naviSpeaking()) speakNow(line) }, 400)
        }
    }

    companion object { const val MAX_WAIT_MS = 8_000L }

    private fun naviSpeaking(configs: List<AudioPlaybackConfiguration> = audio.activePlaybackConfigurations) =
        configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
    @Volatile private var ready = false
    private var pending: String? = null

    private val events = object : EventListener {
        override fun onReady() = Log.i(tag, "준비").let { }
        override fun onStart() = Log.i(tag, "말하기 시작").let { }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = Log.i(tag, "말하기 끝").let { }
        override fun onStop() = Log.i(tag, "멈춤").let { }
        override fun onError(errorType: ErrorType) = Log.w(tag, "오류 $errorType").let { }
    }

    fun start() {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    Log.i(tag, "초기화 성공")
                    tts.addEventListener(events)
                    runCatching { tts.setLocale(Locale.KOREA) }.onFailure { Log.w(tag, "setLocale 실패: $it") }
                    ready = true
                    pending?.let { pending = null; say(it) }
                }
                override fun onFailure(errorType: ErrorType) = Log.w(tag, "초기화 실패 $errorType").let { }
            })
        }.onFailure { Log.w(tag, "초기화 예외: $it") }
    }

    fun say(text: String) {
        if (!ready) { pending = text; Log.i(tag, "아직 준비 전, 보류: $text"); return }
        if (naviSpeaking()) {
            Log.i(tag, "내비 안내 중이라 끝난 뒤에 말함: $text")
            waiting = text
            audio.registerAudioPlaybackCallback(playback, main)
            main.postDelayed(giveUp, MAX_WAIT_MS)
            return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        if (waiting != null) {
            waiting = null
            main.removeCallbacks(giveUp)
            runCatching { audio.unregisterAudioPlaybackCallback(playback) }
        }
        Log.i(tag, "말하기 요청: $text")
        runCatching { tts.speak(text, true) }.onFailure { Log.w(tag, "speak 실패: $it") }
    }
}
