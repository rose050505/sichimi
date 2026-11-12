package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSenseTest {
    @Test fun `곧 도착 - 남은 시간 240초를 받으면 180초 뒤부터`() {
        assertEquals(10_000L + 180_000L, ArrivalGuard.soonAtMs(240, 10_000L))
        assertFalse(ArrivalGuard.arrivingSoon(240, 10_000L, 189_999L, Gear.DRIVE))
        assertTrue(ArrivalGuard.arrivingSoon(240, 10_000L, 190_000L, Gear.DRIVE))
    }

    @Test fun `곧 도착 - 이미 1분 안이면 바로`() {
        assertTrue(ArrivalGuard.arrivingSoon(45, 5_000L, 5_000L, Gear.DRIVE))
    }

    @Test fun `곧 도착 - 경로가 없거나 0초면 아니다`() {
        assertNull(ArrivalGuard.soonAtMs(null, 0))
        assertNull(ArrivalGuard.soonAtMs(0, 0))
        assertFalse(ArrivalGuard.arrivingSoon(null, 0, 10_000_000L, Gear.DRIVE))
    }

    @Test fun `곧 도착 - 기어 P에서는 쓰지 않는다`() {
        assertFalse(ArrivalGuard.arrivingSoon(30, 0, 1_000_000L, Gear.PARK))
        assertTrue(ArrivalGuard.arrivingSoon(30, 0, 1_000_000L, Gear.REVERSE))
    }

    @Test fun `착석 방향 - 기어가 들어가 있는데 운전석이 비었다고 읽히면 뒤집힌 차`() {
        assertEquals(true, SeatPolarity.inferInverted(Gear.DRIVE, Reading.Known(false)))
        assertEquals(false, SeatPolarity.inferInverted(Gear.REVERSE, Reading.Known(true)))
        assertEquals(true, SeatPolarity.inferInverted(Gear.NEUTRAL, Reading.Known(false)))
    }

    @Test fun `착석 방향 - 기어 P이거나 값이 없으면 판단하지 않는다`() {
        assertNull(SeatPolarity.inferInverted(Gear.PARK, Reading.Known(false)))
        assertNull(SeatPolarity.inferInverted(Gear.UNKNOWN, Reading.Known(false)))
        assertNull(SeatPolarity.inferInverted(Gear.DRIVE, Reading.Pending))
        assertNull(SeatPolarity.inferInverted(Gear.DRIVE, Reading.Unsupported))
        assertNull(SeatPolarity.inferInverted(Gear.DRIVE, null))
    }
}
