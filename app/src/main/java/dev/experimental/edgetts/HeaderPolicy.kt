package dev.experimental.edgetts

/**
 * UA/Origin se persisten y se meten en cabeceras OkHttp. Un emoji, CR/LF
 * o un Origin sin esquema deja el motor inutilizable hasta el reset.
 */
object HeaderPolicy {
    private const val MAX_UA = 512
    private const val MAX_ORIGIN = 256

    fun isPrintableAscii(value: String): Boolean =
        value.all { it.code in 32..126 }

    fun isUserAgent(ua: String): Boolean {
        val t = ua.trim()
        return t.length in 8..MAX_UA && isPrintableAscii(t)
    }

    fun isOrigin(origin: String): Boolean {
        val t = origin.trim()
        if (t.length !in 8..MAX_ORIGIN || !isPrintableAscii(t)) return false
        return t.startsWith("chrome-extension://") ||
            t.startsWith("https://") ||
            t.startsWith("http://")
    }

    fun orDefaultUserAgent(ua: String): String =
        ua.trim().takeIf { isUserAgent(it) } ?: EdgeProtocolConstants.DEFAULT_USER_AGENT

    fun orDefaultOrigin(origin: String): String =
        origin.trim().takeIf { isOrigin(it) } ?: EdgeProtocolConstants.DEFAULT_ORIGIN
}
