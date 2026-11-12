package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeTest {
    private fun hop(v: Short, n: Int = 320) = ShortArray(n) { v }

    @Test
    fun 이어지는_토막은_빈틈_없이_이어_쓴다() {
        val t = TakeTimeline(10_000)
        // 가운데 시각이 10ms, 30ms, 51ms(1ms 흔들림)
        t.write(10, hop(1)); t.write(30, hop(2)); t.write(51, hop(3))
        assertEquals(0, t.first)
        assertEquals(960, t.end)
        assertEquals(0, t.jumps)
        assertEquals(1.toShort(), t.pcm[319]); assertEquals(2.toShort(), t.pcm[320]); assertEquals(3.toShort(), t.pcm[959])
    }

    @Test
    fun 멀리_떨어진_토막은_건너뛰고_사이는_무음() {
        val t = TakeTimeline(10_000)
        t.write(1_010, hop(5))
        t.write(3_010, hop(6)) // 2초 뒤(일시정지 뒤)
        assertEquals(1, t.jumps)
        assertEquals(16_000, t.first)
        assertEquals(1_000L, t.startMs)
        assertEquals(2_020L, t.lengthMs)
        assertEquals(0.toShort(), t.pcm[20_000])
        assertEquals(6.toShort(), t.pcm[48_000])
    }

    @Test
    fun 곡_밖으로_나가는_토막은_잘라서_쓴다() {
        val t = TakeTimeline(1_000)
        t.write(0, hop(7)) // 앞 절반이 0 앞
        assertEquals(0, t.first); assertEquals(160, t.end)
        t.write(5_000, hop(8)) // 버퍼 밖
        assertEquals(160, t.end)
        assertFalse(t.isEmpty)
    }

    @Test
    fun 아무것도_안_쓰면_비었다() {
        val t = TakeTimeline(1_000)
        assertTrue(t.isEmpty); assertEquals(0L, t.lengthMs)
    }

    @Test
    fun 짧은_곡은_최고_비트레이트() {
        val p = TakeBudget.plan(180_000)
        assertEquals(32_000, p.bitrate); assertEquals(180_000L, p.keepMs); assertFalse(p.cut)
        assertTrue(p.bitrate / 8 * 180 <= TakeBudget.TARGET_BYTES)
    }

    @Test
    fun 가장_긴_곡도_통째로_들어간다() {
        val p = TakeBudget.plan(228_551) // 02-destroy
        assertEquals(29_000, p.bitrate); assertFalse(p.cut)
        assertTrue(p.bitrate / 8.0 * 228.551 <= TakeBudget.TARGET_BYTES)
    }

    @Test
    fun 아주_긴_녹음은_7분까지만() {
        val p = TakeBudget.plan(600_000)
        assertEquals(16_000, p.bitrate); assertEquals(420_000L, p.keepMs); assertTrue(p.cut)
        assertTrue(p.bitrate / 8.0 * 420 <= TakeBudget.TARGET_BYTES)
    }

    @Test
    fun 넘치면_비트레이트부터_줄이고_바닥이면_길이를_줄인다() {
        val a = TakeBudget.shrink(TakeBudget.Plan(32_000, 200_000, false))
        assertEquals(25_000, a.bitrate); assertEquals(200_000L, a.keepMs)
        val b = TakeBudget.shrink(TakeBudget.Plan(16_000, 420_000, true))
        assertEquals(16_000, b.bitrate); assertEquals(357_000L, b.keepMs); assertTrue(b.cut)
    }
}
