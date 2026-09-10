package dev.experimental.edgetts

/**
 * Segmenta por bytes UTF-8 (rany2/edge-tts split_text_by_byte_length),
 * sin partir unidades multi-byte. El tope protocolario es 4096 bytes;
 * el operativo es menor para acotar la espera del primer audio.
 *
 * joinToString("") reconstruye el texto si no hubo cancelación.
 */
object TextSegmenter {

    const val MAX_SEGMENT_BYTES: Int = EdgeProtocolConstants.MAX_SEGMENT_BYTES

    /** ~1200 caracteres CJK / 3600 ASCII: primer audio razonable, menos cortes en español. */
    const val OPERATIONAL_SEGMENT_BYTES: Int = 3600

    fun segment(text: String): List<String> = segment(text, { false }, MAX_SEGMENT_BYTES)

    fun segment(text: String, isCancelled: () -> Boolean): List<String> =
        segment(text, isCancelled, MAX_SEGMENT_BYTES)

    fun segment(text: String, isCancelled: () -> Boolean, maxBytes: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val limit = maxBytes.coerceAtLeast(1).coerceAtMost(MAX_SEGMENT_BYTES)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val result = ArrayList<String>()
        var offset = 0
        while (offset < bytes.size && !isCancelled()) {
            val cap = minOf(offset + limit, bytes.size)
            val end = if (cap == bytes.size) bytes.size else findSmartSplitPoint(bytes, offset, cap)
            check(end > offset) { "La segmentación no avanzó: $offset -> $end" }
            result += String(bytes, offset, end - offset, Charsets.UTF_8)
            offset = end
        }
        return result
    }

    private fun findSmartSplitPoint(bytes: ByteArray, start: Int, end: Int): Int {
        for (i in end - 1 downTo start + 1) {
            if (bytes[i - 1] == '\n'.code.toByte() && bytes[i] == '\n'.code.toByte()) return i + 1
        }
        for (i in end - 1 downTo start) {
            if (bytes[i] == '\n'.code.toByte()) return i + 1
        }
        for (i in end - 1 downTo start) {
            val b = bytes[i]
            if (b == ' '.code.toByte() || b == '\t'.code.toByte()) return i + 1
        }
        return findUtf8SafeBoundary(bytes, start, end)
    }

    private fun findUtf8SafeBoundary(bytes: ByteArray, start: Int, end: Int): Int {
        var limit = minOf(end, bytes.size)
        var cursor = start
        while (cursor < limit) {
            val width = utf8Width(bytes[cursor])
            if (cursor + width > limit) break
            cursor += width
        }
        if (cursor == start) {
            val width = utf8Width(bytes[start]).coerceAtLeast(1)
            cursor = minOf(start + width, bytes.size)
        }
        return cursor
    }

    private fun utf8Width(lead: Byte): Int {
        val v = lead.toInt() and 0xFF
        return when {
            v < 0x80 -> 1
            v and 0xE0 == 0xC0 -> 2
            v and 0xF0 == 0xE0 -> 3
            v and 0xF8 == 0xF0 -> 4
            else -> 1
        }
    }
}
