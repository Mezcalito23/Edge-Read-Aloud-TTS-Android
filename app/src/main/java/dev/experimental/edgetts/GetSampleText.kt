package dev.experimental.edgetts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Actividad de contrato para la acción
 * `android.speech.tts.engine.GET_SAMPLE_TEXT`: el botón «Escuchar ejemplo» de
 * los Ajustes pide un texto de muestra para el idioma actual y el motor
 * responde RESULT_OK + EXTRA_SAMPLE_TEXT; el sistema lo sintetiza a
 * continuación con este mismo motor.
 *
 * El texto es GENÉRICO (no menciona ninguna voz concreta) y se entrega EN EL
 * IDIOMA SOLICITADO cuando hay muestra disponible: si el selector usa
 * inglés, la muestra es inglesa; si francés, francesa; etc. Así el botón de
 * ejemplo es útil aunque la voz configurada no sea española.
 *
 * Robustez: SIEMPRE responde RESULT_OK con algún texto (respaldo en inglés);
 * nunca crashea la pantalla de Ajustes.
 */
class GetSampleText : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var sample = SAMPLES["en"].orEmpty()
        try {
            sample = sampleFor(requestedLanguage())
        } catch (_: Throwable) {
            // Respaldo: muestra en inglés.
        }
        val data = Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
        setResult(RESULT_OK, data)
        finish()
    }

    /**
     * Idioma que pide Ajustes. Cada versión de Android/OEM lo envía de forma
     * distinta, así que se prueban varias fuentes:
     *  1. `EXTRA_CHECK_VOICE_DATA_FOR` (lista de locales; la vía estándar);
     *  2. el extra plano `language`.
     * Si no llega ninguno → null → muestra por defecto (inglés).
     */
    private fun requestedLanguage(): String? {
        runCatching {
            // Literal en vez de TextToSpeech.Engine.EXTRA_CHECK_VOICE_DATA_FOR
            // (constante deprecada en los SDK recientes; el valor es estable).
            intent.getStringArrayListExtra("android.speech.tts.engine.extra.CHECK_VOICE_DATA_FOR")
                ?.firstOrNull { it.isNotBlank() }
                ?.let { return it }
        }
        return intent.getStringExtra(EXTRA_LANGUAGE)?.takeIf { it.isNotBlank() }
    }

    /**
     * Resuelve la muestra para un locale en cualquier formato típico:
     * `spa-MEX`, `spa_MEX`, `en-US`, `eng-USA`, `fra`, `en`… Se normaliza la
     * sub-etiqueta de idioma a ISO2. Nulo o no reconocible → inglés.
     */
    private fun sampleFor(languageExtra: String?): String {
        val raw = languageExtra?.trim().orEmpty().replace('_', '-')
        if (raw.isEmpty()) return SAMPLES["en"].orEmpty()
        // BUG corregido: antes un ISO3 como "spa" caía al respaldo inglés en
        // vez de resolverse a "es". Ahora se convierte ISO3→ISO2 siempre.
        val lang = iso2Language(raw.substringBefore('-'))
        return SAMPLES[lang] ?: SAMPLES["en"].orEmpty()
    }

    private fun iso2Language(code: String): String {
        val lower = code.lowercase(Locale.ROOT)
        return if (lower.length == 2) lower else iso3ToIso2(lower)
    }

    private fun iso3ToIso2(code: String): String {
        if (code.length != 3) return code
        for (iso2 in Locale.getISOLanguages()) {
            runCatching {
                if (Locale.forLanguageTag(iso2).isO3Language.equals(code, ignoreCase = true)) return iso2
            }
        }
        return code
    }

    companion object {
        // Ajustes pasa el idioma actual en este extra (sin constante pública).
        private const val EXTRA_LANGUAGE = "language"

        /** Muestras genéricas por idioma (sin mencionar voces concretas). */
        private val SAMPLES: Map<String, String> = mapOf(
            "es" to "Hola. Esta es una prueba del motor Edge Read Aloud. " +
                "La voz que escuchas se sintetiza en la nube.",
            "en" to "Hello. This is a test of the Edge Read Aloud engine. " +
                "The voice you hear is synthesized in the cloud.",
            "fr" to "Bonjour. Ceci est un test du moteur Edge Read Aloud. " +
                "La voix que vous entendez est synthétisée dans le cloud.",
            "de" to "Hallo. Dies ist ein Test der Edge Read Aloud-Engine. " +
                "Die Stimme wird in der Cloud synthetisiert.",
            "it" to "Ciao. Questo è un test del motore Edge Read Aloud. " +
                "La voce che senti è sintetizzata nel cloud.",
            "pt" to "Olá. Este é um teste do motor Edge Read Aloud. " +
                "A voz que você ouve é sintetizada na nuvem.",
            "nl" to "Hallo. Dit is een test van de Edge Read Aloud-engine. " +
                "De stem die u hoort, wordt in de cloud gesynthetiseerd.",
            "ru" to "Привет. Это тест движка Edge Read Aloud. " +
                "Голос, который вы слышите, синтезируется в облаке.",
            "pl" to "Cześć. To jest test silnika Edge Read Aloud. " +
                "Głos, który słyszysz, jest syntezowany w chmurze.",
            "tr" to "Merhaba. Bu, Edge Read Aloud motorunun bir testidir. " +
                "Duyduğunuz ses bulutta sentezlenmektedir.",
            "ar" to "مرحباً. هذا اختبار لمحرك Edge Read Aloud. " +
                "الصوت الذي تسمعه يتم توليفه في السحابة.",
            "hi" to "नमस्ते। यह Edge Read Aloud इंजन का परीक्षण है। " +
                "आप जो आवाज़ सुन रहे हैं वह क्लाउड में संश्लेषित की जाती है।",
            "ja" to "こんにちは。これは Edge Read Aloud エンジンのテストです。" +
                "お聞きの声はクラウドで合成されています。",
            "ko" to "안녕하세요. Edge Read Aloud 엔진 테스트입니다. " +
                "들리는 음성은 클라우드에서 합성됩니다.",
            "zh" to "你好。这是 Edge Read Aloud 引擎的测试。" +
                "您听到的语音由云端合成。"
        )
    }
}
