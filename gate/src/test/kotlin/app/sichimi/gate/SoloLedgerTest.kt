package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoloLedgerTest {
    private val ledger = SoloLedger()

    private fun LedgerEvent.trip() = (this as LedgerEvent.TripEnded).trip
    private fun List<LedgerEvent>.plays() = filterIsInstance<LedgerEvent.SongPlayEnded>().map { it.play }

    @Test fun `주행은 P를 벗어난 때부터 다시 P가 될 때까지다`() {
        assertTrue(ledger.onGear(Gear.PARK, 0).isEmpty())
        assertEquals(listOf(LedgerEvent.TripStarted(1_000)), ledger.onGear(Gear.DRIVE, 1_000))
        assertTrue(ledger.onGear(Gear.REVERSE, 2_000).isEmpty())
        assertTrue(ledger.onGear(Gear.DRIVE, 3_000).isEmpty())
        val trip = ledger.onGear(Gear.PARK, 10_000).single().trip()
        assertEquals(1_000L, trip.startedAt)
        assertEquals(10_000L, trip.endedAt)
        assertEquals(false, ledger.inTrip)
    }

    @Test fun `노래방이면서 재생 중인 시간만 혼자 부른 시간이다`() {
        ledger.onGear(Gear.DRIVE, 0)
        ledger.onPlayback("a#1", "a", true, 0)
        ledger.onGateState(GateState.ARMING, 0)
        ledger.onGateState(GateState.KARAOKE, 5_000)
        ledger.onPlayback("a#1", "a", false, 20_000)   // 일시정지 10초
        ledger.onPlayback("a#1", "a", true, 30_000)
        ledger.onGateState(GateState.RETURN, 40_000)   // 잠시 복귀 2초는 빼고
        ledger.onGateState(GateState.KARAOKE, 42_000)
        ledger.onGateState(GateState.NORMAL, 50_000)
        val events = ledger.onPlayback(null, null, false, 60_000)
        val play = events.plays().single()
        assertEquals("a", play.songId)
        assertEquals(50_000L, play.totalMs)             // 0~20초 + 30~60초
        assertEquals(15_000L + 10_000 + 8_000, play.soloMs)
        assertEquals(0L, play.tripStartedAt)
        val trip = ledger.onGear(Gear.PARK, 61_000).single().trip()
        assertEquals(33_000L, trip.soloMs)
        assertEquals(1, trip.soloSongs)
    }

    @Test fun `10초 이상 혼자 부른 곡만 부른 곡으로 센다`() {
        ledger.onGear(Gear.DRIVE, 0)
        ledger.onGateState(GateState.KARAOKE, 0)
        ledger.onPlayback("a#1", "a", true, 0)
        ledger.onPlayback("b#2", "b", true, 9_999)       // a: 9.999초
        ledger.onPlayback("a#3", "a", true, 19_999)      // b: 10초, 같은 곡 a를 다시
        ledger.onPlayback("a#3", "a", true, 40_000)
        val trip = ledger.onGear(Gear.PARK, 40_000).single().trip()
        assertEquals(2, trip.soloSongs)
    }

    @Test fun `곡이 바뀌면 이전 곡 재생을 마감한다`() {
        ledger.onPlayback("a#1", "a", true, 0)
        val events = ledger.onPlayback("b#2", "b", true, 3_000)
        assertEquals(listOf(SongPlaySummary("a", 0, 3_000, 0, null, "a#1")), events.plays())
    }

    @Test fun `소리를 한 번도 내지 않은 곡은 남기지 않는다`() {
        ledger.onPlayback("a#1", "a", false, 0)
        assertTrue(ledger.onPlayback("b#2", "b", false, 3_000).isEmpty())
    }

    @Test fun `들킬 뻔한 횟수와 가장 빠른 대피를 센다`() {
        ledger.onGear(Gear.DRIVE, 0)
        ledger.onExposure(340, 1_000)
        ledger.onExposure(210, 2_000)
        ledger.onExposure(null, 3_000) // 잠시 복귀 중이라 대피 시간 없음
        val trip = ledger.onGear(Gear.PARK, 4_000).single().trip()
        assertEquals(3, trip.exposures)
        assertEquals(210L, trip.fastestReactionMs)
    }

    @Test fun `대피 시간을 잰 적이 없으면 가장 빠른 대피는 없다`() {
        ledger.onGear(Gear.DRIVE, 0)
        assertNull(ledger.onGear(Gear.PARK, 1_000).single().trip().fastestReactionMs)
    }

    @Test fun `주차 중 노래방 시간은 곡에는 남고 주행에는 들어가지 않는다`() {
        ledger.onGear(Gear.PARK, 0)
        ledger.onGateState(GateState.KARAOKE, 0)
        ledger.onPlayback("a#1", "a", true, 0)
        val play = ledger.close(30_000).plays().single()
        assertEquals(30_000L, play.soloMs)
        assertNull(play.tripStartedAt)
        assertEquals(0L, ledger.currentTripSoloMs)
    }

    @Test fun `주행을 마치면 진행 중인 곡의 혼자 시간도 그 주행에 들어간다`() {
        ledger.onGear(Gear.DRIVE, 0)
        ledger.onGateState(GateState.KARAOKE, 0)
        ledger.onPlayback("a#1", "a", true, 0)
        val trip = ledger.onGear(Gear.PARK, 12_000).single().trip()
        assertEquals(12_000L, trip.soloMs)
        assertEquals(1, trip.soloSongs)
    }

    @Test fun `재생 중인 곡의 지금까지 기록을 볼 수 있다`() {
        ledger.onGear(Gear.DRIVE, 0)
        ledger.onGateState(GateState.KARAOKE, 0)
        ledger.onPlayback("a#1", "a", true, 0)
        assertEquals(SongPlaySummary("a", 0, 7_000, 7_000, 0, "a#1"), ledger.currentPlay(7_000))
        ledger.onPlayback(null, null, false, 8_000)
        assertNull(ledger.currentPlay(9_000))
    }
}
