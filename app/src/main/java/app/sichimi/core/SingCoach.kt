package app.sichimi.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.sichimi.audio.Announcer
import app.sichimi.audio.MicPitch
import app.sichimi.audio.TakeCodec
import app.sichimi.gate.TakeTimeline
import app.sichimi.rank.TakeStore
import kotlinx.coroutines.Dispatchers
import app.sichimi.data.RecordDao
import app.sichimi.data.SongScore
import app.sichimi.gate.GateState
import app.sichimi.gate.PitchRef
import app.sichimi.gate.SingScore
import app.sichimi.rank.RankingClient
import app.sichimi.settings.SettingsStore
import app.sichimi.songs.SongSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 방금 끝난 곡의 점수. 주차 화면의 "지난번의 나와 대결"에 쓴다. */
data class ScoreResult(
    val songId: String,
    val title: String,
    val score: Int,
    val finished: Boolean,
    /** 이번 곡 전의 내 최고 점수(완주한 곡만). 처음이면 null. */
    val previousBest: Int?,
)

/**
 * 노래 점수. 혼자만의 노래방이고, 재생 중이고, 설정에서 켰고, 마이크 권한이 있을 때만 마이크를 켠다.
 *
 * - 곡마다 [SingScore]를 하나 만들고, 마이크 음을 그 순간의 곡 위치와 맞춰 넣는다.
 * - 곡이 바뀌거나 끝나면 점수를 저장하고, 그때 혼자만의 노래방이면 한 줄을 말한다(운전 중 화면에는 점수를 띄우지 않는다).
 * - 곡 위치: 재생 서비스의 위치를 메인 스레드에서 50ms마다 읽어 두고, 마이크 스레드는 그 뒤 흐른 시간만큼 더한다.
 * - 녹음: 「전국 랭킹에 올리기」를 켰을 때만 마이크 소리를 곡 시각에 맞춰 모은다([TakeTimeline]).
 *   완주해서 그 곡의 최고 기록이 되면 압축해 차 안에 둔다([TakeStore]). 서버에는 사용자가 「녹음 올리기」를 눌러야 간다.
 *   녹음이 실패해도 점수 저장과 점수 올리기는 그대로 된다.
 */
