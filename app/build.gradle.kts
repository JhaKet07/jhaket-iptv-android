plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jhaket.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jhaket.iptv"
        minSdk = 21              // alcanza decodificadores viejos de Android TV
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Se firma con la clave de depuración para poder instalarlo sin trámites.
            // Para publicar en Google Play hay que generar una clave propia.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
