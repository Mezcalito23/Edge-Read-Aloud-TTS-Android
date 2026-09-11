package dev.experimental.edgetts

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pantalla de configuración del motor (accesible también desde
 * Ajustes → Sistema → Texto a voz y vía CONFIGURE_ENGINE).
 * Toda la lógica vive en [SettingsController]; la actividad solo infla,
 * delega y aplica el idioma de UI guardado ([UiLanguage]).
 */
class MainActivity : Activity() {

    private lateinit var controller: SettingsController

    /** Aplica el idioma de UI guardado antes de inflar cualquier layout. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controller = SettingsController(this).apply {
            bind(
                statusEngine = findViewById(R.id.statusEngine),
                statusProvider = findViewById(R.id.statusProvider),
                textLastError = findViewById(R.id.textLastError),
                spinnerVoice = findViewById(R.id.spinnerVoice),
                labelVoiceCount = findViewById(R.id.labelVoiceCount),
                btnRefreshCatalog = findViewById(R.id.btnRefreshCatalog),
                sliderRate = findViewById(R.id.sliderRate),
                labelRateValue = findViewById(R.id.labelRateValue),
                sliderPitch = findViewById(R.id.sliderPitch),
                labelPitchValue = findViewById(R.id.labelPitchValue),
                switchUnifiedVoice = findViewById(R.id.switchUnifiedVoice),
                btnResetParams = findViewById(R.id.btnResetParams),
                textHandshakeDebug = findViewById(R.id.textHandshakeDebug),
                switchCache = findViewById(R.id.switchCache),
                labelCacheSize = findViewById(R.id.labelCacheSize),
                btnClearCache = findViewById(R.id.btnClearCache),
                textEndpoints = findViewById(R.id.textEndpoints),
                btnEditUserAgent = findViewById(R.id.btnEditUserAgent),
                btnEditOrigin = findViewById(R.id.btnEditOrigin),
                spinnerUiLanguage = findViewById(R.id.spinnerUiLanguage),
                btnTestVoice = findViewById(R.id.btnTestVoice),
                btnReset = findViewById(R.id.btnReset),
                btnOpenTtsSettings = findViewById(R.id.btnOpenTtsSettings)
            )
        }
        controller.refreshAll()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }
}

/**
 * Mediador entre la UI y el almacenamiento/servicios. Usa ExecutorService y
 * Handler (sin corutinas): la única dependencia externa sigue siendo OkHttp.
 */
class SettingsController(private val activity: Activity) {

    private val store = SettingsStore(activity.applicationContext)
    private val cacheRepo = CacheRepository(activity.cacheDir)
    private val catalog = VoiceCatalogRepository(
        SharedProtocol.http,
        activity.cacheDir,
        SharedProtocol.drm,
        snapshotProvider = { store.snapshot() }
    )

    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private var ttsClient: TextToSpeech? = null
    private var probeClient: TextToSpeech? = null
    private var programmaticEdit = false

    // Vistas
    private lateinit var statusEngine: TextView
    private lateinit var statusProvider: TextView
    private lateinit var textLastError: TextView
    private lateinit var spinnerVoice: Spinner
    private lateinit var labelVoiceCount: TextView
    private lateinit var btnRefreshCatalog: Button
    private lateinit var sliderRate: SeekBar
    private lateinit var labelRateValue: TextView
    private lateinit var sliderPitch: SeekBar
    private lateinit var labelPitchValue: TextView
    private lateinit var switchUnifiedVoice: Switch
    private lateinit var btnResetParams: Button
    private lateinit var textHandshakeDebug: TextView
    private lateinit var switchCache: Switch
    private lateinit var labelCacheSize: TextView
    private lateinit var btnClearCache: Button
    private lateinit var textEndpoints: TextView
    private lateinit var btnEditUserAgent: Button
    private lateinit var btnEditOrigin: Button
    private lateinit var spinnerUiLanguage: Spinner
    private lateinit var btnTestVoice: Button
    private lateinit var btnReset: Button
    private lateinit var btnOpenTtsSettings: Button

