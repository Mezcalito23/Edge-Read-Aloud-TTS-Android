package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleTextsTest {

    @Test
    fun spanishDemoIsReplacedForJapaneseVoice() {
        val demo = SampleTexts.SAMPLES.getValue("es")
        val out = SampleTexts.alignDemo(demo, "ja-JP-KeitaNeural")
        assertTrue(out.contains("こんにちは"))
        assertFalse(out.contains("Hola"))
    }

    @Test
    fun longSpanishBookIsNotReplaced() {
        val book = "Hola. " + "Esta es una novela larga. ".repeat(20)
        val out = SampleTexts.alignDemo(book, "en-US-AvaNeural")
        assertEquals(book, out)
    }

    @Test
    fun spanishVoiceKeepsSpanishDemo() {
        val demo = SampleTexts.SAMPLES.getValue("es")
        assertEquals(demo, SampleTexts.alignDemo(demo, "es-MX-DaliaNeural"))
    }

    @Test
    fun iso3ChineseMapsToZhSample() {
        assertTrue(SampleTexts.forRequested("zho", "CHN").contains("你好"))
    }
}
