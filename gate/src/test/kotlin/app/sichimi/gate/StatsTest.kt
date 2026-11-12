package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    private val min = 60_000L
    private val plays = listOf(
        PlayRow(1, "a", 1_000, 200_000, 150_000),                       // 완창
        PlayRow(1, "b", 2_000, 200_000, 20_000, rainSoloMs = 15_000),   // 비
        PlayRow(1, "a", 3_000, 200_000, 160_000),                       // 완창
        PlayRow(2, "c", 4_000, 200_000, 30_000, nightSoloMs = 30_000),  // 밤
        PlayRow(2, "a", 5_000, 200_000, 5_000),                         // 10초 못 넘김: 곡으로 안 셈
        PlayRow(3, "b", 9_000_000, 200_000, 60_000),                    // 다음 주
        PlayRow(null, "b", 6_000, 200_000, 12_000),                     // 주차 중
    )
    private val exposures = listOf(ExposureRow(2, 4_500, 270, relievedAt = 4_600), ExposureRow(2, 4_700, null), ExposureRow(3, 9_000_100, 400))
    private val scores = listOf(ScoreRow("a", 1_500, 82, true, ghostScore = 80), ScoreRow("a", 3_500, 90, false, ghostScore = 70), ScoreRow("b", 9_000_000, 95, true))

    @Test fun `이번 주 기록`() {
        val w = Stats.week(0, 8_000_000, plays, exposures, scores)
        assertEquals(2, w.shows) // 주행 1, 2(주차 중 곡은 공연이 아님)
        assertEquals(150_000L + 20_000 + 160_000 + 30_000 + 5_000 + 12_000, w.soloMs)
        assertEquals(5, w.soloSongs)
        assertEquals(82, w.bestScore)
        assertEquals(270L, w.fastestEscapeMs)
        assertEquals(2, w.exposures)
        assertEquals(3, w.maxCleanTripSongs) // 주행 1: 3곡, 들킬 뻔 없음
        assertEquals(1, w.rainSongs)
        assertEquals(1, w.nightSongs)
        assertEquals(1, w.ghostWins) // 끝까지 못 부른 판은 이겨도 안 셈
        assertEquals(2, w.maxFullPlaysOfOneSong)
    }

    @Test fun `빈 주`() {
        val w = Stats.week(20_000_000, 30_000_000, plays, exposures, scores)
        assertEquals(WeekStats(), w)
        assertNull(w.bestScore)
    }

    @Test fun `누적 기록과 성장 재료`() {
        val w = Stats.week(0, 8_000_000, plays, exposures, scores)
        val l = Stats.lifetime(w, plays, exposures, scores)
        assertEquals(6, l.soloSongs)
        assertEquals(270L, l.fastestEscapeMs)
        assertEquals(1, l.relievedHides)
        val g = Stats.growth(plays, exposures, 15)
        assertEquals(2, g.fullSongs)
        assertEquals(listOf(270L, 400L), g.hideReactionsMs)
        assertEquals((437_000L / min).toInt() + 10 + 5 + 3 + 15, Growth.points(g))
    }

    @Test fun `셋리스트는 많이 부른 순`() {
        assertEquals(listOf("a", "b", "c"), Stats.setlist(0, 8_000_000, plays))
    }
}
