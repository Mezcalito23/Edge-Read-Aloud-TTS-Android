package dev.experimental.edgetts

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Garantiza exactamente una llamada terminal (done XOR error) a SynthesisCallback.
 * Todas las llamadas posteriores son no-op.
 * Las excepciones del callback se capturan y no escapan.
 */
class TerminalGuard(
    private val callback: SynthesisCallback
) {
    private val fired = AtomicBoolean(false)
    val isFired: Boolean get() = fired.get()

    /**
     * Llama callback.done() solo si es la primera terminaciÃ³n.
     */
    fun done() {
        if (fired.compareAndSet(false, true)) {
            runCatching { callback.done() }
        }
    }

    /**
     * Llama callback.error(errorCode) solo si es la primera terminaciÃ³n.
     * Usa TextToSpeech.ERROR_SYNTHESIS por defecto si no se especifica.
     */
    fun error(errorCode: Int = TextToSpeech.ERROR_SYNTHESIS) {
        if (fired.compareAndSet(false, true)) {
            runCatching { callback.error(errorCode) }
        }
    }
}
