package dev.experimental.edgetts

import java.util.Locale

/**
 * Muestras del botón «Escuchar ejemplo» de Ajustes. Varios OEM (p. ej. e-ink)
 * mandan el texto en el idioma de la UI (español) aunque la voz sea china
 * o japonesa. [alignDemo] lo corrige en síntesis.
 */
object SampleTexts {

    fun forRequested(languageExtra: String?, countryExtra: String? = null): String {
        val snap = runCatching { SettingsStore.current() }.getOrNull()
        if (snap != null && snap.unifiedVoiceMode && snap.voice.isNotBlank()) {
            return forIso2(
                iso2Language(LocaleCodes.localeOfVoiceName(snap.voice).substringBefore('-'))
            )
        }
        val fromExtra = iso2Language(languageExtra?.substringBefore('-').orEmpty())
        val fromHint = SharedProtocol.lastSampleIso2
        val iso2 = when {
            fromExtra.isNotEmpty() && fromExtra != "es" -> fromExtra
            fromExtra == "es" && fromHint.isNotEmpty() && fromHint != "es" -> fromHint
            fromExtra.isNotEmpty() -> fromExtra
            fromHint.isNotEmpty() -> fromHint
            else -> iso2Language(countryExtra.orEmpty()).ifEmpty { "en" }
        }
        return forIso2(iso2)
    }

    fun forIso2(iso2: String): String = SAMPLES[iso2] ?: SAMPLES["en"].orEmpty()

    fun alignDemo(
        text: String,
        voiceName: String,
        unified: Boolean = false,
        force: Boolean = false
    ): String {
        val voiceIso2 = iso2Language(
            LocaleCodes.localeOfVoiceName(voiceName).substringBefore('-')
        )
        if (voiceIso2.isEmpty()) return text
        if (text.length > 220) return text
        if (force) return forIso2(voiceIso2)
        val isKnownSample = SAMPLES.values.any { it.equals(text, ignoreCase = true) }
        if (unified && (isKnownSample || looksLikeSettingsDemo(text))) {
            return forIso2(voiceIso2)
        }
        if (voiceIso2 == "es") return text
        if (scriptLooksNative(text, voiceIso2)) return text
        if (!looksLikeSettingsDemo(text) && !isKnownSample) return text
        return forIso2(voiceIso2)
    }

    fun iso2Language(code: String): String {
        val raw = code.trim().lowercase(Locale.ROOT).replace('_', '-')
        if (raw.isEmpty()) return ""
        val tag = raw.substringBefore('-')
        ALIASES[tag]?.let { return it }
        if (tag.length == 2) return tag
        return LocaleCodes.iso3ToIso2LangOrSelf(tag).let { if (it.length == 2) it else "" }
    }

    private fun looksLikeSettingsDemo(text: String): Boolean {
        val n = text.lowercase(Locale.ROOT)
        if (SAMPLES.values.any { it.equals(text, ignoreCase = true) }) return true
        return listOf(
            "síntesis de voz", "sintesis de voz", "síntesis de habla",
            "demostración de la síntesis", "demostracion de la sintesis",
            "esta es una prueba del motor", "esta es una demostración",
            "edge read aloud", "text to speech", "speech synthesis",
            "example of speech", "ejemplo de voz", "escuchas se sintetiza"
        ).any { n.contains(it) }
    }

    private fun scriptLooksNative(text: String, iso2: String): Boolean {
        val block = when (iso2) {
            "zh", "yue" -> Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            "ja" -> Character.UnicodeBlock.HIRAGANA
            "ko" -> Character.UnicodeBlock.HANGUL_SYLLABLES
            "ar", "fa", "ur" -> Character.UnicodeBlock.ARABIC
            "he" -> Character.UnicodeBlock.HEBREW
            "ru", "uk", "bg", "sr" -> Character.UnicodeBlock.CYRILLIC
            "hi", "mr", "ne" -> Character.UnicodeBlock.DEVANAGARI
            "th" -> Character.UnicodeBlock.THAI
            "el" -> Character.UnicodeBlock.GREEK
            else -> null
        } ?: return false
        return text.any { Character.UnicodeBlock.of(it) == block }
    }

    private val ALIASES = mapOf(
        "zho" to "zh", "chi" to "zh", "cmn" to "zh", "yue" to "zh",
        "jpn" to "ja", "kor" to "ko", "fil" to "tl", "tgl" to "tl",
        "heb" to "he", "iw" to "he", "ind" to "id", "in" to "id",
        "ger" to "de", "deu" to "de", "fre" to "fr", "fra" to "fr",
        "spa" to "es", "eng" to "en", "ita" to "it", "por" to "pt",
        "nld" to "nl", "dut" to "nl", "rus" to "ru", "tur" to "tr",
        "ara" to "ar", "hin" to "hi", "pol" to "pl", "swe" to "sv",
        "ukr" to "uk", "vie" to "vi", "tha" to "th", "urd" to "ur",
        "fas" to "fa", "per" to "fa", "ron" to "ro", "rum" to "ro",
        "ces" to "cs", "cze" to "cs", "ell" to "el", "gre" to "el",
        "hun" to "hu", "fin" to "fi", "dan" to "da", "nor" to "nb"
    )

