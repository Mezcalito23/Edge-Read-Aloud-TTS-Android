package dev.experimental.edgetts

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * Idioma de la UI de la app: "" = seguir al sistema, "es" o "en".
 *
 * Implementado con un ContextWrapper (createConfigurationContext) para que
 * funcione en CUALQUIER versión de Android, sin depender del selector de
 * idioma por app (solo Android 13+) ni del orden de locales del dispositivo
 * —que en algunos equipos "en español" tiene en-US primero y resolvía la UI
 * a inglés—.
 *
 * El ajuste se guarda en DataStore ([SettingsStore.setUiLanguage]) y se
 * aplica en `attachBaseContext` de la actividad, antes de inflar layouts.
 */
object UiLanguage {

    /** Contexto con el idioma guardado, o el original si es "sistema". */
    fun wrap(base: Context): Context {
        val saved = runCatching {
            SettingsStore(base).snapshotBlocking().uiLanguage
        }.getOrDefault("")
        if (saved.isBlank()) return base

        // forLanguageTag en vez del constructor Locale(String), deprecado.
        val locale = Locale.forLanguageTag(saved)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return ContextWrapper(base.createConfigurationContext(config))
    }

    /** Índice del spinner ↔ código guardado (0 = sistema). */
    fun indexOf(code: String): Int = when (code) {
        "es" -> 1
        "en" -> 2
        else -> 0
    }

    fun codeOf(index: Int): String = when (index) {
        1 -> "es"
        2 -> "en"
        else -> ""
    }
}
