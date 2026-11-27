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
        // El número de versión es el de la compilación en GitHub (el mismo de la versión
        // publicada: v10, v11…), así "Buscar actualización" compara directo.
        val compilacion = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = compilacion ?: 7
        versionName = if (compilacion != null) "3.0 (v$compilacion)" else "3.0"
        // VLC y FFmpeg traen código nativo: sólo para los procesadores de teléfonos y TV
        // (ARM), así el APK no pesa el doble.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // VLC y el decodificador de FFmpeg traen la misma librería de C++: se usa una sola.
    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
            useLegacyPackaging = true
        }
    }

    // La revisión de "lint" no frena la compilación del APK.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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

    // ===== Reproductor nativo =====
    // ExoPlayer (Media3): HLS, DASH, MPEG-TS, MP4/MKV, RTSP, RTMP y la interfaz del reproductor
    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-datasource-rtmp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Decodificadores de audio de FFmpeg para ExoPlayer (AC3, E-AC3, DTS, TrueHD, MP2…)
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    // VLC, con todos sus códecs: se usa solo cuando ExoPlayer no puede con un enlace
    implementation("org.videolan.android:libvlc-all:3.5.1")

    // ===== Enviar a la tele =====
    // Google Cast: Chromecast, Android TV y Google TV
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
}
