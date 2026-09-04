package dev.experimental.edgetts

/**
 * Segmenta texto por bytes UTF-8 (no caracteres), replicando el comportamiento
 * de rany2/edge-tts v7.2.8 (split_text_by_byte_length).
 *
 * Esto es crítico para idiomas con caracteres multi-byte:
 * - CJK (chino, japoné««, coreano): 3 bytes por carácter
 * - Árabe, hebreo: 2 bytes por carácter
 * - Español, francés: 1-2 bytes por carácter
 *
 * Con 4096 bytes, todos los idiomas tienen el mismo límite efectivo.
 */
object TextSegmenter {

    /**
     * Límite en bytes UTF-8 (no caracteres).
     * Replicado de rany2/edge-tts: split_text_by_byte_length(text, 4096)
     */
    const val MAX_SEGMENT_BYTES: Int = 4096

    /**
     * Segmenta el texto en fragmentos de máximo [MAX_SEGMENT_BYTES] bytes UTF-8.
     *
     * Estrategia de división (prioridad):
     * 1. Saltos de línea dobles (pá««rrafos)
     * 2. Saltos de línea simples
     * 3. Espacios (palabras)
     * 4. Límite duro (sin partir caracteres multi-byte)
     *
     * @param text Texto a segmentar
     * @param isCancelled Funcion de cancelacion cooperativa
     * @return Lista de segmentos, cada uno <= MAX_SEGMENT_BYTES bytes UTF-8
     */
    fun segment(text: String, isCancelled: () -> Boolean = { false }): List<String> {
        val result = mutableListOf<String>()
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0

        while (offset < utf8Bytes.size && !isCancelled()) {
            // Calcular el límite para este segmento
            val segmentEnd = minOf(offset + MAX_SEGMENT_BYTES, utf8Bytes.size)
            if (segmentEnd >= utf8Bytes.size) {
                // Último segmento: tomar todo lo restante
                val segment = utf8Bytes.decodeUtf8Safe(offset, utf8Bytes.size)
                if (segment.isNotBlank()) {
                    result.add(segment)
                }
                break
            }

            // Buscar punto de división inteligente
            val splitPoint = findSmartSplitPoint(utf8Bytes, offset, segmentEnd)

            // Extraer segmento
            val segment = utf8Bytes.decodeUtf8Safe(offset, splitPoint)
            if (segment.isNotBlank()) {
                result.add(segment)
            }
            offset = splitPoint
        }

        return result
    }

    /**
     * Busca el mejor punto de división dentro del rango [start, end).
     *
     * Prioridad:
     * 1. Doble salto de línea (pá««rrafo)
     * 2. Salto de línea simple
     * 3. Espacio (palabra)
     * 4. Límite duro (sin partir caracteres multi-byte UTF-8)
     */
    private fun findSmartSplitPoint(bytes: ByteArray, start: Int, end: Int): Int {
        // Buscar desde el final hacia el inicio (preferir división tardí««a)
        // 1. Buscar doble salto de lí­nea (\n\n)
        for (i in end - 1 downTo start) {
            if (bytes[i] == '\n'.code.toByte() && i > start && bytes[i - 1] == '\n'.code.toByte()) {
                return i + 1 // Incluir el segundo \n
            }
        }

        // 2. Buscar salto de lí­nea simple (\n)
        for (i in end - 1 downTo start) {
            if (bytes[i] == '\n'.code.toByte()) {
                return i + 1
            }
        }

        // 3. Buscar espacio
        for (i in end - 1 downTo start) {
            if (bytes[i] == ' '.code.toByte()) {
                return i + 1
            }
        }

        // 4. Lí­mite duro: asegurar que no partimos un carácter multi-byte
        return findUtf8SafeBoundary(bytes, start, end)
    }

    /**
     * Encuentra un lí­mite seguro para UTF-8 que no parta un carácter multi-byte.
     *
     * UTF-8 encoding:
     * - 0xxxxxxx: 1 byte (ASCII)
     * - 110xxxxx 10xxxxxx: 2 bytes
     * - 1110xxxx 10xxxxxx 10xxxxxx: 3 bytes
     * - 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx: 4 bytes
     *
     * Los bytes de continuacion empiezan con 10xxxxxx (0x80-0xBF).
     */
    private fun findUtf8SafeBoundary(bytes: ByteArray, start: Int, end: Int): Int {
        var safeEnd = end
        // Retroceder hasta encontrar un byte que NO sea de continuacion
        while (safeEnd > start && isUtf8ContinuationByte(bytes[safeEnd - 1])) {
            safeEnd--
        }
        return safeEnd
    }

    /**
     * Verifica si un byte es un byte de continuacion UTF-8 (10xxxxxx).
     */
    private fun isUtf8ContinuationByte(byte: Byte): Boolean {
        val b = byte.toInt() and 0xFF
        return (b and 0xC0) == 0x80
    }

    /**
     * Decodifica un rango de bytes UTF-8 de forma segura.
     * Si el rango termina en medio de un carácter multi-byte, lo excluye.
     */
    private fun ByteArray.decodeUtf8Safe(start: Int, end: Int): String {
        if (start >= end) return ""
        // Asegurar que no partimos un carácter multi-byte
        var safeEnd = end
        while (safeEnd > start && isUtf8ContinuationByte(this[safeEnd - 1])) {
            safeEnd--
        }
        return String(this, start, safeEnd - start, Charsets.UTF_8).trim()
    }

    /**
     * Version de compatibilidad: segmenta con cancelacion por defecto desactivada.
     */
    fun segment(text: String): List<String> = segment(text) { false }
}
