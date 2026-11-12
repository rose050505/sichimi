package app.sichimi

import android.Manifest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sichimi.core.Graph
import app.sichimi.gate.Gear
import app.sichimi.gate.Reading
import app.sichimi.gate.SoloLedger
import app.sichimi.gate.Zone
import app.sichimi.songs.LyricLine
import app.sichimi.ui.DrivingScreen
import app.sichimi.ui.ChartRow
import app.sichimi.ui.MonthRecords
import app.sichimi.ui.ParkedScreen
import app.sichimi.ui.ParkedTab
import app.sichimi.ui.PlayerConnection
import app.sichimi.ui.QueueTab
import app.sichimi.ui.LastScoreUi
import app.sichimi.ui.RankUi
import app.sichimi.ui.RecordsTab
import app.sichimi.ui.ScoreRowUi
import app.sichimi.ui.ScoresTab
import app.sichimi.ui.SettingsTab
import app.sichimi.ui.SettingsUi
import app.sichimi.ui.SongRow
import app.sichimi.ui.MyTakeUi
import app.sichimi.ui.TakeActions
import app.sichimi.ui.KaraokeStage
import app.sichimi.ui.StageTop
import app.sichimi.ui.ReplayCard
import app.sichimi.ui.ReplayUi
import app.sichimi.ui.RevealCard
import app.sichimi.ui.RevealOverlay
import app.sichimi.ui.TourPoster
import app.sichimi.ui.GhostBanner
import app.sichimi.ui.GhostBannerUi
import app.sichimi.ui.EscapeRows
import app.sichimi.gate.ParkedQueue
import app.sichimi.gate.Reveal
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.sichimi.ui.minutesText
import app.sichimi.ui.secondsText
import kotlinx.coroutines.flow.combine
import java.util.Calendar

class MainActivity : ComponentActivity() {
    private lateinit var graph: Graph
    private lateinit var connection: PlayerConnection

    /** 노래 점수를 켤 때 마이크 권한을 묻는다. 허락하면 켠다. */
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) graph.settings.update { it.copy(scoring = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as SichimiApp).graph
        connection = PlayerConnection(this, graph.songs)
        setContent { App(graph, connection, ::setScoring) }
    }

    private fun setScoring(on: Boolean) {
        if (!on) graph.settings.update { it.copy(scoring = false) }
        else if (graph.coach.hasPermission()) graph.settings.update { it.copy(scoring = true) }
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStart() {
        super.onStart()
        connection.connect()
    }

    override fun onStop() {
        connection.release()
        super.onStop()
    }
}

