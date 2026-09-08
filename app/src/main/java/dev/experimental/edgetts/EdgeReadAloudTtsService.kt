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
 *  - done() XOR error(), exactamente una vez por sÃ©ntesis;
 *  - ninguna excepciÃ³n escapa del servicio (la app cliente nunca crashea);
 *  - onStop() cancela la sÃ©ntesis activa;
 *  - el audio se entrega SIEMPRE como PCM 16-bit (el MP3 de Edge se
 *    decodifica con MediaCodec ANTES de tocar SynthesisCallback).
 *
 * Modelo de voces (como Google TTS): se expone UNA voz por idioma. Para el
 * idioma de la voz configurada en la app (espaÃ±ol, Dalia por defecto) esa es
 * la voz expuesta; cambiarla en la app cambia la voz del espaÃ±ol en TODO el
 * sistema. Para otros idiomas se expone una voz representativa del catÃ¡logo
 * y se resuelve automÃ¡ticamente segÃºn el idioma del contenido.
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
    // (inglÃ©s en un equipo inglÃ©s, francÃ©s en uno francÃ©s, etc.) â como
    // hace Google TTS.
    @Volatile
    private var currentLanguage: Array<String> = arrayOf("es", "MX", "")

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

        // Idioma inicial: el del dispositivo, si el catÃ¡logo lo cubre;
        // si no, espaÃ±ol de MÃ©xico. AsÃ© el "idioma predeterminado" del motor
        // coincide con el sistema desde el primer momento.
        runCatching {
            val dev = Locale.getDefault()
            if (languageAvailability(dev.language, dev.country) >= TextToSpeech.LANG_AVAILABLE) {
                currentLanguage = arrayOf(dev.language, dev.country, "")
            }
        }
    }

    override fun onDestroy() {
        active?.cancel()
        super.onDestroy()
    }

    // ââ¬¤ Idioma y voces ââ¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤

    // CachÃ© por proceso de los locales soportados, en DOBLE formato (ISO2 e
    // ISO3). onIsLanguageAvailable se invoca cientos de veces al abrir los
    // ajustes del sistema, asÃ© que no se relee el JSON en cada llamada. Solo
    // se cachea cuando hay datos: si el catÃ¡logo aÃºn no existe, se reintenta.
    //
    // La negociaciÃ³n compara en ambos formatos como seguro: en algunos
    // dispositivos la comparaciÃ³n ISO3 fallaba por un quirk de ICU (la
    // consulta se normalizaba bien pero el set no contenÃ©a la entrada) y
    // TODO respondÃ©a LANG_AVAILABLE en vez de LANG_COUNTRY_AVAILABLE. Con
    // doble formato, la coincidencia exacta de paÃ©s siempre prende.
    private data class LocaleSets(
        val fullIso2: Set<String>,   // "es-mx", "en-us", â
        val langsIso2: Set<String>,  // "es", "en", â
        val fullIso3: Set<String>,   // "spa-mex", "eng-usa", â
        val langsIso3: Set<String>   // "spa", "eng", â
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
     * Normaliza un locale ("es-mx", "es-MX", "spa-mex"â¬¤) a ISO3 minÃºsculo
     * ("spa-mex"). Usa Locale.isO3Language/isO3Country, que aceptan tanto ISO2
     * como ISO3 y devuelven siempre ISO3. Devuelve null si el idioma no se
     * puede resolver.
     */
    private fun toIso3(locale: String): String? {
        val parts = locale.replace('_', '-').split("-")
        val lang = parts.getOrNull(0).orEmpty().trim()
        if (lang.isBlank()) return null
        // forLanguageTag en vez de los constructores Locale(String[, String]),
        // deprecados en los SDK recientes.
        val lang3 = runCatching { Locale.forLanguageTag(lang).isO3Language.lowercase(Locale.ROOT) }
            .getOrNull() ?: return null
        val country = parts.getOrNull(1).orEmpty().trim()
        if (country.isBlank()) return lang3
        val country3 = if (country.length == 3) country.lowercase(Locale.ROOT)
        else runCatching {
            Locale.forLanguageTag("und-${country.uppercase(Locale.ROOT)}").isO3Country.lowercase(Locale.ROOT)
        }.getOrNull() ?: return lang3
        return "$lang3-$country3"
    }

    // ââ¬¤ Voces expuestas: TODO el catÃ¡logo ââ¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤
    // Se exponen las ~322 voces del catÃ¡logo, cada una con SU locale real.
    // Es imprescindible para la integraciÃ³n con el sistema: el framework
    // resuelve setVoice()/setLanguage() buscando la voz en onGetVoices()
    // (findVoice); si la voz pedida no estÃ¡ expuesta, la peticiÃ³n se descarta
    // en silencio y el motor sintetiza con la voz anterior âexactamente el
    // fallo de "algunos idiomas funcionan y otros no".
    //
    // La selecciÃ³n de la app sigue aplicando a todo el sistema vÃ©a
    // onGetDefaultVoiceNameFor (para su idioma, la voz configurada); la
    // lista de IDIOMAS de Ajustes sale de CheckVoiceData (canÃ³nica y
    // estable), NO de esta lista, asÃ© que el selector de idiomas no se
    // ve afectado por exponer el catÃ¡logo completo.

    /**
     * Resuelve la voz para un idioma (y paÃ©s opcional). Compara en ISO3
     * (robusto a entradas ISO2 o ISO3). Prioridad:
     *  1. Si se especifica un PAÃ¬S y el catÃ¡logo tiene una voz para ese paÃ©s,
     *     se respeta el paÃ©s (el Settings manda sobre la variante). Esto hace
     *     que "EspaÃ±ol (Nicaragua)" en Ajustes suene con voz nicaragÃ¼ense y
     *     no con la voz mexicana configurada âera la queja principalâ¬¤.
     *     ExcepciÃ³n: si la voz configurada en la app es de ESE mismo paÃ©s, se
     *     usa la configurada (respeta la elecciÃ³n del usuario).
     *  2. Sin paÃ©s (o paÃ©s sin voz en catÃ¡logo): si la voz configurada es de
     *     este idioma, se usa (modelo de la app: la voz elegida aplica al
     *     sistema para su idioma).
     *  3. Primera voz del idioma en el catÃ¡logo.
     */
    private fun voiceForLanguage(lang: String, country: String = ""): String? {
        val l3 = normLang(lang)
        if (l3.isEmpty()) return null
        val configuredName = runCatching { settings?.snapshotBlocking()?.voice }
            .getOrNull() ?: EdgeProtocolConstants.DEFAULT_VOICE
        val configuredLang3 = normLang(configuredName.substringBefore("-"))
        val catalogVoices = runCatching { catalog?.cached() }.getOrNull().orEmpty()
        val c3 = normCountry(country)

        // 1) PaÃ©s especificado con voz en el catÃ¡logo â respetar el paÃ©s.
        if (c3.isNotEmpty()) {
            val countryVoice = catalogVoices.firstOrNull {
                normLang(it.locale.substringBefore("-")) == l3 &&
                    normCountry(it.locale.substringAfter("-", "")) == c3
            }
            if (countryVoice != null) {
                val configuredCountry3 = normCountry(configuredName.substringAfter("-", ""))
                if (configuredLang3 == l3 && configuredCountry3 == c3) {
                    return validatedVoice(configuredName)
                }
                return countryVoice.shortName
            }
        }

        // 2) La voz configurada aplica a su idioma (modelo de la app).
        if (configuredLang3 == l3) return validatedVoice(configuredName)

        // 3) Primera voz del idioma.
        return catalogVoices.firstOrNull {
            normLang(it.locale.substringBefore("-")) == l3
        }?.shortName
    }

    // ââ¬¤ Resiliencia a cambios del catÃ¡logo de Microsoft ââ¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤
    // Microsoft puede RETIRAR o AGREGAR voces del servicio en cualquier
    // momento. Si la app o un cliente piden una voz que ya no existe, enviar
    // su nombre a Edge hace que el servidor cierre el turno SIN audio (error
    // confuso). validatedVoice() garantiza que solo se sintetice con voces
    // presentes en el catÃ¡logo descargado, con un respaldo razonable.

    /** Nombres de voz presentes en el catÃ¡logo (vacÃ©o si aÃºn no se descarga). */
    private fun catalogShortNames(): Set<String> =
        runCatching { catalog?.cached() }.getOrNull().orEmpty()
            .map { it.shortName }.toSet()

    /**
     * Si [voice] existe en el catÃ¡logo se devuelve tal cual; si no (voz
     * retirada por Microsoft o catÃ¡logo desactualizado) se busca un respaldo:
     * primero una voz del MISMO idioma, luego la voz configurada, luego Dalia.
     * Sin catÃ¡logo descargado se confÃ©a en el nombre pedido (no hay con quÃ©
     * validar).
     */
    private fun validatedVoice(voice: String): String {
        val names = catalogShortNames()
        if (names.isEmpty()) return voice
        if (voice in names) return voice

        val lang = voice.substringBefore("-").lowercase(Locale.ROOT)
        val byLang = runCatching { catalog?.cached() }.getOrNull().orEmpty()
            .firstOrNull { it.locale.substringBefore("-").lowercase(Locale.ROOT) == lang }
        if (byLang != null) {
            Log.w(TAG, "La voz '$voice' ya no estÃ¡ en el catÃ¡logo; usando ${byLang.shortName}")
            return byLang.shortName
        }

        val configured = runCatching { settings?.snapshotBlocking()?.voice }
            .getOrNull() ?: EdgeProtocolConstants.DEFAULT_VOICE
        return if (configured in names) configured else EdgeProtocolConstants.DEFAULT_VOICE
    }

    // ââ¬¤ NormalizaciÃ³n ISO3 ââ¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤
    // Los clientes pueden negociar con ISO2 ("es"/"MX") o con ISO3
    // ("spa"/"MEX"). Locale.isO3Language/isO3Country acepta AMBOS formatos y
    // devuelve siempre ISO3, asÃ© que normalizamos la consulta a ISO3 y
    // comparamos contra los sets ISO3 del catÃ¡logo. Es robusto sin depender de
    // mapas ISO3â¬¤ISO2 que pueden fallar en algunos dispositivos (Onyx/HarmonyOS).

    /**
    * Normaliza un cÃ³digo de idioma a ISO3 minÃºsculo ("spa", "eng").
    * Acepta ISO2 ("es") o ISO3 ("spa"). Los cÃ³digos de 3 letras YA son
    * ISO3: no se convierten (hacerlo con forLanguageTag fallarÃ©a).
    */
    private fun normLang(code: String): String {
        val c = code.trim().lowercase(Locale.ROOT)
        if (c.isEmpty()) return ""
        if (c.length == 3) return c                       // ya es ISO3
        return runCatching {
            Locale.forLanguageTag(c).isO3Language.lowercase(Locale.ROOT)
        }.getOrDefault(c)                                  // ISO2 â ISO3
    }

    /**
     * Normaliza un cÃ³digo de paÃ©s a ISO3 minÃºsculo ("mex", "usa").
     * Acepta ISO2 ("MX") o ISO3 ("MEX"). IMPORTANTE: los cÃ³digos de 3
     * letras YA son ISO3 y se devuelven tal cual, porque BCP-47 (el formato
     * de forLanguageTag) NO acepta regiones alfa-3 â pasar "MEX" por
     * forLanguageTag da paÃ©s vacÃ©o (era el bug que deshabilitaba los
     * controles de Ajustes).
     */
    private fun normCountry(code: String): String {
        val c = code.trim().lowercase(Locale.ROOT)
        if (c.isEmpty()) return ""
        if (c.length == 3) return c                       // ya es ISO3
        return runCatching {
            Locale.forLanguageTag("und-${c.uppercase(Locale.ROOT)}")
                .isO3Country.lowercase(Locale.ROOT)
        }.getOrDefault(c)                                  // ISO2 â ISO3
    }

    // CachÃ©s ISO3â¬¤ISO2. Locale("spa").language devuelve "spa" (NO "es"), asÃ©
    // que la ÃNICA forma fiable de obtener el ISO2 es buscarlo en las tablas
    // de Locale. Se cachean porque languageAvailability se llama cientos de
    // veces al abrir los Ajustes.
    @Volatile
    private var iso3To2LangCache: Map<String, String>? = null

    @Volatile
    private var iso3To2CountryCache: Map<String, String>? = null

    /** Convierte un cÃ³digo ISO3 de idioma ("spa") a ISO2 ("es"). */
    private fun iso3ToIso2Lang(code3: String): String {
        val map = iso3To2LangCache ?: run {
            val m = HashMap<String, String>()
            for (iso2 in Locale.getISOLanguages()) {
                runCatching {
                    m[Locale.forLanguageTag(iso2).isO3Language.lowercase(Locale.ROOT)] = iso2
                }
            }
            m.also { iso3To2LangCache = it }
        }
        return map[code3.lowercase(Locale.ROOT)] ?: code3.lowercase(Locale.ROOT)
    }

    /** Convierte un cÃ³digo ISO3 de paÃ©s ("mex") a ISO2 ("mx"). */
    private fun iso3ToIso2Country(code3: String): String {
        val map = iso3To2CountryCache ?: run {
            val m = HashMap<String, String>()
            for (iso2 in Locale.getISOCountries()) {
                runCatching {
                    m[Locale.forLanguageTag("und-$iso2").isO3Country.lowercase(Locale.ROOT)] = iso2.lowercase(Locale.ROOT)
                }
            }
            m.also { iso3To2CountryCache = it }
        }
        return map[code3.lowercase(Locale.ROOT)] ?: code3.lowercase(Locale.ROOT)
    }

    /**
     * NegociaciÃ³n de idioma derivada del catÃ¡logo de voces, comparando en ISO3
     * (robusto a consultas ISO2 o ISO3):
     *  - locale exacto (lang+country) presente â LANG_COUNTRY_AVAILABLE (2),
     *    que es lo que exigen los ajustes de Android para habilitar los
     *    controles de velocidad/tono/reproducir (Hardy);
     *  - solo el idioma presente â LANG_AVAILABLE (1);
     *  - nada â LANG_NOT_SUPPORTED.
     * Al cubrir los ~75 idiomas del catÃ¡logo, el selector del sistema casi
     * nunca muestra "idioma no soportado".
     */
    private fun languageAvailability(lang: String, country: String): Int {
        val sets = supportedLocaleSets()
        val l3 = normLang(lang)
        val c3 = normCountry(country)
        // ISO2 derivados de los ISO3 con una conversiÃ³n REAL (Locale("spa")
        // .language devuelve "spa", no "es", asÃ© que usamos las tablas de
        // Locale). Doble formato como seguro ante quirks de ICU: la
        // coincidencia exacta de paÃ©s prende LANG_COUNTRY_AVAILABLE (2).
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

        // DiagnÃ³stico INCONDICIONAL (Hardy): muestra la consulta, la
        // normalizaciÃ³n y el resultado, para detectar cualquier fallo de
        // negociaciÃ³n en la prÃ³xima captura de logcat.
        Log.d(
            TAG,
            "languageAvailability($lang,$country) â $result " +
                "(iso3=$l3-$c3 Â iso2=$l2-$c2 Â catÃ¡logo=${sets.fullIso3.size})"
        )
        return result
    }

    /** El contrato del motor devuelve cÃ³digos ISO3 ("spa", "MEX", variante). */
    override fun onGetLanguage(): Array<String> {
        val cur = currentLanguage
        // IMPORTANTE: usar normLang/normCountry (que aceptan ISO2 e ISO3) en
        // lugar de forLanguageTag. forLanguageTag("spa-MEX") descarta "MEX"
        // porque en BCP-47 una regiÃ³n alfa-3 es invÃ¡lida, y perderÃ©amos el
        // paÃ©s (devolverÃ©a ["spa","",""]).
        val l3 = normLang(cur.getOrElse(0) { "" })        // "spa"
        val c3 = normCountry(cur.getOrElse(1) { "" })     // "mex"
        val result = arrayOf(
            l3,
            c3.uppercase(Locale.ROOT),   // "MEX" â mismo caso que Locale.isO3Country
            cur.getOrElse(2) { "" }
        )
        Log.d(TAG, "onGetLanguage â ${result.joinToString(",")}")
        return result
    }

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int {
        val code = languageAvailability(lang, country)
        // DiagnÃ³stico: permite ver en logcat quÃ© consulta hace la sonda de
        // Ajustes y quÃ© respondemos (para depurar los controles deshabilitados).
        Log.d(TAG, "onIsLanguageAvailable($lang,$country,$variant) â $code")
        return code
    }

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int {
        val code = languageAvailability(lang, country)
        if (code >= TextToSpeech.LANG_AVAILABLE) {
            currentLanguage = arrayOf(lang, country, variant)
        }
        Log.d(TAG, "onLoadLanguage($lang,$country,$variant) â $code")
        return code
    }

    /**
     * ResoluciÃ³n por defecto de voz para un locale â la ÃNICA fuente de
     * verdad que comparten onGetDefaultVoiceNameFor() y resolveVoice(). Si
     * divergieran, el sistema prometerÃ©a una voz (vÃ©a onGetDefaultVoiceNameFor)
     * y la sÃ©ntesis usarÃ©a otra cuando voiceName llega vacÃ©o.
     *
     * MODELO 1 â LA VOZ DE LA APP MANDA (verificado en AOSP: desde API 21,
     * TextToSpeech.setLanguage se implementa llamando a setVoice con la voz
     * que devuelva onGetDefaultVoiceNameFor; el control del mapeo
     * localeâ¬¤voz lo tiene el motor, NO el sistema):
     *  - Con el modo unificado activo (por defecto), si el idioma pedido
     *    coincide con el de la voz configurada en la app, se devuelve ESA voz
     *    para cualquier variante del idioma (es-MX, es-PE, es-419, en-USâ¬¤).
     *    AsÃ© la selecciÃ³n de la app se aplica a Play Books, Neo Reader y al
     *    sistema entero para su idioma. Resuelve tambiÃ©n es-419 (no hay voz
     *    Edge para ese locale; cae limpio en la voz configurada).
     *  - Con el modo unificado apagado, se restaura la prioridad por paÃ©s de
     *    la v18 (cada variante con su voz regional) vÃ©a voiceForLanguage.
     *  - Para OTROS idiomas (distintos del configurado), siempre la voz del
     *    catÃ¡logo (con prioridad de paÃ©s si existe).
     */
    private fun resolveDefaultVoiceFor(
        lang: String,
        country: String,
        snap: SettingsStore.Snapshot
    ): String? {
        val requestedLang = normLang(lang)
        if (requestedLang.isEmpty()) return null
        val configuredName = snap.voice
        val configuredLang = normLang(configuredName.substringBefore("-"))

        if (snap.unifiedVoiceMode) {
            // La voz de la app manda para su idioma.
            if (configuredLang == requestedLang) return validatedVoice(configuredName)
            // Libro en espaÃ±ol pero la voz configurada es de OTRO idioma: usar
            // la Ãltima voz de espaÃ±ol elegida en la app (reconocible, p. ej.
            // la mexicana), no una variante arbitraria del catÃ¡logo que suene
            // a un espaÃ±ol que el usuario no reconoce (era la queja reportada).
            if (requestedLang == "spa") {
                return validatedVoice(
                    snap.lastSpanishVoice.ifBlank { EdgeProtocolConstants.DEFAULT_VOICE }
                )
            }
        }
        return voiceForLanguage(lang, country)
    }

    /**
     * PIEZA CLAVE para la integraciÃ³n con el sistema: cuando una app llama a
     * setLanguage() sin especificar voz, TextToSpeechService consulta este
     * mÃ©todo; si devuelve null, setLanguage FALLA y la app hace fallback a
     * otro motor. Delega en resolveDefaultVoiceFor (fuente de verdad Ãnica).
     */
    override fun onGetDefaultVoiceNameFor(
        lang: String,
        country: String,
        variant: String
    ): String? {
        if (normLang(lang).isEmpty()) {
            Log.d(TAG, "onGetDefaultVoiceNameFor($lang,$country,$variant) â null (idioma vacÃ©o)")
            return null
        }
        val snap = settings?.snapshotBlocking()
            ?: return voiceForLanguage(lang, country)
        val resolved = resolveDefaultVoiceFor(lang, country, snap)
        Log.d(TAG, "onGetDefaultVoiceNameFor($lang,$country,$variant) â ${resolved ?: "null"} (unificado=${snap.unifiedVoiceMode})")
        return resolved
    }

    override fun onGetVoices(): List<Voice> {
        val configuredName = runCatching { settings?.snapshotBlocking()?.voice }
            .getOrNull() ?: EdgeProtocolConstants.DEFAULT_VOICE
        val catalogVoices = runCatching { catalog?.cached() }.getOrNull().orEmpty()
        // TODO el catÃ¡logo, con los locales reales: el framework busca aquÃ©
        // cualquier voz que un cliente pida (findVoice). Sin la voz pedida en
        // esta lista, setVoice() se descarta en silencio.
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
        Log.d(
            TAG,
            "onGetVoices: exponiendo ${list.size} voces (configurada=$configuredName)"
        )
        return list.map { edgeToAndroid(it, isDefault = it.shortName == configuredName) }
    }

    /**
     * Construye un [Voice] Android. La voz configurada se marca como
     * predeterminada vÃ©a el constructor oculto de 7 parÃ¡metros (isDefault);
     * si la reflexiÃ³n no estÃ¡ disponible, se degrada al constructor pÃºblico
     * de 6 y la predeterminada se resuelve igualmente por configuraciÃ³n.
     *
     * SOBRE requiresNetwork = false: Edge es un TTS de nube y tÃ©cnicamente
     * necesita red, pero marcar `true` hace que los clientes que filtran
     * voces "usables sin conexiÃ³n" âen particular Google Play Books en su
     * modo "TTS offline/local"â¬¤â¬¤ descarten TODAS nuestras voces y caigan en
     * Google TTS. Marcamos `false` para que el motor sea seleccionable por
     * esos clientes; la sÃ©ntesis seguirÃ¡ requiriendo red y, si no la hay,
     * fallarÃ¡ con un error claro.
     */
    private fun edgeToAndroid(v: EdgeVoice, isDefault: Boolean): Voice {
        // forLanguageTag es tolerante: un locale mal formado del catÃ¡logo no
        // lanza IllformedLocaleException, devuelve un locale "und" inocuo.
        val locale = Locale.forLanguageTag(v.locale.ifBlank { "es-MX" })
        return runCatching {
            Voice::class.java.getConstructor(
                String::class.java,
                Locale::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Set::class.java,
                Boolean::class.javaPrimitiveType
            ).newInstance(
                v.shortName, locale,
                Voice.QUALITY_VERY_HIGH, Voice.LATENCY_HIGH,
                /* requiresNetwork = */ false,
                /* features = */ emptySet<String>(),
                isDefault
            )
        }.getOrDefault(
            Voice(
                v.shortName, locale,
                Voice.QUALITY_VERY_HIGH, Voice.LATENCY_HIGH,
                /* requiresNetwork = */ false,
                /* features = */ emptySet<String>()
            )
        )
    }

    /**
     * Voz a usar en la sÃ©ntesis:
     *  1. Voz explÃ©cita del cliente ([SynthesisRequest.getVoiceName]).
     *  2. Idioma POR PETICIÃ³N (request.language/country): es la fuente mÃ¡s
     *     fiable â un libro en inglÃ©s pide "en" aunque la sesiÃ³n haya
     *     cargado espaÃ±ol antes. Para espaÃ±ol se respeta la voz configurada;
     *     para OTRO idioma, la voz expuesta de ese idioma.
     *  3. Idioma cargado en la sesiÃ³n (onLoadLanguage).
     *  4. Voz predeterminada configurada (Dalia).
     */
    private fun resolveVoice(request: SynthesisRequest, snap: SettingsStore.Snapshot): String {
        // 1) Voz explÃ©cita del cliente (cualquier voz del catÃ¡logo). Tiene la
        //    mÃ¡xima prioridad: si el usuario eligiÃ³ una voz concreta en
        //    Ajustes (o la app llamÃ³ setVoice), ese voiceName llega aquÃ© y
        //    gana. Se valida contra el catÃ¡logo: si Microsoft la retirÃ³, se
        //    usa un respaldo del mismo idioma en lugar de enviar un nombre
        //    inexistente a Edge.
        val name = request.voiceName?.trim().orEmpty()
        if (name.isNotEmpty()) return validatedVoice(name)

        // 2) Idioma por peticiÃ³n (request.language/country): es la fuente mÃ¡s
        //    fiable. Delega en resolveDefaultVoiceFor para que la resoluciÃ³n
        //    sea idÃ©ntica a la que promete onGetDefaultVoiceNameFor (modo
        //    unificado: la voz de la app para su idioma; si no, catÃ¡logo).
        val lang = normLang(request.language.orEmpty())
        if (lang.isNotEmpty()) {
            resolveDefaultVoiceFor(lang, request.country.orEmpty(), snap)?.let { return it }
        }

        // 3) Idioma cargado en la sesiÃ³n (onLoadLanguage).
        val loaded = currentLanguage
        val l3 = normLang(loaded.getOrElse(0) { "" })
        if (l3.isNotEmpty()) {
            resolveDefaultVoiceFor(l3, loaded.getOrElse(1) { "" }, snap)?.let { return it }
        }

        // 4) Voz predeterminada configurada.
        return validatedVoice(snap.voice)
    }

    // ââ¬¤ Velocidad y tono (ajustes de la app + sliders de Android) ââ¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤
    // request.speechRate y request.pitch son enteros donde 100 = 1.0x (los
    // envÃ©an los sliders de Ajustes â Texto a voz). Se combinan de forma
    // aditiva con el ajuste propio de la app y se limitan al rango que Edge
    // acepta. Los valores por defecto (velocidad +0%, tono +0Hz) coinciden
    // EXACTAMENTE con los del navegador Edge / edge-tts, de modo que la
    // prosodia natural (incluidas las pausas en comas y puntos, que en las
    // voces neuronales de Edge son algo mÃ¡s largas que en Google TTS) se
    // reproduce igual que en el navegador.

    /**
     * % de velocidad para Edge: ajuste de la app + slider de Android. El
     * slider del sistema llega hasta 2.0x (speechRate=200 â +100), asÃ© que el
     * lÃ©mite superior es +100 para honrar esa velocidad real (Edge la acepta);
     * el inferior se mantiene en -50 (media velocidad, como el navegador).
     */
    private fun effectiveRatePercent(snap: SettingsStore.Snapshot, request: SynthesisRequest): Int =
        (snap.ratePercent + (request.speechRate - 100)).coerceIn(-50, 100)

    /**
     * Tono para Edge en Hz: ajuste de la app + slider de Android. Se usa Hz
     * (signedHertz), NO %, porque es la unidad que el motor de referencia
     * (edge-tts) envÃ©a a este endpoint y la que se sabe que acepta: el
     * atributo pitch de Edge espera `+XHz` (o semitonos), y un valor en %
     * podrÃ©a ser ignorado o rechazado. El multiplicador del slider
     * (request.pitch, 100 = 1.0x) se traduce a un desplazamiento en Hz. Se
     * acota a Â50Hz, el rango cÃ³modo que Edge maneja bien sin distorsionar.
     */
    private fun effectivePitchHz(snap: SettingsStore.Snapshot, request: SynthesisRequest): Int =
        (snap.pitchHz + (request.pitch - 100)).coerceIn(-50, 50)

    // ââ¬¤ SÃ©ntesis ââ¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤â¬¤

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        // Metadatos de la peticiÃ³n para depurar la integraciÃ³n con el sistema
        // (longitud del texto, NO el contenido; nunca datos sensibles).
        Log.d(
            TAG,
            "onSynthesizeText: chars=${request.charSequenceText?.length ?: 0} " +
                "lang=${request.language}/${request.country}/${request.variant} " +
                "voice=${request.voiceName} rate=${request.speechRate} pitch=${request.pitch}"
        )
        // Una sola llamada terminal (done XOR error) garantizada a este nivel:
        // ni la red ni los errores internos pueden escapar del servicio.
        val guard = TerminalGuard()

        synchronized(synthesisLock) {
            runCatching { synthesizeInternal(request, callback, guard) }
                .onFailure { t ->
                    Log.e(TAG, "fallo interno", t)
                    guard.error(callback, ErrorMapper.spanish(t)) { msg ->
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
            guard.done(callback)
            return
        }

        val snap = settings?.snapshotBlocking()
            ?: return guard.error(callback, "No se pudo leer la configuraciÃ³n local.")

        val segments = runCatching {
            TextSegmenter.segment(text) { stopRequested }
        }.getOrElse { return guard.error(callback, "No se pudo segmentar el texto.") }

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
        // Velocidad y tono EFECTIVOS: combinan el ajuste de la app con los
        // sliders de Ajustes de Android (request.speechRate / request.pitch,
        // donde 100 = 1.0x). Antes se ignoraban estos ÃÂºltimos y mover los
        // sliders del sistema no tenÃ©a efecto (Punto 4).
        val rate = SsmlBuilder.signedPercent(effectiveRatePercent(snap, request))
        val pitch = SsmlBuilder.signedHertz(effectivePitchHz(snap, request))
        val started = AtomicBoolean(false)

        fun ensureStarted(rate: Int): Boolean =
            if (started.compareAndSet(false, true)) {
                runCatching {
                    callback.start(
                        rate,
                        AudioFormat.ENCODING_PCM_16BIT,
                        // NÃºmero de canales (1), no la mÃ¡scara CHANNEL_OUT_MONO.
                        EdgeProtocolConstants.CHANNEL_COUNT_MONO
                    )
                }.isSuccess
            } else true

        for (segment in segments) {
            if (stopRequested || guard.isFired) return

            when (val outcome = synthesizeSegment(segment, snap, voice, rate, pitch)) {
                is SegmentOutcome.Ok -> {
                    if (!ensureStarted(outcome.sampleRateHz)) {
                        guard.error(callback, "No se pudo iniciar el canal de audio.")
                        return
                    }
                    if (!deliver(outcome.pcm, callback)) return
                }

                is SegmentOutcome.Failed -> {
                    guard.error(callback, outcome.message) { msg ->
                        runCatching { settings?.setLastError(msg) }
                    }
                    return
                }

                SegmentOutcome.Cancelled -> return
            }
        }

        if (!guard.isFired) guard.done(callback)
    }

    private sealed class SegmentOutcome {
        class Ok(val pcm: ByteArray, val sampleRateHz: Int) : SegmentOutcome()
        class Failed(val message: String) : SegmentOutcome()
        object Cancelled : SegmentOutcome()
    }

    /**
     * SÃ©ntesis por segmento con el ÃNICO formato que usa el cliente de
     * referencia: audio-24khz-48kbitrate-mono-mp3. El servidor lo produce sin
     * problemas; se decodifica a PCM 16-bit con MediaCodec ANTES de entregarlo
     * a SynthesisCallback (que exige PCM crudo). No hay reintento con otros
     * formatos: el RIFF/PCM estÃ¡ verificado que NO produce audio en este
     * endpoint, asÃ© que reintentar con ÃÂ©l solo gastarÃ©a peticiones.
     *
     * La cachÃ© guarda SIEMPRE el PCM final decodificado a 24 kHz.
     */
    private fun synthesizeSegment(
        segment: String,
        snap: SettingsStore.Snapshot,
        voice: String,
        rate: String,
        pitch: String
    ): SegmentOutcome {
        val cacheKey = cache?.key(
            segment, voice, snap.locale, rate, pitch,
            EdgeProtocolConstants.PROTOCOL_VERSION
        )

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

    /** Un intento de sÃ©ntesis con un formato (y decodificador) concretos. */
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
        // El proveedor se crea por intento: asÃ© siempre usa el User-Agent,
        // el Origin y las URLs vigentes (editables desde la app sin
        // recompilar). El diagnÃ³stico se persiste para depurar desde la app.
        val prov: TtsProvider = EdgeProtocolClient(
            client,
            wsBaseUrl = snap.wsUrl,
            userAgent = snap.userAgent,
            origin = snap.origin,
            outputFormat = outputFormat,
            onDiagnostic = { d -> runCatching { settings?.setHandshakeDebug(d) } }
        )

        val handle = prov.synthesize(
            text = segment,
            voice = voice,
            locale = snap.locale,
            rate = rate,
            pitch = pitch,
            onPcmChunk = { chunk -> synchronized(buffer) { buffer.write(chunk) } },
            onComplete = { latch.countDown() },
            onError = { t -> failure = t; latch.countDown() }
        )

        active = handle
        val finished = latch.await(
            EdgeProtocolConstants.SYNTHESIS_TIMEOUT_MS + 15_000L,
            TimeUnit.MILLISECONDS
        )
        active = null

        if (!finished) {
            handle.cancel()
            return SegmentOutcome.Failed(ErrorMapper.spanish(TimeoutExceptionShim()))
        }

        val t = failure
        if (t is SynthesisCancelledException) return SegmentOutcome.Cancelled
        if (t != null) {
            return SegmentOutcome.Failed(ErrorMapper.spanish(t))
        }

        var pcm = synchronized(buffer) { buffer.toByteArray() }
        if (pcm.isEmpty()) {
            return SegmentOutcome.Failed("El proveedor no devolviÃ³ audio (respuesta vacÃ©a).")
        }

        var sampleRate = EdgeProtocolConstants.SAMPLE_RATE_HZ

        if (decoder != null) {
            // Ruta MP3: decodificar a PCM 16-bit ANTES de tocar el callback.
            val decoded = runCatching { decoder.decode(pcm) }
                .getOrElse { return SegmentOutcome.Failed(ErrorMapper.spanish(it)) }
            pcm = decoded.pcm
            sampleRate = decoded.sampleRateHz
        } else if (AudioFrameParser.detectFormat(pcm) == AudioFrameParser.PayloadFormat.COMPRESSED) {
            // El servidor ignorÃ³ la peticiÃ³n de PCM y enviÃ³ comprimido de
            // todas formas (MP3 en la prÃ¡ctica): se decodifica igualmente.
            val decoded = runCatching { mp3Decoder.decode(pcm) }
                .getOrElse {
                    return SegmentOutcome.Failed(
                        ErrorMapper.spanish(UnsupportedAudioFormatException("datos comprimidos"))
                    )
                }
            pcm = decoded.pcm
            sampleRate = decoded.sampleRateHz
        }

        // La cachÃ© guarda el PCM final (siempre 24 kHz con los formatos de
        // Edge); nunca el audio comprimido ni el texto en claro como nombre.
        if (snap.cacheEnabled && cacheKey != null && sampleRate == EdgeProtocolConstants.SAMPLE_RATE_HZ) {
            runCatching { cache?.write(cacheKey, pcm) }
        }
        return SegmentOutcome.Ok(pcm, sampleRate)
    }

    /**
     * EnvÃ©a el PCM en bloques limitados. La cancelaciÃ³n se detecta en cada
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
        active?.cancel()
    }

    private fun TimeoutExceptionShim(): Throwable =
        java.util.concurrent.TimeoutException(
            "La sÃ©ntesis superÃ³ el tiempo mÃ¡ximo permitido"
        )

    companion object {
        private const val TAG = "EdgeTtsService"
    }
}
