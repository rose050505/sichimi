package app.sichimi.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.sichimi.gate.Gear
import app.sichimi.gate.Reading
import app.sichimi.gate.Zone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 차에서 읽은 값 그대로(설정으로 뒤집기 전). */
data class CabinReadings(
    val seats: Map<Zone, Reading<Boolean>> = Zone.entries.associateWith { Reading.Pending },
    val doors: Map<Zone, Reading<Boolean>> = Zone.entries.associateWith { Reading.Pending },
    val windows: Map<Zone, Reading<Int>> = Zone.entries.associateWith { Reading.Pending },
    /** AAOS CarPropertyManager GEAR_SELECTION에서 읽은 기어(설계 7절, 검증기록 G1). */
    val gearAaos: Gear = Gear.UNKNOWN,
    val turnSignalOn: Boolean = false,
    /** 마지막으로 값이 바뀐 시각(elapsedRealtime). 대피 시간을 잴 때 쓴다. */
    val changedAtMs: Long = 0L,
    /** Vehicle SDK 연결 상태를 사람이 읽을 수 있게. */
    val status: String = "연결 전",
)

/**
 * Pleos Vehicle SDK(좌석·문·창문·방향지시등)와 AAOS CarPropertyManager(기어)를 구독한다.
 * 모든 콜백을 `SC.Signal` 태그로 로그에 남긴다(관문 G1의 증거).
 */
