package dev.experimental.edgetts

import android.util.Log

/**
 * Genera el SSML con el formato EXACTO del cliente de referencia
 * (rany2/edge-tts, mkssml + TTSConfig), verificado en vivo:
 *
 *  - el nombre de voz va en formato LARGO: el cliente expande
 *    "es-MX-DaliaNeural" a "Microsoft Server Speech Text to Speech Voice
 *    (es-MX, DaliaNeural)" (data_classes.py); el servidor espera eso;
 *  - atributos con comillas simples, xmlns de síntesis y xml:lang derivado
 *    de la voz (no fijo en en-US);
 *  - prosody con pitch, rate y volume en ese orden.
 *
 * Si el servidor rechazara SSML extendido, [build] con `minimal = true`
 * emite únicamente speak + voice + texto.
 */
object SsmlBuilder {

    private const val TAG = "EdgeTtsService"

    /** Escapa los cinco caracteres XML, en el orden correcto (& primero). */
    fun escapeXml(raw: String): String = buildString(raw.length + 16) {
        for (c in raw) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /**
     * "+0%", "+25%", "-10%"… recortado al rango que el servicio permite:
     * -50%..+100% (el extremo superior honra el slider del sistema a 2.0x;
     * el extremo inferior coincide con el mínimo del protocolo).
     */
    fun signedPercent(percent: Int): String = signed(percent.coerceIn(-50, 100), "%")

    /** "+0Hz", "-4Hz"… recortado al rango ±50. */
    fun signedHertz(hertz: Int): String = signed(hertz.coerceIn(-50, 50), "Hz")

    private fun signed(value: Int, unit: String): String =
        if (value >= 0) "+$value$unit" else "$value$unit"

    /**
     * Expande el nombre corto al formato largo que usa Microsoft Edge,
     * igual que TTSConfig.__post_init__ de la referencia:
     *
     *   es-MX-DaliaNeural →
     *   Microsoft Server Speech Text to Speech Voice (es-MX, DaliaNeural)
     *
     * También maneja variantes regionales compuestas
     * (zh-CN-shandong-YunxiangNeural → (zh-CN-shandong, YunxiangNeural)).
     * Si el nombre ya viene en formato largo o no encaja, se devuelve tal cual.
     */
    fun voiceLongName(shortName: String): String {
        if (shortName.startsWith("Microsoft Server Speech Text to Speech Voice")) return shortName
        val match = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(shortName) ?: return shortName
        val lang = match.groupValues[1]
        var region = match.groupValues[2]
        var name = match.groupValues[3]
        val dash = name.indexOf('-')
        if (dash != -1) {
            region = "$region-${name.substring(0, dash)}"
            name = name.substring(dash + 1)
        }
        return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
    }

    /**
     * Extrae el locale (lang-region) de un nombre de voz corto.
     *
     * Ejemplos:
     *   - zh-CN-XiaoxiaoNeural → zh-CN
     *   - es-MX-DaliaNeural → es-MX
     *   - en-GB-SoniaNeural → en-GB
     *   - zh-CN-shandong-YunxiangNeural → zh-CN-shandong
     *
     * Si el nombre ya viene en formato largo o no encaja, devuelve 'en-US' como fallback.
     */
    fun extractLocaleFromVoice(voice: String): String {
        if (voice.startsWith("Microsoft Server Speech Text to Speech Voice")) {
            val start = voice.indexOf('(')
            val end = voice.indexOf(')')
            if (start != -1 && end != -1 && end > start) {
                val inside = voice.substring(start + 1, end)
                val comma = inside.indexOf(',')
                if (comma != -1) {
                    return inside.substring(0, comma).trim()
                }
            }
            return "en-US"
        }
        val match = Regex("^([a-z]{2,})-([A-Z]{2,})(?:-([a-zA-Z]+))?-(.+Neural)$").find(voice)
        return if (match != null) {
            val lang = match.groupValues[1]
            val region = match.groupValues[2]
            val subregion = match.groupValues[3]
            if (subregion.isNotEmpty()) {
                "$lang-$region-$subregion"
            } else {
                "$lang-$region"
            }
        } else {
            "en-US"
        }
    }

    /**
     * SSML fiel a mkssml() de la referencia. [rate] y [pitch] ya vienen
     * firmados ("+0%", "+0Hz"); el volumen es fijo "+0%" (no expuesto en v1).
     * [voice] es el nombre CORTO del catálogo (p. ej. "es-MX-DaliaNeural");
     * se expande internamente al formato largo.
     * El locale se deriva automáticamente de la voz para xml:lang.
     */
    fun build(
        voice: String,
        @Suppress("UNUSED_PARAMETER") locale: String,
        rate: String,
        pitch: String,
        text: String,
        minimal: Boolean = false
    ): String {
        val xmlLang = extractLocaleFromVoice(voice)
        Log.d(
            TAG,
            "SsmlBuilder.build: voice=$voice xmlLang=$xmlLang rate=$rate pitch=$pitch minimal=$minimal"
        )
        return buildString {
            append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='")
            append(escapeXml(xmlLang))
            append("'>")
            append("<voice name='").append(escapeXml(voiceLongName(voice))).append("'>")
            if (minimal) {
                append(escapeXml(text))
            } else {
                append("<prosody pitch='").append(escapeXml(pitch))
                append("' rate='").append(escapeXml(rate))
                append("' volume='+0%'>")
                append(escapeXml(text))
                append("</prosody>")
            }
            append("</voice></speak>")
        }
    }
}
