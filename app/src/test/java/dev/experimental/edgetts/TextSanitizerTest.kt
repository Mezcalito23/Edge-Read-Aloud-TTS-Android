package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSanitizerTest {

    @Test
    fun emptyTextReturnsEmpty() {
        assertEquals("", TextSanitizer.removeIncompatibleCharacters(""))
        assertEquals("", TextSanitizer.normalizeSpaces(""))
        assertEquals("", TextSanitizer.sanitize(""))
    }

    @Test
    fun removesControlCharacters00to08() {
        val input = "\u0000Hello\u0001\u0002World\u0008"
        val expected = " Hello  World "  // 1 espacio, Hello, 2 espacios, World, 1 espacio
        assertEquals(expected, TextSanitizer.removeIncompatibleCharacters(input))
    }

    @Test
    fun removesControlCharacters0Bto0C() {
        val input = "Line1\u000BLine2\u000CLine3"
        val expected = "Line1 Line2 Line3"
        assertEquals(expected, TextSanitizer.removeIncompatibleCharacters(input))
    }

    @Test
    fun removesControlCharacters0Eto1F() {
        val input = "Text\u000E\u000F\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001FMore"
        val expected = "Text                  More"  // 18 espacios entre Text y More
        assertEquals(expected, TextSanitizer.removeIncompatibleCharacters(input))
    }

    @Test
    fun preservesTabNewlineCarriage() {
        val input = "Line1\t\n\rLine2"
        assertEquals(input, TextSanitizer.removeIncompatibleCharacters(input))
    }

    @Test
    fun preservesPrintableUnicode() {
        val input = "Hello 世界 🌍 مرحبا שלום"
        assertEquals(input, TextSanitizer.removeIncompatibleCharacters(input))
    }

    @Test
    fun normalizeSpacesCollapsesMultipleSpaces() {
        val input = "Hello    world   with    spaces"
        val expected = "Hello world with spaces"
        assertEquals(expected, TextSanitizer.normalizeSpaces(input))
    }

    @Test
    fun normalizeSpacesHandlesTabsAndNewlines() {
        val input = "Line1\t\t\n\nLine2   with   spaces"
        val expected = "Line1 Line2 with spaces"  // tabs→espacios, newlines→elimina, espacios múltiples→1
        assertEquals(expected, TextSanitizer.normalizeSpaces(input))
    }

    @Test
    fun sanitizeCombinesBothOperations() {
        val input = "\u0000Hello\u0001   world\u000B\u000C  with\u000E\u000F  spaces"
        val result = TextSanitizer.sanitize(input)
        // Primero removeIncompatibleCharacters, luego normalizeSpaces
        assertTrue(result.contains("Hello"))
        assertTrue(result.contains("world"))
        assertTrue(result.contains("with"))
        assertTrue(result.contains("spaces"))
        // Verificar que no hay caracteres de control
        for (c in result) {
            val code = c.code
            assertTrue("Caracter invlido: U+${code.toString(16).uppercase()}",
                code == 0x09 || code == 0x0A || code == 0x0D || code >= 0x20)
        }
    }

    @Test
    fun sanitizePreservesWhitespaceStructure() {
        val input = "Line1\nLine2\n\nParagraph2"
        val result = TextSanitizer.sanitize(input)
        assertTrue(result.contains("Line1"))
        assertTrue(result.contains("Line2"))
        assertTrue(result.contains("Paragraph2"))
    }
}