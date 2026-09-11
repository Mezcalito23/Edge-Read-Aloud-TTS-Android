package dev.experimental.edgetts

import java.util.Locale

/**
 * Muestras del botón «Escuchar ejemplo» de Ajustes y de «Probar voz».
 * Una entrada por idioma del catálogo Edge: [forIso2] nunca cae a inglés
 * salvo que la voz sea inglesa.
 */
object SampleTexts {

    fun forRequested(languageExtra: String?, countryExtra: String? = null): String {
        val snap = runCatching { SettingsStore.current() }.getOrNull()
        if (snap != null && snap.unifiedVoiceMode && snap.voice.isNotBlank()) {
            return forVoice(snap.voice)
        }
        val fromExtra = iso2Language(languageExtra?.substringBefore('-').orEmpty())
        val fromHint = SharedProtocol.lastSampleIso2
        val iso2 = when {
            fromExtra.isNotEmpty() && fromExtra != "es" -> fromExtra
            fromExtra == "es" && fromHint.isNotEmpty() && fromHint != "es" -> fromHint
            fromExtra.isNotEmpty() -> fromExtra
            fromHint.isNotEmpty() -> fromHint
            else -> iso2Language(countryExtra.orEmpty()).ifEmpty { "es" }
        }
        return forIso2(iso2)
    }

    fun forVoice(voiceName: String): String =
        forIso2(iso2Language(LocaleCodes.localeOfVoiceName(voiceName).substringBefore('-')))

