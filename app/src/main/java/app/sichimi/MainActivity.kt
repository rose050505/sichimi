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
            )
        },
        recordsContent = { RecordsTab(monthRecords(graph)) },
        scoresContent = {
            val last by graph.coach.lastResult.collectAsStateWithLifecycle()
            val rows by graph.db.dao().scoreRows().collectAsState(initial = emptyList())
            val version by graph.coach.rankVersion.collectAsStateWithLifecycle()
            val allSongs = remember { graph.songs.songs().map { it.id to it.title } }
            var rankSong by rememberSaveable { mutableStateOf<String?>(null) }
            val shown = rankSong ?: last?.songId ?: allSongs.firstOrNull()?.first
            var rank by remember { mutableStateOf<RankUi>(RankUi.Loading) }
            LaunchedEffect(shown, version) {
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
            )
        },
        settingsContent = {
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
                    if (v) graph.coach.uploadAllBests()
                },
                onNickname = { v ->
                    graph.settings.update { it.copy(nickname = v) }
                    if (settings.rankUpload) graph.coach.uploadAllBests()
                },
                onInvert = { v -> graph.settings.update { it.copy(invertSeatOccupancy = v) } },
                onClear = graph.controller::clearRecords,
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
