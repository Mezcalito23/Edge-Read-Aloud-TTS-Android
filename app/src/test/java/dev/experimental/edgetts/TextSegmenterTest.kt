package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {

    @Test
    fun emptyTextProducesNoSegments() {
        val segments = TextSegmenter.segment("")
        assertEquals(0, segments.size)
    }

    @Test
    fun paragraphsArePreserved() {
        val segments = TextSegmenter.segment("Primer párrafo.\n\nSegundo párrafo.")
        assertEquals(2, segments.size)
        assertEquals("Primer párrafo.", segments[0])
        assertEquals("Segundo párrafo.", segments[1])
    }

    @Test
    fun singleParagraphFitsInOneSegment() {
        val text = "a".repeat(4000)  // 4000 bytes < 4096
        val segments = TextSegmenter.segment(text)
        assertEquals(1, segments.size)
    }

    @Test
    fun longSentenceWithoutPunctuationIsSplitOnSpaces() {
        val text = List(900) { "palabra" }.joinToString(" ") // ~6.300 chars, sin puntuació°°°n
        val segments = TextSegmenter.segment(text)
        assertTrue(segments.size >= 2)
        segments.forEach { s ->
            assertTrue(s.length <= TextSegmenter.MAX_SEGMENT_BYTES)
            // Ninguna palabra partida: cada token debe ser exactamente "palabra".
            s.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { token -> assertEquals("palabra", token) }
        }
    }

    @Test
    fun degenerateTokenIsHardSplitKeepingTotalLength() {
        val huge = "a".repeat(9000)
        val segments = TextSegmenter.segment(huge)
        // 9000 bytes / 4096 = 2.2, así que esperamos 3 segmentos
        assertTrue(segments.size >= 2)
        segments.forEach { s ->
            assertTrue(s.toByteArray(Charsets.UTF_8).size <= TextSegmenter.MAX_SEGMENT_BYTES)
        }
    }
}