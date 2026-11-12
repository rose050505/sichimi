package app.sichimi.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.sichimi.data.Award
import app.sichimi.data.RecordDao
import app.sichimi.gate.BadgeId
import app.sichimi.gate.Badges
import app.sichimi.gate.ExposureRow
import app.sichimi.gate.Growth
import app.sichimi.gate.MascotLook
import app.sichimi.gate.MascotMood
import app.sichimi.gate.MascotPose
import app.sichimi.gate.MissionId
import app.sichimi.gate.Missions
import app.sichimi.gate.PlayRow
import app.sichimi.gate.ScoreRow
import app.sichimi.gate.Stats
import app.sichimi.gate.Tour
import app.sichimi.gate.Week
import app.sichimi.gate.WeekStats
import app.sichimi.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.ZoneId

data class MissionUi(val id: MissionId, val title: String, val progress: String, val done: Boolean, val fraction: Float)

/** 내 기록 탭 맨 위(시치미, 투어, 미션, 배지)에 쓰는 값. */
data class StageUi(
    val points: Int = 0,
    val stage: Int = 1,
    val stageName: String = Growth.STAGE_NAMES[0],
    val nextText: String = Growth.nextText(0),
    val progress: Float = 0f,
    /** 가장 최근에 얻은 배지 = 칭호. */
    val title: String? = null,
    val week: WeekStats = WeekStats(),
    val weekKey: String = "",
    val missions: List<MissionUi> = emptyList(),
    val badges: Set<BadgeId> = emptySet(),
    val setlist: List<String> = emptyList(),
)

/** 시치미 그림에 쓰는 값. [since]는 elapsedRealtime. */
data class MascotUi(val pose: MascotPose = MascotPose.AWAY, val since: Long = 0L, val look: MascotLook = MascotLook())

/**
 * v2 무대: 기록에서 성장 단계·이번 주 미션·배지를 계산하고, 새로 이룬 것을 [Award]로 남긴다(주차 화면에서 한 번 알림).
 * 시치미 자세는 문지기 상태와 들킬 뻔 번호로 정한다([MascotMood]). 모두 기기 안에서만 계산한다.
 */
class StageEngine(
    private val scope: CoroutineScope,
    private val dao: RecordDao,
    private val controller: GateController,
    private val settings: SettingsStore,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val _ui = MutableStateFlow(StageUi())
    val ui: StateFlow<StageUi> = _ui.asStateFlow()

    private val mood = MascotMood()
    private val _mascot = MutableStateFlow(MascotUi())
    val mascot: StateFlow<MascotUi> = _mascot.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val retick = Runnable { updateMascot() }

    fun start() {
        scope.launch {
            combine(dao.allPlays(), dao.allExposures(), dao.allScores(), dao.allAwards()) { p, e, s, a -> Quad(p, e, s, a) }
                .collect { (plays, exps, scores, awards) -> recompute(plays, exps, scores, awards) }
        }
        scope.launch { controller.ui.collect { updateMascot() } }
        scope.launch { settings.state.collect { updateMascot() } }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private suspend fun recompute(
        plays: List<app.sichimi.data.SongPlay>, exps: List<app.sichimi.data.ExposureEvent>,
        scores: List<app.sichimi.data.SongScore>, awards: List<Award>,
    ) {
        val now = System.currentTimeMillis()
        val p = plays.map { PlayRow(it.tripId, it.songId, it.startedAt, it.durationMs, it.soloMs, it.rainSoloMs, it.nightSoloMs) }
        val e = exps.map { ExposureRow(it.tripId, it.at, it.reactionMs, it.relievedAt) }
        val s = scores.map { ScoreRow(it.songId, it.at, it.score, it.finished, it.ghostScore) }
        val from = Week.startMs(now, zone)
        val to = from + 7 * 24 * 3600_000L
        val wk = Week.key(now, zone)
        val week = Stats.week(from, to, p, e, s)
        val life = Stats.lifetime(week, p, e, s)
        val missionIds = Missions.forWeek(Week.monday(now, zone))

        // 새로 이룬 것 남기기(한 번만: (kind, key, week) 유일)
        val have = awards.map { Triple(it.kind, it.key, it.week) }.toSet()
        for (id in missionIds) if (Missions.done(id, week) && Triple("MISSION", id.name, wk) !in have) {
            dao.insertAward(Award(kind = "MISSION", key = id.name, week = wk, at = now, points = Missions.BONUS))
            Log.i(TAG, "미션 완료: ${id.title} (+${Missions.BONUS}점)")
        }
        val earned = Badges.earned(life)
        for (b in earned) if (Triple("BADGE", b.name, "") !in have) {
            dao.insertAward(Award(kind = "BADGE", key = b.name, week = "", at = now, points = 0))
            Log.i(TAG, "배지: ${b.title}")
        }
        if (Tour.complete(week) && Triple("TOUR", "TOUR", wk) !in have) {
            dao.insertAward(Award(kind = "TOUR", key = "TOUR", week = wk, at = now, points = 0))
            Log.i(TAG, "주간 투어 완주: $wk")
        }

        val bonus = awards.filter { it.kind == "MISSION" }.sumOf { it.points }
        val points = Growth.points(Stats.growth(p, e, bonus))
        val title = awards.filter { it.kind == "BADGE" }.maxByOrNull { it.at }?.let { a -> runCatching { BadgeId.valueOf(a.key).title }.getOrNull() }
        _ui.value = StageUi(
            points = points,
            stage = Growth.stage(points),
            stageName = Growth.STAGE_NAMES[Growth.stage(points) - 1],
            nextText = Growth.nextText(points),
            progress = Growth.progress(points),
            title = title,
            week = week,
            weekKey = wk,
            missions = missionIds.map { id ->
                MissionUi(id, id.title, Missions.progressText(id, week), Missions.done(id, week),
                    (Missions.current(id, week).toFloat() / id.goal).coerceIn(0f, 1f))
            },
            badges = earned,
            setlist = Stats.setlist(from, to, p),
        )
    }

    private fun updateMascot() {
        val ui = controller.ui.value
        val now = SystemClock.elapsedRealtime()
        val before = mood.pose
        val pose = mood.update(ui.state, ui.exposureSeq, now)
        if (pose != before) Log.i("SC.Mascot", "시치미 $before -> $pose (상태 ${ui.state})")
        val look = MascotMood.look(ui.wiperOn, Week.isNight(System.currentTimeMillis(), zone), ui.windowsPct, settings.state.value.windowThresholdPct)
        val since = if (pose != before) now else _mascot.value.since
        if (pose != before || look != _mascot.value.look) _mascot.value = MascotUi(pose, since, look)
        main.removeCallbacks(retick)
        mood.nextChangeMs()?.let { main.postDelayed(retick, (it - now).coerceAtLeast(0) + 5) }
    }

    companion object { const val TAG = "SC.Stage" }
}
