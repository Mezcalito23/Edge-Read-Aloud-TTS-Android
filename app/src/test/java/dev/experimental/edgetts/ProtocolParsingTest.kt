package dev.experimental.edgetts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Parseo de catálogo, frames del protocolo, formatos de audio, clave de
 * caché·¡, token Sec-MS-GEC y mapeo de errores. Nada necesita red ni Android:
 * son pruebas de JVM (./gradlew test).
 */
class ProtocolParsingTest {

    // ── Cat\u00e1logo JSON ───────────────────────────────────────────────────────

    private val sampleCatalog = """
        [
          {
            "Name": "Microsoft Server Speech Text to Speech Voice (es-MX, DaliaNeural)",
            "ShortName": "es-MX-DaliaNeural",
            "Gender": "Female",
            "Locale": "es-MX",
            "FriendlyName": "Microsoft Dalia Online (Natural) - Spanish (Mexico)",
            "Status": "GA",
            "StyleList": ["casual", "formal"],
            "RoleList": []
          },
          {
            "ShortName": "en-US-AriaNeural",
            "Gender": "Female",
            "Locale": "en-US",
            "FriendlyName": "Microsoft Aria Online (Natural) - English (United States)"
          }
        ]
    """.trimIndent()

    private fun newCatalogRepo() = VoiceCatalogRepository(
        client = okhttp3.OkHttpClient(),
        cacheDir = java.io.File(System.getProperty("java.io.tmpdir"))
    )

    @Test
    fun catalogParsesKnownFields() {
        val voices = newCatalogRepo().parseCatalog(sampleCatalog)
        assertEquals(2, voices.size)

        val dalia = voices[0]
        assertEquals("es-MX-DaliaNeural", dalia.shortName)
        assertEquals("es-MX", dalia.locale)
        assertEquals("Female", dalia.gender)
        assertTrue(dalia.displayName.contains("Dalia"))
        assertEquals(listOf("casual", "formal"), dalia.styles)
    }

    @Test
    fun catalogFiltersMexicanVoices() {
        val repo = newCatalogRepo()
        val mexican = repo.mexican(repo.parseCatalog(sampleCatalog))
        assertEquals(1, mexican.size)
        assertEquals("es-MX-DaliaNeural", mexican[0].shortName)
    }

    @Test
    fun catalogWithMalformedJsonThrows() {
        try {
            newCatalogRepo().parseCatalog("esto no es json")
            fail("deb\u00eda lanzar")
        } catch (_: org.json.JSONException) {
            // Esperado: refresh() lo convierte en CatalogResult con error.
        }
    }

    // ── Frames del protocolo ────────────────────────────────────────────────

    @Test
    fun textFrameHeadersAreParsed() {
        val frame = "X-RequestId:abc123\r\nPath:turn.end\r\n\r\n"
        val headers = AudioFrameParser.parseTextFrameHeaders(frame)
        assertEquals("abc123", headers["X-RequestId"])
        assertEquals("turn.end", AudioFrameParser.pathOf(headers))
        assertTrue(AudioFrameParser.isTurnEnd(headers))
    }

    @Test
    fun binaryFrameWithLengthPrefixAndDoubleCrlf() {
        // Formato del wire real: [2 bytes longitud][cabeceras terminadas en
        // \r\n\r\n][audio]. Longitud 39 = cabeceras completas.
        val headers = "Path:audio\r\nContent-Type:audio/mpeg\r\n\r\n".toByteArray(Charsets.US_ASCII)
        assertEquals(39, headers.size)  // Corregido: 10+2+23+2+2 = 39
        val audio = byteArrayOf(1, 2, 3)
        val frame = byteArrayOf(0x00, 39) + headers + audio  // Corregido
        val parsed = AudioFrameParser.parseBinaryFrame(frame)
        assertEquals("audio", parsed.path)
        assertArrayEquals(audio, parsed.payload)
        assertTrue(AudioFrameParser.isAudio(parsed.headers))
    }

