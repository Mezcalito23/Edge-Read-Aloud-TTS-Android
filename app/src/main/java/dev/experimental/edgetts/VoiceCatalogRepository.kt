package dev.experimental.edgetts

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Descarga y analiza el catálogo JSON de voces de Edge con org.json.
 * Según V-3.1.md Sección 7: memoización volátil, escritura atómica, reintento 403.
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

    // Constante compartida según V-3.1.md Sección 8
    companion object {
        const val CATALOG_FILENAME = "voice_catalog.json"
        
        val FALLBACK: List<EdgeVoice> = listOf(
            EdgeVoice(
                shortName = EdgeProtocolConstants.DEFAULT_VOICE,
                locale = EdgeProtocolConstants.DEFAULT_LOCALE,
                gender = "Female",
                displayName = "Dalia · Español (México) [respaldo local]"
            )
        )
    }

    private val cacheFile = File(cacheDir, CATALOG_FILENAME)

    /** Memoización volátil del catálogo válido (V-3.1.md Sección 7). */
    @Volatile
    private var memo: CatalogSnapshot? = null

    data class CatalogSnapshot(
        val voices: List<EdgeVoice>,
        val timestamp: Long
    )

    /** Bloqueante: llamar siempre fuera del hilo principal. */
    fun refresh(): CatalogResult {
        var retryCount = 0
        var lastError: Throwable? = null

        while (retryCount <= 1) {  // Máximo 1 reintento por 403
            try {
                val request = Request.Builder()
                    .url(voicesListUrl)
                    .header("User-Agent", EdgeProtocolConstants.DEFAULT_USER_AGENT)
                    .build()
                    
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Reintento único ante 403 (ajuste de reloj externo)
                        if (response.code == 403 && retryCount == 0) {
                            retryCount++
                            lastError = ProviderHttpException(response.code, "catálogo de voces")
                            continue  // Reintentar una vez
                        }
                        throw ProviderHttpException(response.code, "catálogo de voces")
                    }
                    
                    val json = response.body?.string()
                        ?: throw IOException("Respuesta sin cuerpo")
                    
                    val voices = parseCatalog(json)
                    if (voices.isEmpty()) {
                        throw IOException("Catálogo vacío o con formato desconocido")
                    }
                    
                    // Escritura atómica: temporal → rename (V-3.1.md Sección 7)
                    writeAtomically(json)
                    
                    // Actualizar memo volátil
                    memo = CatalogSnapshot(voices, System.currentTimeMillis())
                    
                    return CatalogResult(voices, fromNetwork = true, error = null)
                }
            } catch (t: Throwable) {
                lastError = t
                if (retryCount >= 1) break  // Ya reintentamos
                if (t is ProviderHttpException && t.code == 403) {
                    retryCount++
                    continue
                }
                break
            }
        }

        // Falló todo: retornar caché o fallback
        return CatalogResult(
            voices = cached().ifEmpty { FALLBACK },
            fromNetwork = false,
            error = ErrorMapper.spanish(lastError ?: IOException("Desconocido"))
        )
    }

    /** Escritura atómica mediante archivo temporal + rename (V-3.1.md Sección 7). */
    private fun writeAtomically(json: String) {
        val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        tempFile.parentFile?.mkdirs()
        tempFile.writeText(json)
        tempFile.renameTo(cacheFile)
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
}
