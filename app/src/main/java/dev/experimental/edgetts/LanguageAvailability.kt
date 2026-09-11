package dev.experimental.edgetts

import android.speech.tts.TextToSpeech

/**
 * Disponibilidad de idioma. Nunca devolver [TextToSpeech.LANG_NOT_SUPPORTED]
 * en el arranque (catálogo aún no leído): Play Books cachea el -2 y cae a
 * un español arbitrario (es-UY / es-AR).
 *
 * Constantes reales AOSP: COUNTRY=1, AVAILABLE=0, NOT_SUPPORTED=-2.
 */
object LanguageAvailability {

    fun code(lang: String, country: String, catalogLocales: Collection<String>): Int {
        val l3 = LocaleCodes.normLang(lang)
        if (l3.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        val c3 = LocaleCodes.normCountry(country)
        val pool = if (catalogLocales.size > 1) catalogLocales else catalogLocales + BUILTIN
        var langHit = false
        var countryHit = false
        for (raw in pool) {
            val iso = LocaleCodes.toIso3Locale(raw) ?: continue
            val vl = iso.substringBefore('-')
            if (vl != l3) continue
            langHit = true
            val vc = iso.substringAfter('-', "")
            if (c3.isNotEmpty() && vc == c3) countryHit = true
        }
        return when {
            countryHit -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            langHit -> TextToSpeech.LANG_AVAILABLE
            // Catálogo vacío: no mentir -2. Play Books no reintenta.
            c3.isNotEmpty() -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    fun orderVoices(voices: List<EdgeVoice>, configured: String): List<EdgeVoice> {
        if (voices.isEmpty()) return voices
        val cfgLocale = LocaleCodes.localeOfVoiceName(configured).lowercase()
        val cfgLang = cfgLocale.substringBefore('-')
        return voices.sortedWith(
            compareBy(
                { it.shortName != configured },
                { it.locale.lowercase() != cfgLocale },
                { it.locale.substringBefore('-').lowercase() != cfgLang },
                { it.locale },
                { it.shortName }
            )
        )
    }

    /**
     * Locales ISO2 del catálogo Edge. Cubren el hueco de los primeros
     * 100–200 ms, cuando el JSON aún no está en memoria.
     */
    val BUILTIN: List<String> = """
        af-ZA am-ET ar-AE ar-BH ar-DZ ar-EG ar-IQ ar-JO ar-KW ar-LB ar-LY ar-MA
        ar-OM ar-QA ar-SA ar-SY ar-TN ar-YE az-AZ bg-BG bn-BD bn-IN bs-BA ca-ES
        cs-CZ cy-GB da-DK de-AT de-CH de-DE el-GR en-AU en-CA en-GB en-HK en-IE
        en-IN en-KE en-NG en-NZ en-PH en-SG en-TZ en-US en-ZA es-AR es-BO es-CL
        es-CO es-CR es-CU es-DO es-EC es-ES es-GQ es-GT es-HN es-MX es-NI es-PA
        es-PE es-PR es-PY es-SV es-US es-UY es-VE et-EE eu-ES fa-IR fi-FI fil-PH
        fr-BE fr-CA fr-CH fr-FR ga-IE gl-ES gu-IN he-IL hi-IN hr-HR hu-HU id-ID
        is-IS it-IT iu-Cans-CA iu-Latn-CA ja-JP jv-ID ka-GE kk-KZ km-KH kn-IN ko-KR
        lo-LA lt-LT lv-LV mk-MK ml-IN mn-MN mr-IN ms-MY mt-MT my-MM nb-NO ne-NP
        nl-BE nl-NL or-IN pa-IN pl-PL ps-AF pt-BR pt-PT ro-RO ru-RU si-LK sk-SK
        sl-SI so-SO sq-AL sr-RS su-ID sv-SE sw-KE sw-TZ ta-IN ta-LK ta-MY ta-SG
        te-IN th-TH tr-TR uk-UA ur-IN ur-PK uz-UZ vi-VN wuu-CN yue-CN zh-CN zh-HK
        zh-TW zu-ZA
    """.trim().split(Regex("\\s+"))
}
