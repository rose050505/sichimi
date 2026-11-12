package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class WeekMissionTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, seoul).toInstant().toEpochMilli()

    @Test fun `ISO 주 이름과 월요일`() {
        assertEquals("2026-W46", Week.key(ms(2026, 11, 12), seoul))
        assertEquals("2026-W46", Week.key(ms(2026, 11, 15, 23), seoul))
        assertEquals("2026-W47", Week.key(ms(2026, 11, 16, 0), seoul))
        assertEquals("2026-W53", Week.key(ms(2027, 1, 1), seoul))
        assertEquals(LocalDate.of(2026, 11, 9), Week.monday(ms(2026, 11, 15, 23), seoul))
        assertEquals(ms(2026, 11, 9, 0), Week.startMs(ms(2026, 11, 12), seoul))
        assertTrue(Week.KEY_PATTERN.matches("2026-W46"))
        assertFalse(Week.KEY_PATTERN.matches("2026-46"))
    }

    @Test fun `밤은 19시부터 6시 전까지`() {
        assertTrue(Week.isNight(ms(2026, 11, 12, 19), seoul))
        assertTrue(Week.isNight(ms(2026, 11, 12, 5), seoul))
        assertFalse(Week.isNight(ms(2026, 11, 12, 6), seoul))
        assertFalse(Week.isNight(ms(2026, 11, 12, 18), seoul))
    }

    @Test fun `이번 주 미션은 세트를 주마다 돌린다`() {
        assertEquals(listOf(MissionId.SCORE_80, MissionId.CLEAN_TRIP_3, MissionId.RAIN_SONG), Missions.forWeek(LocalDate.of(2026, 11, 9)))
        assertEquals(Missions.SETS[1], Missions.forWeek(LocalDate.of(2026, 11, 16)))
        assertEquals(Missions.SETS[2], Missions.forWeek(LocalDate.of(2026, 11, 2)))
        assertEquals(Missions.SETS[0], Missions.forWeek(LocalDate.of(2026, 11, 30)))
    }

    @Test fun `미션 달성 판정`() {
        val s = WeekStats(bestScore = 81, maxCleanTripSongs = 2, rainSongs = 1, fastestEscapeMs = 301, shows = 3, soloMs = 15 * 60_000L, maxFullPlaysOfOneSong = 2)
        assertTrue(Missions.done(MissionId.SCORE_80, s))
        assertFalse(Missions.done(MissionId.CLEAN_TRIP_3, s))
        assertTrue(Missions.done(MissionId.RAIN_SONG, s))
        assertFalse(Missions.done(MissionId.FAST_ESCAPE, s))
        assertTrue(Missions.done(MissionId.FAST_ESCAPE, s.copy(fastestEscapeMs = 300)))
        assertTrue(Missions.done(MissionId.SHOWS_3, s))
        assertTrue(Missions.done(MissionId.SOLO_15, s))
        assertTrue(Missions.done(MissionId.SAME_SONG_TWICE, s))
        assertFalse(Missions.done(MissionId.GHOST_WIN, s))
        assertEquals("최고 81점 / 80점", Missions.progressText(MissionId.SCORE_80, s))
        assertEquals("2 / 3곡", Missions.progressText(MissionId.CLEAN_TRIP_3, s))
        assertEquals("0 / 1승", Missions.progressText(MissionId.GHOST_WIN, s))
    }

    @Test fun `공연 5회면 주간 투어 완주`() {
        assertFalse(Tour.complete(WeekStats(shows = 4)))
        assertTrue(Tour.complete(WeekStats(shows = 5)))
    }

    @Test fun `배지 8개`() {
        assertEquals(emptySet<BadgeId>(), Badges.earned(LifetimeStats()))
        val all = Badges.earned(LifetimeStats(soloSongs = 10, fastestEscapeMs = 280, relievedHides = 3, rainSongs = 1, nightSongs = 1, ghostWins = 1,
            week = WeekStats(shows = 3, exposures = 0)))
        assertEquals(BadgeId.entries.toSet(), all)
        assertFalse(BadgeId.CLEAN_WEEK in Badges.earned(LifetimeStats(week = WeekStats(shows = 3, exposures = 1))))
        assertFalse(BadgeId.CLEAN_WEEK in Badges.earned(LifetimeStats(week = WeekStats(shows = 2, exposures = 0))))
        assertFalse(BadgeId.ESCAPE_MASTER in Badges.earned(LifetimeStats(fastestEscapeMs = 301)))
        assertEquals(setOf(BadgeId.FIRST_STAGE), Badges.earned(LifetimeStats(soloSongs = 9)))
    }
}
