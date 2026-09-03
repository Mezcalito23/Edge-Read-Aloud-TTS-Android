package dev.experimental.edgetts

/**
 * Genera el SSML con el formato EXACTO del cliente de referencia
 * (rany2/edge-tts, mkssml + TTSConfig), verificado en vivo:
 *
 *  - el nombre de voz va en formato LARGO: el cliente expande
 *    "es-MX-DaliaNeural" a "Microsoft Server Speech Text to Speech Voice
 *    (es-MX, DaliaNeural)" (data_classes.py); el servidor espera eso;
 *  - atributos con comillas simples, xmlns de síntesis y xml:lang='en-US'
 *    fijo (quirk del cliente original; el idioma real lo marca el voice name);
 *  - prosody con pitch, rate y volume en ese orden.
 *
 * Si el servidor rechazara SSML extendido, [build] con `minimal = true`
 * emite únicamente speak + voice + texto.
 */
object SsmlBuilder {

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
     * SSML fiel a mkssml() de la referencia. [rate] y [pitch] ya vienen
     * firmados ("+0%", "+0Hz"); el volumen es fijo "+0%" (no expuesto en v1).
     * [voice] es el nombre CORTO del catálogo (p. ej. "es-MX-DaliaNeural");
     * se expande internamente al formato largo.
     */
    fun build(
        voice: String,
        @Suppress("UNUSED_PARAMETER") locale: String,
        rate: String,
        pitch: String,
        text: String,
        minimal: Boolean = false
    ): String = buildString {
        append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>")
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
