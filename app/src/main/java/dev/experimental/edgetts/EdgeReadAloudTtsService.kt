package dev.experimental.edgetts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor TTS del sistema que sintetiza mediante el protocolo NO oficial de Microsoft Edge Read Aloud.
 *
 * Contrato garantizado:
 * - done XOR error, exactamente una vez por sÃ©ntesis
 * - ninguna excepciÃ³n escapa del servicio: la app cliente nunca crashea
 * - onStop cancela la sÃ©ntesis activa
 * - el audio se entrega SIEMPRE como PCM 16-bit: el MP3 de Edge se decodifica con MediaCodec ANTES de tocar SynthesisCallback
 *
 * Modelo de voces como Google TTS:
 * - se expone UNA voz por idioma
 * - Para el idioma de la voz configurada en la app (espaÃ±ol, Dalia por defecto) esa es la voz expuesta:
 *   cambiarla en la app cambia la voz del espaÃ±ol en TODO el sistema.
 * - Para otros idiomas se expone una voz representativa del catÃ¡logo y se resuelve automÃ¡ticamente segÃºn el idioma del contenido.
 */
class EdgeReadAloudTtsService : TextToSpeechService() {

    private var settings: SettingsStore? = null
    private var catalog: VoiceCatalogRepository? = null
    private var cache: CacheRepository? = null
    private var http: OkHttpClient? = null
    private val mp3Decoder: Mp3AudioDecoder = Mp3AudioDecoder()

    @Volatile
    private var active: Cancellable? = null

    @Volatile
    private var stopRequested = false

    private val synthesisLock = Any()

