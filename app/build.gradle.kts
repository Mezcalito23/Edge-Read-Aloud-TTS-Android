import java.util.Properties

plugins {
    // Kotlin está integrado por AGP 9 (built-in Kotlin).
    // No añadir "org.jetbrains.kotlin.android": el proyecto ya lo resuelve AGP.
    id("com.android.application")
}

val keystoreProperties: Properties? =
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { stream ->
        Properties().apply { load(stream) }
    }

android {
    namespace = "dev.experimental.edgetts"
    // OkHttp 5.5 (okhttp-android) declara minCompileSdk=37. targetSdk sigue
    // en 36: no cambia el comportamiento en el dispositivo.
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.experimental.edgetts"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "1.0.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // La app solo traduce español (values/) e inglés (values-en/).
    androidResources {
        localeFilters += listOf("es", "en")
    }

    signingConfigs {
        val loaded = keystoreProperties
        if (loaded != null) {
            create("release") {
                storeFile = rootProject.file(loaded.getProperty("storeFile"))
                storePassword = loaded.getProperty("storePassword")
                keyAlias = loaded.getProperty("keyAlias")
                keyPassword = loaded.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // JDK 17. El compilador de Kotlin integrado por AGP hereda el nivel
        // de lenguaje del toolchain de Java del módulo.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf(
            "IconMissingDensityFolder",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            // compileSdk 37 solo para poder compilar. targetSdk 36 mantiene
            // el comportamiento ya probado en la tablet; subirlo cambia
            // reglas de Android en tiempo de ejecución.
            "ExpiredTargetSdkVersion"
        )
    }
}

// update.json de la release. Los números salen de versionCode y versionName;
// no se copian a mano. -PupdateApkName cambia solo el nombre publicado del APK.
tasks.register("writeUpdateManifest") {
    description = "Escribe app/build/update.json con la versión de esta app."
    val versionCodeProvider = provider { android.defaultConfig.versionCode ?: 0 }
    val versionNameProvider = provider { android.defaultConfig.versionName.orEmpty() }
    val apkNameProvider = providers.gradleProperty("updateApkName").orElse("app-release.apk")
    val outputFile = layout.buildDirectory.file("update.json")
    inputs.property("versionCode", versionCodeProvider)
    inputs.property("versionName", versionNameProvider)
    inputs.property("apkAssetName", apkNameProvider)
    outputs.file(outputFile)
    doLast {
        val code = versionCodeProvider.get()
        val name = versionNameProvider.get()
        val apk = apkNameProvider.get()
        val file = outputFile.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(
            "{\n" +
                "  \"schemaVersion\": 1,\n" +
                "  \"versionCode\": $code,\n" +
                "  \"versionName\": ${quoted(name)},\n" +
                "  \"apkAssetName\": ${quoted(apk)}\n" +
                "}\n"
        )
    }
}

fun quoted(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(c)
        }
    }
    append('"')
}

// ── Compatibilidad con "Make Project" de Android Studio anteriores a
//    agosto de 2026: AGP 9 eliminó las tareas agregadas unitTestClasses /
//    androidTestClasses que esos Studio aún piden. Estos alias las mapean a
//    las tareas por variante de la build de depuración.
if (tasks.findByName("unitTestClasses") == null) {
    tasks.register("unitTestClasses") {
        description = "Alias de compileDebugUnitTestSources para Android Studio anterior a agosto de 2026."
        dependsOn("compileDebugUnitTestSources")
    }
}
if (tasks.findByName("androidTestClasses") == null) {
    tasks.register("androidTestClasses") {
        description = "Alias de compileDebugAndroidTestSources para Android Studio anterior a agosto de 2026."
        dependsOn("compileDebugAndroidTestSources")
    }
}

dependencies {
    // Únicas dependencias externas permitidas en la primera versión:
    // OkHttp (WebSocket + HTTP) y DataStore Preferences (configuración).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // org.json está incluido en Android SDK, pero para tests JVM necesitamos la librería externa
    testImplementation("org.json:json:20231013")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Pruebas unitarias (JVM) e instrumentadas (dispositivo/emulador).
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
