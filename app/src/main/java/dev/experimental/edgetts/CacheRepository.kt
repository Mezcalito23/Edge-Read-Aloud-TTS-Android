package dev.experimental.edgetts

import java.io.File
import java.security.MessageDigest

/**
 * Caché opcional de audio PCM en cacheDir.
 *
 * Clave: SHA-256(texto + voz + locale + rate + pitch + versión de protocolo).
 * Nunca entran en la clave (ni en logs) tokens, claves de API ni el texto en
 * claro: el archivo se llama únicamente con el hash.
 *
 * Límite: 100 MB con eliminación de los archivos más antiguos. Si un archivo
 * está corrupto (vacío o con longitud impar, imposible en PCM 16-bit) se
 * borra y se vuelve a sintetizar.
 */
class CacheRepository(rootDir: File) {

    private val dir = File(rootDir, "edge_tts_pcm").also { it.mkdirs() }

    fun key(
        text: String,
        voice: String,
        locale: String,
        rate: String,
        pitch: String,
        protocolVersion: String
    ): String = sha256Hex(
        listOf(text, voice, locale, rate, pitch, protocolVersion).joinToString("\n")
    )

    /** PCM cacheado o null (ausente o corrupto → se elimina y re-sintetiza). */
    fun read(key: String): ByteArray? {
        val file = File(dir, "$key.pcm")
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty() || bytes.size % 2 != 0) {
            runCatching { file.delete() }
            return null
        }
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return bytes
    }

    fun write(key: String, pcm: ByteArray) {
        if (pcm.isEmpty() || pcm.size % 2 != 0) return
        runCatching {
            enforceLimit(pcm.size.toLong())
            File(dir, "$key.pcm").writeBytes(pcm)
        }
    }

    /** @return bytes liberados. */
    fun clear(): Long {
        val freed = sizeBytes()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return freed
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun enforceLimit(incoming: Long) {
        var total = sizeBytes()
        if (total + incoming <= MAX_BYTES) return
        val oldestFirst = dir.listFiles { f -> f.extension == "pcm" }
            ?.sortedBy { it.lastModified() }
            ?: return
        var i = 0
        while (total + incoming > MAX_BYTES && i < oldestFirst.size) {
            val victim = oldestFirst[i]
            total -= victim.length()
            runCatching { victim.delete() }
            i++
        }
    }

    companion object {
        const val MAX_BYTES: Long = 100L * 1024L * 1024L

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
