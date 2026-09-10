package dev.experimental.edgetts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Preferencias del motor. El snapshot en memoria es la fuente para Binder/UI;
 * DataStore se lee y escribe en un hilo de infraestructura.
 */
class SettingsStore(context: Context) {

    data class Snapshot(
        val locale: String,
        val voice: String,
        val ratePercent: Int,
        val pitchHz: Int,
        val cacheEnabled: Boolean,
        val voicesUrl: String,
        val wsUrl: String,
        val userAgent: String,
        val origin: String,
        val uiLanguage: String,
        val unifiedVoiceMode: Boolean,
        val lastSpanishVoice: String,
        val lastError: String,
        val handshakeDebug: String,
        val catalogUpdatedAt: Long,
        val lastMetrics: String
    ) {
        companion object {
            fun defaults(): Snapshot = Snapshot(
                locale = EdgeProtocolConstants.DEFAULT_LOCALE,
                voice = EdgeProtocolConstants.DEFAULT_VOICE,
                ratePercent = 0,
                pitchHz = 0,
                cacheEnabled = true,
                voicesUrl = EdgeProtocolConstants.VOICES_LIST_URL,
                wsUrl = EdgeProtocolConstants.WS_BASE_URL,
                userAgent = EdgeProtocolConstants.DEFAULT_USER_AGENT,
                origin = EdgeProtocolConstants.DEFAULT_ORIGIN,
                uiLanguage = "",
                unifiedVoiceMode = true,
                lastSpanishVoice = EdgeProtocolConstants.DEFAULT_VOICE,
                lastError = "",
                handshakeDebug = "",
                catalogUpdatedAt = 0L,
                lastMetrics = ""
            )
        }
    }

    private val store: DataStore<Preferences> = Holder.get(context)

    fun snapshot(): Snapshot = Holder.snapshot

    fun snapshotBlocking(): Snapshot {
        val disk = runBlocking { store.data.first() }.let { snapshotOf(it) }
        if (Holder.pendingWrites.get() == 0) Holder.snapshot = disk
        return if (Holder.pendingWrites.get() == 0) disk else Holder.snapshot
    }

    fun setVoice(voice: String) = applyAndPersist(
        { it.copy(voice = voice, locale = LocaleCodes.localeOfVoiceName(voice)) }
    ) { it[K_VOICE] = voice }

    fun setRate(percent: Int) {
        val value = percent.coerceIn(-50, 50)
        applyAndPersist({ it.copy(ratePercent = value) }) { it[K_RATE] = value }
    }

    fun setPitch(hz: Int) {
        val value = hz.coerceIn(-50, 50)
        applyAndPersist({ it.copy(pitchHz = value) }) { it[K_PITCH] = value }
    }

    fun setCacheEnabled(enabled: Boolean) =
        applyAndPersist({ it.copy(cacheEnabled = enabled) }) { it[K_CACHE] = enabled }

    fun setUserAgent(ua: String): Boolean {
        if (!HeaderPolicy.isUserAgent(ua)) return false
        val value = ua.trim()
        applyAndPersist({ it.copy(userAgent = value) }) { it[K_USER_AGENT] = value }
        return true
    }

    fun resetUserAgent() = applyAndPersist(
        { it.copy(userAgent = EdgeProtocolConstants.DEFAULT_USER_AGENT) }
    ) { it.remove(K_USER_AGENT) }

    fun setOrigin(o: String): Boolean {
        if (!HeaderPolicy.isOrigin(o)) return false
        val value = o.trim()
        applyAndPersist({ it.copy(origin = value) }) { it[K_ORIGIN] = value }
        return true
    }

    fun resetOrigin() = applyAndPersist(
        { it.copy(origin = EdgeProtocolConstants.DEFAULT_ORIGIN) }
    ) { it.remove(K_ORIGIN) }

    fun setUiLanguage(code: String) = applyAndPersist({ it.copy(uiLanguage = code) }) {
        if (code.isBlank()) it.remove(K_UI_LANG) else it[K_UI_LANG] = code
    }

    fun setUnifiedVoiceMode(enabled: Boolean) =
        applyAndPersist({ it.copy(unifiedVoiceMode = enabled) }) { it[K_UNIFIED_VOICE] = enabled }

    fun setLastSpanishVoice(voice: String) =
        applyAndPersist({ it.copy(lastSpanishVoice = voice) }) { it[K_LAST_ES_VOICE] = voice }

    fun setHandshakeDebug(d: String) {
        val value = d.take(MAX_DIAG_CHARS)
        applyAndPersist({ it.copy(handshakeDebug = value) }) { it[K_HS_DEBUG] = value }
    }

    fun setLastMetrics(line: String) {
        val value = line.take(500)
        applyAndPersist({ it.copy(lastMetrics = value) }) { it[K_LAST_METRICS] = value }
    }

    fun setLastError(message: String) {
        val value = message.take(500)
        applyAndPersist({ it.copy(lastError = value) }) { it[K_LAST_ERROR] = value }
    }

    fun clearLastError() = applyAndPersist({ it.copy(lastError = "") }) { it.remove(K_LAST_ERROR) }

    fun setCatalogUpdatedAt(millis: Long) =
        applyAndPersist({ it.copy(catalogUpdatedAt = millis) }) { it[K_CATALOG_TS] = millis }

