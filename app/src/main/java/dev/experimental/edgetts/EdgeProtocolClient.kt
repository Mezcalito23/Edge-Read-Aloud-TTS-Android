package dev.experimental.edgetts

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * EXPERIMENTAL — cliente del protocolo NO oficial de Edge Read Aloud,
 * verificado byte a byte contra el cliente de referencia rany2/edge-tts
 * 7.2.8 y contra capturas del wire real.
 *
 * Garantías:
 *  - onComplete XOR onError, exactamente una vez por sesión;
 *  - nunca más de UN reintento automático, solo si no se recibió audio y el
 *    error NO es permanente (401/403/404/429);
 *  - ante 403, UNA renovación de contexto (réplica de DRM: ajustar deriva de
 *    reloj con la cabecera Date y reintentar);
 *  - cancelación inmediata vía WebSocket.cancel();
 *  - nunca se registra en Logcat la URL completa (contiene el token).
 */
class EdgeProtocolClient(
    private val client: OkHttpClient,
    private val wsBaseUrl: String = EdgeProtocolConstants.WS_BASE_URL,
    private val trustedClientToken: String = EdgeProtocolConstants.TRUSTED_CLIENT_TOKEN,
    private val secMsGecVersion: String = EdgeProtocolConstants.CLIENT_VERSION,
    private val userAgent: String = EdgeProtocolConstants.DEFAULT_USER_AGENT,
    private val origin: String = EdgeProtocolConstants.DEFAULT_ORIGIN,
    private val outputFormat: String = EdgeProtocolConstants.OUTPUT_FORMAT_MP3,
    private val onDiagnostic: ((String) -> Unit)? = null
) : TtsProvider {

    override fun synthesize(
        text: String,
        voice: String,
        locale: String,
        rate: String,
        pitch: String,
        onPcmChunk: (ByteArray) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ): Cancellable {
        val session = Session(text, voice, locale, rate, pitch, onPcmChunk, onComplete, onError)
        session.connect()
        return Cancellable { session.cancel() }
    }

    private inner class Session(
        private val text: String,
        private val voice: String,
        private val locale: String,
        private val rate: String,
        private val pitch: String,
        private val onPcmChunk: (ByteArray) -> Unit,
        private val onComplete: () -> Unit,
        private val onError: (Throwable) -> Unit
    ) {

        private val requestId = UUID.randomUUID().toString().replace("-", "")
        private val connectionId = UUID.randomUUID().toString().replace("-", "")

        // MUID: identificador de telemetría de navegador que el handshake
        // valida (módulo DRM de la referencia). GUID de 32 hex MAYÚSCULAS.
        @Volatile
        private var muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)

        private val finished = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val receivedAudio = AtomicBoolean(false)
        private val attempts = AtomicInteger(0)
        private val gecRefreshes = AtomicInteger(0)

        // Diagnóstico acumulado: se re-emite completo en cada evento para que
        // el servicio conserve TODAS las líneas (el almacenamiento sobreescribe).
        private val diagLines = CopyOnWriteArrayList<String>()

        // Diagnóstico: cuántos frames binarios llegaron en total (para
        // distinguir "el servidor no envió audio" de "llegó pero no se parseó").
        private val binaryFramesSeen = AtomicInteger(0)

        // Secuencia de paths recibidos, para el diagnóstico de EOF.
        private val pathSequence = ArrayList<String>()

        @Volatile
        private var socket: WebSocket? = null
        private var watchdog: ScheduledFuture<*>? = null

        private fun logDiag(line: String) {
            diagLines += line
            onDiagnostic?.invoke(diagLines.joinToString("\n"))
        }

        fun connect() {
            // La URL lleva el token público del protocolo: NO se loguea jamás.
            // Orden de parámetros idéntico al cliente de referencia:
            // TrustedClientToken, ConnectionId, Sec-MS-GEC, Sec-MS-GEC-Version.
            val nowSeconds = getUnixSeconds()
            val gec = generateSecMsGec(nowSeconds, trustedClientToken)

            val url = wsBaseUrl +
                "?TrustedClientToken=" + trustedClientToken +
                "&ConnectionId=" + connectionId +
                "&Sec-MS-GEC=" + gec +
                "&Sec-MS-GEC-Version=" + secMsGecVersion

            // Réplica del handshake real capturado con el spy (edge-tts 7.2.8).
            // Sec-WebSocket-Version:13 lo añade OkHttp solo; el resto debe
            // coincidir byte a byte o el WAF de Microsoft responde 403.
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Origin", origin)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                // El cliente real envía la cookie en minúscula y con «;» final.
                .header("Cookie", "muid=$muid;")
                .build()

            // Diagnóstico del handshake: SOLO metadatos (prefijos de hash,
            // ventana UTC, origen, UA). Nunca el token completo ni la URL.
            val windowUtc = run {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.format(Date((nowSeconds - nowSeconds % 300) * 1000))
            }
            logDiag(
                "GEC=${gec.take(8)}… · ventana=$windowUtc UTC · versión=$secMsGecVersion" +
                    " · MUID=${muid.take(8)}… · Origin=${origin.take(24)}…" +
                    " · UA=…${userAgent.substringAfterLast(' ')}" +
                    " · intento=${attempts.get() + 1 + gecRefreshes.get()}"
            )

            if (EdgeProtocolConstants.DEBUG_PROTOCOL) {
                Log.d(TAG, "handshake: formato=$outputFormat voz=$voice")
            }

            socket = client.newWebSocket(request, listener)
            scheduleWatchdog()
        }

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            watchdog?.cancel(false)
            runCatching { socket?.cancel() }
            fail(SynthesisCancelledException())
        }

        private fun scheduleWatchdog() {
            watchdog?.cancel(false)
            watchdog = WATCHDOG.schedule(
                {
                    runCatching { socket?.cancel() }
                    fail(
                        TimeoutException(
                            "La síntesis superó " +
                                (EdgeProtocolConstants.SYNTHESIS_TIMEOUT_MS / 1000) +
                                " s sin completarse"
                        )
                    )
                },
                EdgeProtocolConstants.SYNTHESIS_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        }

        private fun finishOk() {
            if (!finished.compareAndSet(false, true)) return
            watchdog?.cancel(false)
            runCatching { socket?.close(1000, "done") }
            onComplete()
        }

        private fun fail(t: Throwable) {
            if (!finished.compareAndSet(false, true)) return
            watchdog?.cancel(false)
            runCatching { socket?.cancel() }
            onError(t)
        }

        private val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(speechConfigFrame())
                webSocket.send(ssmlFrame())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val headers = AudioFrameParser.parseTextFrameHeaders(text)
                val path = AudioFrameParser.pathOf(headers)
                if (path != null) synchronized(pathSequence) { pathSequence += path }
                when (path) {
                    EdgeProtocolConstants.PATH_TURN_START ->
                        Log.i(TAG, "turn.start recibido")

                    EdgeProtocolConstants.PATH_TURN_END -> {
                        if (receivedAudio.get()) {
                            finishOk()
                        } else {
                            // turn.end sin audio. El conteo de frames binarios
                            // recibidos distingue "el servidor no envió nada"
                            // de "envió frames pero no se reconocieron".
                            logDiag(
                                "turn.end sin audio · frames binarios recibidos=" +
                                    binaryFramesSeen.get() + " · formato=$outputFormat"
                            )
                            fail(NoAudioReceivedException())
                        }
                    }

                    EdgeProtocolConstants.PATH_RESPONSE -> {
                        // El servidor puede reportar aquí su intención antes
                        // de cerrar. Se captura SIEMPRE: es la verdad.
                        val body = AudioFrameParser.bodyOf(text)
                        logDiag(
                            "Path:response → " +
                                AudioFrameParser.truncate(body.ifBlank { "(vacío)" }, 160)
                        )
                        val status = Regex("\"status\"\\s*:\\s*\"?(\\d{3})\"?")
                            .find(body)?.groupValues?.get(1)?.toIntOrNull()
                        if (status != null && status >= 400) {
                            fail(ProviderHttpException(status, AudioFrameParser.truncate(body)))
                        }
                    }

                    else -> Unit
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                val n = binaryFramesSeen.incrementAndGet()
                if (n <= 3) {
                    // Verdad del terreno: los primeros bytes de cada frame.
                    val head = raw.copyOfRange(0, minOf(24, raw.size))
                    val hex = head.joinToString(" ") { "%02x".format(it) }
                    logDiag("binario#$n len=${raw.size} hex=$hex")
                }

                val frame = AudioFrameParser.parseBinaryFrame(raw)
                if (frame.path == EdgeProtocolConstants.PATH_AUDIO) {
                    val payload = frame.payload
                    if (payload.isNotEmpty() && !cancelled.get()) {
                        receivedAudio.set(true)
                        onPcmChunk(payload)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { webSocket.close(1000, null) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished.get() && !cancelled.get()) {
                    val seq = synchronized(pathSequence) { pathSequence.toString() }
                    logDiag(
                        "EOF cierre=$code razón='${reason.take(40)}' · paths=[$seq]" +
                            " · binarios=${binaryFramesSeen.get()} · audio=${receivedAudio.get()}" +
                            " · formato=$outputFormat"
                    )
                    fail(
                        IOException(
                            "EOF: el servidor cerró el WebSocket (código $code) " +
                                "antes de completar la síntesis"
                        )
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (finished.get() || cancelled.get()) return

                val code = response?.code ?: -1
                val summary = runCatching { response?.peekBody(160)?.string() }
                    .getOrNull()
                    ?.let { AudioFrameParser.truncate(it) }
                    ?: (t.message ?: "fallo de red")
                runCatching { response?.body?.close() }

                // Permanentes (401/403/404/429): sin reintentos en bucle.
                // ÚNICA excepción, permitida por la especificación como
                // «renovación de contexto explícita»: en 403 el cliente de
                // referencia (DRM.handle_client_response_error) ajusta la
                // deriva de reloj con la cabecera Date y reintenta UNA vez.
                if (code in EdgeProtocolConstants.PERMANENT_HTTP_ERRORS) {
                    if (code == 403 && !receivedAudio.get() &&
                        gecRefreshes.getAndIncrement() == 0
                    ) {
                        val serverSeconds = response?.header("Date")
                            ?.let { parseRfc2616Date(it) }
                        if (serverSeconds != null) {
                            clockSkewSeconds += serverSeconds - getUnixSeconds()
                        }
                        Log.w(
                            TAG,
                            "403 en handshake: deriva ajustada " +
                                "(skew=${"%.1f".format(clockSkewSeconds)} s), renovación única"
                        )
                        logDiag("403 → renovación de contexto (deriva=${clockSkewSeconds}s)")
                        connect()
                        return
                    }
                    logDiag(
                        "FALLO HTTP $code · cuerpo=${AudioFrameParser.truncate(summary, 120)}"
                    )
                    fail(ProviderHttpException(code, summary))
                    return
                }

                // Único reintento automático posible: sin audio recibido todavía.
                if (!receivedAudio.get() &&
                    attempts.incrementAndGet() <= EdgeProtocolConstants.MAX_AUTO_RETRIES
                ) {
                    Log.w(TAG, "Fallo de red antes del audio: reintento único")
                    connect()
                    return
                }

                fail(
                    when {
                        t is SocketTimeoutException -> t
                        code > 0 -> ProviderHttpException(code, summary)
                        else -> t
                    }
                )
            }
        }

        // ── Mensajes del protocolo (formato verificado) ─────────────────────

        private fun speechConfigFrame(): String {
            // La referencia usa boundary=SentenceBoundary por defecto:
            // sentenceBoundaryEnabled="true", wordBoundaryEnabled="false".
            val config = JSONObject()
                .put(
                    "context",
                    JSONObject().put(
                        "synthesis",
                        JSONObject().put(
                            "audio",
                            JSONObject()
                                .put(
                                    "metadataoptions",
                                    JSONObject()
                                        .put("sentenceBoundaryEnabled", "true")
                                        .put("wordBoundaryEnabled", "false")
                                )
                                .put("outputFormat", outputFormat)
                        )
                    )
                )
            return buildString {
                append("X-Timestamp:").append(jsTimestamp())
                append(EdgeProtocolConstants.CRLF)
                append("Content-Type:application/json; charset=utf-8")
                append(EdgeProtocolConstants.CRLF)
                append("Path:").append(EdgeProtocolConstants.PATH_SPEECH_CONFIG)
                append(EdgeProtocolConstants.CRLF)
                append(EdgeProtocolConstants.CRLF)
                append(config.toString())
                // La referencia añade un CRLF final tras el JSON.
                append(EdgeProtocolConstants.CRLF)
            }
        }

        private fun ssmlFrame(): String {
            val ssml = SsmlBuilder.build(voice, locale, rate, pitch, text)
            return buildString {
                append("X-RequestId:").append(requestId)
                append(EdgeProtocolConstants.CRLF)
                append("Content-Type:application/ssml+xml")
                append(EdgeProtocolConstants.CRLF)
                // La "Z" final NO es un error: el cliente de referencia la
                // añade ("This is not a mistake, Microsoft Edge bug").
                append("X-Timestamp:").append(jsTimestamp()).append("Z")
                append(EdgeProtocolConstants.CRLF)
                append("Path:").append(EdgeProtocolConstants.PATH_SSML)
                append(EdgeProtocolConstants.CRLF)
                append(EdgeProtocolConstants.CRLF)
                append(ssml)
            }
        }

        /** Formato JS: "Tue Aug 26 2026 18:55:00 GMT+0000 (Coordinated Universal Time)". */
        private fun jsTimestamp(): String {
            val fmt = SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                Locale.US
            ).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return fmt.format(Date())
        }
    }

    companion object {
        private const val TAG = "EdgeTtsClient"

        /** Segundos entre el epoch FILETIME de Windows (1601-01-01) y el Unix (1970-01-01). */
        private const val WIN_EPOCH_SECONDS: Long = 11_644_473_600L

        /** Ticks de 100 nanosegundos por segundo (formato FILETIME). */
        private const val TICKS_PER_SECOND: Long = 10_000_000L

        /** Deriva de reloj ajustada con la cabecera Date del servidor (DRM). */
        @Volatile
        private var clockSkewSeconds: Double = 0.0

        private val WATCHDOG: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "edge-tts-watchdog").apply { isDaemon = true }
            }

        private fun getUnixSeconds(): Long =
            (System.currentTimeMillis() / 1000.0 + clockSkewSeconds).toLong()

        /**
         * Parsea una fecha RFC 2616 (cabecera `Date` del servidor) a segundos Unix.
         */
        private fun parseRfc2616Date(date: String): Long? = runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.parse(date.trim())?.time?.div(1000)
        }.getOrNull()

        /**
         * Token anti-abuso Sec-MS-GEC — réplica EXACTA del algoritmo real
         * (edge_tts/drm.py → generate_sec_ms_gec), verificada contra la fuente.
         *
         * SHA-256, en hexadecimal MAYÚSCULAS, de la concatenación:
         *   "{ticks}{TrustedClientToken}"
         * donde ticks = (hora Unix + epoch FILETIME 1601) redondeada hacia
         * abajo al intervalo de 5 minutos y convertida a ticks de 100 ns.
         *
         * IMPORTANTE: la versión de cliente NO forma parte del hash; solo se
         * envía como el parámetro independiente Sec-MS-GEC-Version.
         */
        fun generateSecMsGec(
            unixSeconds: Long,
            trustedClientToken: String
        ): String {
            var ticks = unixSeconds
            ticks += WIN_EPOCH_SECONDS      // → epoch FILETIME (segundos desde 1601)
            ticks -= ticks % 300            // redondear al intervalo de 5 minutos
            ticks *= TICKS_PER_SECOND       // segundos → ticks de 100 ns
            val raw = "$ticks$trustedClientToken"   // SIN la versión
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02x".format(it) }.uppercase(Locale.US)
        }
    }
}
