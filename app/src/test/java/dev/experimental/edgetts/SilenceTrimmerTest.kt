package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceTrimmerTest {

    private val sr = 24_000

    @Test
    fun leadingAndTrailingSilenceAreCapped() {
        val pcm = concat(silence(500), tone(80), silence(500))
        val out = SilenceTrimmer.trim(pcm, sr)
        val max = samples(SilenceTrimmer.KEEP_LEADING_MS + 80 + SilenceTrimmer.KEEP_TRAILING_MS) * 2 + sr / 50
        assertTrue("out=${out.size} max=$max", out.size < pcm.size)
        assertTrue(out.size <= max)
    }

    @Test
    fun longInternalPauseIsCappedToMaxInternal() {
        val pcm = concat(tone(40), silence(800), tone(40))
        val out = SilenceTrimmer.trim(pcm, sr)
        val expectedMax = samples(40 + SilenceTrimmer.MAX_INTERNAL_MS + 40) * 2 + 64
        assertTrue("out=${out.size} expectedMax=$expectedMax", out.size <= expectedMax)
        assertTrue(out.size < pcm.size)
    }

    @Test
    fun speechWithoutSilenceIsUnchanged() {
        val pcm = tone(200)
        val out = SilenceTrimmer.trim(pcm, sr)
        assertEquals(pcm.size, out.size)
    }

    private fun samples(ms: Int) = SilenceTrimmer.msToSamples(ms, sr)

    private fun silence(ms: Int): ByteArray = ByteArray(samples(ms) * 2)

    private fun tone(ms: Int): ByteArray {
        val n = samples(ms)
        val b = ByteArray(n * 2)
        var i = 0
        while (i < n) {
            val v = if (i % 2 == 0) 8000 else -8000
            b[i * 2] = (v and 0xFF).toByte()
            b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        return b
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArray(n)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }
}
