package dev.experimental.edgetts

/**
 * Divide el texto en fragmentos aptos para el WebSocket:
 *  - conserva párrafos y su orden;
 *  - corta por puntuación (. ! ? … ; :) sin partir palabras;
 *  - ningún fragmento supera [MAX_SEGMENT_CHARS] caracteres ni [MAX_SEGMENT_BYTES] UTF-8;
 *  - admite cancelación cooperativa entre fragmentos.
 *
 * Nunca se envía un libro entero en una sola petición.
 */
object TextSegmenter {

    const val MAX_SEGMENT_CHARS: Int = EdgeProtocolConstants.MAX_SEGMENT_CHARS
    const val MAX_SEGMENT_BYTES: Int = EdgeProtocolConstants.MAX_SEGMENT_BYTES

    /** Tope operativo de Fase 3: primera audio más rápida y cancelación más fina. */
    const val OPERATIONAL_SEGMENT_CHARS: Int = 1200

    fun segment(text: String): List<String> = segment(text, { false }, MAX_SEGMENT_CHARS)

    fun segment(text: String, isCancelled: () -> Boolean): List<String> =
        segment(text, isCancelled, MAX_SEGMENT_CHARS)

    fun segment(text: String, isCancelled: () -> Boolean, maxChars: Int): List<String> {
        val limit = maxChars.coerceAtLeast(1).coerceAtMost(MAX_SEGMENT_CHARS)
        val result = ArrayList<String>()
        val paragraphs = text.split(PARAGRAPH_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (paragraph in paragraphs) {
            if (isCancelled()) break
            val current = StringBuilder()

            for (sentence in splitSentences(paragraph)) {
                if (isCancelled()) break

                if (sentence.length > limit) {
                    if (current.isNotEmpty()) {
                        result += current.toString().trim()
                        current.clear()
                    }
                    result += splitOversized(sentence, limit)
                    continue
                }

                if (current.isNotEmpty() && current.length + sentence.length + 1 > limit) {
                    result += current.toString().trim()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }

            if (current.isNotEmpty()) result += current.toString().trim()
        }

        if (isCancelled()) return result.filter { it.isNotBlank() }
        return result.filter { it.isNotBlank() }.flatMap { enforceUtf8Limit(it) }
    }

    /** Corta por puntuación de fin de frase, dejando el signo en la frase anterior. */
    private fun splitSentences(paragraph: String): List<String> =
        paragraph
            .split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Una "frase" sin puntuación que supera el límite: primero comas, luego espacios. */
    private fun splitOversized(sentence: String, limit: Int): List<String> {
        val chunks = ArrayList<String>()
        val pending = StringBuilder()

        fun flush() {
            if (pending.isNotEmpty()) {
                chunks += pending.toString().trim()
                pending.clear()
            }
        }

        for (clause in sentence.split(CLAUSE_SPLIT)) {
            if (clause.isEmpty()) continue
            if (pending.isNotEmpty() && pending.length + clause.length + 1 > limit) {
                flush()
            }
            if (clause.length > limit) {
                flush()
                chunks += splitByWords(clause, limit)
            } else {
                if (pending.isNotEmpty()) pending.append(' ')
                pending.append(clause)
            }
        }
        flush()
        return chunks.filter { it.isNotEmpty() }
    }

    private fun splitByWords(text: String, limit: Int): List<String> {
        val chunks = ArrayList<String>()
        val pending = StringBuilder()

        for (piece in text.split(WORD_SPLIT)) {
            if (piece.isEmpty()) continue

            if (piece.length > limit) {
                if (pending.isNotEmpty()) {
                    chunks += pending.toString().trimEnd()
                    pending.clear()
                }
                chunks += piece.chunked(limit)
                continue
            }
            if (pending.length + piece.length > limit) {
                chunks += pending.toString().trimEnd()
                pending.clear()
            }
            pending.append(piece)
        }
        if (pending.isNotEmpty()) chunks += pending.toString().trimEnd()
        return chunks
    }

    private fun enforceUtf8Limit(text: String): List<String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_SEGMENT_BYTES) return listOf(text)
        val out = ArrayList<String>()
        var offset = 0
        while (offset < bytes.size) {
            val limit = minOf(offset + MAX_SEGMENT_BYTES, bytes.size)
            var end = offset
            while (end < limit) {
                val width = utf8Width(bytes[end])
                if (end + width > limit) break
                end += width
            }
            if (end == offset) {
                val width = utf8Width(bytes[offset]).coerceAtLeast(1)
                end = minOf(offset + width, bytes.size)
            }
            out += String(bytes, offset, end - offset, Charsets.UTF_8)
            offset = end
        }
        return out
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

    private val PARAGRAPH_SPLIT = Regex("\\n+")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?…;:])\\s+")
    private val CLAUSE_SPLIT = Regex("(?<=[,;])\\s+")
    private val WORD_SPLIT = Regex("(?<=\\s)")
}
