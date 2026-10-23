package dev.experimental.edgetts

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Consulta manual de la API de releases. No se llama al abrir la app ni
 * desde el servicio TTS.
 */
class UpdateClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val latestUrl: String = UpdateCheck.LATEST_API,
) {

    fun check(installedVersionCode: Int): UpdateResult {
        val release = try {
            fetch(latestUrl)
        } catch (e: Exception) {
            return failure(e)
        }
        when (release.code) {
            404 -> return UpdateResult.NoStableRelease
            429 -> return UpdateResult.RateLimited
            403 -> {
                return if (release.rateLimited) {
                    UpdateResult.RateLimited
                } else {
                    UpdateResult.NetworkFailed
                }
            }
        }
        if (release.code != 200) return UpdateResult.NetworkFailed
        val page = parseRelease(release.body)?.pageUrl?.takeIf { it.isNotBlank() }
            ?: UpdateCheck.RELEASES_PAGE
        val manifestUrl = UpdateCheck.manifestDownloadUrl(release.body)
            ?: return UpdateResult.CannotVerify(page)
        val manifest = try {
            fetch(manifestUrl)
        } catch (e: Exception) {
            return failure(e)
        }
        if (manifest.code == 429 || (manifest.code == 403 && manifest.rateLimited)) {
            return UpdateResult.RateLimited
        }
        if (manifest.code != 200) {
            return UpdateResult.InvalidMetadata(page)
        }
        return UpdateCheck.interpret(installedVersionCode, release.body, manifest.body)
    }

    private fun fetch(url: String): Fetch {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { response ->
            val remaining = response.header("X-RateLimit-Remaining")
            val body = response.body?.string().orEmpty()
            val limited = remaining == "0" ||
                body.contains("rate limit", ignoreCase = true)
            return Fetch(response.code, limited, body)
        }
    }

    private fun failure(error: Exception): UpdateResult {
        return if (error is SocketTimeoutException || error is InterruptedIOException) {
            UpdateResult.TimedOut
        } else {
            UpdateResult.NetworkFailed
        }
    }

    private data class Fetch(val code: Int, val rateLimited: Boolean, val body: String)

    companion object {
        fun http(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
