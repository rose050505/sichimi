package app.sichimi.gate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountInTest {
    @Test fun `노래방이고 켜져 있으면 들린다`() {
        assertTrue(CountIn.audible(GateState.KARAOKE, true))
    }

    @Test fun `노래방이 아니면 들리지 않는다`() {
        for (s in listOf(GateState.NORMAL, GateState.ARMING, GateState.RETURN)) assertFalse(CountIn.audible(s, true))
    }

    @Test fun `설정에서 끄면 노래방이어도 들리지 않는다`() {
        assertFalse(CountIn.audible(GateState.KARAOKE, false))
    }
}
