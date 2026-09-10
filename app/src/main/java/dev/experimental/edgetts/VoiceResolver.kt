package dev.experimental.edgetts

/**
 * Quién gana al elegir voz. Sin Android: se cubre con tests JVM.
 *
 * 1. [explicitVoiceName] (setVoice / Ajustes, voz concreta).
 * 2. Modo unificado: la voz de la app, cualquier idioma o país.
 * 3. Idioma+país de la petición / Ajustes: España suena España, GB suena GB.
 * 4. Idioma de sesión (onLoadLanguage).
 * 5. Voz configurada en la app.
 */
object VoiceResolver {

    fun resolve(
        explicitVoiceName: String?,
        requestLang: String,
        requestCountry: String,
        loadedLang: String,
        loadedCountry: String,
        configuredVoice: String,
        unified: Boolean,
        catalog: List<EdgeVoice>
    ): String {
        explicitVoiceName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return validated(it, configuredVoice, catalog)
        }
        if (unified) return validated(configuredVoice, configuredVoice, catalog)

        val reqLang = LocaleCodes.normLang(requestLang)
        if (reqLang.isNotEmpty()) {
            voiceForLanguage(reqLang, requestCountry, configuredVoice, catalog)?.let {
                return validated(it, configuredVoice, catalog)
            }
        }
        val sessLang = LocaleCodes.normLang(loadedLang)
        if (sessLang.isNotEmpty()) {
            voiceForLanguage(sessLang, loadedCountry, configuredVoice, catalog)?.let {
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
