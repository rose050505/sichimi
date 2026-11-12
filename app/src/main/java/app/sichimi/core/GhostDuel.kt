package app.sichimi.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.sichimi.audio.StemMixProcessor
import app.sichimi.audio.TakeCodec
import app.sichimi.data.GhostPick
import app.sichimi.data.RecordDao
import app.sichimi.gate.GhostGain
import app.sichimi.rank.RankEntry
import app.sichimi.rank.RankingClient
import app.sichimi.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 고스트 대결(설계 15.5).
 *
 * - 고르기: 점수 탭(주차·혼자일 때만 보임)의 랭킹 줄에서 「고스트로 겨루기」. 그 자리에서 녹음을 받아 16kHz PCM으로
 *   `files/ghost/{곡}.pcm`에 둔다(주행 중에는 네트워크를 쓰지 않는다).
 * - 소리: 그 곡이 재생되면 메인 믹서에 건다. 음량은 가수 음량에 묶여, 혼자만의 노래방일 때만 들리고
 *   동승자가 타면 가수가 돌아오는 같은 램프에서 사라진다([GhostGain.follow]).
 * - 곡 위치: 믹서가 처리한 표본 수로 센다. 0.2초마다 재생 위치와 비교해 0.25초 넘게 어긋나면 다시 맞춘다([GHOST_LEAD_MS]).
 * - 결과: SingCoach가 완주 점수를 남길 때 상대 점수와 함께 남기고 [finish]를 부른다(한 판이 끝나면 지움).
 */
