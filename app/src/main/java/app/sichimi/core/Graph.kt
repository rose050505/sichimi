package app.sichimi.core

import android.app.Application
import app.sichimi.audio.Announcer
import app.sichimi.audio.StemMixProcessor
import app.sichimi.data.RecordDb
import app.sichimi.gate.LastSongCue
import app.sichimi.navi.NaviRemaining
import app.sichimi.rank.RankingClient
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

    val controller = GateController(app, scope, signals, settings, mixer, db.dao()) { positionProvider() }
    val ranking = RankingClient(app)
    val coach = SingCoach(app, scope, controller, settings, songs, db.dao(), announcer, ranking) { positionProvider() }

    fun start() {
        signals.start()
        restrictions.start()
        controller.start()
        navi.start()
        announcer.start()
        coach.start()
        // 마지막 곡: 내비 남은 시간이 3분대이고 혼자만의 노래방이면 한 줄을 한 번 말한다.
        combine(navi.remainSec, controller.ui) { remain, ui -> lastSong.update(remain, ui.state) }
            .onEach { line -> if (line != null) announcer.say(line) }
            .launchIn(scope)
    }
}
