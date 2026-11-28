package com.jhaket.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detector de streams: dada una página donde se ve un canal, encuentra el enlace del video
 * (.m3u8 o .mpd) para pasárselo al reproductor propio (ExoPlayer / VLC), sin iframes ni anuncios.
 *
 * Igual que hacen otras apps de TV:
 *  1. Primero se lee la página directo (rápido): se buscan enlaces .m3u8/.mpd en el código,
 *     también dentro de los reproductores (iframes) que tenga, un nivel.
 *  2. Si no aparece nada, se abre la página en un navegador oculto y se escucha todo lo que
 *     carga; el primer pedido a un .m3u8/.mpd es el stream.
 *
 * Los enlaces de estas páginas suelen vencer, por eso los "canales de página" se vuelven a
 * resolver cada vez que se abren.
 */
object Detector {

    /** Una calidad de un mismo canal (altura en píxeles: 360, 480, 720, 1080…). */
    data class Variante(val url: String, val altura: Int)

    /**
     * Stream encontrado y las cabeceras con las que hay que pedirlo (Referer, Origin, Cookie).
     *  - maestra: el enlace es una lista HLS "maestra" con varias calidades adentro (el
     *    reproductor las ofrece y puede ir cambiando solo según la conexión).
     *  - alturas: las calidades que trae esa lista maestra.
     *  - variantes: la página tenía varios enlaces separados del mismo canal, uno por calidad.
     */
    data class Resultado(val url: String, val cabeceras: Map<String, String>, val pagina: String,
                         val maestra: Boolean = false, val alturas: List<Int> = emptyList(),
                         val variantes: List<Variante> = emptyList())

    /** Un enlace candidato y desde dónde se pidió (para el Referer). */
    private data class Candidato(val url: String, val referer: String?)

    /** Algo que se puede cancelar (al cerrar el reproductor antes de que termine la búsqueda). */
    interface Busqueda { fun cancelar() }