    /**
     * Idioma cargado por el cliente (setLanguage). Se inicializa con el idioma del SISTEMA
     * para que el TTS por defecto siga al dispositivo: inglÃ©s en un equipo inglÃ©s, francÃ©s en uno francÃ©s, etc.
     * como hace Google TTS.
     */
    @Volatile
    private var currentLanguage: Array<String> = arrayOf("es", "MX")

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext
        val client = OkHttpClient.Builder()
            .connectTimeout(EdgeProtocolConstants.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(EdgeProtocolConstants.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .pingInterval(EdgeProtocolConstants.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .build()
        http = client
        settings = SettingsStore(app)
        catalog = VoiceCatalogRepository(client, app.cacheDir)
        cache = CacheRepository(app.cacheDir)

        // Idioma inicial: el del dispositivo, si el catÃ¡logo lo cubre; si no, espaÃ±ol de MÃ©xico.
        // AsÃ© el idioma predeterminado del motor coincide con el sistema desde el primer momento.
        runCatching {
            val dev = Locale.getDefault()
            if (languageAvailability(dev.language, dev.country) == TextToSpeech.LANG_AVAILABLE) {
                currentLanguage = arrayOf(dev.language, dev.country)
            }
        }
    }

    override fun onDestroy() {
        active?.cancel()
        super.onDestroy()
    }

    // ... (resto del servicio: idioma, voces, negociaciÃ³n, etc. sin cambios)
    // Se omite aquÃ© para brevedad: se mantiene el contenido original del servicio

    /**
     * SÃ©ntesis
     */
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        // Metadatos de la peticiÃ³n para depurar la integraciÃ³n con el sistema
        // (longitud del texto, NO el contenido: nunca datos sensibles).
        Log.d(
            TAG,
            "onSynthesizeText chars=${request.charSequenceText?.length ?: 0} " +
                "lang=${request.language}${request.country}${request.variant} " +
                "voice=${request.voiceName} rate=${request.speechRate} pitch=${request.pitch}"
        )

        // Una sola llamada terminal (done XOR error) garantizada a este nivel:
        // ni la red ni los errores internos pueden escapar del servicio.
        val guard = TerminalGuard(callback)
        synchronized(synthesisLock) {
            runCatching {
                synthesizeInternal(request, callback, guard)
            }.onFailure { t ->
                Log.e(TAG, "fallo interno", t)
                guard.error(TextToSpeech.ERROR_SYNTHESIS)
                runCatching { settings?.setLastError("Error interno de sÃ©ntesis") }
            }
        }
    }

    private fun synthesizeInternal(
        request: SynthesisRequest,
        callback: SynthesisCallback,
        guard: TerminalGuard
    ) {
        stopRequested = false

        val text = request.charSequenceText?.toString()

        // Texto vacÃ©o o nulo: ÃÂ©xito silencioso sin tocar la red.
        if (text.isNullOrBlank()) {
            runCatching {
                callback.start(
                    EdgeProtocolConstants.SAMPLE_RATE_HZ,
                    AudioFormat.ENCODING_PCM_16BIT,
                    EdgeProtocolConstants.CHANNEL_COUNT_MONO
                )
            }
            guard.done()
            return
        }

        val snap = settings?.snapshotBlocking
            ?: return guard.error(TextToSpeech.ERROR_SYNTHESIS, "No se pudo leer la configuraciÃ³n local.")

        val segments = runCatching { TextSegmenter.segment(text, stopRequested) }
            .getOrElse { return guard.error(TextToSpeech.ERROR_SYNTHESIS, "No se pudo segmentar el texto.") }

        if (segments.isEmpty()) {
            runCatching {
                callback.start(
                    EdgeProtocolConstants.SAMPLE_RATE_HZ,
                    AudioFormat.ENCODING_PCM_16BIT,
                    EdgeProtocolConstants.CHANNEL_COUNT_MONO
                )
            }
            guard.done()
            return
        }

        val voice = resolveVoice(request, snap)

        // Velocidad y tono EFECTIVOS: combinan el ajuste de la app con los sliders de Ajustes de Android
        // (request.speechRate, request.pitch, donde 100 = 1.0x).
        val rate = SsmlBuilder.signedPercent(effectiveRatePercent(snap, request), request)
        val pitch = SsmlBuilder.signedHertz(effectivePitchHz(snap, request), request)

        val started = AtomicBoolean(false)
        fun ensureStarted(rate: Int): Boolean {
            if (started.compareAndSet(false, true)) {
                return runCatching {
                    callback.start(
                        rate,
                        AudioFormat.ENCODING_PCM_16BIT,
                        // NÃºmero de canales = 1, no la mÃ¡scara CHANNEL_OUT_MONO.
                        EdgeProtocolConstants.CHANNEL_COUNT_MONO
                    )
                }.isSuccess
            }
            return true
        }

        for (segment in segments) {
            if (stopRequested || guard.isFired) return

            when (val outcome = synthesizeSegment(segment, snap, voice, rate, pitch)) {
                is SegmentOutcome.Ok -> {
                    if (!ensureStarted(outcome.sampleRateHz)) {
                        guard.error(TextToSpeech.ERROR_SYNTHESIS, "No se pudo iniciar el canal de audio.")
                        return
                    }
                    if (!deliver(outcome.pcm, callback)) return
                }
                is SegmentOutcome.Failed -> {
                    guard.error(TextToSpeech.ERROR_SYNTHESIS, outcome.message)
                    runCatching { settings?.setLastError(outcome.message) }
                    return
                }
                SegmentOutcome.Cancelled -> {
                    return
                }
            }
        }

        if (!guard.isFired) {
            guard.done()
        }
    }

    private sealed class SegmentOutcome {
        class Ok(val pcm: ByteArray, val sampleRateHz: Int) : SegmentOutcome()
        class Failed(val message: String) : SegmentOutcome()
        object Cancelled : SegmentOutcome()
    }

    private fun synthesizeSegment(
        segment: String,
        snap: SettingsStore.Snapshot,
        voice: String,
        rate: String,
        pitch: String
    ): SegmentOutcome {
        val cacheKey = cache?.key(segment, voice, snap.locale, rate, pitch, EdgeProtocolConstants.PROTOCOL_VERSION)

        if (snap.cacheEnabled && cacheKey != null) {
            cache?.read(cacheKey)?.let {
                return SegmentOutcome.Ok(it, EdgeProtocolConstants.SAMPLE_RATE_HZ)
            }
        }

        return synthOnce(
            segment, snap, cacheKey, voice, rate, pitch,
            outputFormat = EdgeProtocolConstants.OUTPUT_FORMAT_MP3,
            decoder = mp3Decoder
        )
    }

    private fun synthOnce(
        segment: String,
        snap: SettingsStore.Snapshot,
        cacheKey: String?,
        voice: String,
        rate: String,
        pitch: String,
        outputFormat: String,
        decoder: AudioDecoder?
    ): SegmentOutcome {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        val buffer = ByteArrayOutputStream()

        val client = http ?: return SegmentOutcome.Failed("Cliente HTTP no inicializado.")

        // El proveedor se crea por intento: asÃ© siempre usa el User-Agent, el Origin y las URLs vigentes (editables desde la app sin recompilar).
        // El diagnÃ³stico se persiste para depurar desde la app.
        val prov = TtsProvider(
            EdgeProtocolClient(
                client,
                wsBaseUrl = snap.wsUrl,
                userAgent = snap.userAgent,
                origin = snap.origin,
                outputFormat = outputFormat,
                onDiagnostic = { d -> runCatching { settings?.setHandshakeDebug(d) } }
            )
        )

        val handle = prov.synthesize(
            text = segment,
            voice = voice,
            locale = snap.locale,
            rate = rate,
            pitch = pitch,
            onPcmChunk = { chunk ->
                synchronized(buffer) { buffer.write(chunk) }
            },
            onComplete = { latch.countDown() },
            onError = { t ->
                failure = t
                latch.countDown()
            }
        )

        active = handle

        val finished = latch.await(
            EdgeProtocolConstants.SYNTHESIS_TIMEOUT_MS + 15000L,
            TimeUnit.MILLISECONDS
        )
        active = null

        if (!finished) {
            handle.cancel()
            return SegmentOutcome.Failed("La sÃ©ntesis superÃ³ el tiempo mÃ¡ximo permitido.")
        }

        val t = failure
        if (t is SynthesisCancelledException) {
            return SegmentOutcome.Cancelled
        }
        if (t != null) {
            return SegmentOutcome.Failed(t.message ?: "Error de sÃ©ntesis")
        }

        var pcm = synchronized(buffer) { buffer.toByteArray() }
        if (pcm.isEmpty()) {
            return SegmentOutcome.Failed("El proveedor no devolviÃ³ audio (respuesta vacÃ©a).")
        }

        var sampleRate = EdgeProtocolConstants.SAMPLE_RATE_HZ

        if (decoder != null) {
            // Ruta MP3: decodificar a PCM 16-bit ANTES de tocar el callback.
            val decoded = runCatching { decoder.decode(pcm) }
                .getOrElse { return SegmentOutcome.Failed(it.message ?: "Error de decodificaciÃ³n") }
            pcm = decoded.pcm
            sampleRate = decoded.sampleRateHz
        } else if (AudioFrameParser.detectFormat(pcm) == AudioFrameParser.PayloadFormat.COMPRESSED) {
            // El servidor ignorÃ³ la peticiÃ³n de PCM y enviÃ³ comprimido de todas formas (MP3 en la prÃ¡ctica).
            // Se decodifica igualmente.
            val decoded = runCatching { mp3Decoder.decode(pcm) }
                .getOrElse { return SegmentOutcome.Failed("Datos comprimidos no soportados") }
            pcm = decoded.pcm
            sampleRate = decoded.sampleRateHz
        }

        // La cach guarda el PCM final (siempre 24 kHz con los formatos de Edge), nunca el audio comprimido ni el texto en claro como nombre.
        if (snap.cacheEnabled && cacheKey != null && sampleRate == EdgeProtocolConstants.SAMPLE_RATE_HZ) {
            runCatching { cache?.write(cacheKey, pcm) }
        }

        return SegmentOutcome.Ok(pcm, sampleRate)
    }

    /**
     * Envia el PCM en bloques limitados.
     * La cancelaciÃ³n se detecta en cada paso mediante stopRequested, que activa onStop:
     * ese es el mecanismo soportado en API 36 (SynthesisCallback.isVoicing fue eliminado del framework).
     */
    private fun deliver(pcm: ByteArray, callback: SynthesisCallback): Boolean {
        val max = callback.maxBufferSize.takeIf { it > 0 } ?: 4096
        var offset = 0
        while (offset < pcm.size) {
            if (stopRequested) return false
            val size = minOf(max, pcm.size - offset)
            val code = runCatching { callback.audioAvailable(pcm, offset, size) }.getOrElse { return false }
            if (code != TextToSpeech.SUCCESS) return false
            offset += size
        }
        return true
    }

    override fun onStop() {
        stopRequested = true
        active?.cancel()
    }

    // ... (resto de helper functions: resolveVoice, effectiveRatePercent, effectivePitchHz, etc.)
    // Se mantienen sin cambios del contenido original

    companion object {
        private const val TAG = "EdgeTtsService"
    }
}
