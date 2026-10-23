package dev.experimental.edgetts

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpdateClientTest {

    private val server = MockWebServer().also { it.start() }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(readTimeoutMs: Long = 2_000): UpdateClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return UpdateClient(
            http = http,
            userAgent = "EdgeReadAloudTTS/test (+https://github.com/Mezcalito23/Edge-Read-Aloud-TTS-Android/)",
            latestUrl = server.url("/repos/latest").toString(),
        )
    }

    @Test
    fun serverErrorIsNotUpToDate() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
        val result = client().check(26)
        assertTrue(result is UpdateResult.NetworkFailed)
        assertEquals("EdgeReadAloudTTS/test (+https://github.com/Mezcalito23/Edge-Read-Aloud-TTS-Android/)",
            server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun missingReleaseIsNotUpToDate() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        assertTrue(client().check(26) is UpdateResult.NoStableRelease)
    }

    @Test
    fun rateLimitIsNotUpToDate() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("X-RateLimit-Remaining", "0")
                .setBody("""{"message":"API rate limit exceeded"}""")
        )
        assertTrue(client().check(26) is UpdateResult.RateLimited)
    }

    @Test
    fun releaseWithoutManifestIsNotUpToDate() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"html_url":"https://github.com/Mezcalito23/Edge-Read-Aloud-TTS-Android/releases/tag/v1.0.10",
                 "draft":false,"prerelease":false,
                 "assets":[{"name":"app.apk","browser_download_url":"https://github.com/x/app.apk"}]}
                """.trimIndent()
            )
        )
        assertTrue(client().check(26) is UpdateResult.CannotVerify)
    }

    @Test
    fun timeoutIsNotUpToDate() {
        server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"))
        assertTrue(client(readTimeoutMs = 200).check(26) is UpdateResult.TimedOut)
    }
}
