package dev.experimental.edgetts

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests JVM para ProtocolState (Fase 2 - V-3.1.md Sección 7).
 * Sin red, deterministas, verifican deriva de reloj atómica.
 */
class ProtocolStateTest {

    @Test
    fun `initial skew is zero`() {
        val state = ProtocolState()
        assertEquals(0.0, state.getSkewSeconds(), 0.0)
    }

    @Test
    fun `adjustClockSkew accepts reasonable skew under 24 hours`() {
        val state = ProtocolState()
        val localTime = 1_000_000L
        val serverTime = localTime + 3600  // 1 hora de diferencia

        val result = state.adjustClockSkew(serverTime, localTime)

        assertTrue("Debería aceptar skew de 1 hora", result)
        assertEquals(3600.0, state.getSkewSeconds(), 0.0)
    }

    @Test
    fun `adjustClockSkew rejects excessive skew over 24 hours`() {
        val state = ProtocolState()
        val localTime = 1_000_000L
        val serverTime = localTime + 100_000  // ~27 horas de diferencia

        val result = state.adjustClockSkew(serverTime, localTime)

        assertFalse("Debería rechazar skew > 24h", result)
        assertEquals(0.0, state.getSkewSeconds(), 0.0)
    }

    @Test
    fun `adjustClockSkew handles negative skew correctly`() {
        val state = ProtocolState()
        val localTime = 1_000_000L
        val serverTime = localTime - 7200  // 2 horas atrás

        val result = state.adjustClockSkew(serverTime, localTime)

        assertTrue("Debería aceptar skew negativo razonable", result)
        assertEquals(-7200.0, state.getSkewSeconds(), 0.0)
    }

    @Test
    fun `getAdjustedUnixSeconds returns local time plus skew`() {
        val state = ProtocolState()
        state.adjustClockSkew(1_000_000L, 996_400L)  // skew = 3600

        val adjusted = state.getAdjustedUnixSeconds()
        val expectedMin = System.currentTimeMillis() / 1000 + 3599
        val expectedMax = System.currentTimeMillis() / 1000 + 3601

        assertTrue("Debería retornar tiempo local + 3600s", 
            adjusted in expectedMin..expectedMax)
    }

    @Test
    fun `resetForTest clears skew to zero`() {
        val state = ProtocolState()
        state.adjustClockSkew(1_000_000L, 996_400L)  // skew = 3600
        assertEquals(3600.0, state.getSkewSeconds(), 0.0)

        state.resetForTest()

        assertEquals(0.0, state.getSkewSeconds(), 0.0)
    }

    @Test
    fun `multiple adjustments overwrite previous skew`() {
        val state = ProtocolState()
        
        state.adjustClockSkew(1_000_000L, 996_400L)  // skew = 3600
        assertEquals(3600.0, state.getSkewSeconds(), 0.0)
        
        state.adjustClockSkew(2_000_000L, 1_998_200L)  // skew = 1800
        
        assertEquals(1800.0, state.getSkewSeconds(), 0.0)
    }

    @Test
    fun `boundary test exactly 24 hours is accepted`() {
        val state = ProtocolState()
        val localTime = 1_000_000L
        val serverTime = localTime + 86400  // Exactamente 24 horas

        val result = state.adjustClockSkew(serverTime, localTime)

        assertTrue("Debería aceptar exactamente 24h", result)
        assertEquals(86400.0, state.getSkewSeconds(), 0.0)
    }

    @Test
    fun `boundary test just over 24 hours is rejected`() {
        val state = ProtocolState()
        val localTime = 1_000_000L
        val serverTime = localTime + 86401  // 24h + 1 segundo

        val result = state.adjustClockSkew(serverTime, localTime)

        assertFalse("Debería rechazar > 24h", result)
        assertEquals(0.0, state.getSkewSeconds(), 0.0)
    }
}
