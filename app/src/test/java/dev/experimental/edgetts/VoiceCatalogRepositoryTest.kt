package dev.experimental.edgetts

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class VoiceCatalogRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var state: ProtocolState
    private lateinit var drm: EdgeDrm

    private val validJson = """
        [
          {
            "ShortName": "es-MX-DaliaNeural",
            "Gender": "Female",
            "Locale": "es-MX",
            "FriendlyName": "Microsoft Dalia Online (Natural) - Spanish (Mexico)"
          }
        ]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = File(System.getProperty("java.io.tmpdir"), "edge-catalog-${System.nanoTime()}").also {
            it.mkdirs()
        }
        state = ProtocolState()
        drm = EdgeDrm(state)
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    private fun repo(): VoiceCatalogRepository = VoiceCatalogRepository(
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        cacheDir = cacheDir,
        drm = drm,
        voicesListUrl = server.url("/voices/list").toString()
    )

    @Test
    fun successfulFetchWritesAtomicCatalogAndMemo() {
        server.enqueue(MockResponse().setBody(validJson))
        val result = repo().refresh()
        assertTrue(result.fromNetwork)
        assertEquals(1, result.voices.size)
        assertEquals("es-MX-DaliaNeural", result.voices[0].shortName)
        val file = VoiceCatalogRepository.cacheFile(cacheDir)
        assertTrue(file.exists())
        assertFalse(File(cacheDir, VoiceCatalogRepository.CATALOG_FILENAME + ".tmp").exists())
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("Sec-MS-GEC="))
        assertTrue(recorded.path!!.contains("Sec-MS-GEC-Version="))
        assertTrue(recorded.getHeader("Cookie")!!.startsWith("muid="))
    }

    @Test
    fun invalidJsonDoesNotReplaceValidCatalog() {
        val file = VoiceCatalogRepository.cacheFile(cacheDir)
        file.writeText(validJson)
        val repository = repo()
        assertEquals("es-MX-DaliaNeural", repository.cached().single().shortName)

        server.enqueue(MockResponse().setBody("esto no es json"))
        val result = repository.refresh()
        assertFalse(result.fromNetwork)
        assertEquals("es-MX-DaliaNeural", repository.cached().single().shortName)
        assertEquals(validJson, file.readText())
    }

    @Test
    fun emptyBodyDoesNotReplaceValidCatalog() {
        VoiceCatalogRepository.cacheFile(cacheDir).writeText(validJson)
        val repository = repo()
        repository.cached()
        server.enqueue(MockResponse().setBody(""))
        val result = repository.refresh()
        assertFalse(result.fromNetwork)
        assertEquals(1, repository.cached().size)
    }

    @Test
    fun http403WithFreshDateRetriesOnce() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Date", rfc2616Now())
        )
        server.enqueue(MockResponse().setBody(validJson))
        val result = repo().refresh()
        assertTrue(result.fromNetwork)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun http403WithoutDateDoesNotRetry() {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = repo().refresh()
        assertFalse(result.fromNetwork)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun http403AbsurdDateDoesNotRetry() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Date", "Tue, 01 Jan 1980 00:00:00 GMT")
        )
        val result = repo().refresh()
        assertFalse(result.fromNetwork)
        assertEquals(1, server.requestCount)
        assertEquals(0L, state.skewSeconds())
    }

    @Test
    fun http429DoesNotRetry() {
        server.enqueue(MockResponse().setResponseCode(429))
        val result = repo().refresh()
        assertFalse(result.fromNetwork)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun http500DoesNotRetry() {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = repo().refresh()
        assertFalse(result.fromNetwork)
        assertEquals(1, server.requestCount)
    }

    private fun rfc2616Now(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }
}
