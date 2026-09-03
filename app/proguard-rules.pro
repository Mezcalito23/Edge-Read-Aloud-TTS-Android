# Edge Read Aloud TTS — reglas ProGuard/R8.

# El servicio TTS y las actividades del contrato se instan por nombre desde
# el framework: nunca ofuscar.
-keep class dev.experimental.edgetts.EdgeReadAloudTtsService { *; }
-keep class dev.experimental.edgetts.MainActivity { *; }
-keep class dev.experimental.edgetts.CheckVoiceData { *; }
-keep class dev.experimental.edgetts.InstallVoiceData { *; }
-keep class dev.experimental.edgetts.GetSampleText { *; }

# OkHttp: avisos estándar (opcional si se activa minify en el futuro).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
