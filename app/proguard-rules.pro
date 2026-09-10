# Edge Read Aloud TTS — R8/ProGuard.

# El framework llama al servicio y a las actividades por Binder / intent-filter.
# No ofuscar miembros: onSynthesizeText, onGetVoices, CHECK_TTS_DATA, etc.
-keep class dev.experimental.edgetts.EdgeReadAloudTtsService { *; }
-keep class dev.experimental.edgetts.MainActivity { *; }
-keep class dev.experimental.edgetts.CheckVoiceData { *; }
-keep class dev.experimental.edgetts.InstallVoiceData { *; }
-keep class dev.experimental.edgetts.GetSampleText { *; }

# DataStore Preferences (protobuf lite).
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
