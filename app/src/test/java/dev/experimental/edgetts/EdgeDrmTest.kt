package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class EdgeDrmTest {

    private val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private val unix = 1_735_689_600L // 2025-01-01 00:00:00 UTC, divisible by 300
    private val knownGec = "B0EDD22C7C09868E2F24C10264A8A3EB877773A7B6040B68AFA4FBCBABEA0238"

    @Test
    fun generateSecMsGecMatchesKnownVector() {
        assertEquals(knownGec, EdgeDrm.generateSecMsGec(unix, token))
        assertEquals(knownGec, EdgeDrm(ProtocolState()).generateSecMsGec(unix, token))
        assertEquals("B0ED", Hex.encode(byteArrayOf(0xB0.toByte(), 0xED.toByte()), upper = true))
    }

    @Test
    fun generateSecMsGecIsUppercaseHex64() {
        val gec = EdgeDrm.generateSecMsGec(unix, token)
        assertEquals(64, gec.length)
        assertTrue(gec.matches(Regex("[0-9A-F]{64}")))
        assertEquals(gec, gec.uppercase(Locale.US))
    }

    @Test
    fun generateSecMsGecIsStableInsideFiveMinuteWindow() {
        val a = EdgeDrm.generateSecMsGec(unix, token)
        val b = EdgeDrm.generateSecMsGec(unix + 299, token)
        assertEquals(a, b)
        assertNotEquals(a, EdgeDrm.generateSecMsGec(unix + 300, token))
        assertNotEquals(a, EdgeDrm.generateSecMsGec(unix, "OTHER"))
    }

    @Test
    fun clientVersionIsNotPartOfTheHash() {
        val drm = EdgeDrm(ProtocolState())
        val a = drm.websocketUrl("wss://example", "cid", token, "1-99-0", unix)
        val b = drm.websocketUrl("wss://example", "cid", token, "1-00-0", unix)
        val gec = Regex("Sec-MS-GEC=([0-9A-F]+)").find(a)!!.groupValues[1]
        assertEquals(gec, Regex("Sec-MS-GEC=([0-9A-F]+)").find(b)!!.groupValues[1])
        assertTrue(a.contains("Sec-MS-GEC-Version=1-99-0"))
        assertTrue(b.contains("Sec-MS-GEC-Version=1-00-0"))
    }

    @Test
    fun newMuidIs32UppercaseHexWithoutDashes() {
        val muid = EdgeDrm(ProtocolState()).newMuid()
        assertEquals(32, muid.length)
        assertTrue(muid.matches(Regex("[0-9A-F]{32}")))
        assertFalse(muid.contains("-"))
        assertNotEquals(muid, EdgeDrm(ProtocolState()).newMuid())
    }

    @Test
    fun cookieHeaderMatchesBrowserFormat() {
        val drm = EdgeDrm(ProtocolState())
        val muid = drm.newMuid()
        val cookie = drm.cookieHeader(muid)
        assertTrue(cookie.startsWith("muid="))
        assertTrue(cookie.endsWith(";"))
        assertTrue(Regex("muid=[0-9A-F]{32};").matches(cookie))
        assertEquals(cookie, drm.handshakeHeaders("ua", "origin", muid)["Cookie"])
    }

    @Test
    fun handshakeHeadersRejectBlankUaAndOrigin() {
        val drm = EdgeDrm(ProtocolState())
        val muid = drm.newMuid()
        val headers = drm.handshakeHeaders("  ", "", muid)
        assertEquals(EdgeProtocolConstants.DEFAULT_USER_AGENT, headers["User-Agent"])
        assertEquals(EdgeProtocolConstants.DEFAULT_ORIGIN, headers["Origin"])
        assertEquals(drm.cookieHeader(muid), headers["Cookie"])
    }

    @Test
    fun tryUpdateSkewFromDateRejectsNullBlankAndGarbage() {
        val drm = EdgeDrm(ProtocolState())
        assertFalse(drm.tryUpdateSkewFromDate(null, localEpochSeconds = unix))
        assertFalse(drm.tryUpdateSkewFromDate("", localEpochSeconds = unix))
        assertFalse(drm.tryUpdateSkewFromDate("invalid-date", localEpochSeconds = unix))
        assertEquals(0L, drm.skewSeconds())
    }

    @Test
    fun tryUpdateSkewFromDateRejectsAbsurd1980() {
        val drm = EdgeDrm(ProtocolState())
        assertFalse(
            drm.tryUpdateSkewFromDate(
                "Tue, 01 Jan 1980 00:00:00 GMT",
                localEpochSeconds = unix
            )
        )
        assertEquals(0L, drm.skewSeconds())
    }

    @Test
    fun tryUpdateSkewFromDateAcceptsFreshHeaderAbsolutely() {
        val state = ProtocolState()
        val drm = EdgeDrm(state)
        assertTrue(
            drm.tryUpdateSkewFromDate(
                "Wed, 01 Jan 2025 01:00:00 GMT",
                localEpochSeconds = unix
            )
        )
        assertEquals(3_600L, drm.skewSeconds())
        assertTrue(
            drm.tryUpdateSkewFromDate(
                "Wed, 01 Jan 2025 00:30:00 GMT",
                localEpochSeconds = unix
            )
        )
        assertEquals(1_800L, drm.skewSeconds())
    }

    @Test
    fun handshakeDiagLineNeverContainsFullSecrets() {
        val drm = EdgeDrm(ProtocolState())
        val line = drm.handshakeDiagLine(
            gec = knownGec,
            muid = "ABCDEF0123456789ABCDEF0123456789",
            origin = "https://www.bing.com",
            userAgent = "Mozilla/5.0 Chrome/131.0.0.0",
            version = EdgeProtocolConstants.CLIENT_VERSION,
            attempt = 1
        )
        assertFalse(line.contains(knownGec))
        assertFalse(line.contains("ABCDEF0123456789ABCDEF0123456789"))
        assertTrue(line.contains("GEC=${knownGec.take(8)}…"))
        assertTrue(line.contains("MUID=ABCDEF01…"))
        assertFalse(line.contains("TrustedClientToken"))
        assertFalse(line.contains(token))
    }

    @Test
    fun redactUrlStripsQueryAndDoesNotLeakToken() {
        val url = EdgeDrm(ProtocolState()).websocketUrl(
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1",
            "cid",
            token,
            "1-40-0",
            unix
        )
        val redacted = EdgeDrm.redactUrl(url)
        assertFalse(redacted.contains(token))
        assertFalse(redacted.contains("Sec-MS-GEC="))
        assertFalse(redacted.contains("?"))
        assertTrue(redacted.contains("speech.platform.bing.com/…/readaloud"))
    }

    @Test
    fun websocketUrlParameterOrderMatchesReferenceClient() {
        val url = EdgeDrm(ProtocolState()).websocketUrl(
            "wss://example/edge/v1", "cid", token, "1-40-0", unix
        )
        val query = url.substringAfter('?')
        val keys = query.split('&').map { it.substringBefore('=') }
        assertEquals(
            listOf("TrustedClientToken", "ConnectionId", "Sec-MS-GEC", "Sec-MS-GEC-Version"),
            keys
        )
    }

    @Test
    fun voicesListUrlAppendsGecWithoutDuplicatingTokenQuery() {
        val base = "https://example/voices/list?trustedclienttoken=$token"
        val url = EdgeDrm(ProtocolState()).voicesListUrl(base, token, "1-40-0", unix)
        assertEquals(1, Regex("(?i)trustedclienttoken=").findAll(url).count())
        assertTrue(url.contains("Sec-MS-GEC=$knownGec"))
        assertTrue(url.contains("Sec-MS-GEC-Version=1-40-0"))
    }

    @Test
    fun jsTimestampOptionalTrailingZ() {
        val drm = EdgeDrm(ProtocolState())
        val plain = drm.jsTimestamp(withTrailingZ = false)
        val zed = drm.jsTimestamp(withTrailingZ = true)
        assertFalse(plain.endsWith("Z"))
        assertTrue(zed.endsWith("Z"))
        assertEquals(plain + "Z", zed)
        assertTrue(plain.contains("GMT+0000 (Coordinated Universal Time)"))
    }
}
