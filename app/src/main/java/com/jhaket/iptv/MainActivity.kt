package com.jhaket.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
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
 * Envoltorio del reproductor JhaKet.
 *
 * El HTML NO se carga como file://, sino servido por WebViewAssetLoader desde
 * https://appassets.androidplatform.net. Eso le da a la página un origen https real,
 * que es lo que permite que funcionen los pedidos a TMDB y a las guías EPG
 * (desde file:// o content:// el navegador los bloquea) y que el almacenamiento
 * local no se borre.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

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
        }
        WebView.setWebContentsDebuggingEnabled(false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }
        // Necesario para que el video a pantalla completa funcione dentro del WebView.
        web.webChromeClient = WebChromeClient()

        web.addJavascriptInterface(Bridge(), "AndroidApp")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        hideSystemBars()
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
    }

    /** El botón atrás primero cierra lo que haya abierto adentro de la página. */
    override fun onBackPressed() {
        web.evaluateJavascript("(window.jhaketBack && window.jhaketBack()) ? 1 : 0") { r ->
            if (r?.trim() != "1") finish()
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
