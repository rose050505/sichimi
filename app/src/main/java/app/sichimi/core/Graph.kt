package app.sichimi.core

import android.app.Application
import app.sichimi.audio.StemMixProcessor
import app.sichimi.data.RecordDb
import app.sichimi.settings.SettingsStore
import app.sichimi.songs.AssetSongSource
import app.sichimi.songs.SongSource
import app.sichimi.vehicle.VehicleSignals
import kotlinx.coroutines.MainScope

/** 앱 하나에 하나씩 있는 것들. 화면과 재생 서비스가 같이 쓴다. */
class Graph(app: Application) {
    val scope = MainScope()
    val settings = SettingsStore(app)
    val signals = VehicleSignals(app)
    val mixer = StemMixProcessor()
    val db = RecordDb.open(app)
    val songs: SongSource = AssetSongSource(app)
    val restrictions = DrivingRestrictions(app)

    /** 재생 서비스가 채운다. 들킬 뻔한 순간의 재생 위치를 기록할 때 쓴다. */
    @Volatile var positionProvider: () -> Long = { 0L }

    val controller = GateController(app, scope, signals, settings, mixer, db.dao()) { positionProvider() }

    fun start() {
        signals.start()
        restrictions.start()
        controller.start()
    }
}
