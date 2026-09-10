package dev.experimental.edgetts

/**
 * Quién manda la voz.
 *
 * Unificado ON: siempre la voz de la app (Play Books no puede imponer Valentina).
 * Unificado OFF: Ajustes del sistema (pin), no el idioma del libro.
 * La app misma (botón Probar) sí puede pedir una voz concreta con OFF.
 */
object VoiceResolver {

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

        val pinL = pinnedLang.ifBlank { loadedLang }
        val pinC = if (pinnedLang.isNotBlank()) pinnedCountry else loadedCountry
        if (LocaleCodes.normLang(pinL).isNotEmpty()) {
            voiceForLanguage(pinL, pinC, configuredVoice, catalog)?.let {
                return validated(it, configuredVoice, catalog)
            }
        }
        val reqLang = LocaleCodes.normLang(requestLang)
        if (reqLang.isNotEmpty()) {
            voiceForLanguage(reqLang, requestCountry, configuredVoice, catalog)?.let {
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
        val c3 = LocaleCodes.normCountry(country)

        if (c3.isNotEmpty()) {
            val countryVoice = catalog.firstOrNull {
                LocaleCodes.normLang(it.locale.substringBefore("-")) == l3 &&
                    LocaleCodes.normCountry(it.locale.substringAfter("-", "")) == c3
            }
            if (countryVoice != null) {
                val configuredCountry = LocaleCodes.normCountry(
                    configuredVoice.substringAfter("-", "")
                )
                if (configuredLang == l3 && configuredCountry == c3) {
                    return configuredVoice
                }
                return countryVoice.shortName
            }
        }

        if (configuredLang == l3) return configuredVoice

        return catalog.firstOrNull {
            LocaleCodes.normLang(it.locale.substringBefore("-")) == l3
        }?.shortName
    }

    fun validated(voice: String, configuredVoice: String, catalog: List<EdgeVoice>): String {
        if (catalog.isEmpty()) return voice
        val names = catalog.map { it.shortName }.toSet()
        if (voice in names) return voice
        val lang = voice.substringBefore("-").lowercase()
        catalog.firstOrNull {
            it.locale.substringBefore("-").lowercase() == lang
        }?.let { return it.shortName }
        if (configuredVoice in names) return configuredVoice
        return EdgeProtocolConstants.DEFAULT_VOICE
    }
}