    fun setWsUrl(url: String) {
        val value = url.trim()
        applyAndPersist({ it.copy(wsUrl = value) }) { it[K_WS_URL] = value }
    }

    fun reset() = applyAndPersist({ Snapshot.defaults() }) { it.clear() }

    private fun applyAndPersist(
        local: (Snapshot) -> Snapshot,
        persist: (MutablePreferences) -> Unit
    ) {
        synchronized(Holder.lock) {
            Holder.snapshot = local(Holder.snapshot)
        }
        Holder.pendingWrites.incrementAndGet()
        Holder.writeExecutor.execute {
            try {
                runBlocking { store.edit { prefs -> persist(prefs) } }
            } finally {
                Holder.pendingWrites.decrementAndGet()
            }
        }
    }

    companion object {
        private const val MAX_DIAG_CHARS = 1500

        val K_VOICE = stringPreferencesKey("voice")
        val K_RATE = intPreferencesKey("rate_percent")
        val K_PITCH = intPreferencesKey("pitch_hz")
        val K_CACHE = booleanPreferencesKey("cache_enabled")
        val K_VOICES_URL = stringPreferencesKey("voices_url")
        val K_WS_URL = stringPreferencesKey("ws_url")
        val K_USER_AGENT = stringPreferencesKey("user_agent")
        val K_ORIGIN = stringPreferencesKey("origin")
        val K_UI_LANG = stringPreferencesKey("ui_language")
        val K_UNIFIED_VOICE = booleanPreferencesKey("unified_voice_mode")
        val K_LAST_ES_VOICE = stringPreferencesKey("last_spanish_voice")
        val K_LAST_ERROR = stringPreferencesKey("last_error")
        val K_HS_DEBUG = stringPreferencesKey("handshake_debug")
        val K_CATALOG_TS = longPreferencesKey("catalog_updated_at")
        val K_LAST_METRICS = stringPreferencesKey("last_metrics")

        fun ensureLoaded(context: Context) {
            Holder.get(context)
            Holder.awaitInitialized()
        }

        fun snapshotOf(prefs: Preferences): Snapshot {
            val voice = prefs[K_VOICE] ?: EdgeProtocolConstants.DEFAULT_VOICE
            return Snapshot(
            locale = LocaleCodes.localeOfVoiceName(voice),
            voice = voice,
            ratePercent = prefs[K_RATE] ?: 0,
            pitchHz = prefs[K_PITCH] ?: 0,
            cacheEnabled = prefs[K_CACHE] ?: true,
            voicesUrl = prefs[K_VOICES_URL] ?: EdgeProtocolConstants.VOICES_LIST_URL,
            wsUrl = prefs[K_WS_URL] ?: EdgeProtocolConstants.WS_BASE_URL,
            userAgent = HeaderPolicy.orDefaultUserAgent(
                prefs[K_USER_AGENT] ?: EdgeProtocolConstants.DEFAULT_USER_AGENT
            ),
            origin = HeaderPolicy.orDefaultOrigin(
                prefs[K_ORIGIN] ?: EdgeProtocolConstants.DEFAULT_ORIGIN
            ),
            uiLanguage = prefs[K_UI_LANG].orEmpty(),
            unifiedVoiceMode = prefs[K_UNIFIED_VOICE] ?: true,
            lastSpanishVoice = prefs[K_LAST_ES_VOICE] ?: EdgeProtocolConstants.DEFAULT_VOICE,
            lastError = prefs[K_LAST_ERROR].orEmpty(),
            handshakeDebug = prefs[K_HS_DEBUG].orEmpty(),
            catalogUpdatedAt = prefs[K_CATALOG_TS] ?: 0L,
            lastMetrics = prefs[K_LAST_METRICS].orEmpty()
            )
        }
    }

    private object Holder {
        val lock = Any()

        @Volatile
        var snapshot: Snapshot = Snapshot.defaults()

        val pendingWrites = AtomicInteger(0)

        val writeExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "edge-tts-settings-io").apply { isDaemon = true }
        }

        private val initialized = AtomicBoolean(false)
        private val initLatch = CountDownLatch(1)

        @Volatile
        private var instance: DataStore<Preferences>? = null

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun get(context: Context): DataStore<Preferences> =
            instance ?: synchronized(this) {
                instance ?: androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                    scope = scope
                ) {
                    context.applicationContext.preferencesDataStoreFile("edge_tts_settings")
                }.also { ds ->
                    instance = ds
                    scope.launch {
                        ds.data.collect { prefs ->
                            if (pendingWrites.get() == 0) {
                                snapshot = snapshotOf(prefs)
                            }
                            if (initialized.compareAndSet(false, true)) initLatch.countDown()
                        }
                    }
                }
            }

        fun awaitInitialized(timeoutMs: Long = 1_000L) {
            if (initialized.get()) return
            if (initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return
            val ds = instance ?: return
            runCatching {
                val disk = runBlocking { ds.data.first() }.let { snapshotOf(it) }
                if (pendingWrites.get() == 0) snapshot = disk
                if (initialized.compareAndSet(false, true)) initLatch.countDown()
            }
        }
    }
}
