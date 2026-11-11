package app.sichimi.gate

/**
 * 마지막 곡 알림(설계 2순위, 장면 7). 내비 남은 시간이 "한 곡 더" 구간에 들어오면 한 줄을 한 번 말한다.
 *
 * - 혼자만의 노래방([GateState.KARAOKE])일 때만 말한다. 동승자가 있을 때 앱이 먼저 말을 꺼내지 않는다(설계 1절 원칙 2).
 * - 한 경로에 한 번만 말한다. 경로가 없어지면(안내 종료, 도착, 취소) 다시 말할 수 있다.
 * - 구간 밖이거나 노래방이 아니면 말하지 않고 기다린다. 구간 안에 있는 동안 노래방이 되면 그때 말한다.
 */
class LastSongCue(
    private val windowFromSec: Int = 180,
    private val windowUntilSec: Int = 240,
) {
    private var spoken = false

    /**
     * @param remainSec 목적지까지 남은 시간(초). 경로가 없으면 null.
     * @return 지금 말할 문장. 말하지 않으면 null.
     */
    fun update(remainSec: Int?, state: GateState): String? {
        if (remainSec == null) {
            spoken = false
            return null
        }
        if (spoken || state != GateState.KARAOKE) return null
        if (remainSec < windowFromSec || remainSec >= windowUntilSec) return null
        spoken = true
        return "도착까지 ${remainSec / 60}분, 한 곡 더 됩니다"
    }
}
