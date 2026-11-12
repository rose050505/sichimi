package app.sichimi.gate

/**
 * 차량 신호 하나의 읽기 결과.
 *
 * - [Known]: 값을 받았다.
 * - [Pending]: 아직 받지 못했거나 읽다가 실패했다. 문지기는 이 값을 "안전하지 않음"으로 본다.
 * - [Unsupported]: 이 차에는 그 영역이 없다(capability 확인 결과). 좌석·문·창문마다 다르게 다룬다.
 */
sealed interface Reading<out T> {
    data class Known<T>(val value: T) : Reading<T>
    data object Pending : Reading<Nothing>
    data object Unsupported : Reading<Nothing>
}

fun <T> known(value: T): Reading<T> = Reading.Known(value)

/** 좌석·문·창문의 위치. 왼쪽 운전석 기준. */
enum class Zone { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * 문지기가 보는 차 안 상태 전체.
 *
 * @property seats 좌석별 착석 여부(true = 사람이 앉아 있음). 운전석은 [Zone.FRONT_LEFT].
 * @property doors 문별 열림 여부(true = 열림).
 * @property windows 창문별 열린 정도(0~100%).
 * @property turnSignalOn 방향지시등이 켜져 있는가.
 * @property playing 사용자가 재생한 곡이 지금 소리를 내고 있는가.
 * @property arrivingSoon 내비 목적지에 곧 도착한다([ArrivalGuard]). 후진·방향지시등과 같은 안전 장치 입력.
 */
data class CabinInput(
    val seats: Map<Zone, Reading<Boolean>>,
    val doors: Map<Zone, Reading<Boolean>>,
    val windows: Map<Zone, Reading<Int>>,
    val gear: Gear,
    val turnSignalOn: Boolean,
    val playing: Boolean,
    val arrivingSoon: Boolean = false,
) {
    companion object {
        /** 앱이 막 켜져 아무 신호도 받지 못한 상태. */
        val INITIAL = CabinInput(
            seats = Zone.entries.associateWith { Reading.Pending },
            doors = Zone.entries.associateWith { Reading.Pending },
            windows = Zone.entries.associateWith { Reading.Pending },
            gear = Gear.UNKNOWN,
            turnSignalOn = false,
            playing = false,
        )
    }
}

/** 들킬 뻔한 원인. 여러 개가 겹치면 문, 좌석, 창문 순으로 하나만 적는다. */
enum class ExposureCause { DOOR, SEAT, WINDOW }

/**
 * 혼자인가.
 * 운전석에 앉았고, 동승석이 비었다는 것을 읽을 수 있고, 뒷좌석이 비어 있다.
 * 동승석을 읽을 수 없는 차(Unsupported)에서는 혼자라고 보지 않는다.
 * 뒷좌석을 읽을 수 없는 차(Unsupported)에서는 비어 있다고 본다.
 */
fun CabinInput.isSolo(): Boolean {
    val driver = seats[Zone.FRONT_LEFT]
    val passenger = seats[Zone.FRONT_RIGHT]
    if (driver != Reading.Known(true)) return false
    if (passenger != Reading.Known(false)) return false
    return listOf(Zone.REAR_LEFT, Zone.REAR_RIGHT).all { zone ->
        when (val r = seats[zone]) {
            is Reading.Known -> !r.value
            Reading.Unsupported -> true
            Reading.Pending, null -> false
        }
    }
}

/** 문이 모두 닫혔는가. 이 차에 없는 문은 빼고 보되, 읽힌 문이 하나도 없으면 닫혔다고 보지 않는다. */
fun CabinInput.allDoorsClosed(): Boolean {
    val present = doors.values.filter { it != Reading.Unsupported }
    if (present.isEmpty()) return false
    return present.all { it == Reading.Known(false) }
}

/** 창문이 모두 기준 이하로 닫혔는가. 이 차에 없는 창문은 빼고 본다. */
fun CabinInput.allWindowsClosed(thresholdPct: Int): Boolean {
    val present = windows.values.filter { it != Reading.Unsupported }
    if (present.isEmpty()) return false
    return present.all { it is Reading.Known && it.value <= thresholdPct }
}

fun CabinInput.isSealed(thresholdPct: Int): Boolean =
    allDoorsClosed() && allWindowsClosed(thresholdPct)

/** 혼자·밀폐가 깨진 원인을 하나만 고른다. 깨지지 않았으면 null. */
fun CabinInput.breachCause(thresholdPct: Int): ExposureCause? = when {
    !allDoorsClosed() -> ExposureCause.DOOR
    !isSolo() -> ExposureCause.SEAT
    !allWindowsClosed(thresholdPct) -> ExposureCause.WINDOW
    else -> null
}
