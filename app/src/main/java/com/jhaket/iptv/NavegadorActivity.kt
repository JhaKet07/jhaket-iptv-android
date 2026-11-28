package com.jhaket.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Navegador privado de JhaKet (sólo lo abre el administrador, desde "Agregar contenido").
 *
 *  - Privado: no guarda historial; al cerrarlo se borran las cookies y los datos de los sitios
 *    que se visitaron (sin tocar la sesión de JhaKet).
 *  - Bloquea anuncios conocidos, ventanas emergentes y las redirecciones a otros sitios que
 *    no pidió el usuario.
 *  - Mientras se navega, va juntando los streams (.m3u8 / .mpd) que cargan las páginas.
 *    Cada uno se puede "Probar" en el reproductor de JhaKet o "Agregar" a la lista.
 *  - "Guardar página como canal": agrega la página como canal de página (la app busca el
 *    stream sola cada vez que se abre, porque estos enlaces suelen vencer).
 *
 * Devuelve a la app (extra "resultado"): {tipo: "stream"|"pagina", url, titulo, h}
 */
class NavegadorActivity : Activity() {

    private data class Hallado(val url: String, val cabeceras: Map<String, String>, val pagina: String)

    private lateinit var web: WebView
    private lateinit var barraUrl: EditText
    private lateinit var progreso: ProgressBar
    private lateinit var bStreams: TextView
    private lateinit var panel: LinearLayout
    private lateinit var listaPanel: LinearLayout
    private val manejador = Handler(Looper.getMainLooper())
    private val hallados = LinkedHashMap<String, Hallado>()
    private val visitados = LinkedHashSet<String>()     // orígenes visitados, para borrarlos al salir
    private var esTv = false

