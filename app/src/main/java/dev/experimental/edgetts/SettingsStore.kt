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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Preferencias del motor en DataStore Preferences.
 *
 * El servicio TTS vive en el MISMO proceso que la actividad (paquete único),
 * así que lee el snapshot de forma síncrona y bloqueante (runBlocking) sin
 * riesgo de deadlock: onSynthesizeText nunca corre en el hilo principal.
 *
 * Por defecto NO se guarda ningún texto leído; la caché (opt-in) almacena
 * solo audio, indexado por hash SHA-256.
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
        val catalogUpdatedAt: Long
    )

    private val store: DataStore<Preferences> = Holder.get(context)

    fun snapshotBlocking(): Snapshot =
        runBlocking { store.data.first() }.toSnapshot()

    fun setVoice(voice: String) = update { it[K_VOICE] = voice }

    fun setRate(percent: Int) = update { it[K_RATE] = percent.coerceIn(-50, 50) }

    fun setPitch(hz: Int) = update { it[K_PITCH] = hz.coerceIn(-50, 50) }

    fun setCacheEnabled(enabled: Boolean) = update { it[K_CACHE] = enabled }

    /**
     * User-Agent configurable: si Microsoft rota la build de Edge que
     * acepta, el usuario lo edita desde la app sin recompilar la APK.
     */
    fun setUserAgent(ua: String) = update { it[K_USER_AGENT] = ua.trim() }

    fun resetUserAgent() = update { it.remove(K_USER_AGENT) }

    /**
     * Origin configurable: el handshake puede exigir el del lector inmersivo
     * (chrome-extension://…) u otro. Editable desde la app sin recompilar.
     */
    fun setOrigin(o: String) = update { it[K_ORIGIN] = o.trim() }

    fun resetOrigin() = update { it.remove(K_ORIGIN) }

    /** Idioma de la UI: "" = sistema, "es", "en". */
    fun setUiLanguage(code: String) = update {
        if (code.isBlank()) it.remove(K_UI_LANG) else it[K_UI_LANG] = code
    }

    /**
     * Modo de voz unificada (Modelo 1): la voz elegida en la app es la fuente
     * de verdad para todo su idioma. Cuando está activo, el sistema (Play
     * Books, Neo Reader, Ajustes) usa la voz de la app para cualquier variante
     * de ese idioma; cuando se apaga, se restaura la prioridad por país de la
     * v18 (cada variante de español con su voz regional). Por defecto ON.
     */
    fun setUnifiedVoiceMode(enabled: Boolean) = update { it[K_UNIFIED_VOICE] = enabled }

    /**
     * Última voz de español elegida en la app. En modo unificado, cuando un
     * libro está en español pero la voz configurada es de OTRO idioma, se usa
     * esta voz (una de español reconocible, p. ej. la mexicana) en lugar de
     * una variante arbitraria del catálogo (que podía sonar a un español que
     * el usuario no reconoce).
     */
    fun setLastSpanishVoice(voice: String) = update { it[K_LAST_ES_VOICE] = voice }

    /** Diagnóstico del último handshake (solo metadatos; lo escribe el cliente). */
    fun setHandshakeDebug(d: String) = update { it[K_HS_DEBUG] = d.take(500) }

    fun setLastError(message: String) = update { it[K_LAST_ERROR] = message.take(500) }

    fun clearLastError() = update { it.remove(K_LAST_ERROR) }

    fun setCatalogUpdatedAt(millis: Long) = update { it[K_CATALOG_TS] = millis }

    /** Restablece TODO a los valores iniciales de la especificación. */
    fun reset() = update { it.clear() }

    private fun update(block: (MutablePreferences) -> Unit) {
        runBlocking {
            store.edit { prefs -> block(prefs) }
        }
    }

    private fun Preferences.toSnapshot(): Snapshot = Snapshot(
        locale = this[K_LOCALE] ?: EdgeProtocolConstants.DEFAULT_LOCALE,
        voice = this[K_VOICE] ?: EdgeProtocolConstants.DEFAULT_VOICE,
        ratePercent = this[K_RATE] ?: 0,
        pitchHz = this[K_PITCH] ?: 0,
        cacheEnabled = this[K_CACHE] ?: true,
        voicesUrl = this[K_VOICES_URL] ?: EdgeProtocolConstants.VOICES_LIST_URL,
        wsUrl = this[K_WS_URL] ?: EdgeProtocolConstants.WS_BASE_URL,
        userAgent = this[K_USER_AGENT] ?: EdgeProtocolConstants.DEFAULT_USER_AGENT,
        origin = this[K_ORIGIN] ?: EdgeProtocolConstants.DEFAULT_ORIGIN,
        uiLanguage = this[K_UI_LANG].orEmpty(),
        unifiedVoiceMode = this[K_UNIFIED_VOICE] ?: true,
        lastSpanishVoice = this[K_LAST_ES_VOICE] ?: EdgeProtocolConstants.DEFAULT_VOICE,
        lastError = this[K_LAST_ERROR].orEmpty(),
        handshakeDebug = this[K_HS_DEBUG].orEmpty(),
        catalogUpdatedAt = this[K_CATALOG_TS] ?: 0L
    )

    private companion object {
        val K_LOCALE = stringPreferencesKey("locale")
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
    }

    /** DataStore exige una única instancia por archivo; Holder la garantiza. */
    private object Holder {
        @Volatile
        private var instance: DataStore<Preferences>? = null

        fun get(context: Context): DataStore<Preferences> =
            instance ?: synchronized(this) {
                instance ?: androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                ) {
                    context.applicationContext.preferencesDataStoreFile("edge_tts_settings")
                }.also { instance = it }
            }
    }
}
