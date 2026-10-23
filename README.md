# Edge Read Aloud TTS

**v1.0.10** — Android system text-to-speech engine (`TextToSpeechService`) that
speaks with Microsoft Edge Read Aloud neural voices.

The **wire protocol** follows [`rany2/edge-tts`](https://github.com/rany2/edge-tts)
7.3.1 ([PR 492](https://github.com/rany2/edge-tts/pull/492)): endpoint, SSML,
headers, and the Sec-MS-GEC v2 token. The **Android engine behavior** follows
[`ag2s20150909/TTS`](https://github.com/ag2s20150909/TTS): `callback.start()`
before the network (Play Books drops the utterance otherwise) and PCM handed
to the system as each MediaCodec buffer is decoded, not after the whole MP3.

> This is **not** an official Microsoft API. Microsoft may change or block it
> at any time. The engine fails in a controlled way (403, 429, timeouts,
> malformed frames) and never retries forever.
>
> The text you synthesize **is sent to remote servers**. The optional local
> cache stores **MP3 audio only**, keyed by SHA-256. The app does not read
> Microsoft accounts, cookies, or credentials.

## What it does

- Registers as an Android TTS engine (Play Books, Neo Reader, Chrome, Settings).
- Exposes the **full Edge catalog** (~322 voices). Voices may appear or
  disappear when Microsoft updates the list; only catalog voices are used.
- **Unified voice (ON by default):** the voice chosen in this app is used
  system-wide. Turn it off and apps follow the voice set in **Android system
  TTS settings**, not in this app.
- Native sample text per catalog language (Settings “Play example” and in-app
  **Test voice**).
- Persistent WebSocket, MP3 cache (atomic write, LRU), streaming MP3→PCM
  decode, and a light clip of **true** leading/trailing silence so Play Books
  periods are not a hole and words are not chopped.

## Install

No Android Studio. Install the APK on Android 8.0 or newer (minSdk 26),
then pick **Edge Read Aloud TTS** as the system engine.

## Build from source

Only if you compile it yourself. The wrapper in this repo is the version
that matches the project.

| | |
|---|---|
| JDK | 17 |
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.7.1 (wrapper) |
| compileSdk | 37 (no API 37 calls; target stays 36) |
| targetSdk | 36 |
| minSdk | 26 |

Kotlin is **built into AGP 9** — do not add `org.jetbrains.kotlin.android`.

Dependencies: OkHttp 4.12.0 and DataStore Preferences 1.2.1 only.

## Build

```bash
./gradlew test
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # R8 + resource shrink
./gradlew connectedAndroidTest   # device, API 26+
./gradlew installDebug
```

The installed package reports **versionName `1.0.10`** (Settings → Apps →
Edge Read Aloud TTS, and the in-app subtitle).

**Signing:** without a local keystore, release is signed with the debug key
(sideload only). One-time keystore: [SIGNING.md](SIGNING.md).

## Updates

The app checks for updates only when the user taps **Check for updates**.
It reads `versionCode` from an `update.json` asset on the latest stable
GitHub release. It does not compare the tag or `versionName`.

`assembleRelease` still writes `app/build/outputs/apk/release/app-release.apk`.
Generate the manifest from the same `versionCode` and `versionName`:

```bash
./gradlew writeUpdateManifest
```

That writes `app/build/update.json` with `apkAssetName` `app-release.apk`.
If the file uploaded to GitHub has another name, pass that exact name:

```bash
./gradlew writeUpdateManifest -PupdateApkName=Edge.Read.Aloud.TTS.v1.0.10.apk
```

Attach **both** files to the **same** release. The name in `update.json` must
match the APK asset exactly, and both must be the build you just signed.
Later updates must use the same `release.jks`. Bump `versionCode` or the
app will report that it is already current.

The published **v1.0.10** release has no `update.json`. Until that file is
added to a stable release, the app says it could not verify and offers
Releases. It does not claim that v1.0.10 is already the latest.

## Where this comes from

| | What we took | What we did not take |
|---|---|---|
| [rany2/edge-tts](https://github.com/rany2/edge-tts) 7.3.1 | WebSocket URL, `TrustedClientToken`, Sec-MS-GEC **v2** (`16 hex`-`64 hex`), `Sec-MS-GEC-Version=2-143.0.3650.75`, SSML, Edge headers, 403 clock skew from `Date` | One new socket per chunk (we keep the socket open) |
| [ag2s20150909/TTS](https://github.com/ag2s20150909/TTS) | System `TextToSpeechService`, `callback.start()` before the request, streaming MP3→PCM so Play Books highlights while audio arrives | Static voice table, in-app pronunciation dictionary, speaking styles, foreground-service notification |

## Enable the engine

1. Settings → System → Text-to-speech (or Languages & input).
2. Preferred engine → **Edge Read Aloud TTS**.
3. Open this app and press **Test voice**, or use Play Books / Neo Reader.

**Android 13+** (sideload): Settings → Apps → Edge Read Aloud TTS → *Allow
restricted settings*, or:

```bash
adb shell appops set --uid dev.experimental.edgetts android:allow_restricted_settings allow
```

Logs — **one command**. `dumpsys` prints version, voice, unified mode, cache,
rate, pitch, last error, handshake, and the last 40 `TURN` lines. `logcat`
adds the service, client, and settings buffer:

```bash
adb shell dumpsys activity service dev.experimental.edgetts/.EdgeReadAloudTtsService; adb logcat -d -t 800 -s EdgeTtsService:D EdgeTtsClient:I EdgeTtsClient:W EdgeTtsSettings:I
```

## Architecture

```text
MainActivity
  └── SettingsController → SettingsStore (DataStore)

EdgeReadAloudTtsService : TextToSpeechService
  ├── VoiceCatalogRepository     JSON catalog + bundled fallback
  ├── EdgeProtocolClient         persistent WebSocket, DRM, SSML
  ├── SsmlBuilder                rany2 mkssml (long voice name, prosody)
  ├── AudioFrameParser           word/sentence metadata
  ├── TextSegmenter              operational cap / 4096-byte protocol cap
  ├── CacheRepository            MP3, .tmp + rename, SHA-256, LRU
  ├── Mp3AudioDecoder            MediaExtractor + MediaCodec → PCM (streaming)
  └── LeadTailClipper            true silence only (lead 40 ms / tail 520 ms)
```

Shared process: `SharedProtocol` (DRM + one OkHttpClient). Swap the cloud
backend by implementing `TtsProvider`.

## Protocol (rany2-compatible)

- Endpoint `speech.platform.bing.com/.../readaloud/edge/v1` (PR 492 keeps this host;
  the 403 fix is the v2 token, not a new server)
- `TrustedClientToken` (public unofficial value), `Sec-MS-GEC` (v2 digest of
  5-minute Windows FILETIME ticks + token, `16 hex`-`64 hex`), `Sec-MS-GEC-Version=2-143.0.3650.75`
- Desktop Edge User-Agent, Origin `chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold`,
  cookie `muid=`
- Output format `audio-24khz-48kbitrate-mono-mp3` → PCM 16-bit 24 kHz mono
- SSML: single `<voice>` + `<prosody>` (Microsoft rejects extra SSML)
- 403: clock skew from `Date`, retry once

The socket is **kept open** across turns (rany2 opens a new one per chunk).
That only cuts TLS latency; it does not change Edge’s pauses.

## Sentence pauses

Play Books sends **one sentence per** `onSynthesizeText`. Edge adds
end-of-turn padding (~600–800 ms). Stacked with the next request, the period
used to feel like a hole. Neo Reader sends longer chunks, so commas/periods
stay inside the MP3.

| | Original | v1.0.10 |
|---|---|---|
| Rate / pitch | User sliders, default `+0%` / `+0Hz` | Same |
| Commas inside a paragraph | Edge | Untouched |
| Period in Play Books | ~600–800 ms tail + next lead + network | True silence only, **40 ms lead / 520 ms tail** |
| Words | Full phonemes | Same (amplitude threshold **80**) |
| Cache | MP3 | MP3 (clip is after decode) |

rany2 does not trim PCM or inject `<break>` / `mstts:silence`.

## Voice policy

Android will not let a normal app write `Settings.Secure` TTS defaults, so
the engine implements **app as source of truth**:

- **Unified ON (default):** the in-app voice is used everywhere for that
  language (every regional variant).
- **Unified OFF:** apps use the voice from **Android system TTS settings**,
  not from this app.
- `onGetVoices()` returns the full catalog. `CheckVoiceData` answers one
  canonical ISO3-COUNTRY locale per language so Settings stays stable.

Rate and pitch: **-50…+50** sliders (center = the voice’s normal values),
combined with system sliders, clamped to Edge’s range. Pitch is `+XHz`.

## Errors

| Condition | UI |
|---|---|
| 403 | Access denied; protocol changed or client blocked (renewal already tried) |
| 429 | Rate limit |
| 400 | Invalid SSML or format |
| timeout | Slow network or server down |
| invalid audio | MP3 not decodable on this device |

## Security

- No private secrets in the APK.
- No passwords, cookies, or Edge account data.
- Cache is audio under SHA-256; clearable in the app.
- Logcat never prints tokens or full signed URLs.

## License / status

Experimental, unofficial. Sideload or a GitHub Release APK. Not on Play Store.