    private val ROJO = 0xFFFF3B47.toInt()
    private val NARANJA = 0xFFFF7A45.toInt()
    private val FONDO = 0xFF0B0C11.toInt()
    private val PANEL = 0xFF15171F.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun boton(texto: String, primario: Boolean = false, accion: () -> Unit) = TextView(this).apply {
        text = texto
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = if (primario)
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ROJO, NARANJA)).apply { cornerRadius = dp(10).toFloat() }
        else GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x22FFFFFF) }
        isFocusable = true
        isClickable = true
        setOnClickListener { accion() }
        setOnFocusChangeListener { v, f -> v.alpha = if (f) 1f else 0.92f; v.scaleX = if (f) 1.06f else 1f; v.scaleY = v.scaleX }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        esTv = packageManager.hasSystemFeature("android.software.leanback")

        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(FONDO) }

        // ===== Barra de arriba: cerrar, "Privado", dirección, recargar =====
        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(PANEL)
        }
        arriba.addView(boton("✕") { finish() })
        val privado = TextView(this).apply {
            text = "Privado"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ROJO, NARANJA)).apply { cornerRadius = dp(20).toFloat() }
        }
        arriba.addView(privado, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        barraUrl = EditText(this).apply {
            setSingleLine(true)
            hint = "Buscá o escribí una dirección"
            setHintTextColor(0x88FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x14FFFFFF) }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, accion, ev ->
                if (accion == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                    ir(text.toString()); true
                } else false
            }
        }
        arriba.addView(barraUrl, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        arriba.addView(boton("↻") { web.reload() })
        raiz.addView(arriba, LinearLayout.LayoutParams(-1, -2))

        progreso = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(NARANJA)
        }
        raiz.addView(progreso, LinearLayout.LayoutParams(-1, dp(3)))

        // ===== La página, con el panel de streams encima =====
        val medio = FrameLayout(this)
        web = WebView(this)
        medio.addView(web, FrameLayout.LayoutParams(-1, -1))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF20B0C11.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }
        panel.addView(TextView(this).apply {
            text = "Streams encontrados en esta navegación"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = "Dale play al video en la página y van a aparecer acá. Estos enlaces suelen vencer: para un canal fijo conviene «Guardar página como canal»."
            setTextColor(0xAAFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(10))
        })
        listaPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { addView(listaPanel) }, LinearLayout.LayoutParams(-1, 0, 1f))
        medio.addView(panel, FrameLayout.LayoutParams(-1, -1))
        raiz.addView(medio, LinearLayout.LayoutParams(-1, 0, 1f))

        // ===== Barra de abajo =====
        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(PANEL)
        }
        abajo.addView(boton("‹") { if (web.canGoBack()) web.goBack() })
        bStreams = boton("Streams (0)") { alternarPanel() }
        abajo.addView(bStreams, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        abajo.addView(boton("Guardar página como canal", primario = true) { guardarPagina() })
        raiz.addView(abajo, LinearLayout.LayoutParams(-1, -2))

        setContentView(raiz)
        prepararWeb()

        val inicial = intent.getStringExtra("url")?.trim().orEmpty()
        if (inicial.isNotEmpty()) ir(inicial) else {
            barraUrl.requestFocus()
            if (!esTv) manejador.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(barraUrl, 0)
            }, 300)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun prepararWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            @Suppress("DEPRECATION")
            saveFormData = false
            setSupportMultipleWindows(true)              // las ventanas nuevas pasan por onCreateWindow (se bloquean)
            javaScriptCanOpenWindowsAutomatically = false
        }
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) } catch (e: Throwable) { }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (Detector.esPublicidad(u)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                if (Detector.esStream(u)) {
                    val ref = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value
                    manejador.post { agregarHallado(u, ref) }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val esquema = u.scheme?.lowercase() ?: ""
                if (esquema != "http" && esquema != "https") return true             // intent://, market://, apps: no
                if (Detector.esPublicidad(u.toString())) return true
                if (request.isForMainFrame && Build.VERSION.SDK_INT >= 24) {
                    // Redirección a otro sitio que no pidió el usuario (típico de los anuncios): se frena.
                    val actual = Uri.parse(view.url ?: "").host ?: ""
                    val otroSitio = actual.isNotEmpty() && dominio(u.host ?: "") != dominio(actual)
                    if (otroSitio && !request.hasGesture() && !request.isRedirect) return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && url.startsWith("http")) {
                    if (!barraUrl.hasFocus()) barraUrl.setText(url)
                    Detector.origen(url).takeIf { it.isNotEmpty() }?.let { visitados.add(it) }
                    if (Detector.esStream(url)) agregarHallado(url, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                manejador.removeCallbacks(leerVideos)
                manejador.postDelayed(leerVideos, 1500)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progreso.progress = p
                progreso.visibility = if (p >= 100) View.INVISIBLE else View.VISIBLE
            }
            // Ventanas emergentes: bloqueadas.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
        }
    }

    /** Además de los pedidos, se miran los <video> de la página cada tanto. */
    private val leerVideos = object : Runnable {
        override fun run() {
            if (isFinishing) return
            web.evaluateJavascript(JS_VIDEOS) { r ->
                val texto = try { org.json.JSONArray("[$r]").optString(0, "") } catch (e: Throwable) { "" }
                texto.split('\n').map { Detector.limpiar(it) }.filter { Detector.esStream(it) }.forEach { agregarHallado(it, null) }
            }
            manejador.postDelayed(this, 3000)
        }
    }

    private fun dominio(host: String): String {
        val partes = host.lowercase().removePrefix("www.").split('.')
        return if (partes.size >= 2) partes.takeLast(2).joinToString(".") else host
    }

    private fun ir(texto: String) {
        val t = texto.trim()
        if (t.isEmpty()) return
        val url = when {
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t) -> t
            !t.contains(' ') && Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE).matches(t) -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        barraUrl.setText(url)
        barraUrl.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(barraUrl.windowToken, 0)
        web.requestFocus()
        web.loadUrl(url)
    }

    private fun agregarHallado(url: String, referer: String?) {
        val u = Detector.limpiar(url)
        if (hallados.containsKey(u)) return
        val pagina = web.url ?: ""
        hallados[u] = Hallado(u, Detector.cabecerasPara(u, pagina, referer), pagina)
        bStreams.text = "Streams (${hallados.size})"
        (bStreams.background as? GradientDrawable)?.setColor(0x55FF7A45)
        pintarLista()
    }

    private fun alternarPanel() {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (panel.visibility == View.VISIBLE) { pintarLista(); listaPanel.getChildAt(0)?.requestFocus() }
    }

    private fun pintarLista() {
        listaPanel.removeAllViews()
        if (hallados.isEmpty()) {
            listaPanel.addView(TextView(this).apply {
                text = "Todavía no apareció ningún stream."
                setTextColor(0x88FFFFFF.toInt()); setPadding(0, dp(12), 0, 0)
            })
            return
        }
        for (h in hallados.values.reversed()) {
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x12FFFFFF) }
            }
            fila.addView(TextView(this).apply {
                text = h.url
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            val acciones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            acciones.addView(boton("▶ Probar") { probar(h) })
            acciones.addView(boton("＋ Agregar", primario = true) { devolver("stream", h.url, h.cabeceras) },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            fila.addView(acciones)
            listaPanel.addView(fila, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    /** Lo abre en el reproductor de JhaKet con las cabeceras que necesita. */
    private fun probar(h: Hallado) {
        val item = JSONObject().put("u", h.url).put("n", web.title ?: "Prueba").put("live", true)
        if (h.cabeceras.isNotEmpty()) item.put("h", JSONObject(h.cabeceras as Map<*, *>))
        val datos = JSONObject().put("items", org.json.JSONArray().put(item)).put("index", 0).put("admin", true)
        try { startActivity(Intent(this, PlayerActivity::class.java).putExtra("datos", datos.toString())) } catch (e: Exception) { }
    }

    private fun guardarPagina() {
        val url = web.url ?: ""
        if (!url.startsWith("http")) return
        devolver("pagina", url, emptyMap())
    }

    private fun devolver(tipo: String, url: String, cabeceras: Map<String, String>) {
        val r = JSONObject()
            .put("tipo", tipo)
            .put("url", url)
            .put("titulo", limpiarTitulo(web.title ?: ""))
            .put("pagina", web.url ?: "")
        if (cabeceras.isNotEmpty()) r.put("h", JSONObject(cabeceras as Map<*, *>))
        setResult(RESULT_OK, Intent().putExtra("resultado", r.toString()))
        finish()
    }

    private fun limpiarTitulo(t: String): String =
        t.replace(Regex("""\s*[|\-–—]\s*[^|\-–—]{0,40}$"""), "").trim().take(80)

    @Deprecated("Se mantiene por compatibilidad")
    override fun onBackPressed() {
        when {
            panel.visibility == View.VISIBLE -> panel.visibility = View.GONE
            web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Privado: al salir se borran las cookies y los datos de los sitios visitados (no los de JhaKet). */
    private fun borrarRastros() {
        try {
            val cm = CookieManager.getInstance()
            for (o in visitados) {
                val c = cm.getCookie(o) ?: continue
                for (par in c.split(';')) {
                    val nombre = par.substringBefore('=').trim()
                    if (nombre.isEmpty()) continue
                    cm.setCookie(o, "$nombre=; Max-Age=0; Path=/")
                    val host = Uri.parse(o).host ?: continue
                    cm.setCookie(o, "$nombre=; Max-Age=0; Path=/; Domain=.${dominio(host)}")
                }
                try { WebStorage.getInstance().deleteOrigin(o) } catch (e: Throwable) { }
            }
            cm.flush()
        } catch (e: Throwable) { }
        try { web.clearHistory(); web.clearFormData(); web.clearCache(true) } catch (e: Throwable) { }
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }

    override fun onDestroy() {
        manejador.removeCallbacksAndMessages(null)
        borrarRastros()
        try { web.stopLoading(); web.destroy() } catch (e: Throwable) { }
        super.onDestroy()
    }

    companion object {
        const val JS_VIDEOS = "(function(){try{var out=[];document.querySelectorAll('video,source').forEach(function(v){var s=v.currentSrc||v.src;if(s&&/^https?:/i.test(s))out.push(s);});return out.join('\\n');}catch(e){return '';}})()"
    }
}
