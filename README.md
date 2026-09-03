# Edge Read Aloud TTS — experimental Android TTS engine (v0.6.0)

An Android application that registers as a **system text-to-speech engine**
(`TextToSpeechService`) and synthesizes natural speech through the
**unofficial protocol** used by Microsoft Edge Read Aloud. Verified byte by
byte against the reference client `rany2/edge-tts` 7.2.8 and against real
wire captures.

> ⚠️ **This is NOT an official Microsoft API.** The protocol is neither
> published nor guaranteed: Microsoft may change or block it at any time.
> The app is designed to **fail in a controlled way** (403, 429, WebSocket
> closures, timeouts, malformed responses) without ever hanging and without
> infinite retry loops.
>
> 🔒 **Privacy:** the text you synthesize **is sent to remote servers**. By
> default no text or audio is stored; the local cache (opt-in) stores audio
> only, indexed by its SHA-256 hash. The app does not read Edge accounts,
> cookies or credentials, and never prints tokens to Logcat.

---

## Requirements

| Tool | Version |
|---|---|
| Android Studio | August 2026 stable (or earlier, with the task aliases) |
| Android Gradle Plugin | 9.3.0 |
| Gradle | 9.5.0 (wrapper; compatible with 9.7.x) |
| JDK | 17 (toolchain) |
| compileSdk / targetSdk | 36 |
| minSdk | 26 (Android 8.0) |

Kotlin ships **integrated in AGP 9** (built-in Kotlin): that is why you will
**not** see `org.jetbrains.kotlin.android` in `plugins {}`.

**External dependencies (only two):**

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("androidx.datastore:datastore-preferences:1.2.1")
```

No Retrofit, Ktor, Gson, Hilt, Room, Kotlin coroutines, Media3 or NDK.
Concurrency uses `ExecutorService`, `HandlerThread`/`Handler` and plain
threads.

---

## Open and build

1. **Generate the wrapper** (if the repository does not include it):

   ```bash
   gradle wrapper --gradle-version 9.5.0
   ```

   or open the folder directly in Android Studio: **File → Open → `android-project/`**.

2. **Unit tests** (JVM, no network):

   ```bash
   ./gradlew test
   ```

3. **Build the APK:**

   ```bash
   ./gradlew assembleDebug
   ```

   The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

4. **Instrumented tests** (require a device/emulator with API 26+, no real network):

   ```bash
   ./gradlew connectedDebugAndroidTest
   ```

## Install and enable the engine

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then, on the device:

1. **Settings → System → Text-to-speech** (the path varies by vendor; also
   "Languages & input").
2. **Preferred engine** (gear icon) → **Edge Read Aloud TTS**.
3. Language: whichever you prefer (the engine covers ~75 catalog languages).
4. Press **Listen to an example**, or open this app and use **Test voice**.

**Android 13+**: if the app was installed from an APK and the engine does not
appear, enable *"Allow restricted settings"* under Settings → Apps → Edge
Read Aloud TTS, or via adb:

```bash
adb shell appops set --uid dev.experimental.edgetts android:allow_restricted_settings allow
```

## Inspect errors with adb logcat

```bash
adb logcat -s EdgeTtsService EdgeTtsClient
```

Human-readable errors are also stored in the app (**Last error**) via
DataStore, together with the **handshake diagnostics** (metadata only: hash
prefixes, UTC window, received paths — never tokens).

---

## Architecture

```text
MainActivity
  └── SettingsController
        └── SettingsStore          (DataStore Preferences)

EdgeReadAloudTtsService : TextToSpeechService
  ├── VoiceCatalogRepository       (JSON catalog + local fallback)
  ├── TtsProvider ← EdgeProtocolClient  (experimental WebSocket)
  ├── SsmlBuilder                  (XML escaping + speak/voice/prosody)
  ├── AudioFrameParser             (text/binary frames, length prefix)
  ├── TextSegmenter                (≤ 4,000 chars, no word splitting)
  ├── CacheRepository              (SHA-256, 100 MB max, LRU)
  └── Mp3AudioDecoder              (MediaExtractor + MediaCodec → PCM)
