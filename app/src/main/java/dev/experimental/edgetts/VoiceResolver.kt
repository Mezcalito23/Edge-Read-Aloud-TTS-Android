package dev.experimental.edgetts

/**
 * Quién manda la voz.
 *
 * Unificado ON: siempre la voz de la app (Play Books no puede imponer Valentina).
 * Unificado OFF: Ajustes del sistema (pin / tts_default_locale), no el idioma
 * del libro. Sin pin, se usa el locale de la voz configurada — nunca el
 * primer es-* alfabético (es-AR / es-UY).
 * La app misma (botón Probar, extra [OWN_PARAM]) sí puede pedir una voz
 * concreta con OFF.
 */
object VoiceResolver {

    const val OWN_PARAM = "dev.experimental.edgetts.own"
    const val OWN_UTTERANCE = "edge-tts-test"

    enum class Caller { OwnApp, SettingsUi, Reader }

    fun resolve(
        explicitVoiceName: String?,
        requestLang: String,
        requestCountry: String,
        loadedLang: String,
        loadedCountry: String,
        configuredVoice: String,
        unified: Boolean,
        catalog: List<EdgeVoice>,
        caller: Caller = Caller.Reader,
        pinnedLang: String = "",
        pinnedCountry: String = ""
    ): String {
        if (unified) return validated(configuredVoice, configuredVoice, catalog)

        if (caller == Caller.OwnApp) {
            explicitVoiceName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return validated(it, configuredVoice, catalog)
            }
        }

        if (caller == Caller.SettingsUi) {
            explicitVoiceName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return validated(it, configuredVoice, catalog)
            }
            voiceForLanguage(requestLang, requestCountry, configuredVoice, catalog)?.let {
                return validated(it, configuredVoice, catalog)
            }
        }

        // Lectores (Neo, Play Books): el libro NO elige variante. Pin de
        // Ajustes, o el locale de la voz de la app — jamás spa/URY del fallback.
        val cfgLocale = LocaleCodes.localeOfVoiceName(configuredVoice)
        val pinL = pinnedLang.ifBlank { loadedLang }.ifBlank { cfgLocale.substringBefore('-') }
        val pinC = when {
            pinnedLang.isNotBlank() -> pinnedCountry
            LocaleCodes.normLang(loadedLang).isNotEmpty() -> loadedCountry
            else -> cfgLocale.substringAfter('-', "")
        }
        if (LocaleCodes.normLang(pinL).isNotEmpty()) {
            voiceForLanguage(pinL, pinC, configuredVoice, catalog)?.let {
                return validated(it, configuredVoice, catalog)
            }
        }
        return validated(configuredVoice, configuredVoice, catalog)
    }

    fun voiceForLanguage(
        lang: String,
        country: String,
        configuredVoice: String,
        catalog: List<EdgeVoice>
    ): String? {
        val l3 = LocaleCodes.normLang(lang)
        if (l3.isEmpty()) return null
        val configuredLang = LocaleCodes.normLang(configuredVoice.substringBefore("-"))
        val configuredCountry = LocaleCodes.normCountry(
            configuredVoice.substringAfter("-", "").substringBefore("-")
        )
        val c3 = LocaleCodes.normCountry(country)

        match(l3, c3, configuredVoice, configuredLang, configuredCountry, catalog)?.let {
            return it
        }

        if (configuredLang == l3) return configuredVoice

        PREFERRED_COUNTRY[l3]?.let { pref ->
            if (pref != c3) {
                match(l3, pref, configuredVoice, configuredLang, configuredCountry, catalog)?.let {
                    return it
                }
            }
        }

        return catalog.firstOrNull {
            LocaleCodes.normLang(it.locale.substringBefore("-")) == l3
        }?.shortName
    }

    private fun match(
        l3: String,
        c3: String,
        configuredVoice: String,
        configuredLang: String,
        configuredCountry: String,
        catalog: List<EdgeVoice>
    ): String? {
        if (c3.isEmpty()) return null
        if (configuredLang == l3 && configuredCountry == c3) {
            if (catalog.isEmpty() || catalog.any { it.shortName == configuredVoice }) {
                return configuredVoice
            }
        }
        return catalog.firstOrNull {
            LocaleCodes.normLang(it.locale.substringBefore("-")) == l3 &&
                LocaleCodes.normCountry(it.locale.substringAfter("-", "")) == c3
        }?.shortName
    }

    fun validated(voice: String, configuredVoice: String, catalog: List<EdgeVoice>): String {
        if (catalog.isEmpty()) return voice
        val names = catalog.map { it.shortName }.toSet()
        if (voice in names) return voice
        val locale = LocaleCodes.localeOfVoiceName(voice)
        voiceForLanguage(
            locale.substringBefore('-'),
            locale.substringAfter('-', ""),
            configuredVoice,
            catalog
        )?.let { return it }
        if (configuredVoice in names) return configuredVoice
        return EdgeProtocolConstants.DEFAULT_VOICE
    }

    /**
     * País canónico ISO3 cuando Ajustes pide el idioma sin región.
     * Evita que `firstOrNull` elija es-AR / es-UY solo por orden de catálogo.
     */
    internal val PREFERRED_COUNTRY: Map<String, String> = mapOf(
        "spa" to "mex",
        "eng" to "usa",
        "por" to "bra",
        "fra" to "fra",
        "deu" to "deu",
        "ita" to "ita",
        "nld" to "nld",
        "zho" to "chn",
        "jpn" to "jpn",
        "kor" to "kor",
        "ara" to "sau",
        "rus" to "rus",
        "hin" to "ind",
        "tur" to "tur",
        "pol" to "pol",
        "swe" to "swe",
        "ukr" to "ukr",
        "vie" to "vnm",
        "tha" to "tha",
        "ind" to "idn",
        "ces" to "cze",
        "ell" to "grc",
        "hun" to "hun",
        "ron" to "rou",
        "fin" to "fin",
        "dan" to "dnk",
        "nob" to "nor",
        "nor" to "nor",
        "heb" to "isr",
        "fas" to "irn",
        "urd" to "pak",
        "cat" to "esp"
    )
}