@Composable
private fun App(graph: Graph, connection: PlayerConnection, setScoring: (Boolean) -> Unit) {
    val gate by graph.controller.ui.collectAsStateWithLifecycle()
    val player by connection.state.collectAsStateWithLifecycle()
    val requiresDo by graph.restrictions.requiresDistractionOptimization.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val pendingTrip by graph.controller.pendingSummaryTrip.collectAsStateWithLifecycle()

    // 주행 화면: 기어가 P가 아니거나(값을 아직 모를 때 포함) 주행 규제가 걸렸을 때
    val driving = gate.gear != Gear.PARK || requiresDo
    if (driving) {
        DrivingScreen(gate.state, player, connection::previous, connection::playPause, connection::next)
        return
    }

    var tab by rememberSaveable { mutableStateOf(ParkedTab.PLAYER) }
    val lyrics: List<LyricLine> = remember(player.songId) {
        player.songId?.let { id -> graph.songs.find(id)?.let(graph.songs::lyrics) }.orEmpty()
    }

    // ---------- v2 무대 ----------
    val stageUi by graph.stage.ui.collectAsStateWithLifecycle()
    val mascot by graph.stage.mascot.collectAsStateWithLifecycle()
    val ghostPicks by graph.ghost.picks.collectAsStateWithLifecycle()
    val dao = graph.db.dao()
    val unrevealed by remember { dao.unrevealedScores() }.collectAsState(initial = emptyList())
    val unshown by remember { dao.unshownAwards() }.collectAsState(initial = emptyList())
    val allScores by remember { dao.allScores() }.collectAsState(initial = emptyList())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var replay by remember { mutableStateOf<ReplayUi?>(null) }
    LaunchedEffect(pendingTrip) {
        replay = pendingTrip?.let { id ->
            dao.exposuresOfTrip(id).filter { it.reactionMs != null }.minByOrNull { it.reactionMs!! }?.let { e ->
                ReplayUi(e.cause, e.reactionMs!!, e.lyric, e.songId?.let { graph.songs.find(it)?.title })
            }
        }
    }

    var summary by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingTrip) {
        summary = pendingTrip?.let { id ->
            val dao = graph.db.dao()
            val trip = dao.trip(id) ?: return@let null
            val fastest = dao.fastestReaction(id)
            val best = dao.bestSince(trip.startedAt)
            buildString {
                append("오늘 ${trip.soloSongs}곡, 혼자 부른 시간 ${minutesText(trip.soloMs)}, 들킬 뻔한 순간 ${trip.exposures}번")
                if (fastest != null) append("(가장 빠른 대피 ${secondsText(fastest)})")
                if (best != null) append(", 최고 점수 ${best.best}점(${graph.songs.find(best.songId)?.title ?: best.songId})")
            }
        }
    }

    ParkedScreen(
        solo = gate.solo,
        state = gate.state,
        player = player,
        summary = summary,
        onDismissSummary = graph.controller::dismissSummary,
        lyrics = lyrics,
        credit = player.songId?.let { graph.songs.find(it)?.credit }.orEmpty(),
        positionMs = connection::positionMs,
        tab = tab,
        onTab = { tab = it },
        onPrev = connection::previous,
        onPlayPause = connection::playPause,
        onNext = connection::next,
        queueContent = {
            QueueTab(
                player,
                graph.songs.songs().map { SongRow(it.id, it.title, it.artist) },
                connection::playNow, connection::reserve, connection::removeAt,
                night = gate.solo && gate.state == app.sichimi.gate.GateState.KARAOKE,
            )
        },
        recordsContent = {
            val frame = app.sichimi.ui.rememberFrameClock(true)
            RecordsTab(monthRecords(graph)) { StageTop(stageUi, mascot, frame) }
        },
        stageContent = if (gate.state == app.sichimi.gate.GateState.KARAOKE && gate.solo) ({
            KaraokeStage(
                player.songId, player, lyrics, player.songId?.let { graph.songs.find(it)?.credit }.orEmpty(), connection::positionMs,
                mascot, stageUi.stage, ghostPicks.firstOrNull { it.songId == player.songId }?.nickname,
                connection::previous, connection::playPause, connection::next,
            )
        }) else null,
        summaryExtra = replay?.let { r -> { ReplayCard(r, stageUi.stage) } },
        playerOverlay = if (mascot.pose == app.sichimi.gate.MascotPose.HIDING) ({
            val frame = app.sichimi.ui.rememberFrameClock(true)
            // 숨는 몸만 그린다(밤 조명·우비 같은 무대 모습은 빼고). 비어 있는 가사 칸 오른쪽 아래.
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(end = 48.dp, bottom = 24.dp), contentAlignment = androidx.compose.ui.Alignment.BottomEnd) {
                app.sichimi.ui.Mascot(stageUi.stage, mascot.pose, app.sichimi.gate.MascotLook(), androidx.compose.ui.Modifier.size(220.dp), frameMs = frame,
                    hide = { ((android.os.SystemClock.elapsedRealtime() - mascot.since).toFloat() / app.sichimi.gate.MascotMood.HIDE_MS).coerceIn(0f, 1f) })
            }
        }) else null,
        overlay = {
            val next = ParkedQueue.next(true, gate.solo, unrevealed.map { it.id }, unshown.map { it.id }, null)
            val card: RevealCard? = when (next) {
                is Reveal.Score -> unrevealed.firstOrNull { it.id == next.scoreId }?.let { sc ->
                    val prev = allScores.filter { it.songId == sc.songId && it.finished && it.at < sc.at }.maxOfOrNull { it.score }
                    RevealCard.Score(sc.id, graph.songs.find(sc.songId)?.title ?: sc.songId, sc.score, sc.finished, prev,
                        sc.ghostNickname, sc.ghostScore, unrevealed.size - 1 + unshown.size)
                }
                is Reveal.Award -> unshown.firstOrNull { it.id == next.awardId }?.let { a ->
                    val (title, how) = when (a.kind) {
                        "BADGE" -> app.sichimi.gate.BadgeId.valueOf(a.key).let { it.title to it.how }
                        "MISSION" -> app.sichimi.gate.MissionId.valueOf(a.key).title to "이번 주 미션을 해냈어요"
                        else -> "출근길 투어 완주" to "이번 주 공연 5회"
                    }
                    val tour = if (a.kind == "TOUR") TourPoster(a.week, stageUi.setlist.mapNotNull { graph.songs.find(it)?.title }, stageUi.week.soloMs,
                        stageUi.week.bestScore, stageUi.week.fastestEscapeMs) else null
                    RevealCard.Award(a.id, a.kind, title, how, a.points, tour, unshown.size - 1)
                }
                else -> null
            }
            if (card != null) RevealOverlay(card, stageUi.stage, mascot.look) {
                scope.launch {
                    when (card) {
                        is RevealCard.Score -> dao.markRevealed(card.id)
                        is RevealCard.Award -> dao.markAwardShown(card.id)
                    }
                }
            }
        },
        scoresContent = {
            val last by graph.coach.lastResult.collectAsStateWithLifecycle()
            val rows by graph.db.dao().scoreRows().collectAsState(initial = emptyList())
            val version by graph.coach.rankVersion.collectAsStateWithLifecycle()
            val takeVersion by graph.uploader.version.collectAsStateWithLifecycle()
            val localTakes by graph.takes.takes.collectAsStateWithLifecycle()
            val takeStatus by graph.uploader.status.collectAsStateWithLifecycle()
            val play by graph.takePlayer.state.collectAsStateWithLifecycle()
            val takeWorking by graph.coach.takeWorking.collectAsStateWithLifecycle()
            val allSongs = remember { graph.songs.songs().map { it.id to it.title } }
            var rankSong by rememberSaveable { mutableStateOf<String?>(null) }
            val shown = rankSong ?: last?.songId ?: allSongs.firstOrNull()?.first
            var rank by remember { mutableStateOf<RankUi>(RankUi.Loading) }
            LaunchedEffect(shown, version, takeVersion) {
                rank = when {
                    !graph.ranking.configured -> RankUi.NotConfigured
                    shown == null -> RankUi.Ready(emptyList(), null, 0)
                    else -> {
                        rank = RankUi.Loading
                        runCatching { graph.ranking.ranking(shown) }
                            .fold({ RankUi.Ready(it.top, it.myRank, it.total) }, { RankUi.Failed })
                    }
                }
            }
            ScoresTab(
                scoring = settings.scoring,
                last = last?.let { LastScoreUi(it.title, it.score, it.finished, it.previousBest) },
                rows = rows.map { ScoreRowUi(graph.songs.find(it.songId)?.title ?: it.songId, it.best, it.last, it.times) },
                rankSongs = allSongs,
                rankSong = shown,
                onRankSong = { rankSong = it },
                rank = rank,
                uploading = settings.rankUpload,
                ghostBanner = ghostPicks.firstOrNull()?.let { g ->
                    { GhostBanner(GhostBannerUi(g.songId, graph.songs.find(g.songId)?.title ?: g.songId, g.nickname, g.score)) { graph.ghost.cancel(g.songId) } }
                },
                escapeContent = if (graph.ranking.configured) ({ songId ->
                    val escVersion by graph.escapes.version.collectAsStateWithLifecycle()
                    var esc by remember { mutableStateOf<app.sichimi.rank.EscapeRanking?>(null) }
                    var failed by remember { mutableStateOf(false) }
                    LaunchedEffect(songId, escVersion) {
                        failed = false
                        runCatching { graph.ranking.escapeRanking(stageUi.weekKey, songId) }.onSuccess { esc = it }.onFailure { failed = true }
                    }
                    when {
                        failed -> Text("대피 랭킹을 불러오지 못했습니다. 네트워크를 확인해 주세요.", style = app.sichimi.ui.Type.bodyM, color = app.sichimi.ui.Night.sub, modifier = androidx.compose.ui.Modifier.padding(32.dp))
                        esc == null -> Text("불러오는 중…", style = app.sichimi.ui.Type.bodyM, color = app.sichimi.ui.Night.sub, modifier = androidx.compose.ui.Modifier.padding(32.dp))
                        else -> EscapeRows(esc!!.top, esc!!.myRank, esc!!.total, settings.rankUpload)
                    }
                }) else null,
                recording = settings.rankUpload && graph.ranking.configured,
                myTakes = localTakes.map { t ->
                    MyTakeUi(t.songId, graph.songs.find(t.songId)?.title ?: t.songId, t.score, t.lengthMs, t.bitrate / 1000,
                        (t.bytes + 999) / 1000, t.cut, t.uploadedAt != null, takeStatus[t.songId])
                },
                play = play,
                takeWorking = takeWorking,
                takeActions = TakeActions(
                    playMine = { id ->
                        val song = graph.songs.find(id)
                        val t = graph.takes.find(id)
                        if (song != null && t != null) {
                            connection.pause()
                            graph.takePlayer.play("mine:$id", song) { (graph.takes.bytes(id) ?: error("파일 없음")) to t.startMs }
                        }
                    },
                    playOther = { id, e ->
                        graph.songs.find(id)?.let { song ->
                            connection.pause()
                            graph.takePlayer.play("$id/${e.uid}", song) {
                                val (meta, bytes) = graph.ranking.fetchTake(id, e.uid)
                                bytes to meta.startMs
                            }
                        }
                    },
                    stop = graph.takePlayer::stop,
                    upload = graph.uploader::upload,
                    delete = { id -> if (graph.takePlayer.state.value.key == "mine:$id") graph.takePlayer.stop(); graph.uploader.delete(id) },
                    pickGhost = { id, e -> graph.ghost.pick(id, e) },
                ),
            )
        },
        settingsContent = {
            var serverTakesStatus by remember { mutableStateOf<String?>(null) }
            SettingsTab(
                SettingsUi(
                    enabled = settings.enabled,
                    guidePercent = settings.guidePercent,
                    dwellSeconds = settings.dwellSeconds,
                    windowThresholdPct = settings.windowThresholdPct,
                    safetyReturn = settings.safetyReturn,
                    countIn = settings.countIn,
                    scoring = settings.scoring,
                    rankUpload = settings.rankUpload,
                    nickname = settings.nickname,
                    rankConfigured = graph.ranking.configured,
                    invertSeat = settings.invertSeatOccupancy,
                    rearSeatsUnreadable = listOf(Zone.REAR_LEFT, Zone.REAR_RIGHT).any { gate.cabin.seats[it] == Reading.Unsupported },
                    passengerUnreadable = gate.cabin.seats[Zone.FRONT_RIGHT] == Reading.Unsupported,
                    ghostPercent = settings.ghostPercent,
                ),
                onEnabled = { v -> graph.settings.update { it.copy(enabled = v) } },
                onGuide = { v -> graph.settings.update { it.copy(guidePercent = v) } },
                onDwell = { v -> graph.settings.update { it.copy(dwellSeconds = v) } },
                onWindow = { v -> graph.settings.update { it.copy(windowThresholdPct = v) } },
                onSafety = { v -> graph.settings.update { it.copy(safetyReturn = v) } },
                onCountIn = { v -> graph.settings.update { it.copy(countIn = v) } },
                onScoring = setScoring,
                onRankUpload = { v ->
                    graph.settings.update { it.copy(rankUpload = v) }
                    if (v) graph.escapes.retryPending()
                    if (v) graph.coach.uploadAllBests()
                },
                onNickname = { v ->
                    graph.settings.update { it.copy(nickname = v) }
                    if (settings.rankUpload) graph.coach.uploadAllBests()
                },
                onInvert = { v -> graph.settings.update { it.copy(invertSeatOccupancy = v) } },
                onClear = { graph.controller.clearRecords(); graph.takePlayer.stop(); graph.takes.deleteAll(); scope.launch { graph.ghost.clearAll() } },
                onGhost = { v -> graph.settings.update { it.copy(ghostPercent = v) } },
                onDeleteServerTakes = if (graph.ranking.configured) ({
                    serverTakesStatus = "지우는 중…"
                    graph.uploader.deleteAllOnServer(graph.songs.songs().map { it.id }) { ok ->
                        serverTakesStatus = if (ok) "서버의 내 녹음을 모두 지웠습니다" else "일부를 지우지 못했습니다. 네트워크를 확인하고 다시 눌러 주세요"
                    }
                }) else null,
                serverTakesStatus = serverTakesStatus,
            )
        },
    )
}

@Composable
private fun monthRecords(graph: Graph): MonthRecords {
    val from = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val dao = graph.db.dao()
    val min = SoloLedger.SOLO_SONG_MIN_MS
    val flow = remember(from) {
        combine(
            dao.soloSongsSince(from, min), dao.soloMsSince(from), dao.topSongsSince(from, min), dao.causesSince(from), dao.fastestSince(from),
        ) { songs, ms, top, causes, fastest ->
            MonthRecords(
                soloSongs = songs,
                soloMs = ms,
                top = top.map { ChartRow(graph.songs.find(it.songId)?.title ?: it.songId, it.plays, it.soloMs) },
                exposures = causes.associate { it.cause to it.n },
                fastestMs = fastest,
            )
        }
    }
    val r by flow.collectAsState(initial = MonthRecords())
    return r
}
