package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LeadTailClipperTest {

    private val sr = 24_000

    @Test
    fun leadingAndTrailingAreClippedInternalPauseKept() {
        val pcm = concat(silence(400), tone(80), silence(200), tone(80), silence(400))
        val out = run(pcm)
        val internal = samples(200) * 2
        assertTrue("internal pause must survive: out=${out.size}", out.size > internal)
        val max =
            samples(LeadTailClipper.KEEP_LEAD_MS + 80 + 200 + 80 + LeadTailClipper.KEEP_TAIL_MS) * 2 + 64
        assertTrue("out=${out.size} max=$max", out.size <= max)
        assertTrue(out.size < pcm.size)
    }

    @Test
    fun speechOnlyUnchanged() {
        val pcm = tone(120)
        assertEquals(pcm.size, run(pcm).size)
    }

    private fun run(pcm: ByteArray): ByteArray {
        val clip = LeadTailClipper(sr)
        val acc = ByteArrayOutputStream()
        assertTrue(clip.push(pcm) { b -> acc.write(b); true })
        assertTrue(clip.finish { b -> acc.write(b); true })
        return acc.toByteArray()
    }

    private fun samples(ms: Int) = LeadTailClipper.samples(ms, sr)

    private fun silence(ms: Int) = ByteArray(samples(ms) * 2)

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
