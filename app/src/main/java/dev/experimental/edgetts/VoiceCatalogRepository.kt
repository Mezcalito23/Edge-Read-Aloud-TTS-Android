package dev.experimental.edgetts

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * Descarga y analiza el catálogo JSON de voces de Edge con org.json.
 * Si la red falla, se usa la copia local y, en última instancia, una entrada
 * de respaldo para es-MX-DaliaNeural: la app nunca queda sin voces.
 */
class VoiceCatalogRepository(
    private val client: OkHttpClient,
    cacheDir: File,
    private val voicesListUrl: String = EdgeProtocolConstants.VOICES_LIST_URL
) {

    data class CatalogResult(
        val voices: List<EdgeVoice>,
        val fromNetwork: Boolean,
        val error: String?
    )

    private val cacheFile = File(cacheDir, "voice_catalog.json")

    /** Bloqueante: llamar siempre fuera del hilo principal. */
    fun refresh(): CatalogResult = try {
        val request = Request.Builder()
            .url(voicesListUrl)
            .header("User-Agent", EdgeProtocolConstants.DEFAULT_USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ProviderHttpException(response.code, "catálogo de voces")
            }
            val json = response.body?.string()
                ?: throw IOException("Respuesta sin cuerpo")
            val voices = parseCatalog(json)
            if (voices.isEmpty()) throw IOException("Catálogo vacío o con formato desconocido")
            runCatching {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(json)
            }
            CatalogResult(voices, fromNetwork = true, error = null)
        }
    } catch (t: Throwable) {
        CatalogResult(
            voices = cached().ifEmpty { FALLBACK },
            fromNetwork = false,
            error = ErrorMapper.spanish(t)
        )
    }

    /** Copia local (última descarga válida) o lista vacía. No lanza. */
    fun cached(): List<EdgeVoice> = runCatching {
        if (cacheFile.exists()) parseCatalog(cacheFile.readText()) else emptyList()
    }.getOrDefault(emptyList())

    /**
     * Visible para pruebas. Busca ShortName, Locale, Gender, FriendlyName y,
     * si existen, StyleList y RoleList.
     */
    fun parseCatalog(json: String): List<EdgeVoice> {
        val array = JSONArray(json)
        val out = ArrayList<EdgeVoice>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val shortName = o.optString("ShortName").takeIf { it.isNotBlank() } ?: continue
            out += EdgeVoice(
                shortName = shortName,
                locale = o.optString("Locale"),
                gender = o.optString("Gender").takeIf { it.isNotBlank() },
                displayName = o.optString("FriendlyName").ifBlank { shortName },
                styles = o.optJSONArray("StyleList")?.toStringList().orEmpty(),
                roles = o.optJSONArray("RoleList")?.toStringList().orEmpty()
            )
        }
        return out
    }

    /** Filtro inicial pedido por la app: voces es-MX. */
    fun mexican(voices: List<EdgeVoice>): List<EdgeVoice> =
        voices.filter { it.locale.equals(EdgeProtocolConstants.DEFAULT_LOCALE, ignoreCase = true) }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }

    companion object {
        val FALLBACK: List<EdgeVoice> = listOf(
            EdgeVoice(
                shortName = EdgeProtocolConstants.DEFAULT_VOICE,
                locale = EdgeProtocolConstants.DEFAULT_LOCALE,
                gender = "Female",
                displayName = "Dalia · Español (México) [respaldo local]"
            )
        )
    }
}
