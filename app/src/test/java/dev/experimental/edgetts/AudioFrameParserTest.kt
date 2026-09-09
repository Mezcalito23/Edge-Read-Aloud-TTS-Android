package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameParserTest {

    @Test
    fun parseTextFrameHeaders() {
        val frame = "audio.metadata\r\nContent-Type: application/json\r\nPath: turn.start\r\n\r\n{\"key\":\"value\"}"
        val headers = AudioFrameParser.parseTextFrameHeaders(frame)
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("turn.start", headers["Path"])
    }

    @Test
    fun parseTextFrameHeadersWithCrlf() {
        val frame = "audio.audio\r\nContent-Type: audio/mpeg\r\nX-Custom: value\r\n\r\n"
        val headers = AudioFrameParser.parseTextFrameHeaders(frame)
        assertEquals("audio/mpeg", headers["Content-Type"])
        assertEquals("value", headers["X-Custom"])
    }

    @Test
    fun parseTextFrameHeadersEmpty() {
        val frame = "\r\n\r\n"
        val headers = AudioFrameParser.parseTextFrameHeaders(frame)
        assertTrue(headers.isEmpty())
    }

    @Test
    fun bodyOfWithBody() {
        val frame = "audio.metadata\r\nContent-Type: application/json\r\n\r\n{\"key\":\"value\"}"
        val body = AudioFrameParser.bodyOf(frame)
        assertEquals("{\"key\":\"value\"}", body)
    }

    @Test
    fun bodyOfEmpty() {
        val frame = "audio.metadata\r\nContent-Type: application/json\r\n\r\n"
        val body = AudioFrameParser.bodyOf(frame)
        assertEquals("", body)
    }

    @Test
    fun bodyOfNoDoubleCrlf() {
        val frame = "audio.metadata\r\nContent-Type: application/json"
        val body = AudioFrameParser.bodyOf(frame)
        assertEquals("", body)
    }

    @Test
    fun parseBinaryFrameWithPrefix() {
        // Prefijo: longitud de cabeceras = 48 bytes (0x0030)
        // Cabeceras: "Path: turn.start\r\nContent-Type: audio/mpeg\r\n\r\n"
        val headerText = "Path: turn.start\r\nContent-Type: audio/mpeg\r\n\r\n"
        val headerBytes = headerText.toByteArray(Charsets.US_ASCII)
        val headerLength = headerBytes.size
        val payload = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte())
        val frameBytes = byteArrayOf(
            ((headerLength shr 8) and 0xFF).toByte(),
            (headerLength and 0xFF).toByte()
        ) + headerBytes + payload

        val result = AudioFrameParser.parseBinaryFrame(frameBytes)
        assertEquals("turn.start", result.path)
        assertEquals("audio/mpeg", result.headers["Content-Type"])
        assertEquals(4, result.payload.size)
    }

    @Test
    fun parseBinaryFrameWithEmptyPayload() {
        val headerText = "Path: turn.end\r\nContent-Type: audio/mpeg\r\n\r\n"
        val headerBytes = headerText.toByteArray(Charsets.US_ASCII)
        val headerLength = headerBytes.size
        val frameBytes = byteArrayOf(
            ((headerLength shr 8) and 0xFF).toByte(),
            (headerLength and 0xFF).toByte()
        ) + headerBytes

        val result = AudioFrameParser.parseBinaryFrame(frameBytes)
        assertEquals("turn.end", result.path)
        assertEquals(0, result.payload.size)
    }

    @Test
    fun parseBinaryFrameWithoutPrefix() {
        // Frame sin prefijo de longitud, solo cabeceras + payload
        val headerText = "Path: audio\r\nContent-Type: audio/mpeg\r\n\r\n"
        val headerBytes = headerText.toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte())
        val frameBytes = headerBytes + payload

        val result = AudioFrameParser.parseBinaryFrame(frameBytes)
        assertEquals("audio", result.path)
        assertEquals(3, result.payload.size)
    }

    @Test
    fun detectFormatPcm() {
        val pcm = byteArrayOf(0x00.toByte(), 0x7F.toByte(), 0x80.toByte(), 0xFF.toByte())
        assertEquals(AudioFrameParser.PayloadFormat.PCM, AudioFrameParser.detectFormat(pcm))
    }

    @Test
    fun detectFormatMp3Id3() {
        val mp3 = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            0x04.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        assertEquals(AudioFrameParser.PayloadFormat.COMPRESSED, AudioFrameParser.detectFormat(mp3))
    }

    @Test
    fun detectFormatMp3Sync() {
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte())
        assertEquals(AudioFrameParser.PayloadFormat.COMPRESSED, AudioFrameParser.detectFormat(mp3))
    }

    @Test
    fun detectFormatWebm() {
        val webm = byteArrayOf(
            0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(),
            0x01.toByte(), 0x02.toByte()
        )
        assertEquals(AudioFrameParser.PayloadFormat.COMPRESSED, AudioFrameParser.detectFormat(webm))
    }
}
