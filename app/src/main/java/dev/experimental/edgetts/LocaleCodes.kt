package dev.experimental.edgetts

import java.util.Locale

/**
 * Tablas ISO2↔ISO3 compartidas por el servicio, CheckVoiceData y GetSampleText.
 * Lazy y de proceso: no se reconstruyen en cada consulta Binder.
 */
object LocaleCodes {

    private val iso3ToIso2Lang: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (iso2 in Locale.getISOLanguages()) {
            runCatching {
                map[Locale.forLanguageTag(iso2).isO3Language.lowercase(Locale.ROOT)] = iso2
            }
        }
        map
    }

    private val iso3ToIso2Country: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (iso2 in Locale.getISOCountries()) {
            runCatching {
                map[Locale.forLanguageTag("und-$iso2").isO3Country.lowercase(Locale.ROOT)] =
                    iso2.lowercase(Locale.ROOT)
            }
        }
        map
    }

    fun iso3ToIso2(code: String?): String? {
        val c = code?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (c.isEmpty()) return null
        if (c.length == 2) return c
        return iso3ToIso2Lang[c]
    }

    fun iso3ToIso2LangOrSelf(code: String): String {
        val c = code.trim().lowercase(Locale.ROOT)
        if (c.isEmpty()) return c
        if (c.length == 2) return c
        return iso3ToIso2Lang[c] ?: c
    }

    fun iso3ToIso2CountryOrSelf(code: String): String {
        val c = code.trim().lowercase(Locale.ROOT)
        if (c.isEmpty()) return c
        if (c.length == 2) return c
        return iso3ToIso2Country[c] ?: c
    }

    fun normLang(code: String): String {
        val c = code.trim().lowercase(Locale.ROOT)
        if (c.isEmpty()) return ""
        if (c.length == 3) return c
        return runCatching {
            Locale.forLanguageTag(c).isO3Language.lowercase(Locale.ROOT)
        }.getOrDefault(c)
    }

    fun normCountry(code: String): String {
        val c = code.trim().lowercase(Locale.ROOT)
        if (c.isEmpty()) return ""
        if (c.length == 3) return c
        return runCatching {
            Locale.forLanguageTag("und-${c.uppercase(Locale.ROOT)}")
                .isO3Country.lowercase(Locale.ROOT)
        }.getOrDefault(c)
    }

    fun toIso3Locale(locale: String): String? {
        val parts = locale.replace('_', '-').split("-")
        val lang = parts.getOrNull(0).orEmpty().trim()
        if (lang.isBlank()) return null
        val lang3 = normLang(lang)
        if (lang3.isEmpty()) return null
        val country = parts.getOrNull(1).orEmpty().trim()
        if (country.isBlank()) return lang3
        val country3 = normCountry(country)
        return if (country3.isEmpty()) lang3 else "$lang3-$country3"
    }

    /**
     * Locale BCP-47 de un shortName Neural ("es-MX-DaliaNeural" → "es-MX").
     * No lee K_LOCALE: esa preferencia nunca se escribió.
     */
    fun localeOfVoiceName(shortName: String): String {
        val parts = shortName.trim().split("-")
        if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
            return "${parts[0]}-${parts[1]}"
        }
        return EdgeProtocolConstants.DEFAULT_LOCALE
    }
}
