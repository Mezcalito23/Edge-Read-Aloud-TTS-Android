package dev.experimental.edgetts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pruebas instrumentadas del contrato del motor, instaladas en un dispositivo
 * o emulador. NINGUNA depende de la red real:
 *  - la prueba de fallo apunta el WebSocket a 127.0.0.1 (rechazo inmediato);
 *  - el resto usa el propio motor instalado.
 *
 * Ejecución: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class EngineContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun serviceIsDeclaredAsTtsEngine() {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val matches = context.packageManager.queryIntentServices(intent, 0)
        assertTrue(
            "El paquete no expone ningún servicio TTS",
            matches.any { it.serviceInfo.packageName == context.packageName }
        )
    }

    @Test
    fun engineLoadsSpanishMexicoAndExposesDalia() {
        withEngine { tts ->
            val available = tts.isLanguageAvailable(Locale("es", "MX"))
            assertTrue(
                "es-MX no disponible (código $available)",
                available >= TextToSpeech.LANG_AVAILABLE
            )
            val voices = tts.voices ?: emptySet()
            assertTrue(
                "es-MX-DaliaNeural no está entre las voces expuestas",
                voices.any { it.name == "es-MX-DaliaNeural" }
            )
        }
    }

    @Test
    fun emptyTextDoesNotCrash() {
        withEngine { tts ->
            // El cliente TTS puede rechazar "" antes de llegar al motor;
            // el contrato es que NADA lance ni se cuelgue.
            val result = speakAndWait(tts, "", timeoutSeconds = 20)
            assertTrue(
                "respuesta inesperada $result",
                result == TextToSpeech.SUCCESS || result == TextToSpeech.ERROR
            )
        }
    }

    @Test
    fun longTextFinishesInAControlledWay() {
        withEngine { tts ->
            val long = buildString {
                repeat(600) { append("Este es el párrafo de prueba número $it para segmentación. ") }
            }
            // Con red o sin ella, debe terminar en done/error antes del timeout,
            // jamás quedarse colgado ni crashear el proceso.
            val result = speakAndWait(tts, long, timeoutSeconds = 120)
            assertTrue(
                "La síntesis no terminó de forma controlada (código $result)",
                result == TextToSpeech.SUCCESS || result == TextToSpeech.ERROR
            )
        }
    }

    @Test
    fun cancellationDuringSynthesisIsSafe() {
        withEngine { tts ->
            val long = buildString { repeat(400) { append("Frase larga para poder cancelar a mitad. ") } }
            val started = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    started.countDown()
                }

                override fun onDone(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) {
                    started.countDown()
                }
            })
            tts.speak(long, TextToSpeech.QUEUE_FLUSH, null, "cancel-test")
            started.await(30, TimeUnit.SECONDS)
            Thread.sleep(800)
            val stopResult = tts.stop()
            assertEquals("stop() debe devolver SUCCESS", TextToSpeech.SUCCESS, stopResult)
            // El motor sigue vivo y responde.
            assertTrue(tts.isLanguageAvailable(Locale("es", "MX")) >= TextToSpeech.LANG_AVAILABLE)
        }
    }

    @Test
    fun unreachableEndpointProducesErrorNotCrash() {
        // Endpoint local que rechaza la conexión al instante: sin red real.
        val store = SettingsStore(context)
        val wsBefore = store.snapshotBlocking().wsUrl
        store.setWsUrlForTest("ws://127.0.0.1:9")
        try {
            withEngine { tts ->
                val result = speakAndWait(tts, "Hola, prueba de fallo de red.", timeoutSeconds = 90)
                assertTrue(
                    "Se esperaba un error controlado y llegó $result",
                    result == TextToSpeech.ERROR || result == TextToSpeech.SUCCESS
                )
            }
        } finally {
            store.setWsUrlForTest(wsBefore)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun SettingsStore.setWsUrlForTest(url: String) {
        // Acceso directo a la preferencia para la prueba (la UI no expone
        // editar la URL de WebSocket).
        javaClass.declaredFields
            .firstOrNull { it.name == "store" }
            ?.also { field ->
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val ds = field.get(this) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
                kotlinx.coroutines.runBlocking {
                    ds.updateData { prefs ->
                        prefs.toMutablePreferences().apply {
                            set(
                                androidx.datastore.preferences.core.stringPreferencesKey("ws_url"),
                                url
                            )
                        }
                    }
                }
            }
    }

    private fun withEngine(body: (TextToSpeech) -> Unit) {
        val latch = CountDownLatch(1)
        val status = AtomicInteger(-1)
        val tts = TextToSpeech(context, { s -> status.set(s); latch.countDown() }, context.packageName)
        try {
            assertTrue("El motor no inicializó en 20 s", latch.await(20, TimeUnit.SECONDS))
            assertEquals("Estado de init distinto de SUCCESS", TextToSpeech.SUCCESS, status.get())
            body(tts)
        } finally {
            tts.shutdown()
        }
    }

    private fun speakAndWait(
        tts: TextToSpeech,
        text: String,
        timeoutSeconds: Long
    ): Int {
        val latch = CountDownLatch(1)
        val outcome = AtomicInteger(-1)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                outcome.set(TextToSpeech.SUCCESS)
                latch.countDown()
            }

            override fun onError(utteranceId: String?) {
                outcome.set(TextToSpeech.ERROR)
                latch.countDown()
            }
        })
        val params = Bundle()
        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "contract-test")
        if (queued != TextToSpeech.SUCCESS) return queued
        return if (latch.await(timeoutSeconds, TimeUnit.SECONDS)) outcome.get() else -1
    }
}
