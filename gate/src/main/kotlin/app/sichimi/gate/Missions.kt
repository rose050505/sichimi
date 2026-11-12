package app.sichimi.gate

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 이번 주 기록. 기기 안 기록(Room)에서 한 주(월~일)만 모아 만든다.
 *
 * @property shows 공연 수: 혼자 부른 곡이 1곡 이상인 주행.
 * @property maxCleanTripSongs 들킬 뻔이 한 번도 없었던 주행 가운데 혼자 부른 곡이 가장 많았던 수.
 * @property rainSongs 와이퍼가 켜진 동안 혼자 10초 넘게 부른 곡 수.
 * @property maxFullPlaysOfOneSong 한 곡을 완창한 횟수 가운데 가장 큰 값.
 */
data class WeekStats(
    val shows: Int = 0,
    val soloMs: Long = 0,
    val soloSongs: Int = 0,
    val bestScore: Int? = null,
    val fastestEscapeMs: Long? = null,
    val exposures: Int = 0,
    val maxCleanTripSongs: Int = 0,
    val rainSongs: Int = 0,
    val nightSongs: Int = 0,
    val ghostWins: Int = 0,
    val maxFullPlaysOfOneSong: Int = 0,
)

enum class MissionId(val title: String, val goal: Int) {
    SCORE_80("80점 넘기기", 80),
    CLEAN_TRIP_3("한 번도 안 들키고 3곡", 3),
    RAIN_SONG("비 오는 날 1곡", 1),
    FAST_ESCAPE("0.3초 안에 대피", 1),
    GHOST_WIN("고스트 이기기", 1),
    SHOWS_3("이번 주 공연 3회", 3),
    NIGHT_SONG("밤에 1곡", 1),
    SAME_SONG_TWICE("같은 곡 두 번 완창", 2),
    SOLO_15("혼자 15분 부르기", 15),
}

/** 이번 주 미션 3개. 주마다 정해진 세트를 차례로 돌린다(사람마다 같다). */
object Missions {
    const val BONUS = 15

    val SETS = listOf(
        listOf(MissionId.SCORE_80, MissionId.CLEAN_TRIP_3, MissionId.RAIN_SONG),
        listOf(MissionId.FAST_ESCAPE, MissionId.GHOST_WIN, MissionId.SHOWS_3),
        listOf(MissionId.NIGHT_SONG, MissionId.SAME_SONG_TWICE, MissionId.SOLO_15),
    )

    /** 세트 차례의 기준 주(이 주가 첫 세트). */
    val ANCHOR_MONDAY: LocalDate = LocalDate.of(2026, 11, 9)

    fun forWeek(monday: LocalDate): List<MissionId> {
        val weeks = ChronoUnit.WEEKS.between(ANCHOR_MONDAY, monday)
        return SETS[Math.floorMod(weeks, SETS.size.toLong()).toInt()]
    }

    /** 지금 값(목표와 같은 단위). */
    fun current(id: MissionId, s: WeekStats): Int = when (id) {
        MissionId.SCORE_80 -> s.bestScore ?: 0
        MissionId.CLEAN_TRIP_3 -> s.maxCleanTripSongs
        MissionId.RAIN_SONG -> s.rainSongs
        MissionId.FAST_ESCAPE -> if ((s.fastestEscapeMs ?: Long.MAX_VALUE) <= 300) 1 else 0
        MissionId.GHOST_WIN -> s.ghostWins
        MissionId.SHOWS_3 -> s.shows
        MissionId.NIGHT_SONG -> s.nightSongs
        MissionId.SAME_SONG_TWICE -> s.maxFullPlaysOfOneSong
        MissionId.SOLO_15 -> (s.soloMs / 60_000).toInt()
    }

    fun done(id: MissionId, s: WeekStats): Boolean = current(id, s) >= id.goal

    /** "54 / 80점" 같은 진행 글. */
    fun progressText(id: MissionId, s: WeekStats): String {
        val c = current(id, s).coerceAtMost(id.goal)
        return when (id) {
            MissionId.SCORE_80 -> "최고 ${current(id, s)}점 / 80점"
            MissionId.FAST_ESCAPE -> if (c >= 1) "달성" else s.fastestEscapeMs?.let { "가장 빠른 대피 %.2f초".format(it / 1000.0) } ?: "아직 대피 없음"
            MissionId.SOLO_15 -> "$c / 15분"
            MissionId.SHOWS_3 -> "$c / 3회"
            MissionId.GHOST_WIN -> "$c / 1승"
            else -> "$c / ${id.goal}곡"
        }
    }
}

/** 주간 투어: 한 번의 주행 = 공연 1회, 일주일에 [GOAL]회면 투어 완주. */
object Tour {
    const val GOAL = 5
    fun complete(s: WeekStats): Boolean = s.shows >= GOAL
}

/** 누적 기록. 배지를 정할 때 쓴다. */
data class LifetimeStats(
    val soloSongs: Int = 0,
    val fastestEscapeMs: Long? = null,
    /** 들킬 뻔한 뒤 다시 노래방이 열린 횟수(잘 숨고 돌아옴). */
    val relievedHides: Int = 0,
    val rainSongs: Int = 0,
    val nightSongs: Int = 0,
    val ghostWins: Int = 0,
    val week: WeekStats = WeekStats(),
)

enum class BadgeId(val title: String, val how: String) {
    FIRST_STAGE("첫 무대", "처음으로 혼자 한 곡"),
    ESCAPE_MASTER("0.3초 대피의 달인", "들킬 뻔한 순간 0.3초 안에 가수 복귀"),
    TRIPLE_SICHIMI("시치미 3단", "들킬 뻔한 뒤 다시 노래방 3번"),
    CLEAN_WEEK("한 번도 안 들킨 주", "한 주에 공연 3회, 들킬 뻔 0번"),
    RAIN_SINGER("빗속의 가수", "와이퍼를 켜고 혼자 한 곡"),
    NIGHT_SINGER("밤의 가수", "밤에 혼자 한 곡"),
    COMMUTE_10("출근길 10곡", "혼자 부른 곡 10곡"),
    GHOST_BUSTER("고스트 버스터", "고스트 대결에서 이김"),
}

object Badges {
    fun earned(s: LifetimeStats): Set<BadgeId> = buildSet {
        if (s.soloSongs >= 1) add(BadgeId.FIRST_STAGE)
        if ((s.fastestEscapeMs ?: Long.MAX_VALUE) <= 300) add(BadgeId.ESCAPE_MASTER)
        if (s.relievedHides >= 3) add(BadgeId.TRIPLE_SICHIMI)
        if (s.week.shows >= 3 && s.week.exposures == 0) add(BadgeId.CLEAN_WEEK)
        if (s.rainSongs >= 1) add(BadgeId.RAIN_SINGER)
        if (s.nightSongs >= 1) add(BadgeId.NIGHT_SINGER)
        if (s.soloSongs >= 10) add(BadgeId.COMMUTE_10)
        if (s.ghostWins >= 1) add(BadgeId.GHOST_BUSTER)
    }
}
