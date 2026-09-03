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
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        OkHttpClient.Builder()
            .connectTimeout(EdgeProtocolConstants.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(EdgeProtocolConstants.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build(),
        activity.cacheDir
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
        sliderRate.setOnSeekBarChangeListener(seekListener { value, _ ->
            store.setRate(value)
            labelRateValue.text = formatPercent(value)
        })
        sliderPitch.setOnSeekBarChangeListener(seekListener { value, _ ->
            store.setPitch(value)
            labelPitchValue.text = formatHertz(value)
        })

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
                if (code != store.snapshotBlocking().uiLanguage) {
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
            val snap = store.snapshotBlocking()
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
            val snap = store.snapshotBlocking()
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
            val snap = store.snapshotBlocking()
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
        val snap = store.snapshotBlocking()
        releaseTts()
        statusProvider.text = activity.getString(R.string.test_starting)
        btnTestVoice.isEnabled = false

        // Locale de la voz elegida (p. ej. "es-MX-DaliaNeural" → es-MX).
        val voiceLocale = localeOfVoice(snap.voice)
        val sample = sampleTextFor(voiceLocale.language)

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
                Log.d(
                    "EdgeTtsSettings",
                    "test: setLanguage($voiceLocale)=$langResult " +
                        "setVoice(${snap.voice})=$voiceResult vozMotor=$motorVoice"
                )
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
                            renderLastError(store.snapshotBlocking())
                            statusProvider.text = activity.getString(R.string.test_ok)
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        main.post {
                            btnTestVoice.isEnabled = true
                            val snap = store.snapshotBlocking()
                            if (snap.lastError.isBlank()) store.setLastError(
                                activity.getString(R.string.test_failed_generic)
                            )
                            renderLastError(store.snapshotBlocking())
                            statusProvider.text = activity.getString(
                                R.string.test_failed_fmt,
                                store.snapshotBlocking().lastError
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
        if (snap.handshakeDebug.isNotBlank()) {
            textHandshakeDebug.visibility = View.VISIBLE
            textHandshakeDebug.text = snap.handshakeDebug
        } else {
            textHandshakeDebug.visibility = View.GONE
        }
    }

    /**
     * Pinta la tarjeta de endpoints con el snapshot vigente. Nunca muestra
     * el TrustedClientToken ni el Sec-MS-GEC reales.
     */
    private fun refreshEndpointsCard() {
        val snap = store.snapshotBlocking()
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
        val current = store.snapshotBlocking().userAgent
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
                    store.setUserAgent(value)
                    statusProvider.text = activity.getString(R.string.user_agent_updated)
                    refreshAll()
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
        val current = store.snapshotBlocking().origin
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
                    store.setOrigin(value)
                    statusProvider.text = activity.getString(R.string.origin_updated)
                    refreshAll()
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
     * Texto de ejemplo en el idioma de la voz seleccionada. Cubre la práctica
     * totalidad de los idiomas del catálogo Edge (~75) para que la prueba
     * suene SIEMPRE en el idioma de la voz, nunca en inglés por defecto. Los
     * pocos idiomas sin muestra propia caen en inglés (último recurso).
     */
    private fun sampleTextFor(language: String): String =
        when (language.lowercase(Locale.ROOT)) {
            "es" -> "Hola. Esta es una prueba del motor Edge Read Aloud. La voz que escuchas se sintetiza en la nube."
            "en" -> "Hello. This is a test of the Edge Read Aloud engine. The voice you hear is synthesized in the cloud."
            "fr" -> "Bonjour. Ceci est un test du moteur Edge Read Aloud. La voix que vous entendez est synthétisée dans le cloud."
            "de" -> "Hallo. Dies ist ein Test der Edge Read Aloud-Engine. Die Stimme wird in der Cloud synthetisiert."
            "it" -> "Ciao. Questo è un test del motore Edge Read Aloud. La voce che senti è sintetizzata nel cloud."
            "pt" -> "Olá. Este é um teste do motor Edge Read Aloud. A voz que você ouve é sintetizada na nuvem."
            "nl" -> "Hallo. Dit is een test van de Edge Read Aloud-engine. De stem wordt in de cloud gesynthetiseerd."
            "ru" -> "Привет. Это тест движка Edge Read Aloud. Голос синтезируется в облаке."
            "pl" -> "Cześć. To jest test silnika Edge Read Aloud. Głos jest syntezowany w chmurze."
            "tr" -> "Merhaba. Bu, Edge Read Aloud motorunun bir testidir. Ses bulutta sentezlenmektedir."
            "ar" -> "مرحباً. هذا اختبار لمحرك Edge Read Aloud. يتم توليف الصوت في السحابة."
            "hi" -> "नमस्ते। यह Edge Read Aloud इंजन का परीक्षण है। आवाज़ क्लाउड में संश्लेषित की जाती है।"
            "ja" -> "こんにちは。これは Edge Read Aloud エンジンのテストです。音声はクラウドで合成されています。"
            "ko" -> "안녕하세요. Edge Read Aloud 엔진 테스트입니다. 음성은 클라우드에서 합성됩니다."
            "zh" -> "你好。这是 Edge Read Aloud 引擎的测试。语音由云端合成。"
            // ── Resto de idiomas del catálogo Edge ──────────────────────
            "af" -> "Hallo. Dit is 'n toets van die Edge Read Aloud-enjin. Die stem word in die wolk gesintetiseer."
            "am" -> "ሰላም። ይህ የ Edge Read Aloud ፈተና ነው። ድምፁ በደመና ውስጥ ተዋህዷል።"
            "az" -> "Salam. Bu, Edge Read Aloud mühərrikinin testidir. Səs buludda sintez olunur."
            "bg" -> "Здравейте. Това е тест на двигателя Edge Read Aloud. Гласът се синтезира в облака."
            "bn" -> "হ্যালো। এটি Edge Read Aloud ইঞ্জিনের একটি পরীক্ষা। কণ্ঠস্বর ক্লাউডে সংশ্লেষিত হয়।"
            "bs" -> "Zdravo. Ovo je test Edge Read Aloud motora. Glas se sintetiše u oblaku."
            "ca" -> "Hola. Aquesta és una prova del motor Edge Read Aloud. La veu se sintetitza al núvol."
            "cs" -> "Ahoj. Toto je test motoru Edge Read Aloud. Hlas je syntetizován v cloudu."
            "cy" -> "Helo. Mae hon yn brawf o'r peiriant Edge Read Aloud. Mae'r llais yn cael ei syntheseiddio yn y cwmwl."
            "da" -> "Hej. Dette er en test af Edge Read Aloud-motoren. Stemmen syntetiseres i skyen."
            "el" -> "Γεια σας. Αυτή είναι μια δοκιμή της μηχανής Edge Read Aloud. Η φωνή συντίθεται στο cloud."
            "et" -> "Tere. See on Edge Read Aloudi mootori test. Hääl sünteesitakse pilves."
            "eu" -> "Kaixo. Hau Edge Read Aloud motorraren proba bat da. Ahotsa hodeian sintetizatzen da."
            "fa" -> "سلام. این آزمایشی برای موتور Edge Read Aloud است. صدا در فضای ابری ترکیب می‌شود."
            "fi" -> "Hei. Tämä on Edge Read Aloud -moottorin testi. Ääni syntetisoidaan pilvessä."
            "fil", "tl" -> "Kumusta. Ito ay isang pagsubok ng Edge Read Aloud engine. Ang boses ay naka-synthesize sa cloud."
            "ga" -> "Dia duit. Is tástáil é seo ar inneall Edge Read Aloud. Déantar an ghuth a shintéisiú sa scamall."
            "gl" -> "Ola. Esta é unha proba do motor Edge Read Aloud. A voz sintetízase na nube."
            "gu" -> "નમસ્તે. આ Edge Read Aloud એન્જિનની કસોટી છે. અવાજ ક્લાઉડમાં સંશ્લેષિત થાય છે."
            "he" -> "שלום. זהו מבחן של מנוע Edge Read Aloud. הקול מיוצר בענן."
            "hr" -> "Bok. Ovo je test Edge Read Aloud motora. Glas se sintetizira u oblaku."
            "hu" -> "Szia. Ez az Edge Read Aloud motor tesztje. A hang a felhőben van szintetizálva."
            "hy" -> "Բարև։ Սա Edge Read Aloud շարժիչի թեստ է։ Ձայնը սինթեզվում է ամպում։"
            "id" -> "Halo. Ini adalah uji mesin Edge Read Aloud. Suara disintesis di cloud."
            "is" -> "Halló. Þetta er prófun á Edge Read Aloud vélinni. Röddin er mynduð í skýinu."
            "jv" -> "Halo. Iki minangka tes mesin Edge Read Aloud. Swara kasebut disintesis ing cloud."
            "ka" -> "გამარჯობა. ეს არის Edge Read Aloud ძრავის ტესტი. ხმა სინთეზდება ღრუბელში."
            "kk" -> "Сәлеметсіз бе. Бұл Edge Read Aloud қозғалтқышының сынағы. Дауыс бұлтта синтезделеді."
            "km" -> "សួស្តី។ នេះគឺជាការសាកល្បងម៉ាស៊ីន Edge Read Aloud។ សំឡេងត្រូវបានសំយោគនៅក្នុងពពក។"
            "kn" -> "ನಮಸ್ಕಾರ. ಇದು Edge Read Aloud ಎಂಜಿನ್‌ನ ಪರೀಕ್ಷೆಯಾಗಿದೆ. ಧ್ವನಿಯು ಕ್ಲೌಡ್‌ನಲ್ಲಿ ಸಂಶ್ಲೇಷಿಸಲ್ಪಟ್ಟಿದೆ."
            "lo" -> "ສະບາຍດີ. ນີ້ແມ່ນການທົດສອບເຄື່ອງຈັກ Edge Read Aloud. ສຽງຖືກສັງເຄາະໃນຄລາວ."
            "lt" -> "Sveiki. Tai yra Edge Read Aloud variklio testas. Balsas sintetinamas debesyje."
            "lv" -> "Sveiki. Šis ir Edge Read Aloud motora tests. Balss tiek sintezēta mākonī."
            "mk" -> "Здраво. Ова е тест на Edge Read Aloud моторот. Гласот се синтетизира во облакот."
            "ml" -> "ഹലോ. ഇത് Edge Read Aloud എഞ്ചിന്റെ പരീക്ഷണമാണ്. ശബ്ദം ക്ലൗഡിൽ സംശ്ലേഷണം ചെയ്യപ്പെടുന്നു."
            "mn" -> "Сайн байна уу. Энэ бол Edge Read Aloud хөдөлгүүрийн тест юм. Дуу нь үүлэнд нийлэгждэг."
            "mr" -> "नमस्कार. हे Edge Read Aloud इंजिनचे परीक्षण आहे. आवाज क्लाउडमध्ये संश्लेषित केला जातो."
            "ms" -> "Helo. Ini adalah ujian enjin Edge Read Aloud. Suara disintesis dalam cloud."
            "mt" -> "Bongu. Dan huwa test tal-mutur Edge Read Aloud. Il-vuċi hija sintetizzata fis-sħaba."
            "my" -> "မင်္ဂလာပါ။ ဒါက Edge Read Aloud အင်ဂျင်ရဲ့ စမ်းသပ်မှုဖြစ်ပါတယ်။ အသံကို cloud မှာ ပေါင်းစပ်ထားပါတယ်။"
            "nb", "no" -> "Hei. Dette er en test av Edge Read Aloud-motoren. Stemmen syntetiseres i skyen."
            "ne" -> "नमस्ते। यो Edge Read Aloud इन्जिनको परीक्षण हो। आवाज क्लाउडमा संश्लेषित गरिएको छ।"
            "ps" -> "سلام. دا د Edge Read Aloud انجن ازموینه ده. غږ په کلاوډ کې ترکیب شوی دی."
            "ro" -> "Bună. Acesta este un test al motorului Edge Read Aloud. Vocea este sintetizată în cloud."
            "si" -> "ආයුබෝවන්. මෙය Edge Read Aloud එන්ජිමේ පරීක්ෂාවකි. හඬ වලාකුළේ සංශ්ලේෂණය වේ."
            "sk" -> "Ahoj. Toto je test motora Edge Read Aloud. Hlas je syntetizovaný v cloude."
            "sl" -> "Pozdravljeni. To je test motorja Edge Read Aloud. Glas je sintetiziran v oblaku."
            "sq" -> "Përshëndetje. Ky është një test i motorit Edge Read Aloud. Zëri sintetizohet në re."
            "sr" -> "Здраво. Ово је тест Edge Read Aloud мотора. Глас се синтетише у облаку."
            "su" -> "Halo. Ieu mangrupikeun tés mesin Edge Read Aloud. Sora disintésis dina cloud."
            "sv" -> "Hej. Detta är ett test av Edge Read Aloud-motorn. Rösten syntetiseras i molnet."
            "sw" -> "Habari. Hii ni jaribio la injini ya Edge Read Aloud. Sauti inasintesiwa kwenye wingu."
            "ta" -> "வணக்கம். இது Edge Read Aloud இயந்திரத்தின் சோதனை. குரல் கிளவுட்டில் தொகுக்கப்படுகிறது."
            "te" -> "నమస్కారం. ఇది Edge Read Aloud ఇంజన్ యొక్క పరీక్ష. వాయిస్ క్లౌడ్‌లో సంశ్లేషణ చేయబడింది."
            "th" -> "สวัสดี นี่คือการทดสอบเอ็นจิน Edge Read Aloud เสียงถูกสังเคราะห์ในคลาวด์"
            "uk" -> "Привіт. Це тест рушія Edge Read Aloud. Голос синтезується в хмарі."
            "ur" -> "سلام۔ یہ Edge Read Aloud انجن کا ٹیسٹ ہے۔ آواز کلاؤڈ میں ترکیب کی جاتی ہے۔"
            "uz" -> "Salom. Bu Edge Read Aloud dvigatelining sinovidir. Ovoz bulutda sintez qilinadi."
            "vi" -> "Xin chào. Đây là bài kiểm tra của công cụ Edge Read Aloud. Giọng nói được tổng hợp trên đám mây."
            "zu" -> "Sawubona. Lokhu ukuhlolwa kwenjini ye-Edge Read Aloud. Izwi lakhiwe efwini."
            else -> "Hello. This is a test of the Edge Read Aloud engine. The voice you hear is synthesized in the cloud."
        }

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
     * Listener de slider: convierte progress (0..100) a valor (-50..+50) y lo
     * persiste. Solo actúa al soltar (onStopTrackingTouch) para no escribir en
     * DataStore en cada píxel de arrastre; el arrastre actualiza la etiqueta.
     */
    private fun seekListener(onValue: (value: Int, fromUser: Boolean) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onValue(progress - SLIDER_OFFSET, true)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                onValue(seekBar.progress - SLIDER_OFFSET, false)
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
        url.replace(Regex("(?i)(token=)([^&]+)"), "$1••••")
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
        private const val UTTERANCE_TEST = "edge-tts-test"

        /** Refrescar el catálogo si lleva más de 7 días sin actualizarse. */
        private const val CATALOG_STALE_MS = 7L * 24 * 60 * 60 * 1000

        /** Sliders de velocidad/tono: rango -50..+50 mapeado a progress 0..100. */
        private const val SLIDER_RANGE = 100
        private const val SLIDER_OFFSET = 50
    }
}
