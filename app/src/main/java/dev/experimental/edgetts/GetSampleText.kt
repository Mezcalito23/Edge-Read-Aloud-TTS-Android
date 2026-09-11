package dev.experimental.edgetts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * «Escuchar ejemplo» de Ajustes. El texto va EN EL IDIOMA DE LA VOZ
 * seleccionada, no en el de la UI (español en esta tablet).
 */
class GetSampleText : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lang = requestedLanguage()
        val country = requestedCountry()
        SharedProtocol.markSettingsSample(lang.orEmpty(), country.orEmpty())
        val sample = runCatching {
            SampleTexts.forRequested(lang, country)
        }.getOrElse { SampleTexts.forIso2("en") }
        setResult(
            RESULT_OK,
            Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
        )
        finish()
    }

    private fun requestedLanguage(): String? {
        runCatching {
            intent.getStringArrayListExtra("android.speech.tts.engine.extra.CHECK_VOICE_DATA_FOR")
                ?.firstOrNull { it.isNotBlank() }
                ?.let { return it }
        }
        for (key in EXTRA_LANGUAGE_KEYS) {
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun requestedCountry(): String? {
        for (key in EXTRA_COUNTRY_KEYS) {
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    companion object {
        private val EXTRA_LANGUAGE_KEYS = arrayOf(
            "language",
            "locale",
            "android.speech.tts.extra.SAMPLE_TEXT_LANGUAGE"
        )
        private val EXTRA_COUNTRY_KEYS = arrayOf("country", "region")
    }
}