    fun bind(
        statusEngine: TextView,
        statusProvider: TextView,
        textLastError: TextView,
        spinnerVoice: Spinner,
        labelVoiceCount: TextView,
        btnRefreshCatalog: Button,
        sliderRate: SeekBar,
        labelRateValue: TextView,
        sliderPitch: SeekBar,
        labelPitchValue: TextView,
        switchUnifiedVoice: Switch,
        btnResetParams: Button,
        textHandshakeDebug: TextView,
        switchCache: Switch,
        labelCacheSize: TextView,
        btnClearCache: Button,
        textEndpoints: TextView,
        btnEditUserAgent: Button,
        btnEditOrigin: Button,
        spinnerUiLanguage: Spinner,
        btnTestVoice: Button,
        btnReset: Button,
        btnOpenTtsSettings: Button
    ) {
        this.statusEngine = statusEngine
        this.statusProvider = statusProvider
        this.textLastError = textLastError
        this.spinnerVoice = spinnerVoice
        this.labelVoiceCount = labelVoiceCount
        this.btnRefreshCatalog = btnRefreshCatalog
        this.sliderRate = sliderRate
        this.labelRateValue = labelRateValue
        this.sliderPitch = sliderPitch
        this.labelPitchValue = labelPitchValue
        this.switchUnifiedVoice = switchUnifiedVoice
        this.btnResetParams = btnResetParams
        this.textHandshakeDebug = textHandshakeDebug
        this.switchCache = switchCache
        this.labelCacheSize = labelCacheSize
        this.btnClearCache = btnClearCache
        this.textEndpoints = textEndpoints
        this.btnEditUserAgent = btnEditUserAgent
        this.btnEditOrigin = btnEditOrigin
        this.spinnerUiLanguage = spinnerUiLanguage
        this.btnTestVoice = btnTestVoice
        this.btnReset = btnReset
        this.btnOpenTtsSettings = btnOpenTtsSettings

        btnEditOrigin.setOnClickListener { editOrigin() }
        // Atajo directo a Ajustes → Texto a voz del sistema (patrón Maise).
        // NOTA: la acción correcta es Settings.ACTION_TTS_SETTINGS, que vale
        // "com.android.settings.TTS_SETTINGS". Un error anterior usaba
        // "android.settings.TTS_SETTINGS" (acción inexistente) y por eso el
        // botón no abría nada en ninguna versión.
        btnOpenTtsSettings.setOnClickListener { openSystemTtsSettings() }

        spinnerVoice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (programmaticEdit) return
                (spinnerVoice.adapter.getItem(pos) as? VoiceItem)?.let { item ->
                    store.setVoice(item.shortName)
                    // Recordar la última voz de español: en modo unificado,
                    // si la app tiene una voz de otro idioma y un libro está
                    // en español, se usará esta voz reconocible.
                    if (item.shortName.startsWith("es-", ignoreCase = true)) {
                        store.setLastSpanishVoice(item.shortName)
                    }
                }
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }

        // Velocidad y tono: sliders de -50 a +50 (progress 0..100, valor =
        // progress - 50). El centro (progress 50) es el valor normal de la
        // voz (0). Se persisten en SettingsStore y los respeta el motor.
        sliderRate.max = SLIDER_RANGE
        sliderPitch.max = SLIDER_RANGE
        sliderRate.setOnSeekBarChangeListener(sliderListener(
            preview = { labelRateValue.text = formatPercent(it) },
            persist = { store.setRate(it) }
        ))
        sliderPitch.setOnSeekBarChangeListener(sliderListener(
            preview = { labelPitchValue.text = formatHertz(it) },
            persist = { store.setPitch(it) }
        ))

        // Modo de voz unificada: la voz de la app manda para todo su idioma.
        switchUnifiedVoice.setOnCheckedChangeListener { _, checked ->
            store.setUnifiedVoiceMode(checked)
        }

        // Restablecer velocidad y tono a sus valores normales (0).
        btnResetParams.setOnClickListener { resetParams() }

