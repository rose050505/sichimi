package app.sichimi.core

import android.app.Application
import app.sichimi.audio.Announcer
import app.sichimi.audio.StemMixProcessor
import app.sichimi.data.RecordDb
import app.sichimi.gate.LastSongCue
import app.sichimi.navi.NaviRemaining
import app.sichimi.audio.TakeAllowance
import app.sichimi.audio.TakePlayer
import app.sichimi.rank.RankingClient
import app.sichimi.rank.EscapeRanker
import app.sichimi.rank.TakeStore
import app.sichimi.rank.TakeUploader
import app.sichimi.settings.SettingsStore
import app.sichimi.songs.AssetSongSource
import app.sichimi.songs.SongSource
import app.sichimi.vehicle.VehicleSignals
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** 앱 하나에 하나씩 있는 것들. 화면과 재생 서비스가 같이 쓴다. */
class Graph(app: Application) {
    val scope = MainScope()
    val settings = SettingsStore(app)
    val signals = VehicleSignals(app)
    val mixer = StemMixProcessor()
    val db = RecordDb.open(app)
    val songs: SongSource = AssetSongSource(app)
    val restrictions = DrivingRestrictions(app)
    val navi = NaviRemaining(app)
    val announcer = Announcer(app)
    private val lastSong = LastSongCue()

    /** 재생 서비스가 채운다. 들킬 뻔한 순간의 재생 위치를 기록할 때 쓴다. */
    @Volatile var positionProvider: () -> Long = { 0L }

    val ranking = RankingClient(app)
    val escapes = EscapeRanker(app, scope, ranking, settings)
    val controller = GateController(
        app, scope, signals, settings, mixer, db.dao(),
        positionMs = { positionProvider() },
        durationOf = { id -> songs.find(id)?.durationMs ?: 0L },
        lyricAt = { id, pos -> songs.find(id)?.let(songs::lyrics)?.lastOrNull { it.atMs <= pos }?.text?.takeIf { it.isNotBlank() } },
        onEscape = { escapes.onEscape(it) },
    )
    val takes = TakeStore(app)
    val uploader = TakeUploader(scope, ranking, takes, settings)
    val coach = SingCoach(app, scope, controller, settings, songs, db.dao(), announcer, ranking, takes) { positionProvider() }
    /** 고스트 대결(설계 15.5). */
    val ghost = GhostDuel(app, scope, db.dao(), ranking, mixer, settings, controller.player) { positionProvider() }
    /** 무대: 성장·미션·배지·시치미(설계 15). */
    val stage = StageEngine(scope, db.dao(), controller, settings)
    /** 녹음 듣기. 기어 P·주행 규제 없음·혼자일 때만 튼다. */
    val takePlayer = TakePlayer(app, scope,
        combine(controller.ui, restrictions.requiresDistractionOptimization) { ui, rdo -> TakeAllowance(ui.gear, rdo, ui.solo) })

    fun start() {
        signals.start()
        restrictions.start()
        controller.start()
        navi.start()
        announcer.start()
        coach.onDuelDone = { ghost.finish(it) }
        coach.start()
        takePlayer.start()
        ghost.start()
        stage.start()
        escapes.start()
        // 마지막 곡: 내비 남은 시간이 3분대이고 혼자만의 노래방이면 한 줄을 한 번 말한다.
        combine(navi.remainSec, controller.ui) { remain, ui -> lastSong.update(remain, ui.state) }
            .onEach { line -> if (line != null) announcer.say(line) }
            .launchIn(scope)
    }
}
