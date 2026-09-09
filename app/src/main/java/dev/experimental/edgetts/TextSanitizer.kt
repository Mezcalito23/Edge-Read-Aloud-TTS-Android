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
     * Normaliza espacios: convierte tabs, newlines y carriage returns a espacios,
     * luego colapsa espacios múltiples.
     */
    fun normalizeSpaces(text: String): String {
        return text
            .replace("\t", " ")  // Tabs a espacios
            .replace("\n", " ")  // Newlines a espacios
            .replace("\r", " ")  // Carriage returns a espacios
            .replace(Regex(" +"), " ")  // Colapsar espacios múltiples
            .trim()
    }

    fun sanitize(text: String): String {
        return normalizeSpaces(removeIncompatibleCharacters(text))
    }
}