package dev.experimental.edgetts

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Abstracción del proveedor de síntesis. [EdgeReadAloudTtsService] solo
 * habla con esta interfaz: sustituir Edge por Azure u otro backend no toca
 * el servicio Android.
 */
interface TtsProvider {

    /**
     * Inicia la síntesis de [text]. Garantiza exactamente UNA llamada
     * terminal: [onComplete] XOR [onError].
     *
     * @param onPcmChunk trozos de audio del formato pedido (PCM crudo o MP3).
     */
    fun synthesize(
        text: String,
        voice: String,
        locale: String,
        rate: String,
        pitch: String,
        onPcmChunk: (ByteArray) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ): Cancellable
}

/** Cancelación cooperativa de una síntesis en curso. */
fun interface Cancellable {
    fun cancel()
}

/** Voz del catálogo de Edge. [shortName] es el identificador estable. */
data class EdgeVoice(
    val shortName: String,
    val locale: String,
    val gender: String?,
    val displayName: String,
    val styles: List<String> = emptyList(),
    val roles: List<String> = emptyList()
)

// ── Errores tipados ─────────────────────────────────────────────────────

/** Error HTTP del proveedor con código y cuerpo resumido (sin tokens). */
class ProviderHttpException(
    val code: Int,
    val summary: String
) : IOException("HTTP $code: $summary")

/** Formato que no se puede entregar a SynthesisCallback ni decodificar. */
class UnsupportedAudioFormatException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** Cancelación por onStop() o petición nueva. NO es un fallo. */
class SynthesisCancelledException : Exception("Síntesis cancelada")

/**
 * El servidor completó el turno (turn.end) sin enviar audio: suele
 * significar que rechazó el outputFormat o el SSML.
 */
class NoAudioReceivedException :
    Exception("El servidor completó la sesión sin enviar audio")

// ── Mapeo a mensajes legibles ───────────────────────────────────────────

/**
 * Traduce cualquier error del proveedor a un mensaje legible en español.
 * Cubre: 400, 401, 403, 404, 429, 5xx, timeout, EOF y audio inválido.
 */
object ErrorMapper {

    fun spanish(t: Throwable): String = when {
        t is SynthesisCancelledException ->
            "Síntesis cancelada por el usuario o por una solicitud nueva."

        t is UnsupportedAudioFormatException ->
            "Audio no decodificable: ${t.message?.take(160) ?: "formato no reconocido"}. " +
                "MP3 se decodifica con MediaCodec; Opus queda para la fase 2."

        t is NoAudioReceivedException ->
            "El servidor no envió audio: pudo rechazar el formato o el SSML solicitados."

        t is ProviderHttpException ->
            httpMessage(t.code, t.summary)

        t is TimeoutException || t is SocketTimeoutException ->
            "Tiempo de espera agotado: red lenta o servidor no disponible."

        t is IOException && t.message.orEmpty().startsWith("EOF") ->
            "La conexión se cerró antes de recibir el audio completo (EOF)."

        t is IOException ->
            "Fallo de red o el servidor cerró la conexión: " +
                (t.message?.take(80) ?: "sin detalle")

        else ->
            "Error inesperado durante la síntesis: ${t.javaClass.simpleName}"
    }

    private fun httpMessage(code: Int, summary: String): String = when (code) {
        400 -> "HTTP 400: SSML o formato inválido; el proveedor rechazó la petición."
        401 -> "HTTP 401: contexto o autenticación inválida; el protocolo pudo haber cambiado."
        403 -> "HTTP 403: acceso rechazado (ya se intentó renovar el contexto una vez). " +
            "El protocolo no oficial pudo haber cambiado: prueba otro User-Agent y " +
            "verifica la hora del dispositivo."
        404 -> "HTTP 404: el endpoint ya no existe; el protocolo no oficial cambió."
        429 -> "HTTP 429: límite de peticiones alcanzado. Reduce la frecuencia de síntesis."
        in 500..599 -> "HTTP $code: fallo del servidor. Intenta más tarde."
        else -> "HTTP $code: respuesta inesperada. ${summary.take(60)}"
    }
}