class SingCoach(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: GateController,
    private val settings: SettingsStore,
    private val songs: SongSource,
    private val dao: RecordDao,
    private val announcer: Announcer,
    private val ranking: RankingClient,
    private val takes: TakeStore,
    private val positionMs: () -> Long,
) {
    private val main = Handler(Looper.getMainLooper())
    private val mic = MicPitch(::onMicFrame, ::onMicPcm)

    private class Clock(val atNanos: Long, val posMs: Long, val playing: Boolean)
    @Volatile private var clock = Clock(0, 0, false)

    /** probes: 마이크 지연을 여러 값으로 가정한 점수(검증용 로그. 실제 점수는 [score]). */
    private class Current(val itemKey: String, val songId: String, val score: SingScore, val durationMs: Long,
                          val probes: List<Pair<Long, SingScore>>, val take: TakeTimeline?)
    @Volatile private var current: Current? = null
    private var lastKey: String? = null // 메인 스레드
    private var lastPos = 0L // 메인 스레드
    private val lock = Any()

    private val _last = MutableStateFlow<ScoreResult?>(null)
    val lastResult: StateFlow<ScoreResult?> = _last.asStateFlow()

    private val ticker = object : Runnable {
        override fun run() {
            val p = controller.player.value
            clock = Clock(System.nanoTime(), positionMs(), p.isPlaying)
            current?.let { c ->
                if (!p.isPlaying && clock.posMs >= c.durationMs - 1_500) finish("곡 끝")
                else if (p.itemKey == c.itemKey && clock.posMs < lastPos - 3_000) { finish("앞으로 되감음"); begin(c.itemKey, c.songId) }
            }
            lastPos = clock.posMs
            main.postDelayed(this, 50)
        }
    }

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        main.post(ticker)
        // 지난번에 올리지 못한 최고 점수(터널, 오프라인)가 있으면 다시 올린다. 서버 점수가 같거나 높으면 쓰지 않는다.
        if (settings.state.value.rankUpload) uploadAllBests()
        scope.launch {
            combine(controller.ui, controller.player, settings.state) { ui, p, s -> Triple(ui.state, p, s.scoring) }
                .collect { (state, p, on) ->
                    // 곡이 바뀌면 앞 곡을 마무리하고 새로 센다
                    val key = p.itemKey
                    if (key != lastKey) {
                        finish("곡 바뀜")
                        lastKey = key
                        if (key != null && p.songId != null) begin(key, p.songId)
                    }
                    val want = on && state == GateState.KARAOKE && p.isPlaying && current != null && hasPermission()
                    if (want && !mic.isRunning) mic.start() else if (!want && mic.isRunning) mic.stop()
                }
        }
    }

    private fun begin(itemKey: String, songId: String) {
        val song = songs.find(songId) ?: return
        val ref = songs.pitch(song) ?: return
        val pr = PitchRef(ref)
        lastPos = 0L
        synchronized(lock) {
            val record = settings.state.value.rankUpload && ranking.configured
            current = Current(itemKey, songId, SingScore(pr), song.durationMs, LAG_PROBES.map { it to SingScore(pr) },
                if (record) runCatching { TakeTimeline(song.durationMs) }.getOrNull() else null)
        }
    }

    /** 마이크 스레드. */
    private fun onMicFrame(atNanos: Long, midi: Float?, heard: Boolean) {
        if (!heard) return // 시스템이 마이크를 막았거나 입력이 없다: 채점하지 않는다
        val c = clock
        if (!c.playing) return
        val songMs = c.posMs + (atNanos - c.atNanos) / 1_000_000 - MIC_LAG_MS
        synchronized(lock) {
            val cur = current ?: return
            cur.score.feed(songMs, midi)
            for ((lag, s) in cur.probes) s.feed(songMs + MIC_LAG_MS - lag, midi)
        }
    }

    /** 마이크 스레드. 녹음을 켰을 때만 소리를 곡 시각 자리에 둔다. */
    private fun onMicPcm(atNanos: Long, hop: ShortArray) {
        val c = clock
        if (!c.playing) return
        val songMs = c.posMs + (atNanos - c.atNanos) / 1_000_000 - MIC_LAG_MS
        synchronized(lock) { current?.take?.write(songMs, hop) }
    }

    private fun finish(why: String) {
        val c = synchronized(lock) { current.also { current = null } } ?: return
        val score = c.score.score ?: return
        if (c.score.judgedFrames < MIN_JUDGED) return // 음표 5초도 안 들었으면 점수로 치지 않는다
        val finished = c.score.finished
        val title = songs.find(c.songId)?.title ?: c.songId
        val karaokeNow = controller.ui.value.state == GateState.KARAOKE
        Log.i(TAG, "점수 $why: ${c.songId} ${score}점, 채점 ${c.score.judgedFrames}프레임(곡 음표의 ${(c.score.coverage * 100).toInt()}%), 완주=$finished")
        Log.i(TAG, "지연 가정별 점수: " + c.probes.joinToString(" ") { (lag, s) -> "${lag}ms=${s.score}" })
        scope.launch {
            val best = dao.bestScore(c.songId)
            dao.insertScore(SongScore(songId = c.songId, at = System.currentTimeMillis(), score = score,
                coverage = c.score.coverage, finished = finished))
            _last.value = ScoreResult(c.songId, title, score, finished, best)
            if (karaokeNow && finished) announcer.say(line(title, score, best))
            if (finished && (best == null || score > best)) {
                upload(c.songId, score)
                c.take?.let { keepTake(c.songId, score, it, takeShiftMs(c)) }
            }
        }
    }

    /** 최고 기록이 된 판의 녹음을 압축해 차 안에 둔다. 실패해도 점수에는 영향이 없다. */
    private suspend fun keepTake(songId: String, score: Int, t: TakeTimeline, shiftMs: Long) {
        if (t.isEmpty || t.lengthMs < MIN_TAKE_MS) return
        _takeWorking.value = songId
        runCatching {
            val e = kotlinx.coroutines.withContext(Dispatchers.Default) {
                // 앞뒤의 작은 잡음(전주 동안 들어온 소리)을 빼고 노래가 있는 구간만 담는다
                val r = t.voicedRange() ?: return@withContext null
                if ((r.last + 1 - r.first) * 1000L / t.sampleRate < MIN_TAKE_MS) return@withContext null
                TakeCodec.encode(t.pcm, r.first, r.last + 1, r.first * 1000L / t.sampleRate - shiftMs)
            } ?: return@runCatching
            takes.save(songId, score, e)
            Log.i(TAG, "녹음: 모은 것 곡 ${t.startMs}ms부터 ${t.lengthMs}ms -> 남긴 것 ${e.startMs + shiftMs}ms부터 ${e.lengthMs}ms(지연 보정 ${shiftMs}ms), 건너뜀 ${t.jumps}번 -> ${e.bitrate / 1000}kbps ${e.bytes.size}바이트" + if (e.cut) " (잘림)" else "")
        }.onFailure { Log.w(TAG, "녹음 저장 실패(점수는 그대로): $it") }
        _takeWorking.value = null
    }

    /**
     * 녹음을 곡 시각에 둘 때 쓴 마이크 지연([MIC_LAG_MS])이 이번 판에 얼마나 틀렸는지.
     * 지연 가정별 점수 가운데 가장 높은 것이 기본 가정보다 [SHIFT_MIN_GAIN]점 넘게 높을 때만 그 차이를 돌려준다(아니면 0).
     * 돌려준 만큼 녹음을 앞으로 당겨 반주와 맞춘다. 마이크와 출력 지연은 차마다 달라서다.
     */
    private fun takeShiftMs(c: Current): Long {
        val base = c.probes.firstOrNull { it.first == MIC_LAG_MS }?.second?.score ?: return 0
        val (lag, s) = c.probes.mapNotNull { (l, sc) -> sc.score?.let { l to it } }.maxByOrNull { it.second } ?: return 0
        return if (s - base >= SHIFT_MIN_GAIN) lag - MIC_LAG_MS else 0
    }

    /** 녹음을 압축하는 중인 곡(화면에 "정리 중"). */
    private val _takeWorking = MutableStateFlow<String?>(null)
    val takeWorking: StateFlow<String?> = _takeWorking.asStateFlow()

    /** 전국 랭킹에 올린다(설정에서 켰고 서버가 설정돼 있을 때만). 실패해도 점수 기록에는 영향이 없다. */
    private suspend fun upload(songId: String, score: Int) {
        val s = settings.state.value
        if (!s.rankUpload || !ranking.configured) return
        runCatching { ranking.submit(songId, s.nickname, score) }
            .onFailure { Log.w(TAG, "랭킹 올리기 실패: $it") }
        _rankVersion.value++
    }

    /** 랭킹 올리기를 켜거나 별명을 바꿨을 때 곡별 최고 점수를 모두 올린다. */
    fun uploadAllBests() {
        scope.launch {
            for (row in dao.bestScores()) upload(row.songId, row.best)
        }
    }

    /** 랭킹을 올릴 때마다 바뀐다. 화면이 랭킹을 다시 읽는 신호. */
    private val _rankVersion = MutableStateFlow(0)
    val rankVersion: StateFlow<Int> = _rankVersion.asStateFlow()

    companion object {
        const val TAG = "SC.Score"
        /** 이만큼(음표 프레임, 20ms) 넘게 채점해야 점수를 남긴다. 250 = 5초. */
        const val MIN_JUDGED = 250
        /** 이보다 짧은 녹음은 남기지 않는다. */
        const val MIN_TAKE_MS = 5_000L
        const val SHIFT_MIN_GAIN = 3
        private val LAG_PROBES = (-100L..400L step 50L).toList()

        /**
         * 마이크 소리가 곡 위치보다 늦게 도착하는 만큼(ms). 사람은 들리는 반주에 맞춰 부르고, 재생 위치는 들리는 소리 기준이므로
         * 남는 것은 마이크 경로의 지연이다. 에뮬레이터에서 잰 값(검증기록 G5). 박자 여유(200ms)가 나머지를 덮는다.
         */
        const val MIC_LAG_MS = 100L

        fun line(title: String, score: Int, previousBest: Int?): String = when {
            previousBest == null -> "$title, ${score}점. 첫 기록이에요"
            score > previousBest -> "$title, ${score}점. 최고 기록이에요"
            score == previousBest -> "$title, ${score}점. 최고 기록과 같아요"
            else -> "$title, ${score}점. 최고 기록까지 ${previousBest - score}점"
        }
    }
}
