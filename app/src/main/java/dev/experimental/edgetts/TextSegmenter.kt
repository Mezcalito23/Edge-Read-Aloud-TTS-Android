package dev.experimental.edgetts

/**
 * Divide el texto en fragmentos aptos para el WebSocket:
 *  - conserva párrafos y su orden;
 *  - corta por puntuación (. ! ? … ; :) sin partir palabras;
 *  - ningún fragmento supera [MAX_SEGMENT_CHARS] (4.000) caracteres;
 *  - admite cancelación cooperativa entre fragmentos.
 *
 * Nunca se envía un libro entero en una sola petición.
 */
object TextSegmenter {

    const val MAX_SEGMENT_CHARS: Int = EdgeProtocolConstants.MAX_SEGMENT_CHARS

    fun segment(text: String): List<String> = segment(text) { false }

    fun segment(text: String, isCancelled: () -> Boolean): List<String> {
        val result = ArrayList<String>()
        val paragraphs = text.split(Regex("\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (paragraph in paragraphs) {
            if (isCancelled()) break
            val current = StringBuilder()

            for (sentence in splitSentences(paragraph)) {
                if (isCancelled()) break

                if (sentence.length > MAX_SEGMENT_CHARS) {
                    if (current.isNotEmpty()) {
                        result += current.toString().trim()
                        current.clear()
                    }
                    result += splitOversized(sentence)
                    continue
                }

                if (current.isNotEmpty() && current.length + sentence.length + 1 > MAX_SEGMENT_CHARS) {
                    result += current.toString().trim()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }

            if (current.isNotEmpty()) result += current.toString().trim()
        }

        return result.filter { it.isNotBlank() }
    }

    /** Corta por puntuación de fin de frase, dejando el signo en la frase anterior. */
    private fun splitSentences(paragraph: String): List<String> =
        paragraph
            .split(Regex("(?<=[.!?…;:])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Una "frase" sin puntuación que supera el límite: primero comas, luego espacios. */
    private fun splitOversized(sentence: String): List<String> {
        val chunks = ArrayList<String>()
        val pending = StringBuilder()

        fun flush() {
            if (pending.isNotEmpty()) {
                chunks += pending.toString().trim()
                pending.clear()
            }
        }

        for (clause in sentence.split(Regex("(?<=[,;])\\s+"))) {
            if (clause.isEmpty()) continue
            if (pending.isNotEmpty() && pending.length + clause.length + 1 > MAX_SEGMENT_CHARS) {
                flush()
            }
            if (clause.length > MAX_SEGMENT_CHARS) {
                flush()
                chunks += splitByWords(clause)
            } else {
                if (pending.isNotEmpty()) pending.append(' ')
                pending.append(clause)
            }
        }
        flush()
        return chunks.filter { it.isNotEmpty() }
    }

    /** Corta por espacios sin partir palabras; solo un token degenerado se corta en duro. */
    private fun splitByWords(text: String): List<String> {
        val chunks = ArrayList<String>()
        val pending = StringBuilder()

        // El lookbehind conserva el espacio al final de cada pieza.
        for (piece in text.split(Regex("(?<=\\s)"))) {
            if (piece.isEmpty()) continue

            if (piece.length > MAX_SEGMENT_CHARS) {
                if (pending.isNotEmpty()) {
                    chunks += pending.toString().trimEnd()
                    pending.clear()
                }
                chunks += piece.chunked(MAX_SEGMENT_CHARS)
                continue
            }
            if (pending.length + piece.length > MAX_SEGMENT_CHARS) {
                chunks += pending.toString().trimEnd()
                pending.clear()
            }
            pending.append(piece)
        }
        if (pending.isNotEmpty()) chunks += pending.toString().trimEnd()
        return chunks
    }
}
