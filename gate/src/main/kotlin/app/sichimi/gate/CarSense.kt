package app.sichimi.gate

/**
 * 도착 직전 미리 시치미(11/13 추가). 내비 목적지까지 남은 시간을 받으면, 받은 시각 + (남은 시간 − [LEAD_SEC]초)부터
 * 「곧 도착」으로 본다. 주차장 입구·발렛·픽업처럼 창문을 내리거나 누가 탈 순간 전에 가수 목소리를 먼저 돌려놓는다.
 * 남은 시간은 내비가 이벤트를 줄 때만 오므로(되풀이해 묻지 않음) 받은 시각에서 시간을 세어 추정한다. 새 값이 오면 다시 센다.
 * 기어 P(이미 세움)에서는 쓰지 않는다. 문지기에서는 후진·방향지시등과 같은 「안전 장치」 입력이다(설정으로 함께 끔).
 */
object ArrivalGuard {
    const val LEAD_SEC = 60

    /** 곧 도착으로 볼 시각(elapsedRealtime ms). 경로가 없으면 null. */
    fun soonAtMs(remainSec: Int?, receivedAtMs: Long): Long? =
        remainSec?.takeIf { it > 0 }?.let { receivedAtMs + (it - LEAD_SEC).coerceAtLeast(0) * 1000L }

    fun arrivingSoon(remainSec: Int?, receivedAtMs: Long, nowMs: Long, gear: Gear): Boolean {
        if (gear == Gear.PARK) return false
        val at = soonAtMs(remainSec, receivedAtMs) ?: return false
        return nowMs >= at
    }
}

/**
 * 착석 값 방향 자동 맞춤(11/13 추가). 차가 기어 D·R·N에 들어가 있으면 운전석에는 사람이 있다.
 * 그때 운전석이 「비었음」으로 읽히면 이 차의 착석 값은 뒤집혀 온다(Pleos Connect Emulator 2.1.0이 그렇다), 「앉음」이면 그대로다.
 * 판단할 수 없으면 null(지금 설정을 그대로 둔다). 운전석 착석이 혼자 조건의 첫째라, 잘못 맞춰도 노래방이 열리지 않는 쪽이다.
 */
object SeatPolarity {
    fun inferInverted(gear: Gear, driverRaw: Reading<Boolean>?): Boolean? {
        if (gear != Gear.DRIVE && gear != Gear.REVERSE && gear != Gear.NEUTRAL) return null
        return (driverRaw as? Reading.Known)?.let { !it.value }
    }
}
