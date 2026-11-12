package app.sichimi.core

import android.content.Context
import kotlinx.coroutines.flow.collectLatest
import app.sichimi.gate.Zone
import app.sichimi.gate.SeatPolarity
import app.sichimi.gate.ArrivalGuard
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
    /** 들킬 뻔할 때마다 1씩 오른다(시치미가 숨는 신호). */
    val exposureSeq: Long = 0,
    val wiperOn: Boolean = false,
    /** 창문별 열린 정도(%). 읽지 못한 창문은 뺀다. */
    val windowsPct: List<Int> = emptyList(),
)

/** 들킬 뻔한 순간 하나(대피 시간을 잰 것). 대피 랭킹과 리플레이 카드가 쓴다. */
data class EscapeMoment(val songId: String, val reactionMs: Long, val cause: String, val at: Long)

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
    /** 곡 길이(완창 판정). */
    private val durationOf: (String) -> Long = { 0L },
    /** 그 곡 그 위치에서 부르던 가사 한 줄. */
    private val lyricAt: (String, Long) -> String? = { _, _ -> null },
    /** 대피 시간을 잰 들킬 뻔이 기록될 때(대피 랭킹). */
    private val onEscape: (EscapeMoment) -> Unit = {},
    /** 내비 목적지까지 남은 시간(초). 도착 직전 미리 시치미([ArrivalGuard]). */
    private val remainSec: StateFlow<Int?> = MutableStateFlow(null),
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
    private var exposureSeq = 0L
    private var reliefPending = false
    private var lastExposureRow: Long? = null // ops 안에서만
    private var lastEnv: Pair<Boolean, Boolean>? = null
    private var lastWiper = false

    private val tick = Runnable { apply(gate.tick(now())) }

    /** 받은 남은 시간으로 센 「곧 도착」 시각이 지났는가(기어 조건은 입력을 만들 때 본다). */
    private val arrivalDue = MutableStateFlow(false)

    fun start() {
        scope.launch { for (op in ops) runCatching { op() }.onFailure { Log.e(TAG, "기록 실패", it) } }
        // 재생 중인 곡도 내 기록에 보이도록 몇 초마다 진행 중 기록을 남긴다.
        scope.launch { while (true) { delay(3_000); envTick(lastWiper); ledger.currentPlay(wall())?.let(::savePlay) } }
        // 도착 직전: 남은 시간을 받은 시각부터 (남은 시간 − 60초)를 센다. 새 값이 오면 다시 센다.
        scope.launch {
            remainSec.collectLatest { r ->
                val at = ArrivalGuard.soonAtMs(r, now())
                if (at == null) { arrivalDue.value = false; return@collectLatest }
                val wait = at - now()
                if (wait > 0) { arrivalDue.value = false; delay(wait) }
                Log.i(TAG, "내비 목적지 곧 도착(약 ${ArrivalGuard.LEAD_SEC}초 전): 안전 장치로 가수 복귀")
                arrivalDue.value = true
            }
        }
        scope.launch {
            combine(signals.readings, settings.state, player, arrivalDue) { r, s, p, a -> Inputs(r, s, p, a) }
                .collect { (r, s, p, a) -> onInputs(r, s, p, a) }
        }
    }

    fun dismissSummary() {
        _pendingSummaryTrip.value = null
        prefs.edit().remove("pendingSummaryTrip").apply()
    }

    fun clearRecords() {
        ops.trySend {
            dao.clearExposures(); dao.clearPlays(); dao.clearTrips(); dao.clearScores(); dao.clearAwards(); dao.clearGhosts()
            lastExposureRow = null
            currentTripId = null
            tripIdByStart.clear()
            playRowByKey.clear()
        }
        dismissSummary()
    }

    /** 와이퍼·밤이 바뀌면 기록 장부에 알린다(비 오는 날·밤 미션). */
    private fun envTick(wiper: Boolean) {
        lastWiper = wiper
        val env = wiper to app.sichimi.gate.Week.isNight(wall(), java.time.ZoneId.systemDefault())
        if (env != lastEnv) {
            lastEnv = env
            ledger.onEnv(env.first, env.second, wall())
            Log.i(TAG, "차 환경 와이퍼=${env.first} 밤=${env.second}")
        }
    }

    private data class Inputs(val r: CabinReadings, val s: AppSettings, val p: PlayerSnapshot, val arrival: Boolean)

    private fun onInputs(r: CabinReadings, s: AppSettings, p: PlayerSnapshot, arrival: Boolean) {
        val t = now()
        envTick(r.wiperOn)
        // 착석 값 방향 자동 맞춤: 기어가 들어가 있으면 운전석에 사람이 있다(SeatPolarity). 다르면 설정을 고치고, 고친 설정으로 다시 들어온다.
        SeatPolarity.inferInverted(r.gearAaos, r.seats[Zone.FRONT_LEFT])?.let { inv ->
            if (inv != s.invertSeatOccupancy) {
                Log.i(TAG, "착석 값 방향 자동 맞춤: 뒤집기 ${s.invertSeatOccupancy} -> $inv (기어 ${r.gearAaos}, 운전석 원래 값 ${r.seats[Zone.FRONT_LEFT]})")
                settings.update { it.copy(invertSeatOccupancy = inv) }
                return
            }
        }
        val input = r.toCabinInput(s, p.isPlaying).let { it.copy(arrivingSoon = arrival && it.gear != Gear.PARK) }
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
                if (e.to == GateState.KARAOKE && reliefPending) {
                    // 들킬 뻔한 뒤 다시 혼자·밀폐가 되어 노래방이 열렸다: 잘 숨고 돌아옴
                    reliefPending = false
                    val at = wall()
                    ops.trySend { lastExposureRow?.let { dao.markRelieved(it, at); Log.i(TAG, "휴, 안 들켰다: 들킬 뻔 $it 뒤 다시 노래방") } }
                }
            }
            is GateEffect.VocalRamp -> {
                lastRampSeq = mixer.setVocalGain(e.targetGain, e.durationMs)
                Log.i(TAG, "가수 음량 -> ${e.targetGain} (${e.durationMs}ms) seq=$lastRampSeq")
            }
            is GateEffect.Exposure -> {
                exposureSeq++
                reliefPending = true
                recordExposure(e, lastRampSeq)
            }
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
            exposureSeq = exposureSeq,
            wiperOn = lastWiper,
            windowsPct = cabin.windows.values.mapNotNull { (it as? Reading.Known)?.value },
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
            val lyric = songId?.let { runCatching { lyricAt(it, pos) }.getOrNull() }
            Log.i(TAG, "들킬 뻔 cause=${e.cause} reactionMs=$reaction song=$songId pos=$pos 가사=$lyric")
            ledger.onExposure(reaction, wall())
            ops.trySend {
                lastExposureRow = dao.insertExposure(ExposureEvent(tripId = currentTripId, at = at, cause = e.cause.name, songId = songId,
                    positionMs = pos, reactionMs = reaction, lyric = lyric))
            }
            if (reaction != null && songId != null) onEscape(EscapeMoment(songId, reaction, e.cause.name, at))
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
            if (row != null) dao.updatePlayEnv(row, p.totalMs, p.soloMs, p.rainSoloMs, p.nightSoloMs)
            else {
                val tripId = p.tripStartedAt?.let { tripIdByStart[it] }
                playRowByKey[p.itemKey] = dao.insertPlay(SongPlay(tripId = tripId, songId = p.songId, startedAt = p.startedAt, totalMs = p.totalMs, soloMs = p.soloMs,
                    durationMs = durationOf(p.songId), rainSoloMs = p.rainSoloMs, nightSoloMs = p.nightSoloMs))
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
