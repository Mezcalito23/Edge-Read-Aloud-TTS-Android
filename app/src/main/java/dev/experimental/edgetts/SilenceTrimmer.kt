package dev.experimental.edgetts

/**
 * Recorta silencios del PCM 16-bit LE mono. Edge Neural mete pausas largas
 * en comas y puntos; Google TTS no. El endpoint no oficial rechaza
 * mstts:silence, así que se recorta aquí.
 *
 * Play Books nota sobre todo el punto: suele ser el silencio de cola de un
 * turno más el de cabeza del siguiente. Por eso se recortan lead/tail más
 * agresivo que las pausas internas (comas).
 */
object SilenceTrimmer {

    const val AMPLITUDE_THRESHOLD: Int = 480
    const val MAX_INTERNAL_MS: Int = 160
    const val KEEP_LEADING_MS: Int = 12
    const val KEEP_TRAILING_MS: Int = 28

    fun trim(pcm: ByteArray, sampleRateHz: Int): ByteArray {
        if (pcm.size < 8 || pcm.size % 2 != 0 || sampleRateHz <= 0) return pcm
        val samples = pcm.size / 2
        val maxInternal = msToSamples(MAX_INTERNAL_MS, sampleRateHz)
        val keepLead = msToSamples(KEEP_LEADING_MS, sampleRateHz)
        val keepTail = msToSamples(KEEP_TRAILING_MS, sampleRateHz)

        val silent = BooleanArray(samples)
        var allSilent = true
        var i = 0
        while (i < samples) {
            val s = silentAt(pcm, i)
            silent[i] = s
            if (!s) allSilent = false
            i++
        }
        if (allSilent) return pcm.copyOf(minOf(pcm.size, keepTail * 2))

        val out = ByteArray(pcm.size)
        var w = 0

        fun copyRange(from: Int, to: Int) {
            var s = from
            while (s < to) {
                val o = s * 2
                out[w++] = pcm[o]
                out[w++] = pcm[o + 1]
                s++
            }
        }

        var idx = 0
        var firstVoice = true
        while (idx < samples) {
            if (!silent[idx]) {
                val start = idx
                while (idx < samples && !silent[idx]) idx++
                copyRange(start, idx)
                firstVoice = false
            } else {
                val start = idx
                while (idx < samples && silent[idx]) idx++
                val run = idx - start
                val keep = when {
                    firstVoice -> minOf(run, keepLead)
                    idx == samples -> minOf(run, keepTail)
                    else -> minOf(run, maxInternal)
                }
                if (keep > 0) copyRange(idx - keep, idx)
            }
        }
        return if (w == pcm.size) pcm else out.copyOf(w)
    }

    private fun silentAt(pcm: ByteArray, sample: Int): Boolean {
        val o = sample * 2
        val v = (pcm[o].toInt() and 0xFF) or (pcm[o + 1].toInt() shl 8)
        val signed = v.toShort().toInt()
        return kotlin.math.abs(signed) < AMPLITUDE_THRESHOLD
    }

    internal fun msToSamples(ms: Int, sampleRateHz: Int): Int =
        ((sampleRateHz.toLong() * ms) / 1000L).toInt().coerceAtLeast(0)
}
