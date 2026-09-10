package dev.experimental.edgetts

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Deriva de reloj compartida entre el catálogo HTTP y el WebSocket.
 * La actualización es absoluta (no incremental) y se rechaza si supera
 * [maxSkewSeconds] (Date ausente o absurda no debe mover el reloj).
 */
class ProtocolState(private val maxSkewSeconds: Long = DEFAULT_MAX_SKEW_SECONDS) {

    private val clockSkewSeconds = AtomicLong(0L)

    fun skewSeconds(): Long = clockSkewSeconds.get()

    fun unixSeconds(nowMillis: Long = System.currentTimeMillis()): Long =
        nowMillis / 1000L + clockSkewSeconds.get()

    /**
     * @return true si la deriva se aplicó; false si |server-local| supera el tope.
     */
    fun updateAbsolute(serverEpochSeconds: Long, localEpochSeconds: Long): Boolean {
        val candidate = serverEpochSeconds - localEpochSeconds
        if (abs(candidate) > maxSkewSeconds) return false
        clockSkewSeconds.set(candidate)
        return true
    }

    fun resetForTest() {
        clockSkewSeconds.set(0L)
    }

    companion object {
        const val DEFAULT_MAX_SKEW_SECONDS: Long = 24L * 60L * 60L
    }
}
