package dev.experimental.edgetts

import java.io.File
import java.security.MessageDigest

/**
 * Caché opcional de audio **MP3** (no PCM) en cacheDir.
 *
 * Clave: SHA-256 de texto sanitizado, voz, locale, rate, pitch, formato y
 * versión de protocolo, serializados con separador U+001F. El nombre de
 * archivo es solo el hash: nunca texto, tokens ni URLs.
 *
 * Escritura atómica: `$key.mp3.tmp` → rename. Un MP3 vacío o un fallo de
 * escritura no reemplazan una entrada válida. Los `.pcm` de fases
 * anteriores se eliminan al construir el repositorio.
 */
class CacheRepository(
    rootDir: File,
    private val maxBytes: Long = MAX_BYTES
) {

    private val dir = File(rootDir, DIR_MP3).also { it.mkdirs() }

    init {
        val legacy = File(rootDir, DIR_PCM)
        if (legacy.exists()) runCatching { legacy.deleteRecursively() }
    }

    fun key(
        text: String,
        voice: String,
        locale: String,
        rate: String,
        pitch: String,
        format: String,
        protocolVersion: String
    ): String = sha256Hex(
        listOf(text, voice, locale, rate, pitch, format, protocolVersion)
            .joinToString("\u001F")
    )

    fun readMp3(key: String): ByteArray? {
        val file = targetFile(key)
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (!isValidMp3(bytes)) {
            runCatching { file.delete() }
            return null
        }
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return bytes
    }

    /**
     * @return true si el MP3 quedó publicado; false si se rechazó o el
     * replace atómico falló (el archivo previo, si existía, se conserva).
     */
    fun writeMp3(key: String, mp3: ByteArray): Boolean {
        if (!isValidMp3(mp3)) return false
        val target = targetFile(key)
        val tmp = File(dir, "$key.mp3.tmp")
        return try {
            enforceLimit(mp3.size.toLong(), keep = target)
            tmp.outputStream().use { it.write(mp3); it.flush() }
            val written = tmp.readBytes()
            if (!isValidMp3(written) || written.size != mp3.size) {
                throw IllegalStateException("MP3 temporal inválido")
            }
            publishAtomic(tmp, target)
            true
        } catch (_: Throwable) {
            runCatching { tmp.delete() }
            false
        }
    }

    fun remove(key: String) {
        runCatching { targetFile(key).delete() }
        runCatching { File(dir, "$key.mp3.tmp").delete() }
    }

    fun clear(): Long {
        val freed = sizeBytes()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return freed
    }

    fun sizeBytes(): Long =
        dir.listFiles { f -> f.extension == "mp3" }?.sumOf { it.length() } ?: 0L

    private fun targetFile(key: String) = File(dir, "$key.mp3")

    private fun publishAtomic(tmp: File, target: File) {
        if (tmp.renameTo(target)) return
        if (target.exists() && !target.delete() && target.exists()) {
            throw IllegalStateException("No se pudo reemplazar la caché")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            if (!tmp.delete() && tmp.exists()) tmp.deleteOnExit()
            if (!target.exists()) throw IllegalStateException("No se pudo reemplazar la caché")
        }
    }

    private fun enforceLimit(incoming: Long, keep: File) {
        var total = sizeBytes()
        if (total + incoming <= maxBytes) return
        val oldestFirst = dir.listFiles { f -> f.extension == "mp3" }
            ?.filter { it.absolutePath != keep.absolutePath }
            ?.sortedBy { it.lastModified() }
            ?: return
        var i = 0
        while (total + incoming > maxBytes && i < oldestFirst.size) {
            val victim = oldestFirst[i]
            total -= victim.length()
            runCatching { victim.delete() }
            i++
        }
    }

    companion object {
        const val MAX_BYTES: Long = 100L * 1024L * 1024L
        const val DIR_MP3: String = "edge_tts_mp3"
        const val DIR_PCM: String = "edge_tts_pcm"

        fun isValidMp3(bytes: ByteArray?): Boolean =
            bytes != null && bytes.isNotEmpty()

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