class VehicleSignals(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val _readings = MutableStateFlow(CabinReadings())
    val readings: StateFlow<CabinReadings> = _readings.asStateFlow()

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var carProperty: CarPropertyManager? = null
    private val unregisters = mutableListOf<() -> Unit>()
    private var attempts = 0

    private val seatAreas = mapOf(
        Zone.FRONT_LEFT to SeatValue.ROW_1_LEFT,
        Zone.FRONT_RIGHT to SeatValue.ROW_1_RIGHT,
        Zone.REAR_LEFT to SeatValue.ROW_2_LEFT,
        Zone.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
    )
    private val doorAreas = mapOf(
        Zone.FRONT_LEFT to DoorValue.ROW_1_LEFT,
        Zone.FRONT_RIGHT to DoorValue.ROW_1_RIGHT,
        Zone.REAR_LEFT to DoorValue.ROW_2_LEFT,
        Zone.REAR_RIGHT to DoorValue.ROW_2_RIGHT,
    )
    private val windowAreas = mapOf(
        Zone.FRONT_LEFT to WindowValue.ROW_1_LEFT,
        Zone.FRONT_RIGHT to WindowValue.ROW_1_RIGHT,
        Zone.REAR_LEFT to WindowValue.ROW_2_LEFT,
        Zone.REAR_RIGHT to WindowValue.ROW_2_RIGHT,
    )

    fun start() {
        startAaosGear()
        startPleos()
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        unregisters.forEach { runCatching(it) }
        unregisters.clear()
        runCatching { vehicle?.release() }
        vehicle = null
        runCatching { car?.disconnect() }
        car = null
    }

    // ---------------- Pleos Vehicle SDK ----------------

    private fun startPleos() {
        val v = runCatching { Vehicle(context).also { it.initialize() } }.getOrElse {
            log("Vehicle.initialize 실패: $it")
            setStatus("Vehicle SDK 초기화 실패")
            retryLater()
            return
        }
        vehicle = v
        setStatus("Vehicle SDK 연결 중")
        // 서비스 연결은 비동기라 capability 확인이 실패하면 잠시 뒤 다시 한다.
        main.postDelayed({ subscribeAll(v) }, 300)
    }

    private fun retryLater() {
        if (++attempts > 20) return
        main.postDelayed({
            stop()
            start()
        }, 2_000)
    }

    private fun subscribeAll(v: Vehicle) {
        subscribeSeats(v)
        subscribeDoors(v)
        subscribeWindows(v)
        subscribeTurnSignal(v)
    }

    private fun subscribeSeats(v: Vehicle) {
        val seat = v.getSeat()
        seat.checkSeatOccupancyCapabilities({ config, areas ->
            log("좌석 capability access=${config.accessType} areas=${areas.map { it.values.toList() }}")
            val supported = areas.flatMap { it.values.toList() }.toSet()
            for ((zone, value) in seatAreas) {
                if (value !in supported) {
                    putSeat(zone, Reading.Unsupported, "capability 없음")
                    continue
                }
                val area = SeatArea(value)
                seat.getCurrentSeatOccupancy(area, { occ -> putSeat(zone, occ.toReading(), "get") }, { e -> fail("좌석 get $zone", e) })
                val listener = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupied: Boolean?) = putSeat(zone, occupied.toReading(), "callback")
                    override fun onFailed(e: Exception) = fail("좌석 callback $zone", e)
                }
                seat.registerSeatOccupancy(area, listener)
                unregisters += { seat.unregisterSeatOccupancy(area, listener) }
            }
            setStatus("Vehicle SDK 연결됨")
        }, { e ->
            fail("좌석 capability", e)
            retryLater()
        })
    }

    private fun subscribeDoors(v: Vehicle) {
        val door = v.getDoor()
        door.checkDoorPositionCapabilities({ config, areaMap ->
            log("문 capability access=${config.accessType} areas=${areaMap.map { (a, b) -> "${a.values.toList()}=${b.minValue}..${b.maxValue}" }}")
            val supported = areaMap.mapKeys { it.key.values.toList() }
            for ((zone, value) in doorAreas) {
                val bounds = supported.entries.firstOrNull { value in it.key }?.value
                if (bounds == null) {
                    putDoor(zone, Reading.Unsupported, "capability 없음")
                    continue
                }
                val area = DoorArea(value)
                door.getCurrentDoorPosition(area, { pos -> putDoor(zone, pos.toOpen(bounds), "get pos=$pos") }, { e -> fail("문 get $zone", e) })
                val listener = object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) = putDoor(zone, pos.toOpen(bounds), "callback pos=$pos")
                    override fun onFailed(e: Exception) = fail("문 callback $zone", e)
                }
                door.registerDoorPosition(area, listener)
                unregisters += { door.unregisterDoorPosition(area, listener) }
            }
        }, { e -> fail("문 capability", e) })
    }

    private fun subscribeWindows(v: Vehicle) {
        val window = v.getWindow()
        window.checkWindowPositionCapabilities({ config, areaMap ->
            log("창문 capability access=${config.accessType} areas=${areaMap.map { (a, b) -> "${a.values.toList()}=${b.minValue}..${b.maxValue}" }}")
            val supported = areaMap.mapKeys { it.key.values.toList() }
            for ((zone, value) in windowAreas) {
                val bounds = supported.entries.firstOrNull { value in it.key }?.value
                if (bounds == null) {
                    putWindow(zone, Reading.Unsupported, "capability 없음")
                    continue
                }
                val area = WindowArea(value)
                window.getCurrentWindowPosition(area, { pos -> putWindow(zone, pos.toPercent(bounds), "get pos=$pos") }, { e -> fail("창문 get $zone", e) })
                val listener = object : WindowPositionListener {
                    override fun onWindowPositionUpdated(pos: Int?) = putWindow(zone, pos.toPercent(bounds), "callback pos=$pos")
                    override fun onFailed(e: Exception) = fail("창문 callback $zone", e)
                }
                window.registerWindowPosition(area, listener)
                unregisters += { window.unregisterWindowPosition(area, listener) }
            }
        }, { e -> fail("창문 capability", e) })
    }

    private fun subscribeTurnSignal(v: Vehicle) {
        val ts = v.getTurnSignal()
        val apply: (TurnSignalValue?, String) -> Unit = { value, how ->
            val on = value != null && value != TurnSignalValue.OFF
            log("방향지시등 $how value=$value")
            _readings.update { it.copy(turnSignalOn = on, changedAtMs = now()) }
        }
        ts.getCurrentTurnSignalState({ apply(it, "get") }, { e -> fail("방향지시등 get", e) })
        val listener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = apply(value, "callback")
            override fun onFailed(e: Exception) = fail("방향지시등 callback", e)
        }
        runCatching { ts.registerTurnSignalState(listener) }.onFailure { log("방향지시등 register 실패: $it") }
        unregisters += { ts.unregisterTurnSignalState(listener) }
    }

    // ---------------- AAOS CarPropertyManager ----------------

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = value.value as? Int
            val mapped = aaosGear(raw)
            log("기어(AAOS GEAR_SELECTION) callback raw=$raw status=${value.status} -> $mapped")
            _readings.update { it.copy(gearAaos = mapped, changedAtMs = now()) }
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            log("기어(AAOS GEAR_SELECTION) error prop=$propId zone=$zone")
        }
    }

    private fun startAaosGear() {
        runCatching {
            val c = Car.createCar(context)
            car = c
            val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            carProperty = cpm
            val registered = cpm.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            log("기어(AAOS GEAR_SELECTION) register=$registered")
            unregisters += { cpm.unregisterCallback(gearCallback) }
        }.onFailure { log("CarPropertyManager 실패: $it") }
    }

    private fun aaosGear(raw: Int?): Gear = when (raw) {
        android.car.VehicleGear.GEAR_PARK -> Gear.PARK
        android.car.VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        android.car.VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        null, android.car.VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // DRIVE, 1~9단
    }

    // ---------------- 공통 ----------------

    private fun Boolean?.toReading(): Reading<Boolean> = if (this == null) Reading.Pending else Reading.Known(this)

    private fun Int?.toOpen(bounds: AreaBounds<Int>): Reading<Boolean> =
        if (this == null) Reading.Pending else Reading.Known(this > bounds.minValue)

    private fun Int?.toPercent(bounds: AreaBounds<Int>): Reading<Int> {
        if (this == null) return Reading.Pending
        val span = (bounds.maxValue - bounds.minValue).coerceAtLeast(1)
        return Reading.Known(((this - bounds.minValue) * 100 / span).coerceIn(0, 100))
    }

    private fun putSeat(zone: Zone, r: Reading<Boolean>, how: String) {
        log("좌석 $zone $how -> $r")
        _readings.update { it.copy(seats = it.seats + (zone to r), changedAtMs = now()) }
    }

    private fun putDoor(zone: Zone, r: Reading<Boolean>, how: String) {
        log("문 $zone $how -> $r")
        _readings.update { it.copy(doors = it.doors + (zone to r), changedAtMs = now()) }
    }

    private fun putWindow(zone: Zone, r: Reading<Int>, how: String) {
        log("창문 $zone $how -> $r")
        _readings.update { it.copy(windows = it.windows + (zone to r), changedAtMs = now()) }
    }

    private fun setStatus(s: String) = _readings.update { it.copy(status = s) }

    private fun fail(what: String, e: Exception) { log("$what 실패: ${e.javaClass.simpleName} ${e.message}") }

    private fun now() = SystemClock.elapsedRealtime()

    private fun log(msg: String) { Log.i(TAG, msg) }

    companion object {
        const val TAG = "SC.Signal"
    }
}
