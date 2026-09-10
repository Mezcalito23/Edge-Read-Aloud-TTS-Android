package dev.experimental.edgetts

import okio.ByteString
import org.json.JSONObject

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
        val raw: ByteArray,
        val payloadOffset: Int
    ) {
        val path: String?
            get() = headers[EdgeProtocolConstants.HEADER_PATH]?.trim()

        val payloadLength: Int
            get() = (raw.size - payloadOffset).coerceAtLeast(0)

        val payload: ByteArray
            get() = when {
                payloadLength <= 0 -> ByteArray(0)
                payloadOffset == 0 && payloadLength == raw.size -> raw
                else -> raw.copyOfRange(payloadOffset, payloadOffset + payloadLength)
            }

        fun contentType(): String? =
            headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value

        fun unexpectedAudioType(): Boolean {
            val type = contentType()?.trim()?.lowercase() ?: return payloadLength > 0
            return type != "audio/mpeg" && type != "audio/mp3"
        }
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

    fun isTurnEnd(headers: Map<String, String>): Boolean =
        pathOf(headers) == EdgeProtocolConstants.PATH_TURN_END

    fun isAudio(headers: Map<String, String>): Boolean =
        pathOf(headers) == EdgeProtocolConstants.PATH_AUDIO

    data class TimedRange(
        val offsetTicks: Long,
        val start: Int,
        val end: Int
    )

    /**
     * WordBoundary de audio.metadata. Índices UTF-16 en [segment].
     * Offset en ticks de 100 ns del audio (rany2/edge-tts).
     *
     * Acepta `text` como objeto {Text, Length} o como string. No usa
     * SentenceBoundary: se solapa con las palabras y Play Books se queda
     * en el último rango del ráfaga.
     */
    fun parseTimedRanges(bodies: List<String>, segment: String): List<TimedRange> {
        if (segment.isEmpty() || bodies.isEmpty()) return emptyList()
        val out = ArrayList<TimedRange>()
        var cursor = 0
        for (body in bodies) {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val arr = root.optJSONArray("Metadata") ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("Type") != "WordBoundary") continue
                val data = item.optJSONObject("Data") ?: continue
                val ticks = data.optLong("Offset", -1L)
                if (ticks < 0L) continue
                val word = wordFrom(data)
                if (word.isEmpty()) continue
                val idx = segment.indexOf(word, cursor)
                val start: Int
                val end: Int
                if (idx >= 0) {
                    start = idx
                    end = (start + word.length).coerceAtMost(segment.length)
                    cursor = end
                } else {
                    val len = word.length.coerceAtMost(segment.length - cursor)
                    if (len <= 0) continue
                    start = cursor
                    end = start + len
                    cursor = end
                }
                out += TimedRange(ticks, start, end)
            }
        }
        return out
    }

    private fun wordFrom(data: JSONObject): String {
        val obj = data.optJSONObject("text") ?: data.optJSONObject("Text")
        if (obj != null) {
            val w = obj.optString("Text")
            if (w.isNotEmpty()) return w
        }
        val raw = data.optString("text")
        return if (raw.isNotEmpty() && raw[0] != '{') raw else ""
    }

    fun ticksToFrames(ticks: Long, sampleRateHz: Int): Int {
        if (sampleRateHz <= 0) return 1
        if (ticks <= 0L) return 1
        return ((ticks * sampleRateHz) / 10_000_000L).toInt().coerceAtLeast(1)
    }

    /**
     * Fallback cuando no hay metadata (caché, parse vacío): una marca por
     * palabra, repartida en [totalFrames]. Nunca usa el frame 0 (AudioTrack
     * lo trata como “sin marcador”).
     */
    fun estimateWordRanges(text: String, totalFrames: Int): List<TimedRange> {
        if (text.isEmpty()) return emptyList()
        val words = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && !text[i].isLetterOrDigit()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && text[i].isLetterOrDigit()) i++
            words += start until i
        }
        if (words.isEmpty()) {
            return listOf(TimedRange(1L, 0, text.length))
        }
        val totalChars = words.sumOf { it.last - it.first + 1 }.coerceAtLeast(1)
        val frames = totalFrames.coerceAtLeast(words.size)
        var acc = 0
        return words.map { r ->
            val marker = 1 + (acc.toLong() * (frames - 1) / totalChars).toInt()
            acc += r.last - r.first + 1
            TimedRange(framesToTicks(marker), r.first, r.last + 1)
        }
    }

    fun framesToTicks(frames: Int, sampleRateHz: Int = EdgeProtocolConstants.SAMPLE_RATE_HZ): Long {
        if (frames <= 0 || sampleRateHz <= 0) return 0L
        return frames.toLong() * 10_000_000L / sampleRateHz
    }

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
                        raw = frame,
                        payloadOffset = audioStart
                    )
                }
            }
        }
        // Respaldo: separador \r\n\r\n en cualquier parte (frames sin prefijo).
        val sep = indexOfDoubleCrlf(frame, 0, frame.size - 4)
        if (sep < 0) return BinaryFrame(emptyMap(), frame, 0)
        val headerText = String(frame, 0, sep, Charsets.US_ASCII)
        return BinaryFrame(
            headers = parseTextFrameHeaders(headerText),
            raw = frame,
            payloadOffset = sep + 4
        )
    }

    /** Copia solo el payload; las cabeceras se leen desde la vista de ByteString. */
    fun parseBinaryFrame(bytes: ByteString): BinaryFrame {
        if (bytes.size >= 2) {
            val headerLength =
                ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            val audioStart = headerLength + 2
            if (headerLength in MIN_HEADER_LEN..minOf(bytes.size - 2, MAX_HEADER_LEN) &&
                audioStart <= bytes.size
            ) {
                val headerText = String(
                    bytes.substring(2, audioStart).toByteArray(),
                    Charsets.US_ASCII
                )
                if (headerText.contains(EdgeProtocolConstants.HEADER_PATH)) {
                    val payload = if (audioStart >= bytes.size) ByteArray(0)
                    else bytes.substring(audioStart).toByteArray()
                    return BinaryFrame(parseTextFrameHeaders(headerText), payload, 0)
                }
            }
        }
        return parseBinaryFrame(bytes.toByteArray())
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
