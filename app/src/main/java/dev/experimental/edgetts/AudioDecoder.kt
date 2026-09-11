package dev.experimental.edgetts

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeoutException

/**
 * Punto de extensión: el audio comprimido NUNCA se entrega a
 * SynthesisCallback; se decodifica a PCM 16-bit antes.
 */
interface AudioDecoder {

    data class DecodeResult(
        val pcm: ByteArray,
        val sampleRateHz: Int,
        val channelCount: Int
    )

    fun decode(compressed: ByteArray): DecodeResult
}

/**
 * MP3 → PCM 16-bit con MediaExtractor + MediaCodec. Fail-fast: vacío,
 * sobre-tamaño, deadline, EOS sin progreso, audio ausente. Recursos en
 * finally. Cancelable desde onStop().
 */
class Mp3AudioDecoder : AudioDecoder {

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun reset() {
        cancelled = false
    }

    override fun decode(compressed: ByteArray): AudioDecoder.DecodeResult =
        decode(compressed, MAX_INPUT_BYTES, MAX_DECODE_MS, sink = null)

    fun decode(
        mp3: ByteArray,
        maxInputBytes: Int,
        deadlineMs: Long
    ): AudioDecoder.DecodeResult = decode(mp3, maxInputBytes, deadlineMs, sink = null)

    /**
     * Decodifica y emite PCM por buffer de MediaCodec (como ag2s/TTS).
     * Play Books empieza a mezclar en el primer [sink]; no espera al MP3 entero.
     */
    fun decodeStreaming(
        mp3: ByteArray,
        sink: (ByteArray) -> Boolean
    ): AudioDecoder.DecodeResult = decode(mp3, MAX_INPUT_BYTES, MAX_DECODE_MS, sink)

    fun decode(
        mp3: ByteArray,
        maxInputBytes: Int,
        deadlineMs: Long,
        sink: ((ByteArray) -> Boolean)?
    ): AudioDecoder.DecodeResult {
        if (cancelled) throw SynthesisCancelledException()
        if (mp3.isEmpty()) throw UnsupportedAudioFormatException("MP3 vacío")
        if (mp3.size > maxInputBytes) {
            throw UnsupportedAudioFormatException("MP3 excede límite (${mp3.size} > $maxInputBytes)")
        }

        val deadline = SystemClock.elapsedRealtime() + deadlineMs
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(InMemorySource(mp3))
            val track = selectAudioTrack(extractor)
                ?: throw UnsupportedAudioFormatException("No hay track de audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw UnsupportedAudioFormatException("MIME ausente")
            if (!mime.startsWith("audio/", ignoreCase = true)) {
                throw UnsupportedAudioFormatException("MIME inesperado: $mime")
            }

            codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrElse {
                throw UnsupportedAudioFormatException(
                    "Este dispositivo no tiene decodificador para $mime", it
                )
            }
            codec.configure(format, null, null, 0)
            codec.start()
            return runDecode(codec, extractor, format, deadline, sink)
        } catch (e: SynthesisCancelledException) {
            throw e
        } catch (e: TimeoutException) {
            throw e
        } catch (e: UnsupportedAudioFormatException) {
            throw e
        } catch (t: Throwable) {
            throw UnsupportedAudioFormatException("MP3 no decodificable: ${detail(t)}", t)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun runDecode(
        codec: MediaCodec,
        extractor: MediaExtractor,
        format: MediaFormat,
        deadline: Long,
        sink: ((ByteArray) -> Boolean)?
    ): AudioDecoder.DecodeResult {
        val pcm = ByteArrayOutputStream(64 * 1024)
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        var stagnantAfterEos = 0
        var decodedFrames = 0
        var channels = formatInt(format, MediaFormat.KEY_CHANNEL_COUNT, 1)
        var sampleRate = formatInt(format, MediaFormat.KEY_SAMPLE_RATE, 24000)

        while (!outputEos) {
            if (cancelled) throw SynthesisCancelledException()
            if (SystemClock.elapsedRealtime() > deadline) {
                throw TimeoutException("Deadline decoder")
            }

            if (!inputEos) inputEos = queueInput(codec, extractor)

            val outIdx = codec.dequeueOutputBuffer(info, POLL_TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    stagnantAfterEos = 0
                    val of = codec.outputFormat
                    sampleRate = formatInt(of, MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = formatInt(of, MediaFormat.KEY_CHANNEL_COUNT, channels)
                }
                outIdx >= 0 -> {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null) {
                            val chunk = ByteArray(info.size)
                            buf.get(chunk)
                            val mono = if (channels >= 2) downmixToMono(chunk, channels) else chunk
                            if (sink != null) {
                                if (!sink(mono)) {
                                    codec.releaseOutputBuffer(outIdx, false)
                                    throw SynthesisCancelledException()
                                }
                            } else {
                                pcm.write(mono)
                            }
                            decodedFrames++
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    stagnantAfterEos = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                }
                else -> {
                    if (inputEos && ++stagnantAfterEos > STAGNANT_AFTER_EOS) {
                        throw UnsupportedAudioFormatException(
                            "Decoder sin progreso después de EOS"
                        )
                    }
                }
            }
        }

        val result = if (sink != null) {
            if (decodedFrames == 0) ByteArray(0) else ByteArray(2)
        } else {
            pcm.toByteArray()
        }
        if (result.isEmpty() || result.size % 2 != 0) {
            throw UnsupportedAudioFormatException(
                "Audio ausente (frames=$decodedFrames, bytes=${result.size})"
            )
        }

        val outFormat = codec.outputFormat
        sampleRate = formatInt(outFormat, MediaFormat.KEY_SAMPLE_RATE, sampleRate)
        channels = formatInt(outFormat, MediaFormat.KEY_CHANNEL_COUNT, channels)
        return AudioDecoder.DecodeResult(
            if (sink != null) ByteArray(0) else result,
            sampleRate,
            1
        )
    }

    private fun queueInput(codec: MediaCodec, extractor: MediaExtractor): Boolean {
        val inIdx = codec.dequeueInputBuffer(POLL_TIMEOUT_US)
        if (inIdx < 0) return false
        val buf = codec.getInputBuffer(inIdx)
        if (buf == null) {
            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        val n = extractor.readSampleData(buf, 0)
        if (n < 0) {
            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/", ignoreCase = true)) return i
        }
        return null
    }

    private fun formatInt(format: MediaFormat, key: String, fallback: Int): Int {
        if (!format.containsKey(key)) return fallback
        return format.getInteger(key)
    }

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

    private fun detail(t: Throwable): String = buildString {
        append(t.javaClass.simpleName)
        t.message?.let { append(": ").append(it.take(120)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && t is MediaCodec.CodecException) {
            append(" · errorCode=").append(t.errorCode)
            t.diagnosticInfo?.let { append(" · diag=").append(it.take(80)) }
        }
    }

    private class InMemorySource(private val data: ByteArray) : MediaDataSource() {
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
        const val MAX_DECODE_MS: Long = 45_000L
        const val MAX_INPUT_BYTES: Int = 2 * 1024 * 1024
        const val STAGNANT_AFTER_EOS: Int = 25
    }
}