        switchCache.setOnCheckedChangeListener { _, checked ->
            store.setCacheEnabled(checked)
        }

        // Idioma de la UI: sistema / español / inglés (persistente y
        // aplicable en cualquier versión de Android vía UiLanguage).
        ArrayAdapter.createFromResource(
            activity, R.array.ui_language_options, android.R.layout.simple_spinner_item
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerUiLanguage.adapter = it
        }
        spinnerUiLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (programmaticEdit) return
                val code = UiLanguage.codeOf(pos)
                if (code != store.snapshot().uiLanguage) {
                    store.setUiLanguage(code)
                    activity.recreate()
                }
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }

        btnRefreshCatalog.setOnClickListener { refreshCatalog() }
        btnClearCache.setOnClickListener { clearCache() }
        btnEditUserAgent.setOnClickListener { editUserAgent() }
        btnTestVoice.setOnClickListener { testVoice() }
        btnReset.setOnClickListener { reset() }
    }

    // ── Acciones ────────────────────────────────────────────────────────────

    fun refreshAll() {
        statusEngine.text = activity.getString(R.string.status_declared_checking)
        io.execute {
            val snap = store.snapshot()
            val cached = catalog.cached()
            val voices = cached.ifEmpty { VoiceCatalogRepository.FALLBACK }
            val declared = isEngineDeclared()
            val cacheBytes = cacheRepo.sizeBytes()
            main.post {
                render(voices, snap, declared, cacheBytes, cached.isEmpty())
                checkSystemExposure()
            }
            maybeAutoRefreshCatalog(snap.catalogUpdatedAt)
        }
    }

    /**
     * Microsoft puede AGREGAR o RETIRAR voces del servicio. Para que la lista
     * expuesta no quede obsoleta, se refresca el catálogo en segundo plano al
     * abrir la app si la última actualización supera [CATALOG_STALE_MS]. Es
     * silencioso: si hay cambios, actualiza el spinner; si falla (sin red),
     * no interrumpe nada y se conserva la copia local.
     */
    private fun maybeAutoRefreshCatalog(lastUpdated: Long) {
        val stale = System.currentTimeMillis() - lastUpdated > CATALOG_STALE_MS
        if (!stale) return
        io.execute {
            val result = catalog.refresh()
            if (!result.fromNetwork) return@execute
            store.setCatalogUpdatedAt(System.currentTimeMillis())
            val snap = store.snapshot()
            main.post {
                renderVoices(result.voices, snap.voice)
                labelVoiceCount.text = activity.getString(
                    R.string.voice_count_fmt,
                    result.voices.size,
                    catalog.mexican(result.voices).size
                )
            }
        }
    }

    /**
     * Pregunta la VERDAD al sistema: ¿aparece nuestro paquete en
     * TextToSpeech.getEngines()? En Android 13+, una app instalada desde APK
     * tiene «ajustes restringidos» y su servicio TTS NO se expone aunque esté
     * bien declarado (queryIntentServices del propio paquete siempre lo ve,
     * por eso la comprobación por manifiesto daría un falso positivo).
     */
    private fun checkSystemExposure() {
        runCatching { probeClient?.shutdown() }
        probeClient = TextToSpeech(activity.applicationContext, { _ ->
            val client = probeClient ?: return@TextToSpeech
            val engines = runCatching { client.engines }.getOrDefault(emptyList())
            val exposed = engines.any { it.name == activity.packageName }
            Log.i(
                "EdgeTtsSettings",
                "motores visibles para getEngines(): " +
                    if (engines.isEmpty()) "(NINGUNO — el filtro de paquetes o el sistema los oculta)"
                    else engines.joinToString { it.name }
            )
            statusEngine.text = if (exposed)
                activity.getString(R.string.status_visible_fmt, engines.size)
            else
                activity.getString(R.string.status_restricted) +
                    " · " + activity.getString(R.string.engines_visible_fmt, engines.size)
            if (!exposed) statusProvider.text = activity.getString(R.string.restricted_hint)
            runCatching { client.shutdown() }
            probeClient = null
        }, activity.packageName)
    }

    private fun refreshCatalog() {
        btnRefreshCatalog.isEnabled = false
        labelVoiceCount.text = activity.getString(R.string.catalog_updating)
        io.execute {
            val result = catalog.refresh()
            if (result.fromNetwork) store.setCatalogUpdatedAt(System.currentTimeMillis())
            val snap = store.snapshot()
            main.post {
                btnRefreshCatalog.isEnabled = true
                renderVoices(result.voices, snap.voice)
                val mexican = catalog.mexican(result.voices).size
                labelVoiceCount.text = activity.getString(
                    R.string.voice_count_fmt, result.voices.size, mexican
                )
                statusProvider.text = result.error
                    ?: activity.getString(R.string.catalog_updated_fmt, result.voices.size)
            }
        }
    }

    private fun clearCache() {
        btnClearCache.isEnabled = false
        io.execute {
            val freed = cacheRepo.clear()
            main.post {
                btnClearCache.isEnabled = true
                labelCacheSize.text = activity.getString(
                    R.string.cache_used_fmt,
                    formatBytes(cacheRepo.sizeBytes()),
                    formatBytes(CacheRepository.MAX_BYTES)
                )
                statusProvider.text = activity.getString(
                    R.string.cache_freed_fmt, formatBytes(freed)
                )
            }
        }
    }

    private fun reset() {
        io.execute {
            store.reset()
            main.post {
                statusProvider.text = activity.getString(R.string.settings_reset_done)
                refreshAll()
            }
        }
    }

    /**
     * Prueba de voz: vincula un cliente TextToSpeech contra NUESTRO propio
     * motor (paquete explícito) para recorrer el camino completo: binder →
     * servicio → WebSocket → audio.
     *
     * El texto de prueba se dice EN EL IDIOMA DE LA VOZ SELECCIONADA y con esa
     * misma voz (no siempre en español): se resuelve el locale de la voz del
     * catálogo y se carga la muestra correspondiente.
     */
    private fun testVoice() {
        val snap = store.snapshot()
        releaseTts()
        statusProvider.text = activity.getString(R.string.test_starting)
        btnTestVoice.isEnabled = false

        // Locale de la voz elegida (p. ej. "es-MX-DaliaNeural" → es-MX).
        val voiceLocale = localeOfVoice(snap.voice)
        val sample = SampleTexts.forVoice(snap.voice)

        ttsClient = TextToSpeech(
            activity.applicationContext,
            { status ->
                if (status != TextToSpeech.SUCCESS) {
                    btnTestVoice.isEnabled = true
                    statusProvider.text = activity.getString(R.string.test_init_failed)
                    return@TextToSpeech
                }
                val tts = ttsClient ?: return@TextToSpeech
                // Si el sistema no expone nuestro paquete, el constructor
                // TextToSpeech(context, listener, paquete) CAE EN SILENCIO al
                // motor por defecto: el usuario escucharía OTRA voz sin saberlo.
                // Detectarlo y negarse a reproducir con el motor equivocado.
                // EngineInfo.name ES el nombre del paquete del motor.
                val engines = runCatching { tts.engines }.getOrDefault(emptyList())
                if (engines.none { it.name == activity.packageName }) {
                    btnTestVoice.isEnabled = true
                    statusProvider.text = activity.getString(R.string.restricted_hint)
                    textLastError.text = activity.getString(R.string.test_cancelled_restricted)
                    runCatching { tts.shutdown() }
                    ttsClient = null
                    return@TextToSpeech
                }
                // Idioma + voz de la selección: la muestra suena en el idioma
                // de la voz elegida (en-US → inglés, fr-FR → francés…) y con
                // esa misma voz. Se fijan el idioma y la voz de sesión, y se
                // REGISTRAN los códigos de resultado: si setVoice no prende
                // (código ≠ 0), se ve aquí y en logcat en vez de fallar en
                // silencio. Además la voz se refuerza por utterance con el
                // parámetro "voiceName" (el que lee el framework), de modo
                // que la voz exacta llegue al motor aunque el estado de
                // sesión no se haya aplicado.
                val testVoice = Voice(
                    snap.voice, voiceLocale,
                    Voice.QUALITY_VERY_HIGH, Voice.LATENCY_HIGH,
                    false, emptySet()
                )
                val langResult = tts.setLanguage(voiceLocale)
                val voiceResult = runCatching { tts.setVoice(testVoice) }
                    .getOrDefault(TextToSpeech.ERROR)
                val motorVoice = runCatching { tts.voice?.name }.getOrNull() ?: "(ninguna)"
                AppLog.d("EdgeTtsSettings") {
                    "test: setLanguage($voiceLocale)=$langResult " +
                        "setVoice(${snap.voice})=$voiceResult vozMotor=$motorVoice"
                }
                statusProvider.text = activity.getString(
                    R.string.test_voice_setup,
                    voiceLocale.toLanguageTag(), langResult,
                    snap.voice, voiceResult, motorVoice
                )
                tts.setSpeechRate(1f)
                tts.setPitch(1f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        main.post {
                            statusProvider.text = activity.getString(R.string.test_synth, snap.voice)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        main.post {
                            btnTestVoice.isEnabled = true
                            store.clearLastError()
                            renderLastError(store.snapshot())
                            statusProvider.text = activity.getString(R.string.test_ok)
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        main.post {
                            btnTestVoice.isEnabled = true
                            val snap = store.snapshot()
                            if (snap.lastError.isBlank()) store.setLastError(
                                activity.getString(R.string.test_failed_generic)
                            )
                            renderLastError(store.snapshot())
                            statusProvider.text = activity.getString(
                                R.string.test_failed_fmt,
                                store.snapshot().lastError
                            )
                        }
                    }
                })
                // Refuerzo por utterance: el framework lee el parámetro
                // "voiceName" (Engine.KEY_PARAM_VOICE, constante @hide cuyo
                // valor es ese literal) y lo traduce a request.voiceName, que
                // resolveVoice() del motor usa con prioridad máxima. Así la
                // voz exacta se sintetiza aunque el setVoice() de sesión no
                // haya prendido en este cliente/ROM.
                val testParams = Bundle().apply {
                    putString("voiceName", snap.voice)
                    putString(VoiceResolver.OWN_PARAM, "1")
                }
                val spoken = tts.speak(
                    sample,
                    TextToSpeech.QUEUE_FLUSH,
                    testParams,
                    UTTERANCE_TEST
                )
                if (spoken != TextToSpeech.SUCCESS) {
                    btnTestVoice.isEnabled = true
                    statusProvider.text = activity.getString(R.string.test_rejected)
                }
            },
            activity.packageName
        )
    }

    // ── Render ──────────────────────────────────────────────────────────────

    private fun render(
        voices: List<EdgeVoice>,
        snap: SettingsStore.Snapshot,
        declared: Boolean,
        cacheBytes: Long,
        catalogMissing: Boolean
    ) {
        // Estado inicial; checkSystemExposure() lo confirma o lo corrige.
        statusEngine.text = if (declared)
            activity.getString(R.string.status_declared_checking)
        else
            activity.getString(R.string.status_not_declared)

        renderLastError(snap)

        renderVoices(voices, snap.voice)
        labelVoiceCount.text = if (catalogMissing) {
            activity.getString(R.string.catalog_offline)
        } else {
            activity.getString(
                R.string.voice_count_fmt, voices.size, catalog.mexican(voices).size
            )
        }

        programmaticEdit = true
        // Sliders: progress 0..100 ↔ valor -50..+50 (centro 50 = 0).
        sliderRate.progress = (snap.ratePercent + SLIDER_OFFSET).coerceIn(0, SLIDER_RANGE)
        labelRateValue.text = formatPercent(snap.ratePercent)
        sliderPitch.progress = (snap.pitchHz + SLIDER_OFFSET).coerceIn(0, SLIDER_RANGE)
        labelPitchValue.text = formatHertz(snap.pitchHz)
        switchUnifiedVoice.isChecked = snap.unifiedVoiceMode
        switchCache.isChecked = snap.cacheEnabled
        spinnerUiLanguage.setSelection(UiLanguage.indexOf(snap.uiLanguage))
        programmaticEdit = false

        labelCacheSize.text = activity.getString(
            R.string.cache_used_fmt,
            formatBytes(cacheBytes),
            formatBytes(CacheRepository.MAX_BYTES)
        )

        refreshEndpointsCard()
    }

    private fun renderVoices(voices: List<EdgeVoice>, selected: String) {
        val items = voices.map { VoiceItem(it) }
        spinnerVoice.adapter = VoiceAdapter(items)
        programmaticEdit = true
        val idx = items.indexOfFirst { it.shortName == selected }
        spinnerVoice.setSelection(if (idx >= 0) idx else 0)
        programmaticEdit = false
    }

    /**
     * Pinta el recuadro "Último error" con el error (o "sin errores") Y el
     * diagnóstico del último handshake. Se usa desde render() y también tras
     * cada prueba de voz (onDone/onError), de modo que el diagnóstico no se
     * pierda al terminar una síntesis (regresión reportada: el recuadro
     * quedaba en "no errors" y descartaba el registro del handshake).
     */
    private fun renderLastError(snap: SettingsStore.Snapshot) {
        textLastError.text = snap.lastError.ifBlank { activity.getString(R.string.no_errors) }
        // El diagnóstico del handshake va en su propio recuadro monoespaciado
        // (igual que la tarjeta de endpoints) para que sea legible por igual
        // en el smartphone y en la tablet, y no se mezcle con el error.
        if (snap.handshakeDebug.isNotBlank() || snap.lastMetrics.isNotBlank()) {
            textHandshakeDebug.visibility = View.VISIBLE
            textHandshakeDebug.text = listOf(snap.lastMetrics, snap.handshakeDebug)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } else {
            textHandshakeDebug.visibility = View.GONE
        }
    }

    /**
     * Pinta la tarjeta de endpoints con el snapshot vigente. Nunca muestra
     * el TrustedClientToken ni el Sec-MS-GEC reales.
     */
    private fun refreshEndpointsCard() {
        val snap = store.snapshot()
        textEndpoints.text = buildString {
            append(activity.getString(R.string.ep_voices)).append(" : ")
                .append(mask(snap.voicesUrl)).append('\n')
            append(activity.getString(R.string.ep_ws)).append("    : ")
                .append(mask(snap.wsUrl))
                .append(
                    "?TrustedClientToken=••••&ConnectionId=<uuid>&Sec-MS-GEC=<sha-256>&" +
                        "Sec-MS-GEC-Version=" + EdgeProtocolConstants.CLIENT_VERSION
                ).append('\n')
            append(activity.getString(R.string.ep_origin)).append(": ")
                .append(snap.origin).append('\n')
            append(activity.getString(R.string.ep_ua)).append("    : ")
                .append(snap.userAgent)
        }
    }

    // ── Diálogos de ajuste fino (sin recompilar) ────────────────────────────

    private fun editUserAgent() {
        val current = store.snapshot().userAgent
        val input = EditText(activity).apply {
            setText(current)
            setSelection(current.length)
            setPadding(48, 32, 48, 16)
            textSize = 13f
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.user_agent_dialog_title)
            .setMessage(R.string.user_agent_dialog_hint)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    if (store.setUserAgent(value)) {
                        statusProvider.text = activity.getString(R.string.user_agent_updated)
                        refreshAll()
                    } else {
                        statusProvider.text = activity.getString(R.string.user_agent_invalid)
                    }
                }
            }
            .setNeutralButton(R.string.action_restore_user_agent) { _, _ ->
                store.resetUserAgent()
                statusProvider.text = activity.getString(R.string.user_agent_restored)
                refreshAll()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Abre Ajustes → Texto a voz del sistema. Intenta, en orden:
     *  1. Settings.ACTION_TTS_SETTINGS ("com.android.settings.TTS_SETTINGS")
     *     —la acción oficial, la que abre la pantalla del motor TTS—.
     *  2. Ajustes generales (Settings.ACTION_SETTINGS) como respaldo.
     *
     * SIN resolveActivity(): en Android 11+ el filtrado de visibilidad de
     * paquetes puede hacer que resolveActivity devuelva null para la
     * actividad de Ajustes AUNQUE exista (era la causa de que el botón no
     * abriera nada). Se intenta startActivity directamente y se captura
     * ActivityNotFoundException. Si ninguna está disponible, avisa con un
     * Toast y lo refleja en el estado. Nunca lanza.
     */
    private fun openSystemTtsSettings() {
        // Acciones como LITERALES, no Settings.ACTION_*: evita el "unresolved
        // reference" si falta el import de android.provider.Settings y es
        // idéntico al patrón documentado de Android (mismas cadenas).
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),  // pantalla Texto a voz
            Intent("android.settings.SETTINGS")           // respaldo: Ajustes generales
        )
        for (intent in candidates) {
            val launched = runCatching {
                activity.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return
        }
        Toast.makeText(activity, R.string.tts_settings_unavailable, Toast.LENGTH_LONG).show()
        statusProvider.text = activity.getString(R.string.tts_settings_unavailable)
    }

    /**
     * Edita el Origin del handshake (persistente). Útil para depurar 403:
     * probar chrome-extension://… vs https://www.bing.com sin recompilar.
     */
    private fun editOrigin() {
        val current = store.snapshot().origin
        val input = EditText(activity).apply {
            setText(current)
            setSelection(current.length)
            setPadding(48, 32, 48, 16)
            textSize = 13f
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.origin_dialog_title)
            .setMessage(R.string.origin_dialog_hint)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    if (store.setOrigin(value)) {
                        statusProvider.text = activity.getString(R.string.origin_updated)
                        refreshAll()
                    } else {
                        statusProvider.text = activity.getString(R.string.origin_invalid)
                    }
                }
            }
            .setNeutralButton(R.string.action_restore_user_agent) { _, _ ->
                store.resetOrigin()
                statusProvider.text = activity.getString(R.string.origin_restored)
                refreshAll()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    private data class VoiceItem(val voice: EdgeVoice) {
        val shortName: String get() = voice.shortName
    }

    /**
     * Adaptador de DOS líneas (nombre legible + shortName técnico).
     *  - Cerrado (getView): el título va en UNA línea con elipsis, para que el
     *    campo ocupe poco.
     *  - Desplegado (getDropDownView): el título puede ocupar HASTA 2 líneas
     *    SIN elipsis, de modo que en pantallas estrechas se lee el país y la
     *    variante completos ("…Spanish (Mexico)") en lugar de "…Spanish (Mé…)".
     */
    private inner class VoiceAdapter(items: List<VoiceItem>) :
        ArrayAdapter<VoiceItem>(activity, R.layout.spinner_voice_item, R.id.voiceTitle, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            row(position, convertView, parent, dropdown = false)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            row(position, convertView, parent, dropdown = true)

        private fun row(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
            dropdown: Boolean
        ): View {
            val v = convertView
                ?: activity.layoutInflater.inflate(R.layout.spinner_voice_item, parent, false)
            val item = getItem(position) ?: return v
            val title = v.findViewById<TextView>(R.id.voiceTitle)
            title.text = item.voice.displayName
            if (dropdown) {
                title.maxLines = 2
                title.ellipsize = null
            } else {
                title.maxLines = 1
                title.ellipsize = android.text.TextUtils.TruncateAt.END
            }
            v.findViewById<TextView>(R.id.voiceSubtitle).text = item.voice.shortName
            return v
        }
    }

    /**
     * Resuelve el [Locale] de una voz del catálogo a partir de su shortName.
     * Usa el campo `locale` del catálogo (autoritativo) y, si la voz no está
     * cacheada, reconstruye el tag de los dos primeros segmentos del nombre
     * ("es-MX-DaliaNeural" → "es-MX"). Nunca devuelve un locale vacío.
     */
    private fun localeOfVoice(shortName: String): Locale {
        val fromCatalog = runCatching {
            catalog.cached().firstOrNull { it.shortName == shortName }?.locale
        }.getOrNull()
        val tag = fromCatalog?.takeIf { it.isNotBlank() }
            ?: shortName.substringBeforeLast("-").ifBlank { "en-US" }
        val loc = runCatching { Locale.forLanguageTag(tag) }.getOrDefault(Locale.US)
        return if (loc.language.isBlank()) Locale.US else loc
    }

    /**
     * Texto de ejemplo en el idioma de la voz. Delegado a [SampleTexts]
     * para no duplicar el mapa ni caer a inglés.
     */
    private fun sampleTextFor(language: String): String = SampleTexts.forIso2(language)

    /**
     * Devuelve velocidad y tono a sus valores normales (0 = centro del slider),
     * actualiza las etiquetas y persiste. Equivale a los valores por defecto
     * del navegador Edge (+0% y +0Hz).
     */
    private fun resetParams() {
        programmaticEdit = true
        sliderRate.progress = SLIDER_OFFSET
        sliderPitch.progress = SLIDER_OFFSET
        programmaticEdit = false
        labelRateValue.text = formatPercent(0)
        labelPitchValue.text = formatHertz(0)
        store.setRate(0)
        store.setPitch(0)
        statusProvider.text = activity.getString(R.string.params_reset_done)
    }

    /**
     * Preview en cada pixel; persistencia una sola vez al soltar.
     */
    private fun sliderListener(
        preview: (value: Int) -> Unit,
        persist: (value: Int) -> Unit
    ) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) preview(progress - SLIDER_OFFSET)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) {
            val value = seekBar.progress - SLIDER_OFFSET
            preview(value)
            persist(value)
        }
    }

    /** "+0%", "+25%", "-10%"… */
    private fun formatPercent(value: Int): String =
        if (value >= 0) "+$value%" else "$value%"

    /** "+0Hz", "+12Hz", "-8Hz"… */
    private fun formatHertz(value: Int): String =
        if (value >= 0) "+${value}Hz" else "${value}Hz"

    /**
     * Solo comprueba que el servicio esté DECLARADO (el propio paquete
     * siempre es visible para sí mismo, así que esto da positivo incluso con
     * ajustes restringidos). La visibilidad real la verifica
     * [checkSystemExposure].
     */
    private fun isEngineDeclared(): Boolean = runCatching {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        activity.packageManager
            .queryIntentServices(intent, 0)
            .any { it.serviceInfo.packageName == activity.packageName }
    }.getOrDefault(false)

    /** Muestra host y path; el valor del token queda oculto. */
    private fun mask(url: String): String =
        url.replace(TOKEN_QUERY, "$1••••")
            .replace(
                "speech.platform.bing.com/consumer/speech/synthesize/readaloud",
                "speech.platform.bing.com/…/readaloud"
            )

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun releaseTts() {
        runCatching { ttsClient?.stop() }
        runCatching { ttsClient?.shutdown() }
        ttsClient = null
        runCatching { probeClient?.shutdown() }
        probeClient = null
    }

    fun release() {
        releaseTts()
        io.shutdownNow()
        Log.i("EdgeTtsSettings", "controlador liberado")
    }

    companion object {
        private const val UTTERANCE_TEST = VoiceResolver.OWN_UTTERANCE

        /** Refrescar el catálogo si lleva más de 7 días sin actualizarse. */
        private const val CATALOG_STALE_MS = 7L * 24 * 60 * 60 * 1000

        /** Sliders de velocidad/tono: rango -50..+50 mapeado a progress 0..100. */
        private const val SLIDER_RANGE = 100
        private const val SLIDER_OFFSET = 50
        private val TOKEN_QUERY = Regex("(?i)(token=)([^&]+)")
    }
}
