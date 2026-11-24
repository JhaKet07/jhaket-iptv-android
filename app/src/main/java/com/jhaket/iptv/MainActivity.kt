package com.jhaket.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
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

        /** Para que la página sepa que corre dentro de esta app (con la protección nativa). */
        @JavascriptInterface
        fun nativo(): Boolean = true
    }

    /**
     * Botón atrás (teléfono y control del TV): primero cierra lo que haya abierto
     * adentro de JhaKet (reproductor, ficha, ventanas); recién al final sale de la app.
     */
    @Deprecated("Se mantiene por compatibilidad con Android viejo")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.jhaketBack && window.jhaketBack()) ? 1 : 0") { r ->
            if (r?.trim() == "1") return@evaluateJavascript
            if (web.canGoBack()) web.goBack() else finish()
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

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes      // hay que reasignar: cambiarlo en el lugar no surte efecto
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume(); hideSystemBars() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