    @Test
    fun binaryFrameWithoutDoubleCrlfIsParsedByLengthPrefix() {
        // Los frames binarios terminan cada cabecera con UN solo \r\n (sin
        // doble). El parser debe usar el prefijo de longitud, no buscar
        // \r\n\r\n \u2014 buscarlo descartaba los frames y causaba "turn.end sin
        // audio" con decenas de frames recibidos.
        val headers = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray(Charsets.US_ASCII)
        assertEquals(37, headers.size)  // Corregido: 10+2+23+2 = 37
        val audio = byteArrayOf(9, 9, 9)
        val frame = byteArrayOf(0x00, 37) + headers + audio  // Corregido
        val parsed = AudioFrameParser.parseBinaryFrame(frame)
        assertEquals("audio", parsed.path)
        assertArrayEquals(audio, parsed.payload)
    }

    @Test
    fun binaryFramePrefixWithPrintableSecondByteDoesNotCorruptPathKey() {
        // REGRESI\u00d3N: el segundo byte del prefijo de longitud puede ser un
        // car\u00e1cter ASCII imprimible (aqu\u00ed 'P' = 0x50, con longitud real 80).
        // El parser no debe confundir ese byte con texto de cabeceras ni
        // corromper la clave "Path".
        val pad = "x".repeat(33)
        val headers = ("Path:audio\r\nContent-Type:audio/mpeg\r\nX-Pad:$pad\r\n\r\n")
            .toByteArray(Charsets.US_ASCII) // 80 bytes \u2192 prefijo 0x00 0x50
        assertEquals(80, headers.size)
        val audio = byteArrayOf(1, 2, 3)
        val frame = byteArrayOf(0x00, 'P'.code.toByte()) + headers + audio
        val parsed = AudioFrameParser.parseBinaryFrame(frame)
        assertEquals("audio", parsed.path)
        assertTrue(AudioFrameParser.isAudio(parsed.headers))
        assertArrayEquals(audio, parsed.payload)
    }

    @Test
    fun binaryFrameWithoutSeparatorTreatsAllAsPayload() {
        val raw = byteArrayOf(9, 9, 9)
        val parsed = AudioFrameParser.parseBinaryFrame(raw)
        assertArrayEquals(raw, parsed.payload)
    }

