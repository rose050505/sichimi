package app.sichimi.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.sichimi.audio.AudioConfig
import app.sichimi.audio.StemMixProcessor
import app.sichimi.data.ExposureEvent
import app.sichimi.data.RecordDao
import app.sichimi.data.SongPlay
import app.sichimi.data.Trip
import app.sichimi.gate.CabinInput
import app.sichimi.gate.Gear
import app.sichimi.gate.GateEffect
import app.sichimi.gate.GateState
import app.sichimi.gate.LedgerEvent
import app.sichimi.gate.PrivacyGate
import app.sichimi.gate.Reading
import app.sichimi.gate.SoloLedger
import app.sichimi.gate.isSealed
import app.sichimi.gate.isSolo
import app.sichimi.gate.CountIn
import app.sichimi.settings.AppSettings
import app.sichimi.settings.SettingsStore
import app.sichimi.vehicle.CabinReadings
import app.sichimi.vehicle.VehicleSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 플레이어에서 문지기가 알아야 하는 것. */
data class PlayerSnapshot(
    val isPlaying: Boolean = false,
    val songId: String? = null,
    /** 곡을 새로 시작할 때마다 바뀌는 값. */
    val itemKey: String? = null,
)

/** 화면이 보는 상태. */
data class GateUi(
    val state: GateState = GateState.NORMAL,
    val cabin: CabinInput = CabinInput.INITIAL,
    val solo: Boolean = false,
    val sealed: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val cautious: Boolean = false,
)

/**
 * 차 신호, 설정, 플레이어 상태를 [PrivacyGate]에 넣고, 문지기가 요청한 일(가수 음량, 기록)을 한다.
 * 모든 일은 메인 스레드에서 한다. 문지기는 재생을 시작하지 않는다. 가수 음량만 바꾼다.
 */
