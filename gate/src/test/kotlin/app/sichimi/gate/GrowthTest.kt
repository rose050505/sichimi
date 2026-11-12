package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthTest {
    @Test fun `점수는 혼자 분, 완창, 잘 숨은 기록, 미션 보너스를 더한다`() {
        val p = Growth.points(Growth.Inputs(soloMs = 12 * 60_000 + 59_000, fullSongs = 2, hideReactionsMs = listOf(270, 450, 900), missionBonus = 15))
        assertEquals(12 + 10 + (5 + 3 + 0) + 15, p)
    }

    @Test fun `들킨 횟수로는 깎지 않는다(느린 대피는 0점)`() {
        assertEquals(0, Growth.hidePoints(2_000))
        assertEquals(5, Growth.hidePoints(300))
        assertEquals(3, Growth.hidePoints(301))
    }

    @Test fun `단계와 남은 점수`() {
        assertEquals(1, Growth.stage(0)); assertEquals(20, Growth.toNext(0))
        assertEquals(2, Growth.stage(20)); assertEquals(40, Growth.toNext(20))
        assertEquals(3, Growth.stage(149)); assertEquals(1, Growth.toNext(149))
        assertEquals(4, Growth.stage(150)); assertNull(Growth.toNext(150))
        assertEquals(1f, Growth.progress(400), 0f)
        assertEquals(0.5f, Growth.progress(40), 1e-6f)
    }

    @Test fun `완창은 곡 길이의 70퍼센트 이상`() {
        assertTrue(Growth.isFullSong(140_000, 200_000))
        assertFalse(Growth.isFullSong(139_999, 200_000))
        assertFalse(Growth.isFullSong(10_000, 0))
    }

    @Test fun `다음 단계 안내 글`() {
        assertEquals("다음 단계까지 12점 · 혼자 12분 또는 완창 3곡", Growth.nextText(8))
        assertEquals("마지막 단계입니다", Growth.nextText(150))
    }
}
