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
 * Escritura atómica: se valida el PCM, se escribe `$key.pcm.tmp` y solo
 * entonces se renombra al nombre definitivo. Un fallo deja el archivo
 * anterior intacto y borra el temporal.
 *
 * Límite: 100 MB con eliminación de los archivos más antiguos. Si un archivo
 * está corrupto (vacío o con longitud impar, imposible en PCM 16-bit) se
 * borra y se vuelve a sintetizar.
 */
class CacheRepository(
    rootDir: File,
    private val maxBytes: Long = MAX_BYTES
) {

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
        val file = targetFile(key)
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (!isValidPcm(bytes)) {
            runCatching { file.delete() }
            return null
        }
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return bytes
    }

    /**
     * @return true si el PCM quedó publicado bajo [key]; false si se rechazó
     * o el replace atómico falló (el archivo previo, si existía, se conserva).
     */
    fun write(key: String, pcm: ByteArray): Boolean {
        if (!isValidPcm(pcm)) return false
        val target = targetFile(key)
        val tmp = File(dir, "$key.pcm.tmp")
        return try {
            enforceLimit(pcm.size.toLong(), keep = target)
            tmp.writeBytes(pcm)
            val written = tmp.readBytes()
            if (!isValidPcm(written) || written.size != pcm.size) {
                throw IllegalStateException("PCM temporal inválido")
            }
            publishAtomic(tmp, target)
            true
        } catch (_: Throwable) {
            runCatching { tmp.delete() }
            false
        }
    }

    /** @return bytes liberados. */
    fun clear(): Long {
        val freed = sizeBytes()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return freed
    }

    fun sizeBytes(): Long =
        dir.listFiles { f -> f.extension == "pcm" }?.sumOf { it.length() } ?: 0L

    private fun targetFile(key: String) = File(dir, "$key.pcm")

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
        val oldestFirst = dir.listFiles { f -> f.extension == "pcm" }
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

        fun isValidPcm(bytes: ByteArray?): Boolean =
            bytes != null && bytes.isNotEmpty() && bytes.size % 2 == 0

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
