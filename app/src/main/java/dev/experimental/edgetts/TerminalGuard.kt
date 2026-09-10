package dev.experimental.edgetts

import android.speech.tts.SynthesisCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Garantiza una única llamada terminal (done XOR error). El resto del
 * servicio puede llamar done/error con libertad: solo la primera surte
 * efecto, como exige el contrato de SynthesisCallback.
 */
class TerminalGuard {
    private val fired = AtomicBoolean(false)

    val isFired: Boolean
        get() = fired.get()

    fun done(callback: SynthesisCallback): Boolean {
        if (!fired.compareAndSet(false, true)) return false
        runCatching { callback.done() }
        return true
    }

    fun error(callback: SynthesisCallback, code: Int): Boolean {
        if (!fired.compareAndSet(false, true)) return false
        runCatching { callback.error(code) }
        return true
    }

    fun error(
        callback: SynthesisCallback,
        message: String,
        persist: ((String) -> Unit)? = null
    ): Boolean {
        if (!fired.compareAndSet(false, true)) return false
        persist?.invoke(message)
        runCatching { callback.error() }
        return true
    }
}
