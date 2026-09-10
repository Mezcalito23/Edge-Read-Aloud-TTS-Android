package dev.experimental.edgetts

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Único dueño de TrustedClientToken, Sec-MS-GEC, MUID, cookie, cabeceras
 * de handshake y timestamps FILETIME/JS. El catálogo y el WebSocket
 * reutilizan esta clase; la fórmula GEC no se altera.
 */
class EdgeDrm(private val state: ProtocolState) {

    fun unixSeconds(): Long = state.unixSeconds()

    fun skewSeconds(): Long = state.skewSeconds()

    fun newMuid(): String =
        UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)

    fun generateSecMsGec(
        unixSeconds: Long = unixSeconds(),
        trustedClientToken: String = EdgeProtocolConstants.TRUSTED_CLIENT_TOKEN
    ): String = Companion.generateSecMsGec(unixSeconds, trustedClientToken)

    fun websocketUrl(
        baseUrl: String,
        connectionId: String,
        trustedClientToken: String = EdgeProtocolConstants.TRUSTED_CLIENT_TOKEN,
        secMsGecVersion: String = EdgeProtocolConstants.CLIENT_VERSION,
        unixSeconds: Long = unixSeconds()
    ): String {
        val gec = generateSecMsGec(unixSeconds, trustedClientToken)
        return baseUrl +
            "?TrustedClientToken=" + trustedClientToken +
            "&ConnectionId=" + connectionId +
            "&Sec-MS-GEC=" + gec +
            "&Sec-MS-GEC-Version=" + secMsGecVersion
    }

    fun voicesListUrl(
        baseUrl: String,
        trustedClientToken: String = EdgeProtocolConstants.TRUSTED_CLIENT_TOKEN,
        secMsGecVersion: String = EdgeProtocolConstants.CLIENT_VERSION,
        unixSeconds: Long = unixSeconds()
    ): String {
        val gec = generateSecMsGec(unixSeconds, trustedClientToken)
        val sep = if (baseUrl.contains('?')) '&' else '?'
        return baseUrl + sep +
            "Sec-MS-GEC=" + gec +
            "&Sec-MS-GEC-Version=" + secMsGecVersion
    }

    fun cookieHeader(muid: String): String = "muid=$muid;"

    fun handshakeHeaders(userAgent: String, origin: String, muid: String): Map<String, String> {
        val ua = userAgent.trim().ifBlank { EdgeProtocolConstants.DEFAULT_USER_AGENT }
        val orig = origin.trim().ifBlank { EdgeProtocolConstants.DEFAULT_ORIGIN }
        return linkedMapOf(
            "User-Agent" to ua,
            "Origin" to orig,
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cookie" to cookieHeader(muid)
        )
    }

    fun jsTimestamp(withTrailingZ: Boolean = false): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val stamp = fmt.format(Date())
        return if (withTrailingZ) stamp + "Z" else stamp
    }

    /**
     * Ajuste absoluto con la cabecera Date del 403. Devuelve false si Date
     * falta, no parsea o la deriva supera 24 h (no reintentar en ese caso).
     */
    fun tryUpdateSkewFromDate(
        dateHeader: String?,
        localEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        if (dateHeader.isNullOrBlank()) return false
        val server = parseRfc2616Date(dateHeader) ?: return false
        return state.updateAbsolute(server, localEpochSeconds)
    }

    fun handshakeDiagLine(
        gec: String,
        muid: String,
        origin: String,
        userAgent: String,
        version: String,
        attempt: Int
    ): String {
        val nowSeconds = unixSeconds()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val windowUtc = fmt.format(Date((nowSeconds - nowSeconds % 300) * 1000))
        return "GEC=${gec.take(8)}… · ventana=$windowUtc UTC · versión=$version" +
            " · MUID=${muid.take(8)}… · Origin=${origin.take(24)}…" +
            " · UA=…${userAgent.substringAfterLast(' ')}" +
            " · intento=$attempt"
    }

    companion object {
        private const val WIN_EPOCH_SECONDS: Long = 11_644_473_600L
        private const val TICKS_PER_SECOND: Long = 10_000_000L

        /**
         * SHA-256 uppercase de "{ticks}{TrustedClientToken}".
         * ticks = (unix + FILETIME 1601) redondeado a 5 min × 10^7.
         * La versión de cliente NO entra en el hash.
         */
        fun generateSecMsGec(unixSeconds: Long, trustedClientToken: String): String {
            var ticks = unixSeconds
            ticks += WIN_EPOCH_SECONDS
            ticks -= ticks % 300
            ticks *= TICKS_PER_SECOND
            val raw = "$ticks$trustedClientToken"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02x".format(it) }.uppercase(Locale.US)
        }

        fun parseRfc2616Date(date: String): Long? = runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.parse(date.trim())?.time?.div(1000)
        }.getOrNull()

        fun redactUrl(url: String): String {
            val cut = url.indexOf('?')
            val base = if (cut >= 0) url.substring(0, cut) else url
            return base
                .replace(
                    "speech.platform.bing.com/consumer/speech/synthesize/readaloud",
                    "speech.platform.bing.com/…/readaloud"
                )
        }
    }
}

/** Instancia de proceso: DRM, sesgo 403 y el único OkHttpClient. */
object SharedProtocol {
    val state: ProtocolState = ProtocolState()
    val drm: EdgeDrm = EdgeDrm(state)
    val http: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(EdgeProtocolConstants.CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(EdgeProtocolConstants.READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .pingInterval(EdgeProtocolConstants.PING_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }
}
