package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {

    @Test
    fun emptyTextProducesNoSegments() {
        assertTrue(TextSegmenter.segment("").isEmpty())
    }

    @Test
    fun shortTextStaysInOneSegment() {
        val text = "Hola, mundo. Esta es una frase corta."
        assertEquals(listOf(text), TextSegmenter.segment(text))
    }

    @Test
    fun paragraphsSplitOnBlankLineAndRejoin() {
        val text = "Primer párrafo.\n\nSegundo párrafo."
        val segments = TextSegmenter.segment(text)
        assertEquals(2, segments.size)
        assertEquals(text, segments.joinToString(""))
    }

    @Test
    fun orderIsKeptAcrossManySentences() {
        val text = (1..60).joinToString(" ") { "Frase número $it." }
        val segments = TextSegmenter.segment(text)
        val rebuilt = segments.joinToString("")
        assertEquals(text, rebuilt)
    }

    @Test
    fun noSegmentExceedsByteLimit() {
        val text = (1..400).joinToString(" ") { "Palabra$it " + "x".repeat(50) + "." }
        val segments = TextSegmenter.segment(text)
        assertTrue(segments.isNotEmpty())
        segments.forEach { s ->
            assertTrue(s.toByteArray(Charsets.UTF_8).size <= TextSegmenter.MAX_SEGMENT_BYTES)
        }
        assertEquals(text, segments.joinToString(""))
    }

    @Test
    fun longSentenceWithoutPunctuationIsSplitOnSpaces() {
        val text = List(900) { "palabra" }.joinToString(" ")
        val segments = TextSegmenter.segment(text)
        assertTrue(segments.size >= 2)
        segments.forEach { s ->
            assertTrue(s.toByteArray(Charsets.UTF_8).size <= TextSegmenter.MAX_SEGMENT_BYTES)
            s.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { token ->
                assertEquals("palabra", token)
            }
        }
    }

    @Test
    fun degenerateTokenIsHardSplitOnUtf8Bytes() {
        val huge = "a".repeat(9000)
        val segments = TextSegmenter.segment(huge)
        assertEquals(huge, segments.joinToString(""))
        segments.dropLast(1).forEach { s ->
            assertEquals(TextSegmenter.MAX_SEGMENT_BYTES, s.toByteArray(Charsets.UTF_8).size)
        }
    }

    @Test
    fun cancellationStopsEarly() {
        val text = (1..100).joinToString("\n") { "Párrafo $it." }
        var checks = 0
        val segments = TextSegmenter.segment(text) { ++checks > 6 }
        assertTrue("la cancelación no surtió efecto", segments.size < 100)
        assertTrue(segments.isNotEmpty())
    }

    @Test
    fun operationalLimitIsSmallerThanProtocolCap() {
        val text = List(400) { "palabra" }.joinToString(" ")
        val operational = TextSegmenter.segment(
            text, { false }, TextSegmenter.OPERATIONAL_SEGMENT_BYTES
        )
        operational.forEach { s ->
            assertTrue(s.toByteArray(Charsets.UTF_8).size <= TextSegmenter.OPERATIONAL_SEGMENT_BYTES)
        }
        assertTrue(operational.size > 1)
        assertTrue(TextSegmenter.OPERATIONAL_SEGMENT_BYTES < TextSegmenter.MAX_SEGMENT_BYTES)
    }

    @Test
    fun cjkIsSplitOnUtf8ByteLimitWithoutReplacementChar() {
        val text = "中".repeat(1366)
        val parts = TextSegmenter.segment(text)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { part ->
            assertTrue(part.toByteArray(Charsets.UTF_8).size <= TextSegmenter.MAX_SEGMENT_BYTES)
            assertTrue(!part.contains('\uFFFD'))
        }
        assertTrue(parts.size >= 2)
    }
}
