plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.riftcoach.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.riftcoach.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        // "debug" é a versão que vai para o celular: só arm64, para o APK ficar menor.
        debug {
            ndk { abiFilters += "arm64-v8a" }
        }
        release {
            isMinifyEnabled = false
            ndk { abiFilters += "arm64-v8a" }
        }
        // Versão de teste para o emulador do PC: inclui uma tela que mostra os prints do jogo.
        create("dev") {
            initWith(getByName("debug"))
            ndk { abiFilters += "x86_64" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.all {
            it.testLogging {
                showStandardStreams = true
            }
        }
    }
}

dependencies {
    // Leitor de texto do Google (roda no próprio aparelho, sem internet)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