    val SAMPLES: Map<String, String> = mapOf(
        "es" to "Hola. Esta es una prueba del motor Edge Read Aloud. La voz que escuchas se sintetiza en la nube.",
        "en" to "Hello. This is a test of the Edge Read Aloud engine. The voice you hear is synthesized in the cloud.",
        "fr" to "Bonjour. Ceci est un test du moteur Edge Read Aloud. La voix que vous entendez est synthétisée dans le cloud.",
        "de" to "Hallo. Dies ist ein Test der Edge Read Aloud-Engine. Die Stimme wird in der Cloud synthetisiert.",
        "it" to "Ciao. Questo è un test del motore Edge Read Aloud. La voce che senti è sintetizzata nel cloud.",
        "pt" to "Olá. Este é um teste do motor Edge Read Aloud. A voz que você ouve é sintetizada na nuvem.",
        "nl" to "Hallo. Dit is een test van de Edge Read Aloud-engine. De stem die u hoort, wordt in de cloud gesynthetiseerd.",
        "ru" to "Привет. Это тест движка Edge Read Aloud. Голос, который вы слышите, синтезируется в облаке.",
        "pl" to "Cześć. To jest test silnika Edge Read Aloud. Głos, który słyszysz, jest syntezowany w chmurze.",
        "tr" to "Merhaba. Bu, Edge Read Aloud motorunun bir testidir. Duyduğunuz ses bulutta sentezlenmektedir.",
        "ar" to "مرحباً. هذا اختبار لمحرك Edge Read Aloud. الصوت الذي تسمعه يتم توليفه في السحابة.",
        "hi" to "नमस्ते। यह Edge Read Aloud इंजन का परीक्षण है। आप जो आवाज़ सुन रहे हैं वह क्लाउड में संश्लेषित की जाती है।",
        "ja" to "こんにちは。これは Edge Read Aloud エンジンのテストです。お聞きの声はクラウドで合成されています。",
        "ko" to "안녕하세요. Edge Read Aloud 엔진 테스트입니다. 들리는 음성은 클라우드에서 합성됩니다.",
        "zh" to "你好。这是 Edge Read Aloud 引擎的测试。您听到的语音由云端合成。",
        "sv" to "Hej. Detta är ett test av Edge Read Aloud-motorn. Rösten du hör syntetiseras i molnet.",
        "da" to "Hej. Dette er en test af Edge Read Aloud-motoren. Stemmen du hører syntetiseres i skyen.",
        "fi" to "Hei. Tämä on Edge Read Aloud -moottorin testi. Kuulemasi ääni syntetisoidaan pilvessä.",
        "nb" to "Hei. Dette er en test av Edge Read Aloud-motoren. Stemmen du hører syntetiseres i skyen.",
        "el" to "Γεια σας. Αυτό είναι ένα τεστ της μηχανής Edge Read Aloud. Η φωνή συντίθεται στο cloud.",
        "cs" to "Ahoj. Toto je test enginu Edge Read Aloud. Hlas, který slyšíte, je syntetizován v cloudu.",
        "ro" to "Bună. Acesta este un test al motorului Edge Read Aloud. Vocea pe care o auziți este sintetizată în cloud.",
        "hu" to "Helló. Ez az Edge Read Aloud motor tesztje. A hallott hang a felhőben szintetizálódik.",
        "uk" to "Привіт. Це тест рушія Edge Read Aloud. Голос, який ви чуєте, синтезується в хмарі.",
        "vi" to "Xin chào. Đây là bài kiểm tra engine Edge Read Aloud. Giọng nói bạn nghe được tổng hợp trên đám mây.",
        "th" to "สวัสดี นี่คือการทดสอบเครื่องมือ Edge Read Aloud เสียงที่คุณได้ยินสังเคราะห์บนคลาวด์",
        "id" to "Halo. Ini adalah tes mesin Edge Read Aloud. Suara yang Anda dengar disintesis di cloud.",
        "ms" to "Helo. Ini ialah ujian enjin Edge Read Aloud. Suara yang anda dengar disintesis di awan.",
        "he" to "שלום. זהו מבחן של מנוע Edge Read Aloud. הקול שאתה שומע מסונתז בענן.",
        "fa" to "سلام. این آزمایش موتور Edge Read Aloud است. صدایی که می‌شنوید در ابر ساخته می‌شود.",
        "ur" to "سلام۔ یہ Edge Read Aloud انجن کا ٹیسٹ ہے۔ آواز کلاؤڈ میں ترکیب کی جاتی ہے۔",
        "tl" to "Kumusta. Ito ay isang pagsubok ng Edge Read Aloud engine. Ang boses ay sintesado sa cloud.",
        "ca" to "Hola. Aquesta és una prova del motor Edge Read Aloud. La veu es sintetitza al núvol."
    )
}
