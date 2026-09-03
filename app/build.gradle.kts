plugins {
    // Kotlin está integrado por AGP 9 (built-in Kotlin).
    // No añadir "org.jetbrains.kotlin.android": el proyecto ya lo resuelve AGP.
    id("com.android.application")
}

android {
    namespace = "dev.experimental.edgetts"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.experimental.edgetts"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "0.8.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        abortOnError = false
    }
}

// ── Compatibilidad con "Make Project" de Android Studio anteriores a
//    agosto de 2026: AGP 9 eliminó las tareas agregadas unitTestClasses /
//    androidTestClasses que esos Studio aún piden. Estos alias las mapean a
//    las tareas por variante de la build de depuración.
if (tasks.findByName("unitTestClasses") == null) {
    tasks.register("unitTestClasses") {
        dependsOn("compileDebugUnitTestSources")
    }
}
if (tasks.findByName("androidTestClasses") == null) {
    tasks.register("androidTestClasses") {
        dependsOn("compileDebugAndroidTestSources")
    }
}

dependencies {
    // Únicas dependencias externas permitidas en la primera versión:
    // OkHttp (WebSocket + HTTP) y DataStore Preferences (configuración).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Pruebas unitarias (JVM) e instrumentadas (dispositivo/emulador).
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
