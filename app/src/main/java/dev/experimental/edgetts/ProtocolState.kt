package dev.experimental.edgetts

import java.util.concurrent.atomic.AtomicLong

/**
 * Estado atómico del protocolo para gestión de deriva de reloj (clock skew).
 * Según V-3.1.md Sección 7: debe ser testeable con resetForTest().
 */
class ProtocolState {

    /** Deriva de reloj en segundos (ajuste aplicado al tiempo local). */
    private val clockSkewSeconds = AtomicLong(0)

    /** Máxima deriva permitida antes de rechazar ajuste (24 horas). */
    private val maxSkewSeconds = 86400L

    /**
     * Ajusta la deriva de reloj usando la cabecera Date del servidor.
     * Solo aplica si está dentro del rango razonable (< 24h).
     *
     * @param serverTimeSeconds Tiempo Unix del servidor (de cabecera Date)
     * @param localTimeSeconds Tiempo Unix local actual
     * @return true si se aplicó el ajuste, false si se rechazó por excesivo
     */
    fun adjustClockSkew(serverTimeSeconds: Long, localTimeSeconds: Long): Boolean {
        val skew = serverTimeSeconds - localTimeSeconds
        if (kotlin.math.abs(skew) > maxSkewSeconds) {
            return false
        }
        clockSkewSeconds.set(skew)
        return true
    }

    /**
     * Obtiene el tiempo Unix ajustado por la deriva.
     */
    fun getAdjustedUnixSeconds(): Long {
        val now = System.currentTimeMillis() / 1000
        return now + clockSkewSeconds.get()
    }

    /**
     * Obtiene la deriva actual en segundos (para diagnóstico).
     */
    fun getSkewSeconds(): Double = clockSkewSeconds.get().toDouble()

    /**
     * Resetea la deriva a cero. SOLO para tests deterministas.
     * Según V-3.1.md: "resetForTest(): Permite tests deterministas"
     */
    fun resetForTest() {
        clockSkewSeconds.set(0)
    }
}