class GhostDuel(
    context: Context,
    private val scope: CoroutineScope,
    private val dao: RecordDao,
    private val ranking: RankingClient,
    private val mixer: StemMixProcessor,
    private val settings: SettingsStore,
    private val player: StateFlow<PlayerSnapshot>,
    private val positionMs: () -> Long,
) {
    private val dir = File(context.filesDir, "ghost").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())

    val picks: StateFlow<List<GhostPick>> = dao.ghostPicks().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 곡 id → "불러오는 중…" 같은 한 줄. */
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    private var loadedFor: String? = null
    private var lastAnchorLog = 0L

    private fun file(songId: String) = File(dir, "$songId.pcm")

    fun start() {
        // 곡이 바뀌거나 고른 고스트·설정이 바뀌면 믹서의 고스트를 다시 건다
        scope.launch {
            combine(player.map { it.songId }.distinctUntilChanged(), picks, settings.state) { song, ps, s -> Triple(song, ps, s) }
                .collect { (song, ps, s) -> load(song, ps.firstOrNull { it.songId == song }, s.ghostPercent, s.guidePercent) }
        }
        main.post(object : Runnable {
            override fun run() { keepAligned(); main.postDelayed(this, 200) }
        })
    }

    private suspend fun load(songId: String?, pick: GhostPick?, ghostPct: Int, guidePct: Int) {
        if (songId == null || pick == null) {
            if (mixer.hasGhost) Log.i(TAG, "고스트 내림")
            mixer.clearGhost(); loadedFor = null
            return
        }
        val pcm = withContext(Dispatchers.IO) { runCatching { readPcm(file(songId)) }.getOrNull() }
        if (pcm == null) { Log.w(TAG, "고스트 파일 없음: $songId"); mixer.clearGhost(); return }
        val level = GhostGain.level(ghostPct, guidePct)
        mixer.setGhost(pcm, pick.startMs, level, guidePct / 100f)
        mixer.reanchor(-1) // 위치는 재생이 안정된 뒤 keepAligned가 맞춘다(그동안 고스트는 쉰다)
        mismatchSince = 0L
        loadedFor = songId
        Log.i(TAG, "고스트 걸음: $songId ${pick.nickname}(${pick.score}점) 음량 ${(level * 100).toInt()}% (가이드 $guidePct%)")
    }

    /** 믹서가 세는 곡 위치가 재생 위치와 어긋났으면(이동, 곡 바뀜) 다시 맞춘다. */
    private var lastGainLog = 0L
    private var mismatchSince = 0L
    private var lastAudible = false

    private fun keepAligned() {
        if (!mixer.hasGhost || !player.value.isPlaying) return
        // 고스트가 들리는지(가수 음량에 묶임) 로그: 바뀔 때와 2초마다
        val g = mixer.currentGhostGain
        val audible = g > 0.001f
        val t = System.currentTimeMillis()
        if (audible != lastAudible || t - lastGainLog > 2000) {
            lastAudible = audible; lastGainLog = t
            Log.i(TAG, "고스트 음량 ${"%.3f".format(g)} (가수 ${"%.2f".format(mixer.currentGain)}, 믹서 위치 ${mixer.processedPosMs}ms)")
        }
        val expected = positionMs() + GHOST_LEAD_MS
        val p = mixer.processedPosMs
        if (p < 0 || abs(p - expected) > REANCHOR_MS) {
            // 어긋남(곡 바뀜·이동 직후): 먼저 고스트를 쉬게 하고, 재생이 [SETTLE_MS] 동안 이어진 뒤에 맞춘다.
            // 이동 직후에는 오디오 길이 아직 새 위치를 흘리지 않아 바로 맞추면 고스트가 앞선다(검증기록 v2).
            val now = android.os.SystemClock.elapsedRealtime()
            if (mismatchSince == 0L) { mismatchSince = now; mixer.reanchor(-1); return }
            if (now - mismatchSince < SETTLE_MS) return
            mixer.reanchor(expected)
            mismatchSince = 0L
            Log.i(TAG, "고스트 위치 맞춤: 믹서 ${p}ms -> ${expected}ms")
        } else mismatchSince = 0L
    }

    /** 랭킹 줄에서 고른다. 주차·혼자일 때만 보이는 화면에서만 부른다. */
    fun pick(songId: String, e: RankEntry) {
        _status.value = _status.value + (songId to "${e.nickname}님의 목소리를 받는 중…")
        scope.launch {
            runCatching {
                val (meta, bytes) = ranking.fetchTake(songId, e.uid)
                val pcm = withContext(Dispatchers.Default) { TakeCodec.decode(bytes) }
                withContext(Dispatchers.IO) { writePcm(file(songId), pcm) }
                dao.putGhost(GhostPick(songId, e.uid, e.nickname, meta.score, meta.startMs, meta.lengthMs, System.currentTimeMillis()))
                Log.i(TAG, "고스트 고름: $songId ${e.nickname} ${meta.score}점 ${pcm.size / 16}ms")
            }.onSuccess { _status.value = _status.value - songId }
                .onFailure { Log.w(TAG, "고스트 받기 실패: $it"); _status.value = _status.value + (songId to "목소리를 받지 못했습니다. 네트워크를 확인해 주세요") }
        }
    }

    fun cancel(songId: String) = scope.launch { finish(songId); Log.i(TAG, "고스트 취소: $songId") }

    /** 한 판이 끝났거나 취소: 고른 것과 목소리 파일을 지운다. */
    suspend fun finish(songId: String) {
        dao.deleteGhost(songId)
        withContext(Dispatchers.IO) { file(songId).delete() }
        if (loadedFor == songId) { mixer.clearGhost(); loadedFor = null }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
        mixer.clearGhost(); loadedFor = null
    }

    private fun writePcm(f: File, pcm: ShortArray) {
        val b = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(pcm)
        f.writeBytes(b.array())
    }

    private fun readPcm(f: File): ShortArray {
        val bytes = f.readBytes()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    companion object {
        const val TAG = "SC.Ghost"
        const val REANCHOR_MS = 250L
        /**
         * 믹서가 지금 처리하는 곡 위치 = 재생 위치 + 이 값(안정된 재생에서 오디오 길의 앞섬 = 출력 지연, 검증기록 G2).
         * 다른 곡 보컬로 만든 고스트(make_ghost.py --voice-from)를 들린 소리에서 찾아 확인한다(align_ghost.py, 검증기록 v2).
         */
        const val GHOST_LEAD_MS = app.sichimi.audio.AudioConfig.OUTPUT_LATENCY_MS
        /** 어긋남을 알아챈 뒤 이만큼 재생이 이어지면 맞춘다. */
        const val SETTLE_MS = 800L
    }
}
