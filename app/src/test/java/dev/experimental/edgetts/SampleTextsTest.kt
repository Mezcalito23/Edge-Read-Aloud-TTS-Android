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
    fun chineseVoiceDoesNotSpeakSpanishDemo() {
        val out = SampleTexts.alignDemo(
            "Esta es una demostración de la síntesis de voz.",
            "zh-CN-XiaoxiaoNeural"
        )
        assertTrue(out.contains("你好"))
    }

    @Test
    fun forceReplacesAnyShortText() {
        val out = SampleTexts.alignDemo(
            "cualquier texto corto de ajustes",
            "ja-JP-NanamiNeural",
            force = true
        )
        assertTrue(out.contains("こんにちは"))
    }
}
