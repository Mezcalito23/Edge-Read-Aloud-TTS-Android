package dev.experimental.edgetts

import android.speech.tts.SynthesisCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Garantiza una ÃNICA llamada terminal (done XOR error). El resto del
 * servicio puede llamar done/error con libertad: solo la primera surte
 * efecto, como exige el contrato de SynthesisCallback.
 */
class TerminalGuard {
    private val fired = AtomicBoolean(false)

    val isFired: Boolean
        get() = fired.get()

    fun done(callback: SynthesisCallback) {
        if (fired.compareAndSet(false, true)) runCatching { callback.done() }
    }

    fun error(
        callback: SynthesisCallback,
        message: String,
        persist: ((String) -> Unit)? = null
    ) {
        if (fired.compareAndSet(false, true)) {
            persist?.invoke(message)
            runCatching { callback.error() }
        }
    }
}
