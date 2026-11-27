package com.jhaket.iptv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * App JhaKet IPTV.
 *
 * El APK lleva sólo un cargador (assets/index.html) que baja la versión actual de JhaKet
 * desde el servidor y la ejecuta. Así la app se actualiza sola, sin reinstalar el APK.
 *
 * Protección contra anuncios de los reproductores de páginas web (código iframe):
 *  - Ninguna página puede reemplazar la app navegando la ventana principal a otro sitio
 *    (eso era lo que "reiniciaba" la app al volver de un anuncio).
 *  - Las ventanas nuevas que abre una página (los anuncios emergentes) se descartan sin
 *    mostrarse. Sólo se dejan pasar, al navegador, los enlaces propios de JhaKet.
 *  - Los "intent://" sólo se aceptan para abrir VLC o MX Player (o instalarlos), que es
 *    lo que usa JhaKet; los de los anuncios (Play Store, otras apps) se ignoran.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    /** Pedido de archivo en curso (botones "Elegir archivo" de la página). */
    private var subida: ValueCallback<Array<Uri>>? = null
    private val pedidoArchivo = 4207
    private val pedidoReproductor = 4300

    /** true mientras se está reproduciendo un contenido: ahí sí se ocultan las barras. */
    @Volatile private var reproduciendo = false

    /** Pantalla completa pedida por una página (el reproductor de un código iframe). */
    private var vistaCompleta: android.view.View? = null
    private var avisoCompleta: WebChromeClient.CustomViewCallback? = null

    companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        const val APP_URL = "https://$APP_HOST/assets/index.html"

        /** Reproductores externos que JhaKet puede abrir. */
        val REPRODUCTORES = setOf(
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
        )

        /** Sitios que sí se pueden abrir en el navegador cuando los pide la app. */
        val SITIOS_PROPIOS = listOf(
            "jhaketiptv.floot.app",
            "play.google.com",
            "videolan.org",
            "mx.j2inter.com",
        )

        fun esSitioPropio(host: String?): Boolean {
            val h = (host ?: "").lowercase()
            return SITIOS_PROPIOS.any { h == it || h.endsWith(".$it") }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // La pantalla no se apaga mientras la app está abierta.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // Muchas listas M3U sirven los canales por http aunque la página sea https.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // Las ventanas nuevas pasan por onCreateWindow, donde se descartan los anuncios.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        WebView.setWebContentsDebuggingEnabled(false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                decidirNavegacion(request.url, request.isForMainFrame)

            @Deprecated("Android 6 o anterior")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                decidirNavegacion(Uri.parse(url), true)
        }

        web.webChromeClient = object : WebChromeClient() {
            /* Muchos reproductores web piden pantalla completa al darle play. Sin esto el
               pedido se perdía y el video no arrancaba (sobre todo en el TV). */
            override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                if (vistaCompleta != null) { callback.onCustomViewHidden(); return }
                vistaCompleta = view
                avisoCompleta = callback
                (window.decorView as android.widget.FrameLayout).addView(
                    view, android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                view.setBackgroundColor(android.graphics.Color.BLACK)
                reproduciendo = true
                hideSystemBars()
            }

            override fun onHideCustomView() { salirDePantallaCompleta() }

            /* "Elegir archivo" de la página (fondos, videos, listas M3U, HTML de la app):
               se abre el selector de archivos del teléfono. Sin esto el botón no hace nada. */
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                subida?.onReceiveValue(null)
                subida = callback
                // accept="image/*,video/mp4" llega como uno o varios textos; las extensiones
                // (".m3u") no sirven para el selector y se ignoran.
                val tipos = params.acceptTypes
                    .flatMap { it.split(",") }
                    .map { it.trim().lowercase() }
                    .filter { it.contains("/") }
                    .distinct()
                val elegir = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                if (tipos.size == 1) {
                    elegir.type = tipos[0]
                } else {
                    elegir.type = "*/*"
                    if (tipos.isNotEmpty()) elegir.putExtra(Intent.EXTRA_MIME_TYPES, tipos.toTypedArray())
                }
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) elegir.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent.createChooser(elegir, "Elegir archivo"), pedidoArchivo)
                    true
                } catch (e: ActivityNotFoundException) {
                    subida = null
                    callback.onReceiveValue(null)
                    false
                }
            }

            /* Una página quiere abrir una ventana nueva. Se le da una ventana "de mentira"
               que nunca se muestra: si el destino es un sitio propio de JhaKet (el panel
               web, la tienda para instalar VLC) y lo pidió el usuario, se abre en el
               navegador; cualquier otra cosa (anuncios) se descarta. */
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val trampa = WebView(this@MainActivity)
                trampa.webViewClient = object : WebViewClient() {
                    private var resuelto = false
                    private fun resolver(uri: Uri?) {
                        if (resuelto || uri == null) return
                        resuelto = true
                        val esWeb = uri.scheme == "http" || uri.scheme == "https"
                        if (isUserGesture && esWeb && esSitioPropio(uri.host)) abrirAfuera(uri)
                        trampa.post { trampa.stopLoading(); trampa.destroy() }
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        resolver(r.url); return true
                    }
                    @Deprecated("Android 6 o anterior")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                        resolver(Uri.parse(url)); return true
                    }
                    override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        if (url != null && url != "about:blank") { resolver(Uri.parse(url)); v.stopLoading() }
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = trampa
                resultMsg.sendToTarget()
                return true
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidApp")
        web.loadUrl(APP_URL)

        hideSystemBars()

        // Notificaciones del administrador: canal, permiso (Android 13+) y revisión periódica.
        Avisos.crearCanal(this)
        Avisos.programar(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /**
     * Qué hacer cuando la ventana principal intenta ir a otro lado.
     * Devuelve true cuando la navegación se frena (la app se queda donde está).
     */
    private fun decidirNavegacion(uri: Uri, ventanaPrincipal: Boolean): Boolean {
        val esquema = (uri.scheme ?: "").lowercase()

        // La propia app (el cargador): siempre.
        if ((esquema == "https" || esquema == "http") && uri.host == APP_HOST) return false
        if (esquema == "about" || esquema == "data" || esquema == "blob") return false

        // VLC / MX Player, o la tienda para instalarlos.
        if (esquema == "intent") {
            abrirIntent(uri.toString())
            return true
        }
        if (esquema == "market") {
            val id = uri.getQueryParameter("id")
            if (id != null && id in REPRODUCTORES) abrirAfuera(uri)
            return true
        }

        // Un marco interno (el reproductor de una página) puede navegar dentro de su marco.
        if (!ventanaPrincipal) return false

        // La ventana principal no se va nunca a otro sitio: así un anuncio no puede
        // reemplazar la app. Los sitios propios se abren en el navegador.
        if ((esquema == "http" || esquema == "https") && esSitioPropio(uri.host)) abrirAfuera(uri)
        return true
    }

    /** intent:// de JhaKet: sólo para VLC, MX Player o instalarlos desde la tienda. */
    private fun abrirIntent(texto: String) {
        val intent = try {
            Intent.parseUri(texto, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return
        }
        val pkg = intent.`package`
        val esReproductor = pkg != null && pkg in REPRODUCTORES
        val idTienda = intent.data?.getQueryParameter("id")
        val instalarReproductor = pkg == "com.android.vending" && idTienda != null && idTienda in REPRODUCTORES
        if (!esReproductor && !instalarReproductor) return   // de un anuncio: se ignora

        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No está instalado: se abre la página para bajarlo.
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null) {
                val f = Uri.parse(fallback)
                if (esSitioPropio(f.host)) abrirAfuera(f)
            }
        } catch (e: SecurityException) {
            // nada
        }
    }

    private fun abrirAfuera(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // sin navegador o tienda: nada
        }
    }

    /** Puente que la página usa para tocar cosas del teléfono. */
    inner class Bridge {
        /** Brillo real de la pantalla, 0.05 a 1.0. Solo afecta a esta app. */
        @JavascriptInterface
        fun setBrightness(value: Float) {
            runOnUiThread {
                val lp = window.attributes
                lp.screenBrightness = value.coerceIn(0.05f, 1.0f)
                window.attributes = lp
            }
        }

        /** La página pasa la sesión del usuario ("" al cerrar sesión) para buscar avisos. */
        @JavascriptInterface
        fun setSession(token: String?) {
            Avisos.guardarSesion(applicationContext, token ?: "")
        }

        /** La página avisa cuando abre o cierra el reproductor. Fuera del reproductor
            se ve la barra de navegación de Android; al reproducir, pantalla completa. */
        @JavascriptInterface
        fun setPlaying(playing: Boolean) {
            runOnUiThread {
                if (reproduciendo != playing) {
                    reproduciendo = playing
                    hideSystemBars()
                }
            }
        }

        /** Para que la página sepa que corre dentro de esta app (con la protección nativa). */
        @JavascriptInterface
        fun nativo(): Boolean = true

        /** "Salir" del cartel de confirmación: cierra la app de verdad (no queda abierta atrás). */
        @JavascriptInterface
        fun salir() {
            runOnUiThread {
                try { finishAndRemoveTask() } catch (e: Exception) { finish() }
            }
        }

        /** Si la app corre en un televisor (Android TV / Google TV). */
        @JavascriptInterface
        fun esTv(): Boolean = try {
            (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature("android.software.leanback")
        } catch (e: Exception) { false }

        /** Abre el reproductor nativo (ExoPlayer + VLC). datos: {items:[{u,n,live}], index, start, ua} */
        @JavascriptInterface
        fun playNative(datos: String?) {
            runOnUiThread {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(this@MainActivity, PlayerActivity::class.java).putExtra("datos", datos ?: "{}"), pedidoReproductor)
                } catch (e: Exception) { }
            }
        }

        /** Número de versión instalada. Coincide con el número de la versión publicada en
            GitHub (v10, v11…): así la página sabe si hay una más nueva para descargar. */
        @JavascriptInterface
        fun version(): Int = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        } catch (e: Exception) { 0 }
    }

    /**
     * Botón atrás (teléfono y control del TV): primero cierra lo que haya abierto
     * adentro de JhaKet (reproductor, ficha, ventanas); recién al final sale de la app.
     */
    @Deprecated("Se mantiene por compatibilidad con Android viejo")
    private fun salirDePantallaCompleta() {
        val v = vistaCompleta ?: return
        (window.decorView as android.widget.FrameLayout).removeView(v)
        vistaCompleta = null
        try { avisoCompleta?.onCustomViewHidden() } catch (e: Exception) { }
        avisoCompleta = null
        hideSystemBars()
    }

    override fun onBackPressed() {
        // Primero se sale de la pantalla completa de la página, si la hay.
        if (vistaCompleta != null) { salirDePantallaCompleta(); return }
        // JhaKet decide qué cierra cada Atrás (ventana, reproductor, ficha…) y, con nada
        // abierto, muestra "¿Querés salir de JhaKet?". Ya no se usa el historial de la
        // página: quedaba lleno de pasos y Atrás nunca llegaba a salir.
        web.evaluateJavascript("(window.jhaketBack && window.jhaketBack()) ? 1 : 0") { r ->
            if (r?.trim() == "1") return@evaluateJavascript
            try { finishAndRemoveTask() } catch (e: Exception) { finish() }
        }
    }

    /** En Android TV el control remoto manda teclas de medios que conviene reenviar. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                web.evaluateJavascript("document.getElementById('bPlay')?.click()", null); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Barras del sistema:
     *  - Reproduciendo: pantalla completa (sin barra de estado ni de navegación).
     *  - Resto de la app: la barra de navegación de Android queda visible, y el contenido
     *    se acomoda arriba de ella (no queda tapado). La barra de estado sigue oculta.
     */
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = if (reproduciendo) (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            ) else (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes      // hay que reasignar: cambiarlo en el lugar no surte efecto
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    /** Vuelve del selector de archivos: se le pasa a la página lo que se eligió (o nada). */
    @Deprecated("Se mantiene por compatibilidad con Android viejo")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // Vuelve del reproductor nativo: la página guarda dónde quedó (Continuar viendo).
        if (requestCode == pedidoReproductor) {
            if (data != null) {
                val url = data.getStringExtra("url")
                if (url != null) {
                    val pos = data.getDoubleExtra("pos", 0.0)
                    val dur = data.getDoubleExtra("dur", 0.0)
                    web.evaluateJavascript(
                        "window.jhaketNativoFin && window.jhaketNativoFin(" + org.json.JSONObject.quote(url) + "," + pos + "," + dur + ")", null
                    )
                }
            }
            hideSystemBars()
            return
        }
        if (requestCode != pedidoArchivo) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val cb = subida
        subida = null
        if (cb == null) return
        if (resultCode != RESULT_OK || data == null) { cb.onReceiveValue(null); return }
        val clip = data.clipData
        val elegidos: Array<Uri>? = when {
            clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        cb.onReceiveValue(elegidos)
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume(); hideSystemBars(); Avisos.revisarYa(this) }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
