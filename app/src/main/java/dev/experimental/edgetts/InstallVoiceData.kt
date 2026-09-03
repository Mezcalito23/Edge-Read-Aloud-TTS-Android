package dev.experimental.edgetts

import android.speech.tts.TextToSpeech

/**
 * Actividad del contrato de motor TTS para la acción
 * `android.speech.tts.engine.INSTALL_TTS_DATA`.
 *
 * La lanzan Ajustes y algunas apps cuando consideran que al motor le faltan
 * datos de voz y quieren "instalarlos". Edge Read Aloud es un motor 100 % en
 * línea: no hay voces que descargar, todas están disponibles desde el primer
 * momento. Por eso esta actividad no instala nada; simplemente informa que los
 * datos YA están presentes respondiendo [TextToSpeech.Engine.CHECK_VOICE_DATA_PASS]
 * con la lista de voces disponibles — exactamente el mismo contrato que
 * [CheckVoiceData], de ahí que la reutilicemos por herencia.
 *
 * Sin esta actividad, el flujo de "comprobar/instalar datos de voz" de algunas
 * apps (entre ellas Google Play Books) queda sin respuesta y hacen fallback a
 * Google TTS.
 */
class InstallVoiceData : CheckVoiceData()
