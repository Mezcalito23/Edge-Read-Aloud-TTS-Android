package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageAvailabilityTest {

    // AOSP: LANG_AVAILABLE=0, LANG_COUNTRY_AVAILABLE=1, LANG_NOT_SUPPORTED=-2
    @Test
    fun emptyCatalogDoesNotRejectEnglish() {
        val code = LanguageAvailability.code("eng", "USA", emptyList())
        assertTrue("cold start eng/USA was $code", code >= 0)
    }

    @Test
    fun mexicoIsCountryAvailable() {
        assertEquals(1, LanguageAvailability.code("spa", "MEX", listOf("es-MX", "en-US")))
    }

    @Test
    fun builtinCoversEnglishAndJapanese() {
        assertTrue(LanguageAvailability.code("eng", "USA", emptyList()) >= 1)
        assertTrue(
            LanguageAvailability.code("jpn", "JPN", LanguageAvailability.BUILTIN) >= 1
        )
    }

    @Test
    fun configuredVoiceIsFirstInList() {
        val voices = listOf(
            EdgeVoice("es-AR-ElenaNeural", "es-AR", "Female", "Elena"),
            EdgeVoice("es-UY-ValentinaNeural", "es-UY", "Female", "Valentina"),
            EdgeVoice("es-MX-JorgeNeural", "es-MX", "Male", "Jorge")
        )
        val ordered = LanguageAvailability.orderVoices(voices, "es-MX-JorgeNeural")
        assertEquals("es-MX-JorgeNeural", ordered.first().shortName)
    }
}
