package com.jhaket.iptv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.util.Rational
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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
    private val pedidoNavegador = 4310

    /** true mientras se está reproduciendo un contenido: ahí sí se ocultan las barras. */
    @Volatile private var reproduciendo = false

    /** Pantalla completa pedida por una página (el reproductor de un código iframe). */
    private var vistaCompleta: android.view.View? = null
    private var avisoCompleta: WebChromeClient.CustomViewCallback? = null

    // Ventana flotante y control en la barra para los reproductores web (código iframe)
    private val manejador = Handler(Looper.getMainLooper())
    private var esTv = false
    private var control: ControlMedia? = null
    private var webMedia = false          // hay un reproductor web abierto
    private var webPausado = false
    private var webTitulo = ""
    private var enPip = false
    private var entrandoPip = false
    private var detenido = false
    private var clavePip = ""

    companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        const val APP_URL = "https://$APP_HOST/assets/index.html"

        /** Se carga en cada página e iframe: pausa los videos que andan y después sigue sólo esos. */
        const val SCRIPT_MEDIA = "(function(){if(window.__jkMedia)return;" +
            "function aplicar(a){try{document.querySelectorAll('video,audio').forEach(function(v){try{" +
            "if(a==='pause'){if(!v.paused){v.pause();v.__jkP=1;}}else if(v.__jkP){v.__jkP=0;var p=v.play();if(p&&p.catch)p.catch(function(){});}" +
            "}catch(e){}});}catch(e){}" +
            "try{for(var i=0;i<window.frames.length;i++){try{window.frames[i].postMessage({__jkMedia:a},'*');}catch(e){}}}catch(e){}}" +
            "window.__jkMedia=aplicar;" +
            "window.addEventListener('message',function(e){var d=e.data;if(d&&typeof d==='object'&&d.__jkMedia)aplicar(d.__jkMedia);},true);})()"

        /** ¿Hay un reproductor web abierto? Devuelve {a, t} (abierto y título). */
        const val JS_ESTADO = "(function(){try{var d=document.documentElement,b=document.body;" +
            "var e=d.classList.contains('emb-on'),p=!!(b&&b.classList.contains('playing'));" +
            "var t=document.querySelector('#embedView .emb-t');" +
            "return JSON.stringify({a:e||p,t:t?t.textContent:''});}catch(x){return '';}})()"

        /** Ventana flotante: se esconde la barra de arriba del reproductor web y se deshace el giro. */
        const val JS_PIP = "(function(on){var s=document.getElementById('jk-pip-css');if(!s){s=document.createElement('style');s.id='jk-pip-css';" +
            "s.textContent='html.jk-pip #embedView .emb-top{display:none!important}" +
            "html.jk-pip #embedView.girado{inset:0!important;top:0!important;left:0!important;width:100%!important;height:100%!important;transform:none!important}" +
            "html.jk-pip .toast,html.jk-pip #toast{display:none!important}';" +
            "(document.head||document.documentElement).appendChild(s);}document.documentElement.classList.toggle('jk-pip',on);})"

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
        esTv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
        // En cada página y en cada iframe: pausar y seguir los videos desde la barra de notificaciones.
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(web, SCRIPT_MEDIA, setOf("*"))
        } catch (e: Throwable) { }
        if (!esTv) control = ControlMedia(this, 4712, object : ControlMedia.Acciones {
            override fun reproducir() { medioWeb("play"); webPausado = false; refrescarWeb() }
            override fun pausar() { medioWeb("pause"); webPausado = true; refrescarWeb() }
            override fun cerrar() { cerrarReproductorWeb() }
        })
        manejador.post(revisarWeb)

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

        /** Esta app sabe buscar el stream de una página (canales de página y navegador privado). */
        @JavascriptInterface
        fun detector(): Boolean = true

        /** Navegador privado para buscar canales (lo ofrece sólo el panel del administrador).
            Al volver llama a window.jhaketDesdeNavegador({tipo, url, titulo, h}). */
        @JavascriptInterface
        fun abrirNavegador(url: String?) {
            runOnUiThread {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(this@MainActivity, NavegadorActivity::class.java).putExtra("url", url ?: ""), pedidoNavegador)
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
        // Vuelve del navegador privado con un stream o una página para agregar.
        if (requestCode == pedidoNavegador) {
            val r = data?.getStringExtra("resultado")
            if (resultCode == RESULT_OK && r != null) {
                web.evaluateJavascript("window.jhaketDesdeNavegador && window.jhaketDesdeNavegador($r)", null)
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

    override fun onPause() {
        super.onPause()
        // En la ventana flotante el reproductor web sigue andando.
        val flotante = enPip || entrandoPip || (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)
        if (!flotante) web.onPause()
    }
    override fun onResume() { super.onResume(); web.onResume(); hideSystemBars(); Avisos.revisarYa(this) }
    override fun onStart() { super.onStart(); detenido = false }
    override fun onStop() { super.onStop(); detenido = true }
    override fun onDestroy() {
        manejador.removeCallbacksAndMessages(null)
        control?.liberar(); control = null
        web.destroy(); super.onDestroy()
    }

    // ============================================================
    // Reproductores web: ventana flotante y control en la barra
    // ============================================================

    private fun medioWeb(accion: String) {
        web.evaluateJavascript("$SCRIPT_MEDIA;window.__jkMedia('$accion')", null)
    }

    private fun cerrarReproductorWeb() {
        if (vistaCompleta != null) salirDePantallaCompleta()
        if (webMedia) web.evaluateJavascript("window.jhaketBack && window.jhaketBack()", null)
        webMedia = false; webPausado = false
        control?.ocultar()
        actualizarPip()
    }

    /** Cada segundo y medio: ¿hay un reproductor web abierto? (y su título) */
    private val revisarWeb = object : Runnable {
        override fun run() {
            if (!detenido) {
                web.evaluateJavascript(JS_ESTADO) { r ->
                    try {
                        val texto = org.json.JSONArray("[$r]").optString(0, "")
                        if (texto.isNotEmpty()) {
                            val o = org.json.JSONObject(texto)
                            val abierto = o.optBoolean("a") || vistaCompleta != null
                            val titulo = o.optString("t", "").trim()
                            if (abierto && (!webMedia || titulo != webTitulo)) webPausado = false
                            webMedia = abierto; webTitulo = titulo
                            refrescarWeb()
                        }
                    } catch (e: Throwable) { }
                }
            }
            manejador.postDelayed(this, 1500)
        }
    }

    private fun refrescarWeb() {
        val c = control
        if (c != null) {
            if (webMedia) {
                val abrir = PendingIntent.getActivity(this, 4712, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                c.mostrar(webTitulo.ifEmpty { "JhaKet IPTV" }, "JhaKet IPTV", null, !webPausado, 0L, 0L, false, abrir)
            } else c.ocultar()
        }
        actualizarPip()
    }

    private fun pipDisponible() = !esTv && Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun paramsPip(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val acciones = ArrayList<RemoteAction>()
        control?.let { c ->
            val accion = if (webPausado)
                RemoteAction(Icon.createWithResource(this, R.drawable.ic_rep_play), "Reproducir", "Reproducir", c.pi("play", 3))
            else
                RemoteAction(Icon.createWithResource(this, R.drawable.ic_rep_pausa), "Pausar", "Pausar", c.pi("pausa", 2))
            acciones.add(accion)
        }
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setActions(acciones)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(webMedia && !webPausado).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun actualizarPip() {
        if (!pipDisponible()) return
        val clave = "$webMedia|$webPausado"
        if (clave == clavePip) return
        clavePip = clave
        try { paramsPip()?.let { setPictureInPictureParams(it) } } catch (e: Throwable) { }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!pipDisponible() || !webMedia || webPausado) return
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode) {
            entrandoPip = try { paramsPip()?.let { enterPictureInPictureMode(it) } ?: false } catch (e: Throwable) { false }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        enPip = isInPictureInPictureMode
        entrandoPip = false
        // En la ventana flotante se ve sólo el reproductor (sin la barra de arriba ni el giro).
        web.evaluateJavascript(JS_PIP + "(" + enPip + ")", null)
        if (!enPip) {
            hideSystemBars()
            // Se cerró la ventana flotante con la X: se cierra el reproductor (deja de sonar).
            manejador.postDelayed({ if (detenido && !enPip) { cerrarReproductorWeb(); web.onPause() } }, 600)
        }
    }
}
