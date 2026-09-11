package dev.experimental.edgetts

import java.io.ByteArrayOutputStream

/**
 * Recorta silencio de cabeza y de cola de un turno PCM. Las pausas internas
 * (comas) se dejan tal cual: recortarlas aceleró Neo.
 *
 * Play Books manda una frase por [onSynthesizeText]. El punto largo es el
 * silencio de cola de Edge más el de cabeza del turno siguiente.
 * Cola 220 ms ≈ pausa de punto de Google TTS; 50 ms pegaba las frases.
 */
class LeadTailClipper(sampleRateHz: Int) {

    private val keepLead = samples(KEEP_LEAD_MS, sampleRateHz) * 2
    private val keepTail = samples(KEEP_TAIL_MS, sampleRateHz) * 2
    private var heardVoice = false
    private val held = ByteArrayOutputStream(8 * 1024)

    fun push(chunk: ByteArray, emit: (ByteArray) -> Boolean): Boolean {
        if (chunk.size < 2) return true
        var i = 0
        while (i + 1 < chunk.size) {
            val silent = isSilent(chunk, i)
            if (!heardVoice) {
                if (silent) {
                    held.write(chunk, i, 2)
                    i += 2
                } else {
                    heardVoice = true
                    val lead = tailBytes(held.toByteArray(), keepLead)
                    held.reset()
                    if (lead.isNotEmpty() && !emit(lead)) return false
                }
                continue
            }
            if (silent) {
                val end = runTo(chunk, i, silent = true)
                held.write(chunk, i, end - i)
                i = end
            } else {
                val pending = held.toByteArray()
                held.reset()
                if (pending.isNotEmpty() && !emit(pending)) return false
                val end = runTo(chunk, i, silent = false)
                if (!emit(chunk.copyOfRange(i, end))) return false
                i = end
            }
        }
        return true
    }

    fun finish(emit: (ByteArray) -> Boolean): Boolean {
        val tail = tailBytes(held.toByteArray(), keepTail)
        held.reset()
        return tail.isEmpty() || emit(tail)
    }

    private fun runTo(chunk: ByteArray, start: Int, silent: Boolean): Int {
        var i = start
        while (i + 1 < chunk.size && isSilent(chunk, i) == silent) i += 2
        return i
    }

    companion object {
        const val AMPLITUDE_THRESHOLD: Int = 480
        const val KEEP_LEAD_MS: Int = 40
        const val KEEP_TAIL_MS: Int = 220

        fun samples(ms: Int, sampleRateHz: Int): Int =
            ((sampleRateHz.toLong() * ms) / 1000L).toInt().coerceAtLeast(0)

        fun isSilent(pcm: ByteArray, offset: Int): Boolean {
            val v = (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
            return kotlin.math.abs(v.toShort().toInt()) < AMPLITUDE_THRESHOLD
        }

        fun tailBytes(src: ByteArray, maxBytes: Int): ByteArray {
            if (src.isEmpty() || maxBytes <= 0) return ByteArray(0)
            val n = minOf(src.size, maxBytes)
            val aligned = n - (n % 2)
            if (aligned <= 0) return ByteArray(0)
            return src.copyOfRange(src.size - aligned, src.size)
        }
    }
}