    fun forIso2(iso2: String): String {
        val raw = iso2.trim().lowercase(Locale.ROOT).replace('_', '-')
        val tag = raw.substringBefore('-')
        SAMPLES[tag]?.let { return it }
        val mapped = iso2Language(tag)
        if (mapped.isNotEmpty()) SAMPLES[mapped]?.let { return it }
        return if (mapped == "en" || tag == "en") SAMPLES.getValue("en") else SAMPLES.getValue("es")
    }

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
        if (tag.length == 2 || tag == "yue" || tag == "wuu" || tag == "fil") return tag
        return LocaleCodes.iso3ToIso2LangOrSelf(tag).let { if (it.length == 2 || it == "yue") it else "" }
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
            "zh", "yue", "wuu" -> Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            "ja" -> Character.UnicodeBlock.HIRAGANA
            "ko" -> Character.UnicodeBlock.HANGUL_SYLLABLES
            "ar", "fa", "ur", "ps" -> Character.UnicodeBlock.ARABIC
            "he" -> Character.UnicodeBlock.HEBREW
            "ru", "uk", "bg", "sr", "mk", "kk", "mn" -> Character.UnicodeBlock.CYRILLIC
            "hi", "mr", "ne" -> Character.UnicodeBlock.DEVANAGARI
            "th" -> Character.UnicodeBlock.THAI
            "el" -> Character.UnicodeBlock.GREEK
            "hy" -> Character.UnicodeBlock.ARMENIAN
            "ka" -> Character.UnicodeBlock.GEORGIAN
            "am" -> Character.UnicodeBlock.ETHIOPIC
            "ta" -> Character.UnicodeBlock.TAMIL
            "te" -> Character.UnicodeBlock.TELUGU
            "kn" -> Character.UnicodeBlock.KANNADA
            "ml" -> Character.UnicodeBlock.MALAYALAM
            "gu" -> Character.UnicodeBlock.GUJARATI
            "pa" -> Character.UnicodeBlock.GURMUKHI
            "bn" -> Character.UnicodeBlock.BENGALI
            "si" -> Character.UnicodeBlock.SINHALA
            "km" -> Character.UnicodeBlock.KHMER
            "lo" -> Character.UnicodeBlock.LAO
            "my" -> Character.UnicodeBlock.MYANMAR
            else -> null
        } ?: return false
        return text.any { Character.UnicodeBlock.of(it) == block }
    }

    private val ALIASES = mapOf(
        "zho" to "zh", "chi" to "zh", "cmn" to "zh",
        "jpn" to "ja", "kor" to "ko", "fil" to "tl", "tgl" to "tl",
        "heb" to "he", "iw" to "he", "ind" to "id", "in" to "id",
        "ger" to "de", "deu" to "de", "fre" to "fr", "fra" to "fr",
        "spa" to "es", "eng" to "en", "ita" to "it", "por" to "pt",
        "nld" to "nl", "dut" to "nl", "rus" to "ru", "tur" to "tr",
        "ara" to "ar", "hin" to "hi", "pol" to "pl", "swe" to "sv",
        "ukr" to "uk", "vie" to "vi", "tha" to "th", "urd" to "ur",
        "fas" to "fa", "per" to "fa", "ron" to "ro", "rum" to "ro",
        "ces" to "cs", "cze" to "cs", "ell" to "el", "gre" to "el",
        "hun" to "hu", "fin" to "fi", "dan" to "da", "nor" to "nb",
        "yue" to "yue", "wuu" to "wuu"
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
        "yue" to "你好。呢個係 Edge Read Aloud 引擎嘅測試。你聽到嘅聲音係雲端合成嘅。",
        "wuu" to "侬好。迭是 Edge Read Aloud 引擎个测试。侬听见个语音是云端合成个。",
        "sv" to "Hej. Detta är ett test av Edge Read Aloud-motorn. Rösten du hör syntetiseras i molnet.",
        "da" to "Hej. Dette er en test af Edge Read Aloud-motoren. Stemmen du hører syntetiseres i skyen.",
        "fi" to "Hei. Tämä on Edge Read Aloud -moottorin testi. Kuulemasi ääni syntetisoidaan pilvessä.",
        "nb" to "Hei. Dette er en test av Edge Read Aloud-motoren. Stemmen du hører syntetiseres i skyen.",
        "no" to "Hei. Dette er en test av Edge Read Aloud-motoren. Stemmen du hører syntetiseres i skyen.",
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
        "fil" to "Kumusta. Ito ay isang pagsubok ng Edge Read Aloud engine. Ang boses ay sintesado sa cloud.",
        "ca" to "Hola. Aquesta és una prova del motor Edge Read Aloud. La veu es sintetitza al núvol.",
        "af" to "Hallo. Dit is 'n toets van die Edge Read Aloud-enjin. Die stem word in die wolk gesintetiseer.",
        "am" to "ሰላም። ይህ የ Edge Read Aloud ፈተና ነው። ድምፁ በደመና ውስጥ ተዋህዷል።",
        "az" to "Salam. Bu, Edge Read Aloud mühərrikinin testidir. Səs buludda sintez olunur.",
        "bg" to "Здравейте. Това е тест на двигателя Edge Read Aloud. Гласът се синтезира в облака.",
        "bn" to "হ্যালো। এটি Edge Read Aloud ইঞ্জিনের একটি পরীক্ষা। কণ্ঠস্বর ক্লাউডে সংশ্লেষিত হয়।",
        "bs" to "Zdravo. Ovo je test Edge Read Aloud motora. Glas se sintetiše u oblaku.",
        "cy" to "Helo. Mae hon yn brawf o'r peiriant Edge Read Aloud. Mae'r llais yn cael ei syntheseiddio yn y cwmwl.",
        "et" to "Tere. See on Edge Read Aloudi mootori test. Hääl sünteesitakse pilves.",
        "eu" to "Kaixo. Hau Edge Read Aloud motorraren proba bat da. Ahotsa hodeian sintetizatzen da.",
        "ga" to "Dia duit. Is tástáil é seo ar inneall Edge Read Aloud. Déantar an ghuth a shintéisiú sa scamall.",
        "gl" to "Ola. Esta é unha proba do motor Edge Read Aloud. A voz sintetízase na nube.",
        "gu" to "નમસ્તે. આ Edge Read Aloud એન્જિનની કસોટી છે. અવાજ ક્લાઉડમાં સંશ્લેષિત થાય છે.",
        "hr" to "Bok. Ovo je test Edge Read Aloud motora. Glas se sintetizira u oblaku.",
        "hy" to "Բարև։ Սա Edge Read Aloud շարժիչի թեստ է։ Ձայնը սինթեզվում է ամպում։",
        "is" to "Halló. Þetta er prófun á Edge Read Aloud vélinni. Röddin er mynduð í skýinu.",
        "iu" to "ᐊᐃ. ᐅᓇ Edge Read Aloud ᖃᕆᑕᐅᔭᕆᐊᖃᕐᑐᖅ. ᓂᐱ ᓄᓇᕐᔪᐊᒥ ᓴᖅᑭᑦᑎᔪᖅ.",
        "jv" to "Halo. Iki minangka tes mesin Edge Read Aloud. Swara kasebut disintesis ing cloud.",
        "ka" to "გამარჯობა. ეს არის Edge Read Aloud ძრავის ტესტი. ხმა სინთეზდება ღრუბელში.",
        "kk" to "Сәлеметсіз бе. Бұл Edge Read Aloud қозғалтқышының сынағы. Дауыс бұлтта синтезделеді.",
        "km" to "សួស្តី។ នេះគឺជាការសាកល្បងម៉ាស៊ីន Edge Read Aloud។ សំឡេងត្រូវបានសំយោគនៅក្នុងពពក។",
        "kn" to "ನಮಸ್ಕಾರ. ಇದು Edge Read Aloud ಎಂಜಿನ್‌ನ ಪರೀಕ್ಷೆಯಾಗಿದೆ. ಧ್ವನಿಯು ಕ್ಲೌಡ್‌ನಲ್ಲಿ ಸಂಶ್ಲೇಷಿಸಲ್ಪಟ್ಟಿದೆ.",
        "lo" to "ສະບາຍດີ. ນີ້ແມ່ນການທົດສອບເຄື່ອງຈັກ Edge Read Aloud. ສຽງຖືກສັງເຄາະໃນຄລາວ.",
        "lt" to "Sveiki. Tai yra Edge Read Aloud variklio testas. Balsas sintetinamas debesyje.",
        "lv" to "Sveiki. Šis ir Edge Read Aloud motora tests. Balss tiek sintezēta mākonī.",
        "mk" to "Здраво. Ова е тест на Edge Read Aloud моторот. Гласот се синтетизира во облакот.",
        "ml" to "ഹലോ. ഇത് Edge Read Aloud എഞ്ചിന്റെ പരീക്ഷണമാണ്. ശബ്ദം ക്ലൗഡിൽ സംശ്ലേഷണം ചെയ്യപ്പെടുന്നു.",
        "mn" to "Сайн байна уу. Энэ бол Edge Read Aloud хөдөлгүүрийн тест юм. Дуу нь үүлэнд нийлэгждэг.",
        "mr" to "नमस्कार. हे Edge Read Aloud इंजिनचे परीक्षण आहे. आवाज क्लाउडमध्ये संश्लेषित केला जातो.",
        "mt" to "Bongu. Dan huwa test tal-mutur Edge Read Aloud. Il-vuċi hija sintetizzata fis-sħaba.",
        "my" to "မင်္ဂလာပါ။ ဒါက Edge Read Aloud အင်ဂျင်ရဲ့ စမ်းသပ်မှုဖြစ်ပါတယ်။ အသံကို cloud မှာ ပေါင်းစပ်ထားပါတယ်။",
        "ne" to "नमस्ते। यो Edge Read Aloud इन्जिनको परीक्षण हो। आवाज क्लाउडमा संश्लेषित गरिएको छ।",
        "or" to "ନମସ୍କାର। ଏହା Edge Read Aloud ଇଞ୍ଜିନର ଏକ ପରୀକ୍ଷା। ସ୍ୱର କ୍ଲାଉଡରେ ସଂଶ୍ଳେଷିତ ହୁଏ।",
        "pa" to "ਸਤ ਸ੍ਰੀ ਅਕਾਲ। ਇਹ Edge Read Aloud ਇੰਜਣ ਦੀ ਇੱਕ ਪਰਖ ਹੈ। ਆਵਾਜ਼ ਕਲਾਉਡ ਵਿੱਚ ਸੰਸਲੇਸ਼ਿਤ ਹੁੰਦੀ ਹੈ।",
        "ps" to "سلام. دا د Edge Read Aloud انجن ازموینه ده. غږ په کلاوډ کې ترکیب شوی دی.",
        "si" to "ආයුබෝවන්. මෙය Edge Read Aloud එන්ජිමේ පරීක්ෂාවකි. හඬ වලාකුළේ සංශ්ලේෂණය වේ.",
        "sk" to "Ahoj. Toto je test motora Edge Read Aloud. Hlas je syntetizovaný v cloude.",
        "sl" to "Pozdravljeni. To je test motorja Edge Read Aloud. Glas je sintetiziran v oblaku.",
        "so" to "Salaan. Tani waa tijaabada mishiinka Edge Read Aloud. Codka waxaa lagu sameeyaa daruurga.",
        "sq" to "Përshëndetje. Ky është një test i motorit Edge Read Aloud. Zëri sintetizohet në re.",
        "sr" to "Здраво. Ово је тест Edge Read Aloud мотора. Глас се синтетише у облаку.",
        "su" to "Halo. Ieu mangrupikeun tés mesin Edge Read Aloud. Sora disintésis dina cloud.",
        "sw" to "Habari. Hii ni jaribio la injini ya Edge Read Aloud. Sauti inasintesiwa kwenye wingu.",
        "ta" to "வணக்கம். இது Edge Read Aloud இயந்திரத்தின் சோதனை. குரல் கிளவுட்டில் தொகுக்கப்படுகிறது.",
        "te" to "నమస్కారం. ఇది Edge Read Aloud ఇంజన్ యొక్క పరీక్ష. వాయిస్ క్లౌడ్‌లో సంశ్లేషణ చేయబడింది.",
        "uz" to "Salom. Bu Edge Read Aloud dvigatelining sinovidir. Ovoz bulutda sintez qilinadi.",
        "zu" to "Sawubona. Lokhu ukuhlolwa kwenjini ye-Edge Read Aloud. Izwi lakhiwe efwini."
    )
}
