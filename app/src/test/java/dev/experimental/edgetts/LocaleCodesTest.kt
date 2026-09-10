package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleCodesTest {

    @Test
    fun spanishIso2AndIso3RoundTrip() {
        assertEquals("spa", LocaleCodes.normLang("es"))
        assertEquals("spa", LocaleCodes.normLang("spa"))
        assertEquals("es", LocaleCodes.iso3ToIso2LangOrSelf("spa"))
        assertEquals("mex", LocaleCodes.normCountry("MX"))
        assertEquals("mex", LocaleCodes.normCountry("MEX"))
        assertEquals("mx", LocaleCodes.iso3ToIso2CountryOrSelf("mex"))
    }

    @Test
    fun toIso3LocaleNormalizesMixedTags() {
        assertEquals("spa-mex", LocaleCodes.toIso3Locale("es-MX"))
        assertEquals("eng-usa", LocaleCodes.toIso3Locale("en-US"))
        assertEquals("spa-mex", LocaleCodes.toIso3Locale("spa-MEX"))
    }

    @Test
    fun localeOfVoiceNameUsesFirstTwoTags() {
        assertEquals("es-MX", LocaleCodes.localeOfVoiceName("es-MX-DaliaNeural"))
        assertEquals("zh-CN", LocaleCodes.localeOfVoiceName("zh-CN-YunyangNeural"))
        assertEquals(
            EdgeProtocolConstants.DEFAULT_LOCALE,
            LocaleCodes.localeOfVoiceName("")
        )
    }
}