    private val ENLACE = Regex(
        """https?:\\?/\\?/[^"'\s<>()\[\]{}|^`]+?\.(?:m3u8|mpd)(?:\?[^"'\s<>()\[\]{}|^`]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val IFRAME = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** Sitios de publicidad y estadísticas: nunca son el stream y se bloquean en el navegador oculto. */
    val PUBLICIDAD = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.",
        "google-analytics.com", "googletagmanager.com", "adnxs.com", "popads.net", "popcash.net",
        "propellerads.com", "propellerclick.com", "exoclick.com", "exosrv.com", "adsterra.com",
        "juicyads.com", "onclickads.net", "onclkds.com", "clickadu.com", "hilltopads.net",
        "a-ads.com", "adcash.com", "mgid.com", "taboola.com", "outbrain.com", "revcontent.com",
        "trafficjunky.net", "adskeeper.com", "richpush.co", "pushame.com", "monetag.com",
        "highperformanceformat.com", "profitablecpmrate.com", "displayvertising.com",
        "betterads", "adsco.re", "yandex.ru/ads", "mc.yandex.ru", "scorecardresearch.com",
        "amazon-adsystem.com", "criteo.com", "pubmatic.com", "rubiconproject.com", "openx.net"
    )

    fun esPublicidad(url: String): Boolean {
        val u = url.lowercase()
        return PUBLICIDAD.any { u.contains(it) }
    }

    /** ¿Este enlace es un stream que el reproductor sabe abrir? */
    fun esStream(url: String): Boolean {
        val u = url.lowercase().substringBefore('#')
        if (!u.startsWith("http")) return false
        val ruta = u.substringBefore('?')
        return ruta.endsWith(".m3u8") || ruta.endsWith(".mpd") ||
            u.contains(".m3u8?") || u.contains("type=m3u8") || u.contains("format=m3u8") ||
            u.contains("/manifest(format=m3u8")
    }

    fun limpiar(u: String): String = u.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim()

    fun origen(url: String): String = try {
        val x = Uri.parse(url); "${x.scheme}://${x.authority}"
    } catch (e: Exception) { "" }

    /** Cabeceras para pedir un stream que salió de una página. */
    fun cabecerasPara(stream: String, pagina: String, referer: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        val ref = referer ?: pagina
        if (ref.startsWith("http")) {
            h["Referer"] = ref
            val o = origen(ref)
            if (o.isNotEmpty() && origen(stream) != o) h["Origin"] = o
        }
        try {
            val c = CookieManager.getInstance().getCookie(stream)
            if (!c.isNullOrBlank()) h["Cookie"] = c
        } catch (e: Throwable) { }
        return h
    }

    /**
     * Busca el stream de una página. El resultado llega en el hilo principal (null si no se
     * encontró nada en [limiteMs]). Se puede llamar desde cualquier Activity.
     */
    fun buscar(actividad: Activity, pagina: String, limiteMs: Long = 25_000, alTerminar: (Resultado?) -> Unit): Busqueda {
        val principal = Handler(Looper.getMainLooper())
        val listo = AtomicBoolean(false)
        var oculto: NavegadorOculto? = null
        val terminar: (Resultado?) -> Unit = { r ->
            if (listo.compareAndSet(false, true)) principal.post {
                oculto?.cerrar(); oculto = null
                alTerminar(r)
            }
        }
        val ua = agenteDe(actividad)

        Thread {
            val r = try { porHttp(pagina, ua) } catch (e: Throwable) { null }
            if (listo.get()) return@Thread
            if (r != null) { terminar(r); return@Thread }
            principal.post {
                if (listo.get() || actividad.isFinishing) { terminar(null); return@post }
                oculto = NavegadorOculto(actividad, pagina, ua) { candidatos ->
                    // Ya se juntaron los enlaces que cargó la página: se revisan en segundo plano.
                    Thread {
                        val r = try { analizar(candidatos, pagina, ua) } catch (e: Throwable) { null }
                        terminar(r)
                    }.start()
                }
                principal.postDelayed({ terminar(null) }, limiteMs)
            }
        }.start()

        return object : Busqueda {
            override fun cancelar() {
                if (listo.compareAndSet(false, true)) principal.post { oculto?.cerrar(); oculto = null }
            }
        }
    }

    fun agenteDe(actividad: Activity): String = try {
        WebSettings.getDefaultUserAgent(actividad)
    } catch (e: Throwable) {
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    // ============================================================
    // Paso 1: leer la página directo
    // ============================================================

    private fun bajar(url: String, ua: String, referer: String?, maxBytes: Int = 2_000_000): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            c.setRequestProperty("Accept-Language", "es-AR,es;q=0.9")
            if (referer != null) c.setRequestProperty("Referer", referer)
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < maxBytes) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    private fun enlacesEn(html: String, base: String): List<String> =
        ENLACE.findAll(html).map { limpiar(it.value) }
            .filter { esStream(it) && !esPublicidad(it) }
            .distinct().toList()

    private fun porHttp(pagina: String, ua: String): Resultado? {
        // La página puede ser directamente el stream.
        if (esStream(pagina)) return analizar(listOf(Candidato(pagina, null)), pagina, ua)
            ?: Resultado(pagina, emptyMap(), pagina)
        val html = bajar(pagina, ua, null) ?: return null
        val candidatos = ArrayList<Candidato>()
        enlacesEn(html, pagina).forEach { candidatos.add(Candidato(it, pagina)) }
        // Un nivel de reproductores (iframes) adentro de la página.
        if (candidatos.isEmpty()) {
            val marcos = IFRAME.findAll(html).map { limpiar(it.groupValues[1]) }
                .mapNotNull { absoluta(pagina, it) }
                .filter { !esPublicidad(it) }
                .distinct().take(4).toList()
            for (m in marcos) {
                val interno = try { bajar(m, ua, pagina) } catch (e: Throwable) { null } ?: continue
                enlacesEn(interno, m).forEach { candidatos.add(Candidato(it, m)) }
            }
        }
        if (candidatos.isEmpty()) return null
        return analizar(candidatos, pagina, ua)
    }

    // ============================================================
    // Elegir entre los enlaces encontrados: lista maestra o varias calidades sueltas
    // ============================================================

    private val RESOLUCION = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
    private val ALTURAS_VALIDAS = 144..2160

    /** Lee el principio del enlace: null si no responde o no es una lista HLS/DASH. */
    private fun leerLista(stream: String, ua: String, cabeceras: Map<String, String>): String? {
        return try {
            val c = URL(stream).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = true
                c.connectTimeout = 7000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", ua)
                cabeceras.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (c.responseCode !in 200..299) return null
                val texto = c.inputStream.use { ins ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                    while (out.size() < 65536) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
                if (texto.contains("#EXTM3U") || texto.contains("<MPD", true)) texto else null
            } finally { c.disconnect() }
        } catch (e: Throwable) { null }
    }

    /** La calidad que dice el nombre del enlace (…_720p.m3u8, …/1080/…, 1280x720…). */
    fun alturaPorNombre(url: String): Int {
        val u = url.lowercase().substringBefore('?')
        val pruebas = listOf(
            Regex("""(\d{3,4})p(?![a-z])"""),
            Regex("""\d{3,4}x(\d{3,4})"""),
            Regex("""[/_\-.](\d{3,4})(?:[/_\-.]|$)""")
        )
        for (r in pruebas) {
            for (m in r.findAll(u)) {
                val h = m.groupValues[1].toIntOrNull() ?: continue
                if (h in ALTURAS_VALIDAS && h in setOf(144, 240, 288, 360, 404, 432, 480, 540, 576, 720, 900, 1080, 1440, 2160)) return h
            }
        }
        return 0
    }

    /**
     * De todos los enlaces que se encontraron:
     *  - si alguno es una lista maestra (trae varias calidades), se usa ese;
     *  - si hay varios enlaces sueltos con distintas calidades, se guardan todos para elegir;
     *  - si no, el primero que funcione.
     */
    private fun analizar(candidatos: List<Candidato>, pagina: String, ua: String): Resultado? {
        val vistos = LinkedHashMap<String, Candidato>()
        for (c in candidatos) if (!vistos.containsKey(c.url) && !esPublicidad(c.url)) vistos[c.url] = c
        var mejorMaestra: Resultado? = null
        val sueltos = ArrayList<Pair<Candidato, Map<String, String>>>()
        for (c in vistos.values.take(10)) {
            val h = cabecerasPara(c.url, pagina, c.referer)
            val texto = leerLista(c.url, ua, h) ?: continue
            if (texto.contains("#EXT-X-STREAM-INF")) {
                val alturas = RESOLUCION.findAll(texto).mapNotNull { it.groupValues[1].toIntOrNull() }
                    .filter { it in ALTURAS_VALIDAS }.distinct().sortedDescending().toList()
                if (mejorMaestra == null || alturas.size > mejorMaestra.alturas.size)
                    mejorMaestra = Resultado(c.url, h, pagina, maestra = true, alturas = alturas)
            } else if (texto.contains("<MPD", true)) {
                // DASH: también trae las calidades adentro; el reproductor las ofrece.
                if (mejorMaestra == null) mejorMaestra = Resultado(c.url, h, pagina, maestra = true)
            } else {
                sueltos.add(Pair(c, h))
            }
        }
        mejorMaestra?.let { return it }
        if (sueltos.isEmpty()) return null
        // Varios enlaces del mismo canal en distintas calidades: se guardan todos.
        val porAltura = LinkedHashMap<Int, Pair<Candidato, Map<String, String>>>()
        for (s in sueltos) {
            val alt = alturaPorNombre(s.first.url)
            if (alt > 0 && !porAltura.containsKey(alt)) porAltura[alt] = s
        }
        if (porAltura.size >= 2) {
            val variantes = porAltura.entries.sortedByDescending { it.key }.map { Variante(it.value.first.url, it.key) }
            val mejor = porAltura.entries.maxByOrNull { it.key }!!.value
            return Resultado(mejor.first.url, mejor.second, pagina, variantes = variantes)
        }
        val primero = sueltos.first()
        return Resultado(primero.first.url, primero.second, pagina)
    }

    fun absoluta(base: String, rel: String): String? = try {
        val r = if (rel.startsWith("//")) "https:$rel" else rel
        val u = URL(URL(base), r).toString()
        if (u.startsWith("http")) u else null
    } catch (e: Exception) { null }

    // ============================================================
    // Paso 2: navegador oculto que escucha lo que carga la página
    // ============================================================

    /** Le da play a los videos (en silencio) y toca los botones de play típicos de los reproductores. */
    const val JS_PLAY = "(function(){try{document.querySelectorAll('video').forEach(function(v){try{v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}});" +
        "var b=document.querySelector('.vjs-big-play-button,.jw-icon-display,.plyr__control--overlaid,.fp-play,.play-button,.btn-play,[aria-label=Play],[aria-label=Reproducir],.clappr-big-play-button,.media-control-button[data-playpause]');" +
        "if(b)b.click();var out=[];document.querySelectorAll('video,source').forEach(function(v){var s=v.currentSrc||v.src;if(s&&/^https?:/i.test(s))out.push(s);});return out.join('\\n');}catch(e){return '';}})()"

    @SuppressLint("SetJavaScriptEnabled")
    private class NavegadorOculto(
        private val actividad: Activity,
        private val pagina: String,
        ua: String,
        private val encontrado: (List<Candidato>) -> Unit
    ) {
        /* Cuando aparece el primer enlace se esperan unos segundos más: muchas páginas cargan
           primero una calidad y después la lista maestra u otras calidades. */
        private val juntados = LinkedHashMap<String, Candidato>()
        private var avisoProgramado = false
        private val entregar = Runnable {
            if (!cerrado) encontrado(juntados.values.toList())
        }
        private val web = WebView(actividad)
        private val principal = Handler(Looper.getMainLooper())
        @Volatile private var cerrado = false

        private val tocarPlay = object : Runnable {
            override fun run() {
                if (cerrado) return
                web.evaluateJavascript(JS_PLAY) { r ->
                    val texto = try { org.json.JSONArray("[$r]").optString(0, "") } catch (e: Throwable) { "" }
                    texto.split('\n').map { limpiar(it) }.firstOrNull { esStream(it) }?.let { avisar(it, null) }
                }
                principal.postDelayed(this, 2000)
            }
        }

        init {
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = ua
                setSupportMultipleWindows(true)          // las ventanas nuevas (anuncios) no se abren
                javaScriptCanOpenWindowsAutomatically = false
            }
            try { CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) } catch (e: Throwable) { }
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    if (esPublicidad(u)) return vacia()
                    if (esStream(u)) avisar(u, request.requestHeaders?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value)
                    return null
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val esquema = request.url.scheme?.lowercase() ?: ""
                    return !(esquema == "http" || esquema == "https") || esPublicidad(request.url.toString())
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && esStream(url)) avisar(url, null)
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    principal.removeCallbacks(tocarPlay)
                    principal.postDelayed(tocarPlay, 800)
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
            }
            // Se agrega a la pantalla, de 1 píxel e invisible: así la página corre como en un navegador normal.
            web.alpha = 0f
            web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            web.isFocusable = false
            try {
                (actividad.window.decorView as ViewGroup).addView(web, 0, FrameLayout.LayoutParams(1, 1))
            } catch (e: Throwable) { }
            web.loadUrl(pagina)
        }

        private fun vacia() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        private fun avisar(stream: String, referer: String?) {
            if (cerrado) return
            val s = limpiar(stream)
            principal.post {
                if (cerrado || juntados.containsKey(s)) return@post
                juntados[s] = Candidato(s, referer)
                if (!avisoProgramado) { avisoProgramado = true; principal.postDelayed(entregar, 3000) }
            }
        }

        fun cerrar() {
            if (cerrado) return
            cerrado = true
            principal.removeCallbacks(tocarPlay)
            principal.removeCallbacks(entregar)
            try {
                web.stopLoading()
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            } catch (e: Throwable) { }
        }
    }
}
