package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolStateTest {

    @Test
    fun updateAbsoluteAppliesWhenWithinCap() {
        val state = ProtocolState()
        assertTrue(state.updateAbsolute(1_000L, 1_600L))
        assertEquals(-600L, state.skewSeconds())
        assertEquals(400L, state.unixSeconds(nowMillis = 1_000_000L))
    }

    @Test
    fun updateAbsoluteRejectsSkewBeyond24h() {
        val state = ProtocolState()
        val local = 1_700_000_000L
        val server = local + ProtocolState.DEFAULT_MAX_SKEW_SECONDS + 1
        assertFalse(state.updateAbsolute(server, local))
        assertEquals(0L, state.skewSeconds())
    }

    @Test
    fun resetForTestClearsSkew() {
        val state = ProtocolState()
        assertTrue(state.updateAbsolute(50, 10))
        assertEquals(40L, state.skewSeconds())
        state.resetForTest()
        assertEquals(0L, state.skewSeconds())
    }

    @Test
    fun secondUpdateReplacesAbsolutelyNotIncrementally() {
        val state = ProtocolState()
        assertTrue(state.updateAbsolute(100, 90))
        assertEquals(10L, state.skewSeconds())
        assertTrue(state.updateAbsolute(100, 80))
        assertEquals(20L, state.skewSeconds())
    }
}
