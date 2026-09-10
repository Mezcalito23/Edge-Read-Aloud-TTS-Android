package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Mp3AudioDecoderTest {

    @Test
    fun emptyInputIsRejectedBeforeNativeCodec() {
        try {
            Mp3AudioDecoder().decode(byteArrayOf())
            fail("se esperaba UnsupportedAudioFormatException")
        } catch (e: UnsupportedAudioFormatException) {
            assertTrue(e.message!!.contains("vacío"))
        }
    }

    @Test
    fun oversizedInputIsRejected() {
        val tooBig = ByteArray(Mp3AudioDecoder.MAX_INPUT_BYTES + 1) { 1 }
        try {
            Mp3AudioDecoder().decode(tooBig, Mp3AudioDecoder.MAX_INPUT_BYTES, 1_000)
            fail("se esperaba UnsupportedAudioFormatException")
        } catch (e: UnsupportedAudioFormatException) {
            assertTrue(e.message!!.contains("límite"))
        }
    }

    @Test
    fun corruptBytesDoNotProducePcm() {
        val junk = ByteArray(64) { 0x11 }
        try {
            Mp3AudioDecoder().decode(junk)
            fail("se esperaba fallo de decodificación")
        } catch (e: Exception) {
            assertTrue(
                e is UnsupportedAudioFormatException || e is IllegalStateException
            )
        }
    }

    @Test
    fun metricsLineHasNoSecrets() {
        val m = SynthesisMetrics().apply {
            segments = 2
            cacheHits = 1
            cacheMisses = 1
            decodeMs = 12
            networkMs = 80
            mp3Bytes = 4096
        }
        val line = m.line()
        assertTrue(line.startsWith("métricas"))
        assertEquals(-1, line.indexOf("http"))
        assertEquals(-1, line.indexOf("token", ignoreCase = true))
        assertTrue(line.contains("hit=1"))
        assertTrue(line.contains("miss=1"))
    }
}
