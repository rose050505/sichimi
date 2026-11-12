package app.sichimi.gate

/**
 * 시치미 성장. 재료는 혼자 부른 시간, 완창 곡 수, 잘 숨은 기록, 미션 보너스다. 들킨 횟수로는 깎지 않는다.
 *
 * - 혼자 부른 1분 = 1점, 완창 1곡 = [FULL_SONG_POINTS]점
 * - 잘 숨은 기록(대피 시간을 잰 들킬 뻔): 0.3초 이내 5점, 0.5초 이내 3점
 * - 미션 보너스: 완료한 미션마다 [Missions.BONUS]점
 */
object Growth {
    const val FULL_SONG_POINTS = 5
    const val FULL_SONG_RATIO = 0.7
    /** 단계 n(1~4)이 시작하는 점수. */
    val THRESHOLDS = intArrayOf(0, 20, 60, 150)
    val STAGE_NAMES = listOf("흥얼 씨앗", "콧노래", "골목 가수", "무대의 주인공")

    data class Inputs(val soloMs: Long, val fullSongs: Int, val hideReactionsMs: List<Long>, val missionBonus: Int)

    fun hidePoints(reactionMs: Long): Int = when {
        reactionMs <= 300 -> 5
        reactionMs <= 500 -> 3
        else -> 0
    }

    fun points(i: Inputs): Int =
        (i.soloMs / 60_000).toInt() + i.fullSongs * FULL_SONG_POINTS + i.hideReactionsMs.sumOf { hidePoints(it) } + i.missionBonus

    /** 1~4. */
    fun stage(points: Int): Int = THRESHOLDS.indexOfLast { points >= it } + 1

    /** 다음 단계까지 남은 점수. 마지막 단계면 null. */
    fun toNext(points: Int): Int? = THRESHOLDS.getOrNull(stage(points))?.let { it - points }

    /** 지금 단계 안에서 얼마나 왔는가(0~1). 마지막 단계면 1. */
    fun progress(points: Int): Float {
        val s = stage(points)
        val from = THRESHOLDS[s - 1]
        val to = THRESHOLDS.getOrNull(s) ?: return 1f
        return ((points - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    }

    /** 완창: 그 재생에서 혼자 부른 시간이 곡 길이의 70% 이상. */
    fun isFullSong(soloMs: Long, durationMs: Long): Boolean = durationMs > 0 && soloMs >= durationMs * FULL_SONG_RATIO

    /** "다음 단계까지 12점 · 혼자 12분 또는 완창 3곡" */
    fun nextText(points: Int): String {
        val left = toNext(points) ?: return "마지막 단계입니다"
        val songs = (left + FULL_SONG_POINTS - 1) / FULL_SONG_POINTS
        return "다음 단계까지 ${left}점 · 혼자 ${left}분 또는 완창 ${songs}곡"
    }
}
