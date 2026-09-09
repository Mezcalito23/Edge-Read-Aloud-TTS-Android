package dev.experimental.edgetts

import java.util.Locale
import java.util.UUID

/**
 * Módulo DRM centralizado para el protocolo Edge Read Aloud.
 * Según V-3.1.md Sección 7: "EdgeDrm: Debe concentrar TODO"
 * - TrustedClientToken
 * - Versión GEC
 * - FILETIME con sufijo Z
 * - Sec-MS-GEC
 * - MUID
 * - Cookie
 * - Cabeceras
 */
class EdgeDrm(
    private val trustedClientToken: String = EdgeProtocolConstants.TRUSTED_CLIENT_TOKEN,
    private val secMsGecVersion: String = EdgeProtocolConstants.CLIENT_VERSION,
    userAgent: String = EdgeProtocolConstants.DEFAULT_USER_AGENT,
    origin: String = EdgeProtocolConstants.DEFAULT_ORIGIN
) {

    /** Estado atómico del protocolo (deriva de reloj). */
    val protocolState = ProtocolState()

    @Volatile
    private var muid: String = generateMuid()

    @Volatile
    var userAgent: String = userAgent
        private set

    @Volatile
    var origin: String = origin
        private set

    /**
     * Genera el token Sec-MS-GEC usando el algoritmo verificado.
     * SHA-256 en hex MAYÚSCULAS de "{ticks}{TrustedClientToken}"
     * donde ticks = (hora Unix + epoch FILETIME) redondeada a 5 min.
     */
    fun generateSecMsGec(): String {
        val unixSeconds = protocolState.getAdjustedUnixSeconds()
        var ticks = unixSeconds + WIN_EPOCH_SECONDS
        ticks -= ticks % 300  // Redondear a intervalo de 5 minutos
        ticks *= TICKS_PER_SECOND
        val raw = "$ticks$trustedClientToken"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02x".format(it) }.uppercase(Locale.US)
    }

    /**
     * Obtiene el MUID actual (identificador de telemetría).
     */
    fun getMuid(): String = muid

    /**
     * Regenera el MUID (cuando se renueva el contexto).
     */
    fun refreshMuid() {
        muid = generateMuid()
    }

    /**
     * Actualiza User-Agent y Origin (configurables sin recompilar).
     */
    fun setUserAgent(ua: String) {
        userAgent = ua.trim()
    }

    fun setOrigin(o: String) {
        origin = o.trim()
    }

    /**
     * Construye la cookie completa para el handshake.
     */
    fun buildCookieHeader(): String = "muid=$muid;"

    /**
     * Ajusta la deriva de reloj con tiempo del servidor.
     */
    fun adjustClockSkewFromServerDate(dateHeader: String?): Boolean {
        val serverSeconds = parseRfc2616Date(dateHeader ?: return false) ?: return false
        val localSeconds = System.currentTimeMillis() / 1000
        return protocolState.adjustClockSkew(serverSeconds, localSeconds)
    }

    companion object {
        private const val WIN_EPOCH_SECONDS: Long = 11_644_473_600L
        private const val TICKS_PER_SECOND: Long = 10_000_000L

        private fun generateMuid(): String =
            UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)

        private fun parseRfc2616Date(date: String): Long? = runCatching {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
            fmt.parse(date.trim())?.time?.div(1000)
        }.getOrNull()
    }
}
