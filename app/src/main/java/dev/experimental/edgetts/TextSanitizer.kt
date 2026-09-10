package dev.experimental.edgetts

/**
 * Sanitiza texto eliminando caracteres de control incompatibles con XML 1.0 y el servicio Edge TTS.
 *
 * Caracteres reemplazados por espacio:
 * - U+0000..U+0008
 * - U+000B..U+000C
 * - U+000E..U+001F
 *
 * Caracteres preservados:
 * - U+0009 (tab)
 * - U+000A (LF)
 * - U+000D (CR)
 * - U+0020+ (imprimibles)
 */
object TextSanitizer {

    fun removeIncompatibleCharacters(text: String): String {
        if (text.isEmpty()) return text
        val result = StringBuilder(text.length)
        for (char in text) {
            val code = char.code
            if (isIncompatibleCharacter(code)) {
                result.append(' ')
            } else {
                result.append(char)
            }
        }
        return result.toString()
    }

    private fun isIncompatibleCharacter(code: Int): Boolean {
        return (code in 0..8) ||
                (code in 11..12) ||
                (code in 14..31)
    }

    /**
     * Normaliza espacios: convierte tabs a espacios, elimina newlines/carriage returns,
     * luego colapsa espacios múltiples.
     */
    fun normalizeSpaces(text: String): String {
        return text
            .replace("\t", " ")  // Tabs a espacios (1 tab = 1 espacio)
            .replace("\n", "")  // Newlines se eliminan
            .replace("\r", "")  // Carriage returns se eliminan
            .replace(SPACES, " ")  // Colapsar espacios múltiples
            .trim()
    }

    fun sanitize(text: String): String {
        return normalizeSpaces(removeIncompatibleCharacters(text))
    }

    fun escapeXml(raw: String): String = buildString(raw.length + 16) {
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            val point: Int
            val width: Int
            when {
                Character.isHighSurrogate(ch) &&
                    index + 1 < raw.length &&
                    Character.isLowSurrogate(raw[index + 1]) -> {
                    point = Character.toCodePoint(ch, raw[index + 1])
                    width = 2
                }
                Character.isSurrogate(ch) -> {
                    point = 0xFFFD
                    width = 1
                }
                else -> {
                    point = ch.code
                    width = 1
                }
            }
            if (!isXml10CodePoint(point)) append('\uFFFD')
            else when (point) {
                '&'.code -> append("\u0026amp;")
                '<'.code -> append("\u0026lt;")
                '>'.code -> append("\u0026gt;")
                '"'.code -> append("\u0026quot;")
                '\''.code -> append("\u0026apos;")
                else -> appendCodePoint(point)
            }
            index += width
        }
    }

    private fun isXml10CodePoint(point: Int): Boolean =
        point == 0x9 || point == 0xA || point == 0xD ||
            point in 0x20..0xD7FF || point in 0xE000..0xFFFD ||
            point in 0x10000..0x10FFFF

    private val SPACES = Regex(" +")
}