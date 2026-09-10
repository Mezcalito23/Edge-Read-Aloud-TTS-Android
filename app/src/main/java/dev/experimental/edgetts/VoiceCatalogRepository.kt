package dev.experimental.edgetts

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * Descarga y valida el catálogo JSON de voces. Escritura atómica (tmp+rename)
 * y memo en memoria: un JSON inválido nunca reemplaza un catálogo bueno.
 */
class VoiceCatalogRepository(
    private val client: OkHttpClient,
    cacheDir: File,
    private val drm: EdgeDrm = SharedProtocol.drm,
    private val voicesListUrl: String = EdgeProtocolConstants.VOICES_LIST_URL,
    private val snapshotProvider: () -> SettingsStore.Snapshot? = { null }
) {

    data class CatalogResult(
        val voices: List<EdgeVoice>,
        val fromNetwork: Boolean,
        val error: String?
    )

    data class CatalogSnapshot(
        val voices: List<EdgeVoice>,
        val loadedAt: Long
    )

    private val cacheFile: File = cacheFile(cacheDir)

    @Volatile
    private var memo: CatalogSnapshot? = null

    fun refresh(): CatalogResult = try {
        val snap = snapshotProvider()
        val ua = snap?.userAgent ?: EdgeProtocolConstants.DEFAULT_USER_AGENT
        val origin = snap?.origin ?: EdgeProtocolConstants.DEFAULT_ORIGIN
        val baseUrl = snap?.voicesUrl ?: voicesListUrl
        val (voices, json) = fetchValidated(baseUrl, ua, origin)
        commitAtomic(json)
        CatalogResult(voices, fromNetwork = true, error = null)
    } catch (t: Throwable) {
        CatalogResult(
            voices = cached().ifEmpty { FALLBACK },
            fromNetwork = false,
            error = ErrorMapper.spanish(t)
        )
    }

    fun cached(): List<EdgeVoice> {
        memo?.let { return it.voices }
        return runCatching {
            if (!cacheFile.exists()) emptyList()
            else {
                val voices = parseCatalog(cacheFile.readText())
                if (voices.isNotEmpty()) {
                    memo = CatalogSnapshot(voices, System.currentTimeMillis())
                }
                voices
            }
        }.getOrDefault(emptyList())
    }

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

    fun mexican(voices: List<EdgeVoice>): List<EdgeVoice> =
        voices.filter { it.locale.equals(EdgeProtocolConstants.DEFAULT_LOCALE, ignoreCase = true) }

    private data class RawResponse(val code: Int, val dateHeader: String?, val body: String?)

    private fun fetchValidated(
        baseUrl: String,
        userAgent: String,
        origin: String
    ): Pair<List<EdgeVoice>, String> {
        val first = executeOnce(baseUrl, userAgent, origin)
        if (first.code == 403) {
            if (drm.tryUpdateSkewFromDate(first.dateHeader)) {
                val second = executeOnce(baseUrl, userAgent, origin)
                return readBody(second)
            }
            throw ProviderHttpException(403, "catálogo de voces")
        }
        return readBody(first)
    }

    private fun executeOnce(baseUrl: String, userAgent: String, origin: String): RawResponse {
        val muid = drm.newMuid()
        val url = drm.voicesListUrl(baseUrl)
        val headers = drm.handshakeHeaders(userAgent, origin, muid)
        val request = Request.Builder().url(url).apply {
            for ((k, v) in headers) header(k, v)
        }.build()
        client.newCall(request).execute().use { response ->
            return RawResponse(response.code, response.header("Date"), response.body?.string())
        }
    }

    private fun readBody(raw: RawResponse): Pair<List<EdgeVoice>, String> {
        if (!raw.code.toHttpSuccess()) {
            throw ProviderHttpException(raw.code, "catálogo de voces")
        }
        val json = raw.body?.takeIf { it.isNotBlank() }
            ?: throw IOException("Respuesta sin cuerpo")
        val voices = parseCatalog(json)
        if (voices.isEmpty()) throw IOException("Catálogo vacío o con formato desconocido")
        return voices to json
    }

    private fun commitAtomic(json: String) {
        val dir = cacheFile.parentFile ?: throw IOException("Sin directorio de caché")
        dir.mkdirs()
        val tmp = File(dir, cacheFile.name + ".tmp")
        try {
            tmp.writeText(json)
            val parsed = parseCatalog(tmp.readText())
            if (parsed.isEmpty()) throw IOException("Catálogo temporal inválido")
            if (cacheFile.exists() && !cacheFile.delete() && cacheFile.exists()) {
                throw IOException("No se pudo reemplazar el catálogo")
            }
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                if (!tmp.delete() && tmp.exists()) tmp.deleteOnExit()
                if (!cacheFile.exists()) throw IOException("No se pudo reemplazar el catálogo")
            }
            memo = CatalogSnapshot(parsed, System.currentTimeMillis())
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }

    private fun Int.toHttpSuccess(): Boolean = this in 200..299

    companion object {
        const val CATALOG_FILENAME: String = "voice_catalog.json"

        fun cacheFile(cacheDir: File): File = File(cacheDir, CATALOG_FILENAME)

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
