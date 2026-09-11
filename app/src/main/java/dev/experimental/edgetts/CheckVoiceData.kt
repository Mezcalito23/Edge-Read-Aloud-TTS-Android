package dev.experimental.edgetts

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale

/**
 * Actividad del contrato de motor TTS para `ACTION_CHECK_TTS_DATA`
 * (también base de [InstallVoiceData] para `INSTALL_TTS_DATA`).
 *
 * Responde con la lista de datos de voz instalados. Reglas de diseño,
 * aprendidas de fallos reales:
 *
 *  1. TODOS los locales del catálogo, uno por locale, en formato canónico
 *     ISO3-PAÍS (`spa-MEX`, `spa-ESP`, `spa-USA`, `eng-USA`…): así el
 *     selector de Ajustes muestra todas las variantes de español del motor.
 *  2. La lista es ESTABLE: depende solo del catálogo, NUNCA de la voz
 *     configurada en la app. (Cuando la lista cambiaba entre visitas —
 *     `spa-MEX` con Dalia, `spa-PA` con la voz panameña— el selector de
 *     Ajustes perdía su selección persistida y COLAPSABA.)
 *  3. Solo entradas con idioma Y país: las entradas de una sola parte
 *     ("es", "en") rompen el parseo de locales de algunos selectores.
 *  4. Unificado OFF: la variante de Ajustes (spa-ESP, eng-GBR…) determina
 *     la voz regional. Unificado ON: la voz de la app se usa en todas partes.
 *
 * Esta actividad NUNCA lanza: lectura de extras tolerante (ArrayList,
 * String[] o String) y flujo completo en try/catch con FAIL de respaldo.
 */
open class CheckVoiceData : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            respond()
        } catch (_: Throwable) {
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL)
            finish()
        }
    }

    private fun respond() {
        val requested = readRequestedLocales()
        val locales = canonicalLocales()

        val available = ArrayList<String>()
        val unavailable = ArrayList<String>()

        if (requested.isEmpty()) {
            available.addAll(locales)
        } else {
            for (raw in requested) {
                if (matches(raw, locales)) available += raw else unavailable += raw
            }
        }

        val result = android.content.Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable)
        }
        setResult(
            if (available.isNotEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
            else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL,
            result
        )
        finish()
    }

    /**
     * Todos los locales del catálogo en forma canónica **ISO3** (`spa-MEX`,
     * `eng-USA`…), con orden estable: variantes de español primero, resto
     * alfabético. Sin catálogo (primera ejecución sin red) se responde solo
     * `spa-MEX`.
     *
     * POR QUÉ ISO3 Y NO ISO2 (lección verificada en dispositivo): los Ajustes
     * comparan estas entradas con `TextToSpeech.getDefaultLanguage()`, cuyo
     * contrato exige ISO3 (`onGetLanguage()` → "spa","MEX"). En Java
     * `Locale("es","MX") != Locale("spa","MEX")`: si los formatos difieren,
     * los Ajustes no encuentran el locale por defecto, no habilitan los
     * controles de velocidad/tono/reproducir e incluso pueden CERRAR la
     * pantalla (regresión real observada al responder en ISO2). Google TTS
     * también reporta en ISO3.
     */
    private fun canonicalLocales(): List<String> {
        val set = LinkedHashSet<String>()
        runCatching {
            val file = File(cacheDir, VoiceCatalogRepository.CATALOG_FILENAME)
            if (file.exists()) {
                val array = org.json.JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val raw = array.optJSONObject(i)?.optString("Locale")?.trim().orEmpty()
                    canonical(raw)?.let { set += it }
                }
            }
        }
        if (set.isEmpty()) set += "spa-MEX"
        return set.sortedWith(compareBy({ !it.startsWith("spa-") }, { it }))
    }

    /** "es-MX" → "spa-MEX" (ISO3, el mismo formato que onGetLanguage). */
    private fun canonical(raw: String): String? {
        val loc = Locale.forLanguageTag(raw.replace('_', '-'))
        if (loc.language.isBlank()) return null
        val l3 = runCatching { loc.isO3Language }.getOrNull() ?: return null
        val c3 = runCatching { loc.isO3Country }.getOrNull().orEmpty()
        if (c3.isBlank()) return null
        return "$l3-$c3"
    }

    /** Compara en ISO2/ISO3, con guion o guion bajo, idioma solo o completo. */
    private fun matches(requestedRaw: String, locales: List<String>): Boolean {
        val req = requestedRaw.trim().lowercase(Locale.ROOT).replace('_', '-')
        if (req.isEmpty()) return false
        val reqLang = LocaleCodes.iso3ToIso2LangOrSelf(req.substringBefore('-'))
        return locales.any { entry ->
            val e = entry.lowercase(Locale.ROOT)
            e == req ||
                e.substringBefore('-') == req ||
                LocaleCodes.iso3ToIso2LangOrSelf(e.substringBefore('-')) == reqLang
        }
    }

    private fun readRequestedLocales(): List<String> {
        // Literal en vez de TextToSpeech.Engine.EXTRA_CHECK_VOICE_DATA_FOR,
        // deprecado en los SDK recientes (el valor es estable).
        val key = "android.speech.tts.engine.extra.CHECK_VOICE_DATA_FOR"
        runCatching {
            intent.getStringArrayListExtra(key)?.filter { it.isNotBlank() }?.toList()
        }.getOrNull()?.let { return it }
        runCatching {
            intent.getStringArrayExtra(key)?.filter { it.isNotBlank() }?.toList()
        }.getOrNull()?.let { return it }
        runCatching {
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { listOf(it) }
        }.getOrNull()?.let { return it }
        return emptyList()
    }
}
