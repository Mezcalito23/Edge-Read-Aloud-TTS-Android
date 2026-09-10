package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceResolverTest {

    private val catalog = listOf(
        EdgeVoice("es-MX-DaliaNeural", "es-MX", "Female", "Dalia"),
        EdgeVoice("es-MX-JorgeNeural", "es-MX", "Male", "Jorge"),
        EdgeVoice("es-ES-ElviraNeural", "es-ES", "Female", "Elvira"),
        EdgeVoice("en-GB-SoniaNeural", "en-GB", "Female", "Sonia"),
        EdgeVoice("en-US-AriaNeural", "en-US", "Female", "Aria"),
        EdgeVoice("ur-IN-GulNeural", "ur-IN", "Female", "Gul")
    )

    private fun resolve(
        explicit: String? = null,
        lang: String,
        country: String,
        configured: String = "es-MX-DaliaNeural",
        unified: Boolean = false,
        loadedLang: String = "",
        loadedCountry: String = ""
    ) = VoiceResolver.resolve(
        explicit, lang, country, loadedLang, loadedCountry,
        configured, unified, catalog
    )

    @Test
    fun explicitVoiceWinsOverUnifiedAndLocale() {
        assertEquals(
            "es-ES-ElviraNeural",
            resolve(explicit = "es-ES-ElviraNeural", lang = "spa", country = "MEX", unified = true)
        )
    }

    @Test
    fun unifiedForcesAppVoiceForAnyLanguage() {
        assertEquals(
            "es-MX-DaliaNeural",
            resolve(lang = "urd", country = "IND", unified = true)
        )
        assertEquals(
            "es-MX-DaliaNeural",
            resolve(lang = "spa", country = "ESP", unified = true)
        )
    }

    @Test
    fun spainInSettingsIsSpainWhenUnifiedOff() {
        assertEquals(
            "es-ES-ElviraNeural",
            resolve(lang = "spa", country = "ESP", configured = "es-MX-DaliaNeural")
        )
        assertEquals(
            "es-ES-ElviraNeural",
            resolve(lang = "es", country = "ES", configured = "es-MX-JorgeNeural")
        )
    }

    @Test
    fun mexicoUsesAppVoiceIfItIsMexican() {
        assertEquals(
            "es-MX-JorgeNeural",
            resolve(lang = "spa", country = "MEX", configured = "es-MX-JorgeNeural")
        )
    }

    @Test
    fun britishEnglishIsNotAmerican() {
        assertEquals(
            "en-GB-SoniaNeural",
            resolve(lang = "eng", country = "GBR", configured = "es-MX-DaliaNeural")
        )
        assertEquals(
            "en-US-AriaNeural",
            resolve(lang = "en", country = "US", configured = "es-MX-DaliaNeural")
        )
    }

    @Test
    fun urduSystemLanguageUsesUrduWhenUnifiedOff() {
        assertEquals(
            "ur-IN-GulNeural",
            resolve(lang = "urd", country = "IND", configured = "es-MX-DaliaNeural")
        )
    }

    @Test
    fun retiredVoiceFallsBackToSameLanguage() {
        assertEquals(
            "es-MX-DaliaNeural",
            VoiceResolver.validated("es-MX-MissingNeural", "en-US-AriaNeural", catalog)
        )
    }
}
