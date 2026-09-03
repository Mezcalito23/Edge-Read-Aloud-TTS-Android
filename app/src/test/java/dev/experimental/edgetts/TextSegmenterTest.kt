package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {

    @Test
    fun emptyTextProducesNoSegments() {
        assertTrue(TextSegmenter.segment("").isEmpty())
        assertTrue(TextSegmenter.segment("   \n\n  ").isEmpty())
    }

    @Test
    fun shortTextStaysInOneSegment() {
        val text = "Hola, mundo. Esta es una frase corta."
        assertEquals(listOf(text), TextSegmenter.segment(text))
    }

    @Test
    fun paragraphsArePreserved() {
        val segments = TextSegmenter.segment("Primer párrafo.\n\nSegundo párrafo.")
        assertEquals(2, segments.size)
        assertEquals("Primer párrafo.", segments[0])
        assertEquals("Segundo párrafo.", segments[1])
    }

    @Test
    fun orderIsKeptAcrossManySentences() {
        val text = (1..60).joinToString(" ") { "Frase número $it." }
        val segments = TextSegmenter.segment(text)
        val rebuilt = segments.joinToString(" ")
        for (i in 1..60) {
            assertTrue("falta la frase $i", rebuilt.contains("Frase número $i."))
        }
        assertTrue(
            "el orden se rompió",
            rebuilt.indexOf("Frase número 1.") < rebuilt.indexOf("Frase número 60.")
        )
    }

    @Test
    fun noSegmentExceedsTheLimit() {
        val text = (1..400).joinToString(" ") { "Palabra$it " + "x".repeat(50) + "." }
        val segments = TextSegmenter.segment(text)
        assertTrue(segments.isNotEmpty())
        segments.forEach { s ->
            assertTrue(
                "segmento de ${s.length} chars supera el límite",
                s.length <= TextSegmenter.MAX_SEGMENT_CHARS
            )
        }
    }

    @Test
    fun longSentenceWithoutPunctuationIsSplitOnSpaces() {
        val text = List(900) { "palabra" }.joinToString(" ") // ~6.300 chars, sin puntuación
        val segments = TextSegmenter.segment(text)
        assertTrue(segments.size >= 2)
        segments.forEach { s ->
            assertTrue(s.length <= TextSegmenter.MAX_SEGMENT_CHARS)
            // Ninguna palabra partida: cada token debe ser exactamente "palabra".
            s.split(Regex("\\s+")).forEach { token -> assertEquals("palabra", token) }
        }
    }

    @Test
    fun degenerateTokenIsHardSplitKeepingTotalLength() {
        val huge = "a".repeat(9000)
        val segments = TextSegmenter.segment(huge)
        assertEquals(3, segments.size)
        assertEquals(4000, segments[0].length)
        assertEquals(4000, segments[1].length)
        assertEquals(1000, segments[2].length)
    }

    @Test
    fun cancellationStopsEarly() {
        val text = (1..100).joinToString("\n") { "Párrafo $it." }
        var checks = 0
        val segments = TextSegmenter.segment(text) { ++checks > 6 }
        assertTrue("la cancelación no surtió efecto", segments.size < 100)
        assertTrue(segments.isNotEmpty())
    }
}
