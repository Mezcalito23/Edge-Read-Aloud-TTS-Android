package dev.experimental.edgetts

import java.io.ByteArrayOutputStream

/**
 * Recorta silencio **verdadero** de cabeza y cola de un turno PCM.
 *
 * rany2/edge-tts no altera el audio: pausas = las que Edge mete en el SSML.
 * Neo manda párrafos, así que comas/puntos van **dentro** del MP3 y se oyen
 * naturales. Play Books manda una frase por [onSynthesizeText]: el punto es
 * cola de un turno + cabeza del siguiente, y Edge añade ~600–800 ms de
 * padding de fin de síntesis.
 *
 * Umbral bajo (80): un umbral de 480 se comía el ataque/coda de cada frase
 * de Play Books y aceleraba la lectura. Las pausas internas no se tocan.
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
        /** Casi silencio digital. 480 recortaba fonemas suaves. */
        const val AMPLITUDE_THRESHOLD: Int = 80
        const val KEEP_LEAD_MS: Int = 40
        /** Pausa de punto de Edge dentro de un párrafo (Neo). */
        const val KEEP_TAIL_MS: Int = 520

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
