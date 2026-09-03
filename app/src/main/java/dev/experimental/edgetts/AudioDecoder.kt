package dev.experimental.edgetts

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaDataSource
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Punto de extensión previsto por la especificación: el audio comprimido
 * NUNCA se entrega directamente a SynthesisCallback; se decodifica a PCM
 * 16-bit antes. Esta interfaz permite añadir decodificadores (Opus, fase 2)
 * sin tocar el servicio ni el cliente.
 */
interface AudioDecoder {

    data class DecodeResult(
        val pcm: ByteArray,
        val sampleRateHz: Int,
        val channelCount: Int
    )

    /**
     * Decodifica un flujo comprimido COMPLETO a PCM 16-bit.
     * @throws UnsupportedAudioFormatException si el dispositivo no puede
     * decodificar el formato o los datos están corruptos.
     */
    fun decode(compressed: ByteArray): DecodeResult
}

/**
 * Decodificador MP3 → PCM 16-bit con la tubería canónica de Android:
 * **MediaExtractor + MediaCodec**. El extractor parsea el contenedor MP3
 * desde memoria (sin tocar disco) y entrega al códec un MediaFormat COMPLETO
 * (mime + sample-rate + channel-count), que es lo que los decodificadores de
 * fabricante esperan: configurar el códec a mano con el MP3 crudo lanza
 * IllegalStateException en muchos equipos.
 *
 * Válida para audio-24khz-48kbitrate-mono-mp3 y cualquier MP3 que acepte el
 * códec del dispositivo.
 */
class Mp3AudioDecoder : AudioDecoder {

    override fun decode(compressed: ByteArray): AudioDecoder.DecodeResult {
        if (compressed.isEmpty()) {
            throw UnsupportedAudioFormatException("MP3 vacío: nada que decodificar")
        }

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(InMemorySource(compressed))
            if (extractor.trackCount == 0) {
                throw UnsupportedAudioFormatException(
                    "MediaExtractor no encontró pistas: los datos no son MP3"
                )
            }
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw UnsupportedAudioFormatException("La pista no declara MIME")
            if (!mime.startsWith("audio/", ignoreCase = true)) {
                throw UnsupportedAudioFormatException("MIME inesperado: $mime")
            }

            val codec = runCatching { MediaCodec.createDecoderByType(mime) }
                .getOrElse {
                    throw UnsupportedAudioFormatException(
                        "Este dispositivo no tiene decodificador para $mime"
                    )
                }

            try {
                codec.configure(format, null, null, 0)
                codec.start()
                runDecode(codec, extractor, format)
            } catch (e: UnsupportedAudioFormatException) {
                throw e
            } catch (t: Throwable) {
                throw UnsupportedAudioFormatException(
                    "MP3 no decodificable: ${detail(t)}", t
                )
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } catch (e: UnsupportedAudioFormatException) {
            throw e
        } catch (t: Throwable) {
            throw UnsupportedAudioFormatException("MP3 no decodificable: ${detail(t)}", t)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun runDecode(
        codec: MediaCodec,
        extractor: MediaExtractor,
        format: MediaFormat
    ): AudioDecoder.DecodeResult {
        val pcm = ByteArrayOutputStream(64 * 1024)
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var decodedFrames = 0
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_DECODE_MS)

        while (true) {
            if (System.nanoTime() > deadline) {
                throw UnsupportedAudioFormatException(
                    "La decodificación MP3 superó ${MAX_DECODE_MS} ms"
                )
            }

            // ── Alimentar entrada desde el extractor ─────────────────────
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(POLL_TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    if (buf == null) {
                        codec.queueInputBuffer(
                            inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEos = true
                    } else {
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            // ── Drenar salida ────────────────────────────────────────────
            var outIdx = codec.dequeueOutputBuffer(info, POLL_TIMEOUT_US)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outIdx = codec.dequeueOutputBuffer(info, 0)
            }
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null) {
                        val chunk = ByteArray(info.size)
                        buf.get(chunk)
                        pcm.write(chunk)
                        decodedFrames++
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }

            if (sawInputEos && decodedFrames == 0 && outIdx < 0 &&
                System.nanoTime() > deadline - TimeUnit.SECONDS.toNanos(2)
            ) {
                break
            }
        }

        val outFormat = codec.outputFormat
        val sampleRate = outFormat.getInteger(
            MediaFormat.KEY_SAMPLE_RATE,
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 24000)
        )
        val channels = outFormat.getInteger(
            MediaFormat.KEY_CHANNEL_COUNT,
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
        )

        val result = pcm.toByteArray()
        if (result.isEmpty() || result.size % 2 != 0) {
            throw UnsupportedAudioFormatException(
                "El decodificador no produjo PCM 16-bit válido " +
                    "(frames=$decodedFrames, bytes=${result.size})"
            )
        }

        // Si algún dispositivo entregara más de un canal, se mezcla a mono:
        // SynthesisCallback se configura en mono (1 canal).
        val mono = if (channels >= 2) downmixToMono(result, channels) else result
        return AudioDecoder.DecodeResult(mono, sampleRate, 1)
    }

    /** Mezcla pares de muestras 16-bit LE a mono (media de canales). */
    private fun downmixToMono(pcm: ByteArray, channels: Int): ByteArray {
        val frameBytes = channels * 2
        val frames = pcm.size / frameBytes
        val mono = ByteArray(frames * 2)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val off = i * frameBytes + c * 2
                sum += (pcm[off].toInt() and 0xFF) or (pcm[off + 1].toInt() shl 8)
            }
            val mixed = (sum / channels).toShort()
            mono[i * 2] = (mixed.toInt() and 0xFF).toByte()
            mono[i * 2 + 1] = ((mixed.toInt() shr 8) and 0xFF).toByte()
        }
        return mono
    }

    /** Detalle completo de la excepción para el diagnóstico en la app. */
    private fun detail(t: Throwable): String = buildString {
        append(t.javaClass.simpleName)
        t.message?.let { append(": ").append(it.take(120)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && t is android.media.MediaCodec.CodecException) {
            append(" · errorCode=").append(t.errorCode)
            t.diagnosticInfo?.let { append(" · diag=").append(it.take(80)) }
        }
    }

    /** MediaDataSource en memoria: el extractor lee del array, sin archivo. */
    private class InMemorySource(private val data: ByteArray) : MediaDataSource() {
        private var pos = 0L
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    companion object {
        private const val POLL_TIMEOUT_US = 10_000L
        private const val MAX_DECODE_MS = 45_000L
    }
}
