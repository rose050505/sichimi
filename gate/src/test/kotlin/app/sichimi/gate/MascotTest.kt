package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotTest {
    @Test fun `노래방이 아니면 그리지 않는다`() {
        val m = MascotMood()
        for (s in listOf(GateState.NORMAL, GateState.ARMING, GateState.RETURN)) assertEquals(MascotPose.AWAY, m.update(s, 0, 0))
        assertFalse(MascotPose.AWAY.visible)
    }

    @Test fun `들킬 뻔하면 숨고 0점3초 뒤에는 흔적이 없다`() {
        val m = MascotMood()
        assertEquals(MascotPose.SINGING, m.update(GateState.KARAOKE, 0, 0))
        assertEquals(MascotPose.HIDING, m.update(GateState.NORMAL, 1, 1_000))
        assertEquals(1_300L, m.nextChangeMs())
        assertEquals(0.5f, m.hideFraction(1_150), 1e-6f)
        assertEquals(MascotPose.HIDING, m.update(GateState.NORMAL, 1, 1_299))
        assertEquals(MascotPose.AWAY, m.update(GateState.NORMAL, 1, 1_300))
        assertNull(m.nextChangeMs())
    }

    @Test fun `다시 혼자가 되어 노래방이 열리면 휴 안 들켰다, 4초 뒤 다시 노래`() {
        val m = MascotMood()
        m.update(GateState.KARAOKE, 0, 0)
        m.update(GateState.NORMAL, 1, 1_000)
        m.update(GateState.NORMAL, 1, 2_000)
        m.update(GateState.ARMING, 1, 10_000)
        assertEquals(MascotPose.RELIEVED, m.update(GateState.KARAOKE, 1, 18_000))
        assertEquals(MascotPose.RELIEVED, m.update(GateState.KARAOKE, 1, 21_999))
        assertEquals(MascotPose.SINGING, m.update(GateState.KARAOKE, 1, 22_000))
    }

    @Test fun `들킬 뻔 없이 노래방이 끝나면(일시정지 등) 숨는 동작 없이 사라지고, 돌아와도 안도하지 않는다`() {
        val m = MascotMood()
        m.update(GateState.KARAOKE, 0, 0)
        assertEquals(MascotPose.AWAY, m.update(GateState.RETURN, 0, 1_000))
        assertEquals(MascotPose.SINGING, m.update(GateState.KARAOKE, 0, 3_000))
    }

    @Test fun `숨는 중에 다시 노래방이 되어도 안도부터`() {
        val m = MascotMood()
        m.update(GateState.KARAOKE, 0, 0)
        m.update(GateState.NORMAL, 1, 100)
        assertEquals(MascotPose.RELIEVED, m.update(GateState.KARAOKE, 1, 200))
    }

    @Test fun `노래방이 아닐 때 들킬 뻔이 와도 다음 노래방에서 안도`() {
        val m = MascotMood()
        m.update(GateState.NORMAL, 0, 0)
        assertEquals(MascotPose.AWAY, m.update(GateState.NORMAL, 1, 100))
        assertEquals(MascotPose.RELIEVED, m.update(GateState.KARAOKE, 1, 9_000))
    }

    @Test fun `차 상태에 따른 모습`() {
        assertEquals(MascotLook(raincoat = true), MascotMood.look(true, false, listOf(0, 0, 0, 0), 2))
        assertEquals(MascotLook(night = true, breeze = true), MascotMood.look(false, true, listOf(2, 0), 2))
        assertFalse(MascotMood.look(false, false, listOf(0, 30), 2).breeze)
    }

    @Test fun `덮개 카드는 주차하고 혼자일 때만, 점수 - 배지 - 요약 순`() {
        assertNull(ParkedQueue.next(false, true, listOf(3), listOf(1), 9))
        assertNull(ParkedQueue.next(true, false, listOf(3), listOf(1), 9))
        assertEquals(Reveal.Score(2), ParkedQueue.next(true, true, listOf(3, 2), listOf(1), 9))
        assertEquals(Reveal.Award(1), ParkedQueue.next(true, true, emptyList(), listOf(4, 1), 9))
        assertEquals(Reveal.Summary(9), ParkedQueue.next(true, true, emptyList(), emptyList(), 9))
        assertNull(ParkedQueue.next(true, true, emptyList(), emptyList(), null))
    }

    @Test fun `고스트는 가이드보다 작게, 가수가 돌아오면 함께 사라진다`() {
        assertEquals(0.10f, GhostGain.level(10, 20), 1e-6f)
        assertEquals(0.10f, GhostGain.level(20, 20), 1e-6f)
        assertEquals(0f, GhostGain.level(10, 0), 0f)
        val lv = GhostGain.level(10, 20)
        assertEquals(lv, GhostGain.follow(0.2f, 0.2f, lv), 1e-6f)
        assertEquals(0f, GhostGain.follow(1f, 0.2f, lv), 1e-6f)
        assertEquals(lv / 2, GhostGain.follow(0.6f, 0.2f, lv), 1e-6f)
        assertEquals(lv, GhostGain.follow(0f, 0.2f, lv), 1e-6f)
    }

    @Test fun `대피 랭킹 값 범위와 갱신`() {
        assertFalse(EscapeRule.valid(99)); assertTrue(EscapeRule.valid(100)); assertTrue(EscapeRule.valid(3_000)); assertFalse(EscapeRule.valid(3_001))
        assertTrue(EscapeRule.improves(250, null)); assertTrue(EscapeRule.improves(250, 260))
        assertFalse(EscapeRule.improves(260, 260)); assertFalse(EscapeRule.improves(50, null))
    }
}
