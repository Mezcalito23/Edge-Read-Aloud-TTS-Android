package dev.experimental.edgetts

/** Hex sin String.format: tabla de nibbles, una pasada. */
object Hex {
    private val LOWER = "0123456789abcdef".toCharArray()
    private val UPPER = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray, upper: Boolean = false): String {
        val alphabet = if (upper) UPPER else LOWER
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = alphabet[v ushr 4]
            out[i++] = alphabet[v and 0x0F]
        }
        return String(out)
    }
}