    @Test
    fun riffHeaderIsStrippedFromFirstAudioFrame() {
        val wav = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(36, 0, 0, 0))
            write("WAVE".toByteArray(Charsets.US_ASCII))
            write("fmt ".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(16, 0, 0, 0))
            write(ByteArray(16)) // fmt chunk
            write("data".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(4, 0, 0, 0))
            write(byteArrayOf(9, 9, 8, 8)) // PCM
        }.toByteArray()

        val pcm = AudioFrameParser.stripRiffHeader(wav)
        assertArrayEquals(byteArrayOf(9, 9, 8, 8), pcm)
    }

    // ── Detecci\u00f3n de formatos ───────────────────────────────────────────────

    @Test
    fun mp3PayloadsAreDetectedAsCompressed() {
        val id3 = "ID3".toByteArray(Charsets.US_ASCII) + ByteArray(60)
        assertEquals(
            AudioFrameParser.PayloadFormat.COMPRESSED,
            AudioFrameParser.detectFormat(id3)
        )
        val sync = byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(60)
        assertEquals(
            AudioFrameParser.PayloadFormat.COMPRESSED,
            AudioFrameParser.detectFormat(sync)
        )
    }

    @Test
    fun opusAndWebmPayloadsAreDetectedAsCompressed() {
        val ogg = "OggS".toByteArray(Charsets.US_ASCII) + ByteArray(60)
        assertEquals(AudioFrameParser.PayloadFormat.COMPRESSED, AudioFrameParser.detectFormat(ogg))

        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(60)
        assertEquals(AudioFrameParser.PayloadFormat.COMPRESSED, AudioFrameParser.detectFormat(webm))
    }

    @Test
    fun plainBytesAreAssumedPcm() {
        val pcm = ByteArray(64) { 0x10 }
        assertEquals(AudioFrameParser.PayloadFormat.PCM, AudioFrameParser.detectFormat(pcm))
    }

    // ── Constantes verificadas contra la referencia ─────────────────────────

    @Test
    fun verifiedProtocolConstantsMatchTheReferenceClient() {
        // VERIFICADO contra constants.py de rany2/edge-tts 7.2.8: si estos
        // valores cambian aqu\u00ed, hay que comprobarlos contra la referencia.
        assertEquals("1-143.0.3650.75", EdgeProtocolConstants.CLIENT_VERSION)
        assertEquals(
            "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
            EdgeProtocolConstants.DEFAULT_ORIGIN
        )
        assertTrue(EdgeProtocolConstants.DEFAULT_USER_AGENT.contains("Edg/143.0.0.0"))
        assertEquals(
            "audio-24khz-48kbitrate-mono-mp3",
            EdgeProtocolConstants.OUTPUT_FORMAT_MP3
        )
        assertEquals(24000, EdgeProtocolConstants.SAMPLE_RATE_HZ)
    }

    // ── Clave de cach\u00e9 SHA-256 ──────────────────────────────────────────────

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CacheRepository.sha256Hex("abc")
        )
    }

    @Test
    fun cacheKeyChangesWithAnyParameter() {
        val repo = CacheRepository(java.io.File(System.getProperty("java.io.tmpdir")))
        val base = repo.key("hola", "es-MX-DaliaNeural", "es-MX", "+0%", "+0Hz", "v1")
        assertEquals(64, base.length)

        assertTrue(base != repo.key("hola.", "es-MX-DaliaNeural", "es-MX", "+0%", "+0Hz", "v1"))
        assertTrue(base != repo.key("hola", "es-MX-DaliaNeural", "es-MX", "+10%", "+0Hz", "v1"))
        assertTrue(base != repo.key("hola", "es-MX-DaliaNeural", "es-MX", "+0%", "+0Hz", "v2"))
    }

    // ── Token anti-abuso Sec-MS-GEC ─────────────────────────────────────────

    @Test
    fun secMsGecIsDeterministicWithinAWindow() {
        val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        // Dos instantes dentro del MISMO intervalo de 5 minutos \u2192 mismo token.
        val base = 1_735_689_600L // 2025-01-01 00:00:00 UTC, divisible por 300
        val a = EdgeProtocolClient.generateSecMsGec(base, token)
        val b = EdgeProtocolClient.generateSecMsGec(base + 299, token)
        assertEquals(a, b)
        // Formato: 64 caracteres hexadecimales en MAY\u00daSCULAS.
        assertEquals(64, a.length)
        assertTrue(Regex("^[0-9A-F]{64}$").matches(a))
    }

    @Test
    fun secMsGecChangesAcrossWindowsAndToken() {
        val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val base = 1_735_689_600L
        val a = EdgeProtocolClient.generateSecMsGec(base, token)
        // Cruzar al siguiente intervalo de 5 minutos \u2192 token distinto.
        val next = EdgeProtocolClient.generateSecMsGec(base + 300, token)
        assertTrue(a != next)
        // Cambiar el TrustedClientToken tambi\u00e9n debe alterar el resultado.
        assertTrue(a != EdgeProtocolClient.generateSecMsGec(base, "OTHER"))
    }

    // ── Mapeo de errores ────────────────────────────────────────────────────

    @Test
    fun httpErrorsMapToReadableSpanish() {
        assertTrue(ErrorMapper.spanish(ProviderHttpException(403, "x")).contains("403"))
        assertTrue(ErrorMapper.spanish(ProviderHttpException(429, "x")).contains("l\u00edmite"))
        assertTrue(ErrorMapper.spanish(ProviderHttpException(400, "x")).contains("inv\u00e1lido"))
        assertTrue(ErrorMapper.spanish(ProviderHttpException(401, "x")).contains("autenticaci\u00f3n"))
        assertTrue(ErrorMapper.spanish(ProviderHttpException(404, "x")).contains("404"))
        assertTrue(ErrorMapper.spanish(ProviderHttpException(503, "x")).contains("503"))
    }

    @Test
    fun networkAndAudioErrorsMapToReadableSpanish() {
        assertTrue(ErrorMapper.spanish(SocketTimeoutException()).contains("Tiempo de espera"))
        assertTrue(ErrorMapper.spanish(TimeoutException()).contains("Tiempo de espera"))
        assertTrue(ErrorMapper.spanish(IOException("EOF: conexi\u00f3n cerrada")).contains("cerr\u00f3"))
        assertTrue(
            ErrorMapper.spanish(UnsupportedAudioFormatException("opus"))
                .contains("decodificable")
        )
        assertTrue(ErrorMapper.spanish(NoAudioReceivedException()).contains("no envi\u00f3 audio"))
        assertTrue(ErrorMapper.spanish(SynthesisCancelledException()).contains("cancelada"))
    }
}