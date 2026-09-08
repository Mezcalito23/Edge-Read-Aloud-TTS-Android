package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameParserTest {

    @Test
    fun parseTextFrame() {
        val frame = "audio.metadata\r\nContent-Type: application/json\r\n\r\n{\"key\":\"value\"}"
        val result = AudioFrameParser.parseFrame(frame)
        assertTrue(result is AudioFrameParser.Frame.TextFrame)
        val textFrame = result as AudioFrameParser.Frame.TextFrame
        assertEquals("audio.metadata", textFrame.type)
        assertEquals("{\"key\":\"value\"}", textFrame.body)
    }

    @Test
    fun parseBinaryFrameWithPrefix() {
        val header = "audio.audio\r\nContent-Type: audio/mpeg\r\n\r\n"
        val payload = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte())
        val frameBytes = header.toByteArray() + payload
        val result = AudioFrameParser.parseBinaryFrame(frameBytes)
        assertTrue(result is AudioFrameParser.Frame.BinaryFrame)
        val binaryFrame = result as AudioFrameParser.Frame.BinaryFrame
        assertEquals("audio.audio", binaryFrame.type)
        assertEquals(4, binaryFrame.payload.size)
    }

    @Test
    fun parseFrameWithCrlfHeaders() {
        val frame = "audio.metadata\r\nContent-Type: application/json\r\nX-Custom: value\r\n\r\n{}"
        val result = AudioFrameParser.parseFrame(frame)
        assertTrue(result is AudioFrameParser.Frame.TextFrame)
        val textFrame = result as AudioFrameParser.Frame.TextFrame
        assertEquals("audio.metadata", textFrame.type)
        assertEquals("{}", textFrame.body)
    }

    @Test
    fun parseFrameWithEmptyPayload() {
        val frame = "audio.metadata\r\nContent-Type: application/json\r\n\r\n"
        val result = AudioFrameParser.parseFrame(frame)
        assertTrue(result is AudioFrameParser.Frame.TextFrame)
        val textFrame = result as AudioFrameParser.Frame.TextFrame
        assertEquals("", textFrame.body)
    }

    @Test
    fun parseFrameWithCorruptHeader() {
        val frame = "INVALID FRAME FORMAT"
        val result = AudioFrameParser.parseFrame(frame)
        assertTrue(result is AudioFrameParser.Frame.UnknownFrame)
    }

    @Test
    fun parseBinaryFrameWithEmptyPayload() {
        val header = "audio.audio\r\nContent-Type: audio/mpeg\r\n\r\n"
        val frameBytes = header.toByteArray()
        val result = AudioFrameParser.parseBinaryFrame(frameBytes)
        assertTrue(result is AudioFrameParser.Frame.BinaryFrame)
        val binaryFrame = result as AudioFrameParser.Frame.BinaryFrame
        assertEquals(0, binaryFrame.payload.size)
    }

    @Test
    fun parseFrameWithLargePayload() {
        val largeBody = "x".repeat(10000)
        val frame = "audio.metadata\r\nContent-Type: application/json\r\n\r\n$largeBody"
        val result = AudioFrameParser.parseFrame(frame)
        assertTrue(result is AudioFrameParser.Frame.TextFrame)
        val textFrame = result as AudioFrameParser.Frame.TextFrame
        assertEquals(largeBody, textFrame.body)
    }

    @Test
    fun parseFramePreservesUtf8InBody() {
        val frame = "audio.metadata\r\nContent-Type: application/json\r\n\r\n{\"text\":\"你好世界🌍\"}"
        val result = AudioFrameParser.parseFrame(frame)
        assertTrue(result is AudioFrameParser.Frame.TextFrame)
        val textFrame = result as AudioFrameParser.Frame.TextFrame
        assertTrue(textFrame.body.contains("你好世界"))
        assertTrue(textFrame.body.contains("🌍"))
    }
}
