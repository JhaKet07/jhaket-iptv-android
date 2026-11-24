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
        versionCode = 5
        versionName = "2.3"
    }

    // Clave propia de JhaKet: llega desde los secretos de GitHub (ver build.yml). Con la
    // misma firma siempre, cada APK nuevo se instala encima del anterior sin desinstalar.
    val claveArchivo = System.getenv("JHAKET_KEYSTORE_FILE")
    val claveContrasena = System.getenv("JHAKET_KEYSTORE_PASS")
    signingConfigs {
        if (claveArchivo != null && claveContrasena != null) {
            create("jhaket") {
                storeFile = file(claveArchivo)
                storePassword = claveContrasena
                keyAlias = "jhaket"
                keyPassword = claveContrasena
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sin la clave propia (secretos sin cargar) se usa la de depuración.
            signingConfig = signingConfigs.findByName("jhaket") ?: signingConfigs.getByName("debug")
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
    // Revisión de avisos en segundo plano cada 15 minutos
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