```

**Replacement point:** `EdgeReadAloudTtsService` only talks to the
`TtsProvider` interface. To migrate to Azure Cognitive Services or another
backend, implement `TtsProvider` and inject it in `onCreate()`. Nothing else
in the service changes.

## Protocol (verified against the reference)

One WebSocket session per synthesis:

1. Optional `GET` of the voice catalog (15 s connect / 30 s read timeouts).
2. WebSocket opened with the exact URL:
   `…/edge/v1?TrustedClientToken=…&ConnectionId=<uuid>&Sec-MS-GEC=<token>&Sec-MS-GEC-Version=1-143.0.3650.75`
3. Handshake headers: desktop Edge `User-Agent`
   (`Chrome/143.0.0.0 … Edg/143.0.0.0`), `Origin:
   chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold`, `Pragma`/
   `Cache-Control: no-cache`, `Accept-Encoding`, `Accept-Language`, and the
   cookie `Cookie: muid=<32 uppercase hex>;`.
4. The client sends `Path:speech.config` (`X-Timestamp` header in JS format,
   `sentenceBoundaryEnabled:"true"`, `outputFormat:
   audio-24khz-48kbitrate-mono-mp3`) and `Path:ssml` (dash-less UUID
   `X-RequestId`, `X-Timestamp` with the trailing "Z" — a documented Edge
   quirk —, SSML with the LONG voice name).
5. The server replies with `Path:turn.start`, binary `Path:audio` frames
   (2-byte big-endian length prefix; the audio starts at
   `header_length + 2`, replicating `get_headers_and_data`) and
   `Path:turn.end`.
6. Clean close with code 1000.

> 🔑 **`Sec-MS-GEC` token (anti-abuse):** SHA-256 (uppercase hex) of
> `"{ticks}{TrustedClientToken}"`, where ticks = (Unix time + FILETIME 1601
> epoch) rounded down to 5 minutes × 10⁷. **The version is NOT part of the
> hash**; it is sent as a separate parameter (`Sec-MS-GEC-Version`). On a
> **403**, the client corrects clock skew using the server's `Date` header
> and retries **exactly once** (replicating
> `DRM.handle_client_response_error`).

### Audio: MP3 → PCM

The endpoint produces `audio-24khz-48kbitrate-mono-mp3` (48 kbps CBR MP3).
`SynthesisCallback` requires PCM, so `Mp3AudioDecoder` decodes it through the
canonical **MediaExtractor + MediaCodec** pipeline (the extractor hands the
codec a complete MediaFormat; configuring the codec by hand with raw MP3
throws `IllegalStateException` on many devices) and delivers 16-bit 24 kHz
mono PCM to the callback. Opus is deferred to phase 2 behind the same
`AudioDecoder` interface.

### Voice model (like Google TTS)

The engine exposes **ONE voice per language** (~75, not the 322 in the
catalog): for the language of the voice configured in the app (Spanish, Dalia
by default) that voice is the one exposed; **changing it in the app changes
the Spanish voice system-wide** (Play Books, Neo Reader, any app). For other
languages a representative catalog voice is exposed and resolved
automatically from the content language (an English book is read with an
English voice with no configuration).

### Engine language = system language

The engine initializes its language to the **device locale** (English on an
English device, French on a French one…) and negotiates ISO2 and ISO3 against
the catalog: `LANG_COUNTRY_AVAILABLE` for present locales, `LANG_AVAILABLE`
for languages. `onGetDefaultVoiceNameFor` returns the exposed voice for the
requested language (for Spanish, always the one configured in the app).
Without it, `setLanguage()` would fail and apps like Google Play Books would
fall back to Google TTS.

### Full engine contract

- **`CheckVoiceData`** (`ACTION_CHECK_TTS_DATA`): replies
  `CHECK_VOICE_DATA_PASS` with **one canonical ISO3-COUNTRY entry per
  language** (`spa-MEX`, `eng-USA`…), stable and independent of the
  configured voice (replying with voice-dependent variants used to crash the
  Settings selector). Without this component, local-TTS apps fall back to
  Google TTS.
- **`GetSampleText`** (`GET_SAMPLE_TEXT`): sample text **in the requested
  language** (converts ISO3→ISO2; 15 languages; English fallback).
- **`InstallVoiceData`** (`INSTALL_TTS_DATA`): reports that data is already
  available (100 % online engine).
- Voices with `requiresNetwork = false` (eligible for "offline/local" modes
  such as Play Books'; the network is still required and its absence is
  reported as a clear error).

### Settings toggleable without recompiling

From the app's endpoints card: **User-Agent** and **Origin**. A **UI
language** selector (system / Spanish / English) that works on any Android
version (ContextWrapper; on Android 13+ also the system per-app selector).

## In-app voice test

The **Test voice** button speaks a sample **in the language of the selected
voice**, using that same voice: selecting `en-US-AriaNeural` plays an English
sample with an English voice, `fr-FR-DeniseNeural` plays French, and so on
for all 322 catalog voices. The locale is resolved from the catalog (or
reconstructed from the voice short-name) and the sample text is chosen by the
voice's 2-letter language code.

## Errors handled

| Situation | Message (UI) |
|---|---|
| 403 | Access denied; protocol changed or client blocked (context renewal already attempted) |
| 429 | Rate limit; reduce frequency |
| 400 | Invalid SSML or format |
| 401 | Invalid context/authentication |
| timeout | Slow network or server unavailable |
| EOF | Connection closed before the audio completed |
| invalid audio | Undecodable format (MP3 via MediaCodec; Opus, phase 2) |

## Security

- No private secrets in the APK (the `TrustedClientToken` is a public value
  of the unofficial protocol, configurable, and shown masked in the UI only).
- No collection of passwords, cookies or Edge data.
- No authentication bypass and no traffic obfuscation.
- Text is not stored by default; the cache stores audio under a SHA-256 key
  and can be cleared from the app.
- Token-free Logcat: URLs with sensitive parameters are never printed.

## Known limitations (v0.6)

- The protocol remains unofficial: if Microsoft rotates the accepted
  version, the handshake will return 403 again. No-recompile adjustments:
  User-Agent and Origin from the app; as a last resort,
  `EdgeProtocolConstants.CLIENT_VERSION`.
- The decoder is MP3; Opus is deferred to phase 2 behind the `AudioDecoder`
  interface.
- The width of the system Settings language box is drawn by Android (not the
  engine); on narrow screens it may appear clipped.
- The system Settings "Speech rate", "Pitch" and "Play example" controls may
  appear disabled for this engine on some devices (see *System Settings
  controls* below).
- Single module, XML Views, no coroutines: an explicit decision to minimize
  dependencies.

## Checkpoint #2 (v0.8.3) — estado estable verificado

Punto de restauración seguro que integra todo el feedback desde el Checkpoint
#1 (v0.6.8):

- **Modelo 1 consolidado:** la voz elegida en la app manda en todo el sistema
  para su idioma (toggle "La voz elegida aquí manda en todo el sistema",
  ON por defecto; apagado restaura la prioridad por país).
- **Sliders de velocidad/tono** (-50…+50, centro = normal) con botón de
  restablecer; persisten y se combinan con los sliders de Ajustes
  (el sistema puede llevar la velocidad hasta +100% real, como Edge).
- **Valores 0 = navegador Edge** exactamente; las pausas en comas/puntos son
  la prosodia natural de las voces neuronales de Edge (documentado en la UI).
- **Prueba de voz en ~75 idiomas:** cada voz no inglesa lee la muestra en su
  propio idioma.
- **Botón de Ajustes** con la acción literal `com.android.settings.TTS_SETTINGS`
  (sin `resolveActivity`, que fallaba en Android 11+).
- **Negociación ISO2+ISO3** doble formato con conversión real vía tablas de
  `Locale` (sin constructores deprecados).
- **Resiliencia al catálogo:** solo se sintetiza con voces presentes en el
  catálogo descargado (Microsoft puede retirar/agregar voces).

Para restaurar este punto: `git checkout` del tag/commit del checkpoint, o
re-descargar `android-project-v22-completo.zip` de la consola.

## Voice source of truth — the app's voice drives the system (Model 1)

Android keeps the TTS default in `Settings.Secure`, which a regular app
cannot write, so true two-way sync is impossible. This engine implements
**Model 1 — the app is the source of truth**, verified against AOSP: since
API 21, `TextToSpeech.setLanguage` resolves by calling `setVoice` with the
voice returned by `onGetDefaultVoiceNameFor`, so the engine controls the
locale→voice mapping, not the system.

- **Unified voice mode (ON by default):** for the language of the voice you
  pick in the app, *that exact voice* is used system-wide (Play Books, Neo
  Reader, Settings) for every variant of the language (`es-MX`, `es-PE`,
  `es-419`…). It also cleanly resolves `es-419`, which has no Edge voice of
  its own. Turning it off restores per-country priority (each Spanish
  variant with its regional voice, as in v18).
- **Other languages** resolve automatically to a catalog voice (country
  preferred when available).
- **Precedence:** explicit `request.voiceName` (user picked a concrete voice
  in Settings) > unified/default resolution > catalog fallback > Dalia.
- The app shows a disclaimer: the generated voice is the one selected in the
  app, and the engine must be chosen as the preferred TTS engine in Android
  Settings for it to work.

### Rate & pitch sliders

Speed and pitch are adjusted with **-50…+50 sliders** (center = the voice's
normal rate/pitch) instead of raw numeric fields. The values persist on exit
and are honored by every app using the engine; they combine additively with
the system Settings sliders (`request.speechRate`/`request.pitch`, 100 = 1.0x)
and are clamped to Edge's accepted range. Pitch is sent in Hz (`+XHz`), the
unit the Edge endpoint is known to accept.

## System Settings controls (Speech rate / Pitch / Play example)

On some devices the Android system TTS Settings screen shows the **Speech
rate**, **Pitch** and **Play example** controls disabled for this engine.
This is a Settings-side behavior, not a synthesis failure:

- AOSP `TextToSpeechSettings` enables those three controls together via
  `updateWidgetState(true)`, and only after this chain succeeds in the
  Settings process:
  1. engine init reports `SUCCESS`;
  2. `getDefaultLanguage()` is non-null (we answer with ISO3 codes);
  3. the `ACTION_CHECK_TTS_DATA` activity returns
     `CHECK_VOICE_DATA_PASS` with a non-empty `EXTRA_AVAILABLE_VOICES`;
  4. the engine default locale, normalized to `ISO3lang-ISO3country`
     (e.g. `spa-MEX`), matches one of those available voices
     (our `CheckVoiceData` answers in exactly that canonical form);
  5. `setLanguage(defaultLocale)` returns ≥ `LANG_AVAILABLE`.
  Our engine implements all five steps, so on stock Android the controls
  light up. Vendor skins (e.g. HarmonyOS) add their own extra gating that a
  third-party engine cannot influence, so there they may stay disabled.
- Actual synthesis through real apps (Play Books, Neo Reader, this app) works
  regardless, because those apps drive the engine directly rather than
  through the Settings probe.

**Lesson learned (v0.6.2 → v0.6.4):** an ISO2 experiment (`es-MX` instead of
`spa-MEX`) made the Settings screen **close** on some devices, because the
screen compares the reported locales against `getDefaultLanguage()`, whose
contract is **ISO3** — in Java `Locale("es") != Locale("spa")`, so the
mismatch broke the default-locale lookup. `CheckVoiceData` was reverted to
**ISO3** (`spa-MEX`, `eng-USA`…), the same convention Google TTS reports and
the only one consistent with `onGetLanguage()`. The remaining
disabled-controls behaviour is vendor-side probe logic (stock Android vs
HarmonyOS) that a third-party engine cannot fully control.

If the controls still appear disabled on a given device after this, capture
`adb logcat -s EdgeTtsService` while opening Settings → Text-to-speech and
inspect the `onIsLanguageAvailable` / `onLoadLanguage` queries the Settings
app makes. Stock Android and HarmonyOS run different probe heuristics, so a
per-vendor residual difference is possible and is tracked as a follow-up.

## Voice selection and sync with system Settings

**The voice is chosen in the app and applies system-wide.** The sync is
inherently one-directional, and this is a property of the Android TTS API, not
a limitation of this engine:

- **App → Settings (automatic).** The Settings screen asks the engine for the
  default voice via `onGetDefaultVoiceNameFor()`, and we return the voice the
  user configured in the app. So Settings always *reflects* the app's choice,
  and real apps (Play Books, Neo Reader, Chrome…) synthesize with it.
- **Settings → App (not available).** When a voice is picked in the Settings
  voice picker, AOSP calls `setVoice()` on Settings' own transient probe
  client. That selection is never broadcast to the engine and is not stored in
  any location a third-party engine can read through the public API, so the
  engine cannot observe it. Writing `Settings.Secure` would require the
  system-only `WRITE_SECURE_SETTINGS` permission. The reliable way to change
  the voice is therefore in this app.

**Multiple voices per language are exposed.** `onGetVoices()` returns the full
catalog (~300 voices), each with its real locale. In Settings → Text-to-speech
→ *Default voice* (or the per-language voice row) Android lists them grouped by
locale, so Spanish (Mexico) shows Dalia, Jorge, etc., and English/Chinese show
their several voices. If only one row is visible, the catalog likely hadn't
loaded yet (first launch without network); refresh it from the app.

## "Open system Text-to-speech settings" button

The button uses the official action `Settings.ACTION_TTS_SETTINGS`
(`"com.android.settings.TTS_SETTINGS"`), falling back to the general Settings
screen. (An earlier revision used the non-existent
`"android.settings.TTS_SETTINGS"` action, which is why the button opened
nothing on every device; that is fixed.)
