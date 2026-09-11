package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceResolverTest {

    private val catalog = listOf(
        EdgeVoice("es-AR-ElenaNeural", "es-AR", "Female", "Elena"),
        EdgeVoice("es-UY-ValentinaNeural", "es-UY", "Female", "Valentina"),
        EdgeVoice("es-MX-DaliaNeural", "es-MX", "Female", "Dalia"),
        EdgeVoice("es-MX-JorgeNeural", "es-MX", "Male", "Jorge"),
        EdgeVoice("es-ES-ElviraNeural", "es-ES", "Female", "Elvira"),
        EdgeVoice("es-PR-KarinaNeural", "es-PR", "Female", "Karina"),
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
        loadedCountry: String = "",
        caller: VoiceResolver.Caller = VoiceResolver.Caller.SettingsUi,
        pinnedLang: String = "",
        pinnedCountry: String = ""
    ) = VoiceResolver.resolve(
        explicit, lang, country, loadedLang, loadedCountry,
        configured, unified, catalog, caller, pinnedLang, pinnedCountry
    )

    @Test
    fun unifiedIgnoresPlayBooksVoiceName() {
        assertEquals(
            "es-MX-DaliaNeural",
            resolve(
                explicit = "es-UY-ValentinaNeural",
                lang = "spa",
                country = "URY",
                unified = true,
                caller = VoiceResolver.Caller.Reader
            )
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
    fun readerUsesPinnedSettingsLocaleWhenUnifiedOff() {
        assertEquals(
            "en-US-AriaNeural",
            resolve(
                explicit = "es-UY-ValentinaNeural",
                lang = "spa",
                country = "URY",
                loadedLang = "spa",
                loadedCountry = "URY",
                caller = VoiceResolver.Caller.Reader,
                pinnedLang = "eng",
                pinnedCountry = "USA"
            )
        )
    }

    @Test
    fun readerWithoutPinIgnoresBookUruguay() {
        assertEquals(
            "es-MX-JorgeNeural",
            resolve(
                explicit = "es-UY-ValentinaNeural",
                lang = "spa",
                country = "URY",
                configured = "es-MX-JorgeNeural",
                caller = VoiceResolver.Caller.Reader
            )
        )
    }

    @Test
    fun spanishWithoutCountryIsMexicoNotArgentina() {
        assertEquals(
            "es-MX-DaliaNeural",
            VoiceResolver.voiceForLanguage("spa", "", "en-US-AriaNeural", catalog)
        )
        assertEquals(
            "es-MX-JorgeNeural",
            VoiceResolver.voiceForLanguage("es", "", "es-MX-JorgeNeural", catalog)
        )
    }

    @Test
    fun englishWithoutCountryIsUsNotFirstCatalogHit() {
        val shuffled = listOf(
            EdgeVoice("en-GB-SoniaNeural", "en-GB", "Female", "Sonia"),
            EdgeVoice("en-AU-NatashaNeural", "en-AU", "Female", "Natasha"),
            EdgeVoice("en-US-AriaNeural", "en-US", "Female", "Aria")
        )
        assertEquals(
            "en-US-AriaNeural",
            VoiceResolver.voiceForLanguage("eng", "", "es-MX-DaliaNeural", shuffled)
        )
    }

    @Test
    fun settingsPuertoRicoIsKarina() {
        assertEquals(
            "es-PR-KarinaNeural",
            resolve(lang = "spa", country = "PRI", configured = "es-MX-JorgeNeural")
        )
    }

    @Test
    fun ownAppHonorsExplicitWhenUnifiedOff() {
        assertEquals(
            "en-GB-SoniaNeural",
            resolve(
                explicit = "en-GB-SoniaNeural",
                lang = "spa",
                country = "MEX",
                caller = VoiceResolver.Caller.OwnApp
            )
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
