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
 * Cliente WebSocket del protocolo no oficial. DRM, sesgo de reloj y
 * cabeceras salen de [EdgeDrm]; el diagnóstico se acumula en memoria y
 * se persiste una sola vez al terminar, fuera del hilo de OkHttp.
 */
class EdgeProtocolClient(
    private val client: OkHttpClient,
    private val drm: EdgeDrm = SharedProtocol.drm,
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

        @Volatile
        private var muid = drm.newMuid()

        private val finished = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val receivedAudio = AtomicBoolean(false)
        private val attempts = AtomicInteger(0)
        private val gecRefreshes = AtomicInteger(0)

        private val diagLines = CopyOnWriteArrayList<String>()
        private val binaryFramesSeen = AtomicInteger(0)
        private val pathSequence = ArrayList<String>()

        @Volatile
        private var socket: WebSocket? = null
        private var watchdog: ScheduledFuture<*>? = null

        private fun logDiag(line: String) {
            diagLines += line
        }

        private fun flushDiag() {
            val text = diagLines.joinToString("\n")
            if (text.isBlank()) return
            DIAG_IO.execute { onDiagnostic?.invoke(text) }
        }

        fun connect() {
            val nowSeconds = drm.unixSeconds()
            val gec = drm.generateSecMsGec(nowSeconds, trustedClientToken)
            val url = drm.websocketUrl(
                wsBaseUrl, connectionId, trustedClientToken, secMsGecVersion, nowSeconds
            )

            val request = Request.Builder().url(url)
            for ((k, v) in drm.handshakeHeaders(userAgent, origin, muid)) {
                request.header(k, v)
            }

            logDiag(
                drm.handshakeDiagLine(
                    gec, muid, origin, userAgent, secMsGecVersion,
                    attempts.get() + 1 + gecRefreshes.get()
                )
            )

            if (EdgeProtocolConstants.DEBUG_PROTOCOL) {
                Log.d(TAG, "handshake: formato=$outputFormat voz=$voice")
            }

            socket = client.newWebSocket(request.build(), listener)
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
            flushDiag()
            onComplete()
        }

        private fun fail(t: Throwable) {
            if (!finished.compareAndSet(false, true)) return
            watchdog?.cancel(false)
            runCatching { socket?.cancel() }
            flushDiag()
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
                            logDiag(
                                "turn.end sin audio · frames binarios recibidos=" +
                                    binaryFramesSeen.get() + " · formato=$outputFormat"
                            )
                            fail(NoAudioReceivedException())
                        }
                    }

                    EdgeProtocolConstants.PATH_RESPONSE -> {
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

                if (code in EdgeProtocolConstants.PERMANENT_HTTP_ERRORS) {
                    if (code == 403 && !receivedAudio.get() &&
                        gecRefreshes.getAndIncrement() == 0
                    ) {
                        if (drm.tryUpdateSkewFromDate(response?.header("Date"))) {
                            Log.w(
                                TAG,
                                "403 en handshake: deriva ajustada " +
                                    "(skew=${drm.skewSeconds()} s), renovación única"
                            )
                            logDiag("403 → renovación de contexto (deriva=${drm.skewSeconds()}s)")
                            muid = drm.newMuid()
                            connect()
                            return
                        }
                        logDiag("403 → Date ausente o absurda, sin renovación")
                    }
                    logDiag(
                        "FALLO HTTP $code · cuerpo=${AudioFrameParser.truncate(summary, 120)}"
                    )
                    fail(ProviderHttpException(code, summary))
                    return
                }

                if (!receivedAudio.get() &&
                    attempts.incrementAndGet() <= EdgeProtocolConstants.MAX_AUTO_RETRIES
                ) {
                    Log.w(TAG, "Fallo de red antes del audio: reintento único")
                    muid = drm.newMuid()
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

        private fun speechConfigFrame(): String {
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
                append("X-Timestamp:").append(drm.jsTimestamp(withTrailingZ = false))
                append(EdgeProtocolConstants.CRLF)
                append("Content-Type:application/json; charset=utf-8")
                append(EdgeProtocolConstants.CRLF)
                append("Path:").append(EdgeProtocolConstants.PATH_SPEECH_CONFIG)
                append(EdgeProtocolConstants.CRLF)
                append(EdgeProtocolConstants.CRLF)
                append(config.toString())
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
                append("X-Timestamp:").append(drm.jsTimestamp(withTrailingZ = true))
                append(EdgeProtocolConstants.CRLF)
                append("Path:").append(EdgeProtocolConstants.PATH_SSML)
                append(EdgeProtocolConstants.CRLF)
                append(EdgeProtocolConstants.CRLF)
                append(ssml)
            }
        }
    }

    companion object {
        private const val TAG = "EdgeTtsClient"

        private val WATCHDOG: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "edge-tts-watchdog").apply { isDaemon = true }
            }

        private val DIAG_IO = Executors.newSingleThreadExecutor { r ->
            Thread(r, "edge-tts-diag").apply { isDaemon = true }
        }

        fun generateSecMsGec(unixSeconds: Long, trustedClientToken: String): String =
            EdgeDrm.generateSecMsGec(unixSeconds, trustedClientToken)
    }
}
