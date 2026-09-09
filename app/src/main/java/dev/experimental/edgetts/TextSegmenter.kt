package dev.experimental.edgetts

/**
 * Segmenta texto por bytes UTF-8 no caracteres, replicando el comportamiento de rany2/edge-tts v7.2.8 splitTextByByteLength.
 * Esto es crítico para idiomas con caracteres multi-byte:
 * - CJK (chino, japon\u00e9s, coreano) ~3 bytes por car\u00e1cter
 * - \u00c1rabe, hebreo ~2 bytes por car\u00e1cter
 * - Espa\u00f1ol, franc\u00e9s 1-2 bytes por car\u00e1cter
 *
 * Con 4096 bytes, todos los idiomas tienen el mismo l\u00edmite efectivo.
 */
object TextSegmenter {
    /** L\u00edmite en bytes UTF-8 (no caracteres). Replicado de rany2/edge-tts splitTextByByteLength(text, 4096) */
    const val MAX_SEGMENT_BYTES: Int = 4096

    /**
     * Segmenta el texto en fragmentos de m\u00e1ximo MAX_SEGMENT_BYTES bytes UTF-8.
     * Estrategia de divisi\u00f3n (prioridad):
     * 1. Saltos de l\u00ednea dobles (p\u00e1rrafos)
     * 2. Saltos de l\u00ednea simples
     * 3. Espacios (palabras)
     * 4. L\u00edmite duro (sin partir caracteres multi-byte)
     *
     * @param text Texto a segmentar
     * @param isCancelled Funci\u00f3n de cancelaci\u00f3n cooperativa
     * @return Lista de segmentos, cada uno <= MAX_SEGMENT_BYTES bytes UTF-8
     */
    fun segment(text: String, isCancelled: () -> Boolean = { false }): List<String> {
        // Si el texto cabe en un segmento, devolverlo directamente
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        if (utf8Bytes.size <= MAX_SEGMENT_BYTES) {
            return listOf(text.trim())
        }

        // Texto largo - aplicar segmentaci\u00f3n por bytes
        return segmentByBytes(text, isCancelled)
    }

    /**
     * Segmenta un texto por bytes UTF-8 cuando excede MAX_SEGMENT_BYTES.
     * Estrategia de divisi\u00f3n (prioridad):
     * 1. Doble salto de l\u00ednea (p\u00e1rrafo)
     * 2. Salto de l\u00ednea simple
     * 3. Espacio (palabra)
     * 4. L\u00edmite duro (sin partir caracteres multi-byte)
     */
    private fun segmentByBytes(text: String, isCancelled: () -> Boolean): List<String> {
        val result = mutableListOf<String>()
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0

        while (offset < utf8Bytes.size && !isCancelled()) {
            // Calcular el l\u00edmite para este segmento
            val segmentEnd = minOf(offset + MAX_SEGMENT_BYTES, utf8Bytes.size)

            if (segmentEnd >= utf8Bytes.size) {
                // \u00daltimo segmento - tomar todo lo restante
                val segment = utf8Bytes.decodeUtf8Safe(offset, utf8Bytes.size)
                if (segment.isNotBlank()) {
                    result.add(segment)
                }
                break
            }

            // Buscar punto de divisi\u00f3n inteligente
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
     * Busca el mejor punto de divisi\u00f3n dentro del rango [start, end).
     * Prioridad: 1. Doble salto de l\u00ednea (p\u00e1rrafo), 2. Salto de l\u00ednea, 3. Espacio, 4. L\u00edmite duro
     */
    private fun findSmartSplitPoint(bytes: ByteArray, start: Int, end: Int): Int {
        // Buscar desde el final hacia el inicio (preferir divisi\u00f3n tard\u00eda)

        // 1. Buscar doble salto de l\u00ednea (\n\n)
        for (i in end - 1 downTo start + 1) {
            if (bytes[i].toInt() == 0x0A && bytes[i - 1].toInt() == 0x0A) { // '\n\n'
                return i + 1 // Incluir el segundo salto de l\u00ednea en el segmento anterior
            }
        }

        // 2. Buscar salto de l\u00ednea simple
        for (i in end - 1 downTo start) {
            if (bytes[i].toInt() == 0x0A) { // '\n'
                return i + 1 // Incluir el salto de l\u00ednea en el segmento anterior
            }
        }

        // 3. Buscar espacio (palabra)
        for (i in end - 1 downTo start) {
            if (bytes[i].toInt() == 0x20) { // ' '
                return i + 1 // Incluir el espacio en el segmento anterior
            }
        }

        // 4. L\u00edmite duro - asegurar que no partimos un car\u00e1cter multi-byte
        return findUtf8SafeBoundary(bytes, start, end)
    }

    /**
     * Encuentra un l\u00edmite seguro para UTF-8 que no parta un car\u00e1cter multi-byte.
     * UTF-8 encoding:
     * - 0xxxxxxx: 1 byte (ASCII)
     * - 110xxxxx 10xxxxxx: 2 bytes
     * - 1110xxxx 10xxxxxx 10xxxxxx: 3 bytes
     * - 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx: 4 bytes
     *
     * Los bytes de continuaci\u00f3n empiezan con 10xxxxxx (0x80-0xBF).
     */
    private fun findUtf8SafeBoundary(bytes: ByteArray, start: Int, end: Int): Int {
        var safeEnd = end

        // Retroceder hasta encontrar un byte que NO sea de continuaci\u00f3n
        while (safeEnd > start && isUtf8ContinuationByte(bytes[safeEnd - 1])) {
            safeEnd--
        }

        return safeEnd
    }

    /**
     * Verifica si un byte es un byte de continuaci\u00f3n UTF-8 (10xxxxxx).
     */
    private fun isUtf8ContinuationByte(byte: Byte): Boolean {
        val b = byte.toInt() and 0xFF
        return (b and 0xC0) == 0x80
    }

    /**
     * Decodifica un rango de bytes UTF-8 de forma segura.
     * Si el rango termina en medio de un car\u00e1cter multi-byte, lo excluye.
     */
    private fun ByteArray.decodeUtf8Safe(start: Int, end: Int): String {
        if (start >= end) return ""

        // Asegurar que no partimos un car\u00e1cter multi-byte
        var safeEnd = end
        while (safeEnd > start && isUtf8ContinuationByte(this[safeEnd - 1])) {
            safeEnd--
        }

        return String(this, start, safeEnd - start, Charsets.UTF_8)
    }

    /**
     * Versi\u00f3n de compatibilidad: segmenta con cancelaci\u00f3n por defecto desactivada.
     */
    fun segment(text: String): List<String> = segment(text, isCancelled = { false })
}