class GateController(
    context: Context,
    private val scope: CoroutineScope,
    private val signals: VehicleSignals,
    private val settings: SettingsStore,
    private val mixer: StemMixProcessor,
    private val dao: RecordDao,
    private val positionMs: () -> Long,
) {
    private val gate = PrivacyGate(settings.state.value.toGateConfig())
    private val ledger = SoloLedger()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("records", Context.MODE_PRIVATE)

    val player = MutableStateFlow(PlayerSnapshot())

    private val _ui = MutableStateFlow(GateUi())
    val ui: StateFlow<GateUi> = _ui.asStateFlow()

    /** 오늘 요약을 띄울 주행 id. 띄운 뒤 사용자가 닫으면 지운다. */
    private val _pendingSummaryTrip = MutableStateFlow(prefs.getLong("pendingSummaryTrip", -1).takeIf { it > 0 })
    val pendingSummaryTrip: StateFlow<Long?> = _pendingSummaryTrip.asStateFlow()

    // DB 작업은 들어온 순서대로 하나씩 한다(주행 id가 생긴 뒤에 곡·들킬 뻔 기록이 붙도록).
    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var currentTripId: Long? = null
    private val tripIdByStart = mutableMapOf<Long, Long>()
    private val playRowByKey = mutableMapOf<String, Long>()
    private var lastRampSeq = 0L
    private var lastGear = Gear.UNKNOWN
    private var lastSignalAt = 0L
    private var lastConfig = settings.state.value.toGateConfig()

    private val tick = Runnable { apply(gate.tick(now())) }

    fun start() {
        scope.launch { for (op in ops) runCatching { op() }.onFailure { Log.e(TAG, "기록 실패", it) } }
        // 재생 중인 곡도 내 기록에 보이도록 몇 초마다 진행 중 기록을 남긴다.
        scope.launch { while (true) { delay(3_000); ledger.currentPlay(wall())?.let(::savePlay) } }
        scope.launch {
            combine(signals.readings, settings.state, player) { r, s, p -> Triple(r, s, p) }
                .collect { (r, s, p) -> onInputs(r, s, p) }
        }
    }

    fun dismissSummary() {
        _pendingSummaryTrip.value = null
        prefs.edit().remove("pendingSummaryTrip").apply()
    }

    fun clearRecords() {
        ops.trySend {
            dao.clearExposures(); dao.clearPlays(); dao.clearTrips(); dao.clearScores()
            currentTripId = null
            tripIdByStart.clear()
            playRowByKey.clear()
        }
        dismissSummary()
    }

    private fun onInputs(r: CabinReadings, s: AppSettings, p: PlayerSnapshot) {
        val t = now()
        val input = r.toCabinInput(s, p.isPlaying)
        // 설정이 바뀌었으면 먼저 반영한다.
        val config = s.toGateConfig()
        if (config != lastConfig) {
            lastConfig = config
            apply(gate.updateConfig(config, t))
        }
        // 기록: 기어, 재생 상태
        if (input.gear != lastGear) {
            lastGear = input.gear
            handleLedger(ledger.onGear(input.gear, wall()))
        }
        handleLedger(ledger.onPlayback(p.itemKey, p.songId, p.isPlaying, wall()))
        // 차 신호가 새로 들어왔으면 그 신호가 도착한 시각을, 아니면(재생 버튼, 설정) 지금 시각을 쓴다.
        val fresh = r.changedAtMs != lastSignalAt && r.changedAtMs in 1..t
        lastSignalAt = r.changedAtMs
        apply(gate.update(input, if (fresh) r.changedAtMs else t))
    }

    private fun apply(effects: List<GateEffect>) {
        for (e in effects) when (e) {
            is GateEffect.StateChanged -> {
                Log.i(TAG, "상태 ${e.from} -> ${e.to}")
                ledger.onGateState(e.to, wall())
            }
            is GateEffect.VocalRamp -> {
                lastRampSeq = mixer.setVocalGain(e.targetGain, e.durationMs)
                Log.i(TAG, "가수 음량 -> ${e.targetGain} (${e.durationMs}ms) seq=$lastRampSeq")
            }
            is GateEffect.Exposure -> recordExposure(e, lastRampSeq)
        }
        mixer.setCountInAudible(CountIn.audible(gate.state, settings.state.value.countIn))
        val cabin = gate.input
        _ui.value = GateUi(
            state = gate.state,
            cabin = cabin,
            solo = cabin.isSolo(),
            sealed = cabin.isSealed(gate.config.windowThresholdPct),
            gear = cabin.gear,
            cautious = gate.cautious,
        )
        handler.removeCallbacks(tick)
        gate.nextDeadlineMs()?.let { handler.postDelayed(tick, (it - now()).coerceAtLeast(0)) }
    }

    private fun recordExposure(e: GateEffect.Exposure, rampSeq: Long) {
        val songId = player.value.songId
        val pos = positionMs()
        val at = wall()
        scope.launch {
            var reaction: Long? = null
            if (e.vocalWasDown) {
                // 믹서가 음량을 올리기 시작한 시각을 기다린다.
                val until = now() + 1_000
                while (mixer.lastAppliedSeq < rampSeq && now() < until) delay(5)
                if (mixer.lastAppliedSeq >= rampSeq) {
                    reaction = (mixer.lastAppliedAtMs - e.signalAtMs).coerceAtLeast(0) + AudioConfig.OUTPUT_LATENCY_MS
                }
            }
            Log.i(TAG, "들킬 뻔 cause=${e.cause} reactionMs=$reaction song=$songId pos=$pos")
            ledger.onExposure(reaction, wall())
            ops.trySend {
                dao.insertExposure(ExposureEvent(tripId = currentTripId, at = at, cause = e.cause.name, songId = songId, positionMs = pos, reactionMs = reaction))
            }
        }
    }

    private fun handleLedger(events: List<LedgerEvent>) {
        for (ev in events) when (ev) {
            is LedgerEvent.TripStarted -> ops.trySend {
                val id = dao.insertTrip(Trip(startedAt = ev.startedAt))
                currentTripId = id
                tripIdByStart[ev.startedAt] = id
                Log.i(TAG, "주행 시작 id=$id")
            }
            is LedgerEvent.TripEnded -> ops.trySend {
                val t = ev.trip
                val id = tripIdByStart[t.startedAt]
                if (currentTripId == id) currentTripId = null
                Log.i(TAG, "주행 끝 id=$id solo=${t.soloMs}ms 곡=${t.soloSongs} 들킬뻔=${t.exposures}")
                if (id != null) {
                    dao.finishTrip(id, t.endedAt, t.soloMs, t.soloSongs, t.exposures)
                    if (t.soloMs > 0) {
                        _pendingSummaryTrip.value = id
                        prefs.edit().putLong("pendingSummaryTrip", id).apply()
                    }
                }
            }
            is LedgerEvent.SongPlayEnded -> {
                savePlay(ev.play)
                ops.trySend { playRowByKey.remove(ev.play.itemKey) }
            }
        }
    }

    /** 곡 재생 기록을 넣거나(처음) 고친다(그다음). */
    private fun savePlay(p: app.sichimi.gate.SongPlaySummary) {
        if (p.totalMs <= 0) return
        ops.trySend {
            val row = playRowByKey[p.itemKey]
            if (row != null) dao.updatePlay(row, p.totalMs, p.soloMs)
            else {
                val tripId = p.tripStartedAt?.let { tripIdByStart[it] }
                playRowByKey[p.itemKey] = dao.insertPlay(SongPlay(tripId = tripId, songId = p.songId, startedAt = p.startedAt, totalMs = p.totalMs, soloMs = p.soloMs))
            }
        }
    }

    private fun CabinReadings.toCabinInput(s: AppSettings, playing: Boolean): CabinInput {
        val seatsView = if (s.invertSeatOccupancy) seats.mapValues { (_, v) -> if (v is Reading.Known) Reading.Known(!v.value) else v } else seats
        return CabinInput(seats = seatsView, doors = doors, windows = windows, gear = gearAaos, turnSignalOn = turnSignalOn, playing = playing)
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun wall() = System.currentTimeMillis()

    companion object {
        const val TAG = "SC.Gate"
    }
}
