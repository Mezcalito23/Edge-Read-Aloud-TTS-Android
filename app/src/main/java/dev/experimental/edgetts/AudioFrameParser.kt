package dev.experimental.edgetts

/**
 * Parser de los frames del protocolo WebSocket, aislado del cliente para
 * que un cambio de formato solo toque esta clase.
 *
 * - Frames de texto: cabeceras ASCII "Clave:Valor" separadas por CRLF, una
 *   línea en blanco y, en algunos paths, un cuerpo (p. ej. response).
 * - Frames binarios: prefijo de longitud de 2 bytes (big-endian) + cabeceras
 *   ASCII + audio, igual que `get_headers_and_data` de edge-tts.
 */
object AudioFrameParser {

    enum class PayloadFormat { PCM, COMPRESSED }

    data class BinaryFrame(
        val headers: Map<String, String>,
        val payload: ByteArray
    ) {
        val path: String?
            get() = headers[EdgeProtocolConstants.HEADER_PATH]?.trim()
    }

    // ── Frames de texto ─────────────────────────────────────────────────────

    fun parseTextFrameHeaders(frame: String): Map<String, String> {
        // IMPORTANTE: sin valor por defecto. Los frames binarios terminan
        // cada cabecera con un solo \r\n (sin doble); con "" por defecto el
        // mapa salía vacío y los frames de audio se descartaban en silencio.
        val section = frame.substringBefore("\r\n\r\n")
            .substringBefore("\n\n")
        return section
            .split("\r\n", "\n")
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
    }

    /** Cuerpo posterior a la línea en blanco (vacío si no existe). */
    fun bodyOf(frame: String): String {
        val crlf = frame.indexOf("\r\n\r\n")
        if (crlf >= 0) return frame.substring(crlf + 4)
        val lf = frame.indexOf("\n\n")
        return if (lf >= 0) frame.substring(lf + 2) else ""
    }

    /** Valor del header Path ("turn.end", "audio", …) o null. */
    fun pathOf(headers: Map<String, String>): String? =
        headers[EdgeProtocolConstants.HEADER_PATH]?.trim()

    fun isTurnStart(headers: Map<String, String>): Boolean =
        pathOf(headers) == EdgeProtocolConstants.PATH_TURN_START

    fun isTurnEnd(headers: Map<String, String>): Boolean =
        pathOf(headers) == EdgeProtocolConstants.PATH_TURN_END

    fun isAudio(headers: Map<String, String>): Boolean =
        pathOf(headers) == EdgeProtocolConstants.PATH_AUDIO

    // ── Frames binarios ─────────────────────────────────────────────────────

    private const val MIN_HEADER_LEN = 8
    private const val MAX_HEADER_LEN = 8192

    /**
     * Réplica de `get_headers_and_data` de edge-tts:
     *
     *   header_length = int.from_bytes(data[:2], "big")   // p. ej. 0x0080 = 128
     *   cabeceras     = data[2, header_length + 2)
     *   audio         = data[header_length + 2:]
     *
     * Se valida el rango del prefijo y que el bloque contenga "Path"; si el
     * prefijo no es plausible se degrada al separador \r\n\r\n. El payload se
     * extrae SIEMPRE que haya una interpretación válida: nunca se descarta un
     * frame por dudas menores (la referencia tampoco lo hace).
     */
    fun parseBinaryFrame(frame: ByteArray): BinaryFrame {
        if (frame.size >= 2) {
            val headerLength =
                ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
            val audioStart = headerLength + 2
            if (headerLength in MIN_HEADER_LEN..minOf(frame.size - 2, MAX_HEADER_LEN) &&
                audioStart <= frame.size
            ) {
                val headerText = String(frame, 2, audioStart - 2, Charsets.US_ASCII)
                if (headerText.contains(EdgeProtocolConstants.HEADER_PATH)) {
                    return BinaryFrame(
                        headers = parseTextFrameHeaders(headerText),
                        payload = frame.copyOfRange(audioStart, frame.size)
                    )
                }
            }
        }
        // Respaldo: separador \r\n\r\n en cualquier parte (frames sin prefijo).
        val sep = indexOfDoubleCrlf(frame, 0, frame.size - 4)
        if (sep < 0) return BinaryFrame(emptyMap(), frame)
        val headerText = String(frame, 0, sep, Charsets.US_ASCII)
        return BinaryFrame(
            headers = parseTextFrameHeaders(headerText),
            payload = frame.copyOfRange(sep + 4, frame.size)
        )
    }

    private fun indexOfDoubleCrlf(b: ByteArray, from: Int, to: Int): Int {
        val cr = '\r'.code.toByte()
        val lf = '\n'.code.toByte()
        var i = maxOf(from, 0)
        while (i <= minOf(to, b.size - 4)) {
            if (b[i] == cr && b[i + 1] == lf && b[i + 2] == cr && b[i + 3] == lf) return i
            i++
        }
        return -1
    }

    // ── Audio ───────────────────────────────────────────────────────────────

    /**
     * El formato riff-24khz-16bit-mono-pcm llega así: el primer payload es un
     * WAV completo (cabecera RIFF de ~44+ bytes) y los siguientes son PCM
     * crudo. Quitamos la cabecera para entregar solo muestras al callback.
     */
    fun stripRiffHeader(payload: ByteArray): ByteArray {
        if (payload.size < 44) return payload
        if (ascii(payload, 0, 4) != "RIFF") return payload
        // Buscar el sub-chunk "data" dentro de la región de cabecera.
        val limit = minOf(payload.size - 8, 256)
        var i = 12
        while (i <= limit) {
            if (ascii(payload, i, 4) == "data") {
                return payload.copyOfRange(i + 8, payload.size)
            }
            i++
        }
        return payload
    }

    /**
     * Detección defensiva del formato real recibido:
     *  - MP3: tag ID3 o sincronía de frame 0xFF 0xEx/0xFx (formato VERIFICADO
     *    del servicio, audio-24khz-48kbitrate-mono-mp3);
     *  - WebM/Opus: "OggS" o magic EBML 0x1A45DFA3;
     *  - cualquier otra cosa se asume PCM (muestras crudas no tienen magic).
     */
    fun detectFormat(payload: ByteArray): PayloadFormat {
        if (payload.size >= 3 && ascii(payload, 0, 3) == "ID3") return PayloadFormat.COMPRESSED
        if (payload.size >= 2) {
            val b0 = payload[0].toInt() and 0xFF
            val b1 = payload[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return PayloadFormat.COMPRESSED
        }
        if (payload.size >= 4) {
            if (ascii(payload, 0, 4) == "OggS") return PayloadFormat.COMPRESSED
            if (payload[0] == 0x1A.toByte() && payload[1] == 0x45.toByte() &&
                payload[2] == 0xDF.toByte() && payload[3] == 0xA3.toByte()
            ) return PayloadFormat.COMPRESSED
        }
        return PayloadFormat.PCM
    }

    private fun ascii(b: ByteArray, offset: Int, length: Int): String =
        if (offset + length <= b.size) String(b, offset, length, Charsets.US_ASCII) else ""

    /** Resumen seguro para mensajes de error: nunca URLs ni tokens completos. */
    fun truncate(s: String, max: Int = 120): String =
        if (s.length <= max) s else s.take(max) + "…"
}
