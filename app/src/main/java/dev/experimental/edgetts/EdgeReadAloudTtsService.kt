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
 * Motor TTS del sistema que sintetiza mediante el protocolo NO oficial de
 * Microsoft Edge Read Aloud.
 *
 * Contrato garantizado:
 *  - done() XOR error(), exactamente una vez por síntesis;
 *  - ninguna excepción escapa del servicio (la app cliente nunca crashea);
 *  - onStop() cancela la síntesis activa;
 *  - el audio se entrega SIEMPRE como PCM 16-bit (el MP3 de Edge se
 *    decodifica con MediaCodec ANTES de tocar SynthesisCallback).
 *
 * Modelo de voces (como Google TTS): se expone UNA voz por idioma. Para el
 * idioma de la voz configurada en la app (español, Dalia por defecto) esa es
 * la voz expuesta; cambiarla en la app cambia la voz del español en TODO el
 * sistema. Para otros idiomas se expone una voz representativa del catálogo
 * y se resuelve automáticamente según el idioma del contenido.
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

    // Idioma cargado por el cliente (setLanguage). Se inicializa con el
    // idioma del SISTEMA para que el TTS por defecto siga al dispositivo
    // (inglés en un equipo inglés, francés en uno francés, etc.) — como
    // hace Google TTS.
    @Volatile
    private var currentLanguage: Array<String> = arrayOf("es", "MX", "")

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext

        val client = SharedProtocol.http
        http = client
        settings = SettingsStore(app)
        SettingsStore.ensureLoaded(app)
        catalog = VoiceCatalogRepository(
            client,
            app.cacheDir,
            SharedProtocol.drm
        ) { settings?.snapshot() }
        cache = CacheRepository(app.cacheDir)
        catalog?.cached()

        runCatching {
            val configured = settings?.snapshot()?.voice ?: EdgeProtocolConstants.DEFAULT_VOICE
            val tag = LocaleCodes.localeOfVoiceName(configured)
            val loc = java.util.Locale.forLanguageTag(tag)
            currentLanguage = arrayOf(loc.language, loc.country, "")
        }
    }

    override fun onDestroy() {
        stopRequested = true
        mp3Decoder.cancel()
        active?.cancel()
        super.onDestroy()
    }

    // ── Idioma y voces ──────────────────────────────────────────────────────

    // Caché por proceso de los locales soportados, en DOBLE formato (ISO2 e
    // ISO3). onIsLanguageAvailable se invoca cientos de veces al abrir los
    // ajustes del sistema, así que no se relee el JSON en cada llamada. Solo
    // se cachea cuando hay datos: si el catálogo aún no existe, se reintenta.
    //
    // La negociación compara en ambos formatos como seguro: en algunos
    // dispositivos la comparación ISO3 fallaba por un quirk de ICU (la
    // consulta se normalizaba bien pero el set no contenía la entrada) y
    // TODO respondía LANG_AVAILABLE en vez de LANG_COUNTRY_AVAILABLE. Con
    // doble formato, la coincidencia exacta de país siempre prende.
    private data class LocaleSets(
        val fullIso2: Set<String>,   // "es-mx", "en-us", …
        val langsIso2: Set<String>,  // "es", "en", …
        val fullIso3: Set<String>,   // "spa-mex", "eng-usa", …
        val langsIso3: Set<String>   // "spa", "eng", …
    )

    @Volatile
    private var localeSets: LocaleSets? = null

    private fun supportedLocaleSets(): LocaleSets {
        localeSets?.let { return it }
        val voices = runCatching { catalog?.cached() }.getOrNull().orEmpty()
        val rawLocales = (voices.map { it.locale } + EdgeProtocolConstants.DEFAULT_LOCALE)
            .filter { it.isNotBlank() }
            .map { it.trim().lowercase(Locale.ROOT) }
        val fullIso2 = rawLocales.toSet()
        val langsIso2 = fullIso2.map { it.substringBefore('-') }.toSet()
        val fullIso3 = rawLocales.mapNotNull { toIso3(it) }.toSet()
        val langsIso3 = fullIso3.map { it.substringBefore('-') }.toSet()
        val sets = LocaleSets(fullIso2, langsIso2, fullIso3, langsIso3)
        if (fullIso2.size > 1 || fullIso3.size > 1) localeSets = sets
        return sets
    }

    /**
     * Normaliza un locale ("es-mx", "es-MX", "spa-mex"…) a ISO3 minúsculo
     * ("spa-mex"). Usa Locale.isO3Language/isO3Country, que aceptan tanto ISO2
     * como ISO3 y devuelven siempre ISO3. Devuelve null si el idioma no se
     * puede resolver.
     */
    private fun toIso3(locale: String): String? = LocaleCodes.toIso3Locale(locale)

    // ── Voces expuestas: TODO el catálogo ────────────────────────────────────
    // Se exponen las ~322 voces del catálogo, cada una con SU locale real.
    // Es imprescindible para la integración con el sistema: el framework
    // resuelve setVoice()/setLanguage() buscando la voz en onGetVoices()
    // (findVoice); si la voz pedida no está expuesta, la petición se descarta
    // en silencio y el motor sintetiza con la voz anterior —exactamente el
    // fallo de "algunos idiomas funcionan y otros no".
    //
    // La selección de la app sigue aplicando a todo el sistema vía
    // onGetDefaultVoiceNameFor (para su idioma, la voz configurada); la
    // lista de IDIOMAS de Ajustes sale de CheckVoiceData (canónica y
    // estable), NO de esta lista, así que el selector de idiomas no se
    // ve afectado por exponer el catálogo completo.

    /**
     * Resuelve la voz para un idioma (y país opcional). Delegado en
     * [VoiceResolver]: el país de Ajustes gana (España ≠ México).
     */
    private fun voiceForLanguage(lang: String, country: String = ""): String? {
        val configuredName = runCatching { settings?.snapshot()?.voice }
            .getOrNull() ?: EdgeProtocolConstants.DEFAULT_VOICE
        val catalogVoices = runCatching { catalog?.cached() }.getOrNull().orEmpty()
        return VoiceResolver.voiceForLanguage(lang, country, configuredName, catalogVoices)
    }

    // ── Resiliencia a cambios del catálogo de Microsoft ─────────────────────
    // Microsoft puede RETIRAR o AGREGAR voces del servicio en cualquier
    // momento. Si la app o un cliente piden una voz que ya no existe, enviar
    // su nombre a Edge hace que el servidor cierre el turno SIN audio (error
    // confuso). validatedVoice() garantiza que solo se sintetice con voces
    // presentes en el catálogo descargado, con un respaldo razonable.

    /**
     * Si [voice] existe en el catálogo se devuelve tal cual; si no (voz
     * retirada por Microsoft o catálogo desactualizado) se busca un respaldo:
     * primero una voz del MISMO idioma, luego la voz configurada, luego Dalia.
     * Sin catálogo descargado se confía en el nombre pedido (no hay con qué
     * validar).
     */
    private fun validatedVoice(voice: String): String {
        val configured = runCatching { settings?.snapshot()?.voice }
            .getOrNull() ?: EdgeProtocolConstants.DEFAULT_VOICE
        val catalogVoices = runCatching { catalog?.cached() }.getOrNull().orEmpty()
        return VoiceResolver.validated(voice, configured, catalogVoices)
    }

    // ── Normalización ISO3 ──────────────────────────────────────────────────
    // Los clientes pueden negociar con ISO2 ("es"/"MX") o con ISO3
    // ("spa"/"MEX"). Locale.isO3Language/isO3Country acepta AMBOS formatos y
    // devuelve siempre ISO3, así que normalizamos la consulta a ISO3 y
    // comparamos contra los sets ISO3 del catálogo. Es robusto sin depender de
    // mapas ISO3→ISO2 que pueden fallar en algunos dispositivos (Onyx/HarmonyOS).

    /**
    * Normaliza un código de idioma a ISO3 minúsculo ("spa", "eng").
    * Acepta ISO2 ("es") o ISO3 ("spa"). Los códigos de 3 letras YA son
    * ISO3: no se convierten (hacerlo con forLanguageTag fallaría).
    */
    private fun normLang(code: String): String = LocaleCodes.normLang(code)

    private fun normCountry(code: String): String = LocaleCodes.normCountry(code)

    private fun iso3ToIso2Lang(code3: String): String = LocaleCodes.iso3ToIso2LangOrSelf(code3)

    private fun iso3ToIso2Country(code3: String): String = LocaleCodes.iso3ToIso2CountryOrSelf(code3)

    /**
     * Negociación de idioma derivada del catálogo de voces, comparando en ISO3
     * (robusto a consultas ISO2 o ISO3):
     *  - locale exacto (lang+country) presente → LANG_COUNTRY_AVAILABLE (2),
     *    que es lo que exigen los ajustes de Android para habilitar los
     *    controles de velocidad/tono/reproducir (Hardy);
     *  - solo el idioma presente → LANG_AVAILABLE (1);
     *  - nada → LANG_NOT_SUPPORTED.
     * Al cubrir los ~75 idiomas del catálogo, el selector del sistema casi
     * nunca muestra "idioma no soportado".
     */
    private fun languageAvailability(lang: String, country: String): Int {
        val sets = supportedLocaleSets()
        val l3 = normLang(lang)
        val c3 = normCountry(country)
        // ISO2 derivados de los ISO3 con una conversión REAL (Locale("spa")
        // .language devuelve "spa", no "es", así que usamos las tablas de
        // Locale). Doble formato como seguro ante quirks de ICU: la
        // coincidencia exacta de país prende LANG_COUNTRY_AVAILABLE (2).
        val l2 = if (l3.isNotEmpty()) iso3ToIso2Lang(l3) else ""
        val c2 = if (c3.isNotEmpty()) iso3ToIso2Country(c3) else ""

        val result = when {
            l3.isEmpty() && l2.isEmpty() -> TextToSpeech.LANG_NOT_SUPPORTED
            c3.isNotEmpty() && (sets.fullIso3.contains("$l3-$c3") ||
                (c2.isNotEmpty() && sets.fullIso2.contains("$l2-$c2"))) ->
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            sets.langsIso3.contains(l3) || (l2.isNotEmpty() && sets.langsIso2.contains(l2)) ->
                TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }

        // Diagnóstico INCONDICIONAL (Hardy): muestra la consulta, la
        // normalización y el resultado, para detectar cualquier fallo de
        // negociación en la próxima captura de logcat.
        AppLog.d(TAG) {
            "languageAvailability($lang,$country) → $result " +
                "(iso3=$l3-$c3 · iso2=$l2-$c2 · catálogo=${sets.fullIso3.size})"
        }
        return result
    }

    /** El contrato del motor devuelve códigos ISO3 ("spa", "MEX", variante). */
    override fun onGetLanguage(): Array<String> {
        val cur = currentLanguage
        // IMPORTANTE: usar normLang/normCountry (que aceptan ISO2 e ISO3) en
        // lugar de forLanguageTag. forLanguageTag("spa-MEX") descarta "MEX"
        // porque en BCP-47 una región alfa-3 es inválida, y perderíamos el
        // país (devolvería ["spa","",""]).
        val l3 = normLang(cur.getOrElse(0) { "" })        // "spa"
        val c3 = normCountry(cur.getOrElse(1) { "" })     // "mex"
        val result = arrayOf(
            l3,
            c3.uppercase(Locale.ROOT),   // "MEX" — mismo caso que Locale.isO3Country
            cur.getOrElse(2) { "" }
        )
        AppLog.d(TAG) { "onGetLanguage → ${result.joinToString(",")}" }
        return result
    }

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int {
        val code = languageAvailability(lang, country)
        // Diagnóstico: permite ver en logcat qué consulta hace la sonda de
        // Ajustes y qué respondemos (para depurar los controles deshabilitados).
        AppLog.d(TAG) { "onIsLanguageAvailable($lang,$country,$variant) → $code" }
        return code
    }

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int {
        val code = languageAvailability(lang, country)
        if (code >= TextToSpeech.LANG_AVAILABLE) {
            currentLanguage = arrayOf(lang, country, variant)
        }
        AppLog.d(TAG) { "onLoadLanguage($lang,$country,$variant) → $code" }
        return code
    }

    /**
     * Unificado ON: la voz de la app en todas partes (Neo, Books, cualquier
     * idioma). Unificado OFF: el país de Ajustes gana (es-ES ≠ es-MX).
     */
    private fun resolveDefaultVoiceFor(
        lang: String,
        country: String,
        snap: SettingsStore.Snapshot
    ): String? {
        if (normLang(lang).isEmpty()) return null
        val catalogVoices = runCatching { catalog?.cached() }.getOrNull().orEmpty()
        return VoiceResolver.resolve(
            explicitVoiceName = null,
            requestLang = lang,
            requestCountry = country,
            loadedLang = "",
            loadedCountry = "",
            configuredVoice = snap.voice,
            unified = snap.unifiedVoiceMode,
            catalog = catalogVoices
        )
    }

    /**
     * PIEZA CLAVE para la integración con el sistema: cuando una app llama a
     * setLanguage() sin especificar voz, TextToSpeechService consulta este
     * método; si devuelve null, setLanguage FALLA y la app hace fallback a
     * otro motor. Delega en resolveDefaultVoiceFor (fuente de verdad única).
     */
    override fun onGetDefaultVoiceNameFor(
        lang: String,
        country: String,
        variant: String
    ): String? {
        if (normLang(lang).isEmpty()) {
            AppLog.d(TAG) { "onGetDefaultVoiceNameFor($lang,$country,$variant) → null (idioma vacío)" }
            return null
        }
        val snap = settings?.snapshot()
            ?: return voiceForLanguage(lang, country)
        val resolved = resolveDefaultVoiceFor(lang, country, snap)
        AppLog.d(TAG) { "onGetDefaultVoiceNameFor($lang,$country,$variant) → ${resolved ?: "null"} (unificado=${snap.unifiedVoiceMode})" }
        return resolved
    }

    override fun onGetVoices(): List<Voice> {
        val configuredName = runCatching { settings?.snapshot()?.voice }
            .getOrNull() ?: EdgeProtocolConstants.DEFAULT_VOICE
        val catalogVoices = runCatching { catalog?.cached() }.getOrNull().orEmpty()
        val list = if (catalogVoices.isEmpty()) {
            listOf(
                EdgeVoice(
                    configuredName,
                    EdgeProtocolConstants.DEFAULT_LOCALE,
                    "Female",
                    "Voz configurada [respaldo local]"
                )
            )
        } else catalogVoices
        AppLog.d(TAG) {
            "onGetVoices: exponiendo ${list.size} voces (configurada=$configuredName)"
        }
        return list.map { edgeToAndroid(it) }
    }

    /**
     * Constructor público de 6 parámetros. El default lo resuelve
     * [onGetDefaultVoiceNameFor], no el flag oculto isDefault.
     */
    private fun edgeToAndroid(v: EdgeVoice): Voice {
        val locale = Locale.forLanguageTag(v.locale.ifBlank { "es-MX" })
        return Voice(
            v.shortName, locale,
            Voice.QUALITY_VERY_HIGH, Voice.LATENCY_HIGH,
            /* requiresNetwork = */ false,
            /* features = */ emptySet<String>()
        )
    }

    private fun resolveVoice(request: SynthesisRequest, snap: SettingsStore.Snapshot): String {
        val catalogVoices = runCatching { catalog?.cached() }.getOrNull().orEmpty()
        val loaded = currentLanguage
        return VoiceResolver.resolve(
            explicitVoiceName = request.voiceName,
            requestLang = request.language.orEmpty(),
            requestCountry = request.country.orEmpty(),
            loadedLang = loaded.getOrElse(0) { "" },
            loadedCountry = loaded.getOrElse(1) { "" },
            configuredVoice = snap.voice,
            unified = snap.unifiedVoiceMode,
            catalog = catalogVoices
        )
    }

    // ── Velocidad y tono (ajustes de la app + sliders de Android) ────────────
    // request.speechRate y request.pitch son enteros donde 100 = 1.0x (los
    // envían los sliders de Ajustes → Texto a voz). Se combinan de forma
    // aditiva con el ajuste propio de la app y se limitan al rango que Edge
    // acepta. Los valores por defecto (velocidad +0%, tono +0Hz) coinciden
    // EXACTAMENTE con los del navegador Edge / edge-tts, de modo que la
    // prosodia natural (incluidas las pausas en comas y puntos, que en las
    // voces neuronales de Edge son algo más largas que en Google TTS) se
    // reproduce igual que en el navegador.

    /**
     * % de velocidad para Edge: ajuste de la app + slider de Android. El
     * slider del sistema llega hasta 2.0x (speechRate=200 → +100), así que el
     * límite superior es +100 para honrar esa velocidad real (Edge la acepta);
     * el inferior se mantiene en -50 (media velocidad, como el navegador).
     */
    private fun effectiveRatePercent(snap: SettingsStore.Snapshot, request: SynthesisRequest): Int =
        (snap.ratePercent + (request.speechRate - 100)).coerceIn(-50, 100)

    /**
     * Tono para Edge en Hz: ajuste de la app + slider de Android. Se usa Hz
     * (signedHertz), NO %, porque es la unidad que el motor de referencia
     * (edge-tts) envía a este endpoint y la que se sabe que acepta: el
     * atributo pitch de Edge espera `+XHz` (o semitonos), y un valor en %
     * podría ser ignorado o rechazado. El multiplicador del slider
     * (request.pitch, 100 = 1.0x) se traduce a un desplazamiento en Hz. Se
     * acota a ±50Hz, el rango cómodo que Edge maneja bien sin distorsionar.
     */
    private fun effectivePitchHz(snap: SettingsStore.Snapshot, request: SynthesisRequest): Int =
        (snap.pitchHz + (request.pitch - 100)).coerceIn(-50, 50)

    // ── Síntesis ────────────────────────────────────────────────────────────

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        // Metadatos de la petición para depurar la integración con el sistema
        // (longitud del texto, NO el contenido; nunca datos sensibles).
        AppLog.d(TAG) {
            "onSynthesizeText: chars=${request.charSequenceText?.length ?: 0} " +
                "lang=${request.language}/${request.country}/${request.variant} " +
                "voice=${request.voiceName} rate=${request.speechRate} pitch=${request.pitch}"
        }
        // Una sola llamada terminal (done XOR error) garantizada a este nivel:
        // ni la red ni los errores internos pueden escapar del servicio.
        val guard = TerminalGuard()

        synchronized(synthesisLock) {
            runCatching { synthesizeInternal(request, callback, guard) }
                .onFailure { t ->
                    Log.e(TAG, "fallo interno", t)
                    guard.error(callback, mapped(t)) { msg ->
                        runCatching { settings?.setLastError(msg) }
                    }
                }
        }
    }

    private fun synthesizeInternal(
        request: SynthesisRequest,
        callback: SynthesisCallback,
        guard: TerminalGuard
    ) {
        stopRequested = false
        mp3Decoder.reset()

        val raw = request.charSequenceText?.toString()

        // Texto vacío o nulo: éxito silencioso sin tocar la red.
        if (raw.isNullOrBlank()) {
            runCatching {
                callback.start(
                    EdgeProtocolConstants.SAMPLE_RATE_HZ,
                    AudioFormat.ENCODING_PCM_16BIT,
                    EdgeProtocolConstants.CHANNEL_COUNT_MONO
                )
            }
            guard.done(callback)
            return
        }

        val text = TextSanitizer.removeIncompatibleCharacters(raw)
        if (text.isBlank()) {
            runCatching {
                callback.start(
                    EdgeProtocolConstants.SAMPLE_RATE_HZ,
                    AudioFormat.ENCODING_PCM_16BIT,
                    EdgeProtocolConstants.CHANNEL_COUNT_MONO
                )
            }
            guard.done(callback)
            return
        }

        val snap = settings?.snapshot()
        if (snap == null) {
            guard.error(callback, tr(R.string.error_settings_unreadable))
            return
        }

        val segments = runCatching {
            TextSegmenter.segment(text, { stopRequested }, TextSegmenter.OPERATIONAL_SEGMENT_BYTES)
        }.getOrElse {
            guard.error(callback, tr(R.string.error_segment_failed))
            return
        }

        if (segments.isEmpty()) {
            runCatching {
                callback.start(
                    EdgeProtocolConstants.SAMPLE_RATE_HZ,
                    AudioFormat.ENCODING_PCM_16BIT,
                    EdgeProtocolConstants.CHANNEL_COUNT_MONO
                )
            }
            guard.done(callback)
            return
        }

        val voice = resolveVoice(request, snap)
        AppLog.d(TAG) {
            "voz resuelta=$voice unificado=${snap.unifiedVoiceMode} pedida=${request.voiceName ?: ""}"
        }
        val rate = SsmlBuilder.signedPercent(effectiveRatePercent(snap, request))
        val pitch = SsmlBuilder.signedHertz(effectivePitchHz(snap, request))
        val started = AtomicBoolean(false)
        val metrics = SynthesisMetrics()
        metrics.segments = segments.size

        fun ensureStarted(rate: Int): Boolean =
            if (started.compareAndSet(false, true)) {
                runCatching {
                    callback.start(
                        rate,
                        AudioFormat.ENCODING_PCM_16BIT,
                        EdgeProtocolConstants.CHANNEL_COUNT_MONO
                    )
                }.isSuccess
            } else true

        try {
            for (segment in segments) {
                if (guard.isFired) return
                if (stopRequested) {
                    guard.error(callback, TextToSpeech.STOPPED)
                    return
                }

                when (val outcome = synthesizeSegment(segment, snap, voice, rate, pitch, metrics)) {
                    is SegmentOutcome.Ok -> {
                        if (!ensureStarted(outcome.sampleRateHz)) {
                            guard.error(callback, tr(R.string.error_audio_start))
                            return
                        }
                        if (!deliver(outcome.pcm, callback)) {
                            if (stopRequested) {
                                guard.error(callback, TextToSpeech.STOPPED)
                            } else {
                                guard.error(callback, tr(R.string.error_audio_deliver)) { msg ->
                                    runCatching { settings?.setLastError(msg) }
                                }
                            }
                            return
                        }
                    }

                    is SegmentOutcome.Failed -> {
                        guard.error(callback, outcome.message) { msg ->
                            runCatching { settings?.setLastError(msg) }
                        }
                        return
                    }

                    SegmentOutcome.Cancelled -> {
                        guard.error(callback, TextToSpeech.STOPPED)
                        return
                    }
                }
            }

            if (!guard.isFired) guard.done(callback)
        } finally {
            runCatching { settings?.setLastMetrics(metrics.line()) }
        }
    }

    private sealed class SegmentOutcome {
        class Ok(val pcm: ByteArray, val sampleRateHz: Int) : SegmentOutcome()
        class Failed(val message: String) : SegmentOutcome()
        object Cancelled : SegmentOutcome()
    }

    /**
     * Síntesis por segmento con el ÚNICO formato que usa el cliente de
     * referencia: audio-24khz-48kbitrate-mono-mp3. El servidor lo produce sin
     * problemas; se decodifica a PCM 16-bit con MediaCodec ANTES de entregarlo
     * a SynthesisCallback (que exige PCM crudo). No hay reintento con otros
     * formatos: el RIFF/PCM está verificado que NO produce audio en este
     * endpoint, así que reintentar con él solo gastaría peticiones.
     *
     * La caché guarda el MP3 del WebSocket; SynthesisCallback recibe PCM.
     */
    private fun synthesizeSegment(
        segment: String,
        snap: SettingsStore.Snapshot,
        voice: String,
        rate: String,
        pitch: String,
        metrics: SynthesisMetrics
    ): SegmentOutcome {
        val format = EdgeProtocolConstants.OUTPUT_FORMAT_MP3
        val locale = LocaleCodes.localeOfVoiceName(voice)
        val cacheKey = cache?.key(
            segment, voice, locale, rate, pitch, format,
            EdgeProtocolConstants.PROTOCOL_VERSION
        )

        if (snap.cacheEnabled && cacheKey != null) {
            cache?.readMp3(cacheKey)?.let { mp3 ->
                when (val decoded = decodeMp3(mp3, metrics)) {
                    is SegmentOutcome.Ok -> {
                        metrics.cacheHits++
                        metrics.mp3Bytes += mp3.size
                        return decoded
                    }
                    is SegmentOutcome.Cancelled -> return decoded
                    is SegmentOutcome.Failed -> cache?.remove(cacheKey)
                }
            }
        }

        metrics.cacheMisses++
        return synthOnce(segment, snap, cacheKey, voice, rate, pitch, format, metrics)
    }

    private fun decodeMp3(mp3: ByteArray, metrics: SynthesisMetrics): SegmentOutcome {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val decoded = runCatching { mp3Decoder.decode(mp3) }
        metrics.decodeMs += android.os.SystemClock.elapsedRealtime() - t0
        return decoded.fold(
            onSuccess = { SegmentOutcome.Ok(it.pcm, it.sampleRateHz) },
            onFailure = {
                if (it is SynthesisCancelledException) SegmentOutcome.Cancelled
                else SegmentOutcome.Failed(mapped(it))
            }
        )
    }

    /** Un intento de síntesis de red; el buffer es MP3, no PCM. */
    private fun synthOnce(
        segment: String,
        snap: SettingsStore.Snapshot,
        cacheKey: String?,
        voice: String,
        rate: String,
        pitch: String,
        outputFormat: String,
        metrics: SynthesisMetrics
    ): SegmentOutcome {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        val buffer = ByteArrayOutputStream()

        val client = http ?: return SegmentOutcome.Failed(tr(R.string.error_http_client))
        val prov = protocolFor(snap, outputFormat, client)
            ?: return SegmentOutcome.Failed(tr(R.string.error_http_client))

        val netStart = android.os.SystemClock.elapsedRealtime()
        val handle = prov.prepare(
            text = segment,
            voice = voice,
            locale = LocaleCodes.localeOfVoiceName(voice),
            rate = rate,
            pitch = pitch,
            onEncodedAudioChunk = { data, off, len ->
                synchronized(buffer) { buffer.write(data, off, len) }
            },
            onComplete = { latch.countDown() },
            onError = { t -> failure = t; latch.countDown() }
        )
        active = handle
        if (stopRequested) {
            handle.cancel()
            active = null
            return SegmentOutcome.Cancelled
        }
        handle.start()
        if (prov.lastReuse) metrics.persistHits++ else metrics.persistMisses++
        var finished = false
        val deadline = android.os.SystemClock.elapsedRealtime() +
            EdgeProtocolConstants.SYNTHESIS_TIMEOUT_MS + 15_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (stopRequested) break
            if (latch.await(250, TimeUnit.MILLISECONDS)) {
                finished = true
                break
            }
        }
        active = null
        metrics.networkMs += android.os.SystemClock.elapsedRealtime() - netStart

        if (stopRequested) {
            handle.cancel()
            return SegmentOutcome.Cancelled
        }

        if (!finished) {
            handle.cancel()
            return SegmentOutcome.Failed(mapped(TimeoutExceptionShim()))
        }

        val t = failure
        if (t is SynthesisCancelledException) return SegmentOutcome.Cancelled
        if (t != null) {
            return SegmentOutcome.Failed(mapped(t))
        }

        val mp3 = synchronized(buffer) { buffer.toByteArray() }
        if (mp3.isEmpty()) {
            return SegmentOutcome.Failed(tr(R.string.error_empty_audio))
        }
        metrics.mp3Bytes += mp3.size

        val decoded = decodeMp3(mp3, metrics)
        if (decoded is SegmentOutcome.Ok && snap.cacheEnabled && cacheKey != null) {
            runCatching { cache?.writeMp3(cacheKey, mp3) }
        }
        return decoded
    }

    private fun protocolFor(
        snap: SettingsStore.Snapshot,
        outputFormat: String,
        client: OkHttpClient
    ): EdgeProtocolClient? {
        val fp = EdgeProtocolClient.ConnectionFingerprint(
            wsUrl = snap.wsUrl,
            userAgent = snap.userAgent.ifBlank { EdgeProtocolConstants.DEFAULT_USER_AGENT },
            origin = snap.origin.ifBlank { EdgeProtocolConstants.DEFAULT_ORIGIN },
            outputFormat = outputFormat,
            token = EdgeProtocolConstants.TRUSTED_CLIENT_TOKEN
        )
        return SharedProtocol.ttsClient(fp) {
            EdgeProtocolClient(
                client,
                drm = SharedProtocol.drm,
                wsBaseUrl = fp.wsUrl,
                userAgent = fp.userAgent,
                origin = fp.origin,
                outputFormat = fp.outputFormat,
                onDiagnostic = { d -> runCatching { settings?.setHandshakeDebug(d) } }
            )
        }
    }

    /**
     * Envía el PCM en bloques limitados. La cancelación se detecta en cada
     * paso mediante [stopRequested], que activa onStop(): ese es el mecanismo
     * soportado en API 36 (SynthesisCallback.isVoicing fue eliminada del
     * framework; no usarla, no compila con compileSdk 36).
     */
    private fun deliver(pcm: ByteArray, callback: SynthesisCallback): Boolean {
        val max = callback.maxBufferSize.takeIf { it > 0 } ?: 4096
        var offset = 0
        while (offset < pcm.size) {
            if (stopRequested) return false
            val size = minOf(max, pcm.size - offset)
            val code = runCatching { callback.audioAvailable(pcm, offset, size) }
                .getOrElse { return false }
            if (code != TextToSpeech.SUCCESS) return false
            offset += size
        }
        return true
    }

    override fun onStop() {
        stopRequested = true
        mp3Decoder.cancel()
        active?.cancel()
    }

    private fun TimeoutExceptionShim(): Throwable =
        java.util.concurrent.TimeoutException(
            "La síntesis superó el tiempo máximo permitido"
        )

    private fun mapped(t: Throwable): String =
        runCatching { ErrorMapper.localize(this, t) }.getOrElse { ErrorMapper.spanish(t) }

    private fun tr(id: Int): String =
        runCatching { getString(id) }.getOrDefault("")

    companion object {
        private const val TAG = "EdgeTtsService"
    }
}
