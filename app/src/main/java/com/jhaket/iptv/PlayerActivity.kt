package com.jhaket.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Reproductor nativo de JhaKet.
 *
 * Motor principal: ExoPlayer (Media3) con HLS, DASH, MPEG-TS, MP4/MKV, RTSP y RTMP, y los
 * decodificadores de audio de FFmpeg (AC3, E-AC3, DTS, TrueHD, MP2…) para que ningún canal
 * quede sin sonido. Si ExoPlayer no puede con un enlace, pasa solo a VLC (libVLC), que trae
 * todos sus códecs adentro. Los esquemas que ExoPlayer no conoce (udp, rtp, mms…) van
 * directo a VLC.
 *
 * Para que no se corte: búfer más grande, reintentos con espera creciente, reconexión al
 * vivo si se queda atrás, y un vigía que reinicia la señal si se traba cargando.
 *
 * Recibe en el extra "datos" un JSON: { items:[{u,n,live}], index, start, ua }.
 * Devuelve al salir: url, pos y dur (segundos) para "Continuar viendo".
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private data class Item(val url: String, val nombre: String, val vivo: Boolean)

    private lateinit var raiz: FrameLayout
    private lateinit var titulo: TextView
    private lateinit var estado: TextView
    private lateinit var cargando: ProgressBar
    private val handler = Handler(Looper.getMainLooper())

    private var items = listOf<Item>()
    private var indice = 0
    private var inicioMs = 0L
    /* Se presenta como el navegador del teléfono, igual que el reproductor de la página
       (que es lo que ya funcionaba con estas listas). Si el canal lo rechaza, se prueba
       como VLC. */
    private var userAgent = ""
    private val uaVlc = "VLC/3.0.20 LibVLC/3.0.20"
    private var probeUa = false
    /** El canal llegó a reproducir: recién ahí el vigía lo reinicia si se traba. */
    private var yaReprodujo = false

    // ExoPlayer
    private var exo: ExoPlayer? = null
    private var vista: PlayerView? = null
    private var reintentos = 0
    private var probeHls = false
    private var cargandoDesde = 0L
    private var modoAspecto = 0

    // VLC
    private var libVlc: LibVLC? = null
    private var vlc: org.videolan.libvlc.MediaPlayer? = null
    private var vlcVista: VLCVideoLayout? = null

    private val itemActual get() = items[indice]

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        userAgent = try {
            android.webkit.WebSettings.getDefaultUserAgent(this)
        } catch (e: Throwable) {
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }
        leerDatos()
        if (items.isEmpty()) { finish(); return }

        raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        titulo = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            maxLines = 1
        }
        estado = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        cargando = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        setContentView(raiz)
        pantallaCompleta()
        arrancar()
        handler.postDelayed(vigia, 2000)
    }

    private fun leerDatos() {
        try {
            val j = JSONObject(intent.getStringExtra("datos") ?: "{}")
            val arr = j.optJSONArray("items")
            val lista = mutableListOf<Item>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("u")
                if (u.isNotEmpty()) lista.add(Item(u, o.optString("n", ""), o.optBoolean("live", false)))
            }
            items = lista
            indice = j.optInt("index", 0).coerceIn(0, maxOf(0, lista.size - 1))
            inicioMs = (j.optDouble("start", 0.0) * 1000).toLong()
            val ua = j.optString("ua", "")
            if (ua.isNotBlank()) userAgent = ua
        } catch (e: Exception) {
            items = emptyList()
        }
    }

    // ===================== Elección del motor =====================

    private fun arrancar() {
        liberarTodo()
        reintentos = 0
        probeHls = false
        probeUa = false
        yaReprodujo = false
        cargandoDesde = 0L
        val esquema = Uri.parse(itemActual.url).scheme?.lowercase() ?: ""
        titulo.text = itemActual.nombre
        if (esquema in setOf("udp", "rtp", "mms", "mmsh", "srt", "rtmps")) arrancarVlc()
        else arrancarExo(null)
    }

    // ===================== ExoPlayer =====================

    /** Reintentos con espera creciente: en vivo se insiste más antes de dar error. */
    private inner class Reintentos : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (itemActual.vivo) 6 else 4
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = loadErrorInfo.exception
            // El servidor rechazó el pedido (403, 404…) o lo que mandó no se entiende:
            // insistir no sirve, se pasa enseguida a la otra estrategia.
            if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
            if (e is ParserException) return C.TIME_UNSET
            return (loadErrorInfo.errorCount * 1000L).coerceAtMost(4000L)
        }
    }

    private fun tipoPorUrl(url: String, forzarHls: Boolean): String? {
        if (forzarHls) return MimeTypes.APPLICATION_M3U8
        val ruta = Uri.parse(url).path?.lowercase() ?: url.lowercase()
        return when {
            ruta.endsWith(".m3u8") || ruta.endsWith(".m3u") || url.contains("type=m3u8") -> MimeTypes.APPLICATION_M3U8
            ruta.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            Uri.parse(url).scheme.equals("rtsp", true) -> MimeTypes.APPLICATION_RTSP
            else -> null   // se detecta solo (TS, MP4, MKV, FLV…)
        }
    }

    private fun arrancarExo(forzarHls: Boolean?) {
        cargandoDesde = 0L
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setKeepPostFor302Redirects(true)
        val datos = DefaultDataSource.Factory(this, http)
        val extractores = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)
        val fuentes = DefaultMediaSourceFactory(datos, extractores).setLoadErrorHandlingPolicy(Reintentos())
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val bufer = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(fuentes)
            .setLoadControl(bufer)
            .build()
        exo = p

        val v = PlayerView(this).apply {
            player = p
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3500
            keepScreenOn = true
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
                titulo.visibility = vis
                if (vis != View.VISIBLE) pantallaCompleta()
            })
            setOnLongClickListener { cambiarAspecto(); true }
        }
        vista = v
        armarVista(v)

        // Toda la lista como playlist: los botones anterior/siguiente cambian de canal o
        // de episodio. El que se fuerza como HLS reemplaza sólo al actual.
        val medios = items.mapIndexed { i, it ->
            val forzar = if (i == indice) forzarHls == true else false
            MediaItem.Builder()
                .setUri(it.url)
                .setMimeType(tipoPorUrl(it.url, forzar))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.nombre).build())
                .build()
        }
        p.setMediaItems(medios, indice, if (itemActual.vivo) C.TIME_UNSET else inicioMs)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = errorExo(error)
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_BUFFERING) { if (cargandoDesde == 0L) cargandoDesde = System.currentTimeMillis() }
                else cargandoDesde = 0L
                if (s == Player.STATE_READY) { reintentos = 0; yaReprodujo = true; ocultarEstado() }
                if (s == Player.STATE_ENDED && !itemActual.vivo && indice < items.size - 1) {
                    /* fin del episodio: el siguiente lo pasa la playlist sola */
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = p.currentMediaItemIndex
                if (i in items.indices && i != indice) {
                    indice = i; inicioMs = 0; reintentos = 0; probeHls = false; probeUa = false; yaReprodujo = false
                    cargandoDesde = System.currentTimeMillis()
                    titulo.text = itemActual.nombre
                    mostrarEstado(itemActual.nombre, 2500)
                }
            }
        })
        p.playWhenReady = true
        p.prepare()
    }

    private fun errorExo(error: Throwable) {
        val p = exo ?: return
        val causa = error.cause
        // Se quedó atrás del vivo: se vuelve al borde en directo.
        if (error is PlaybackException && (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || causa is BehindLiveWindowException)) {
            p.seekToDefaultPosition(); p.prepare(); return
        }
        // Formato que no se reconoce: muchos HLS no terminan en .m3u8 (php, sin extensión).
        val noReconocido = error is PlaybackException && (
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        if (noReconocido && !probeHls) {
            probeHls = true
            mostrarEstado("Probando otro formato…", 0)
            val pos = p.currentPosition
            liberarExo()
            inicioMs = pos
            arrancarExo(true)
            return
        }
        // El servidor rechazó el pedido (403…): se prueba presentándose como VLC.
        val rechazado = error is PlaybackException && error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        if (rechazado && !probeUa && userAgent != uaVlc) {
            probeUa = true
            userAgent = uaVlc
            mostrarEstado("Probando otra conexión…", 0)
            val pos = p.currentPosition
            liberarExo()
            inicioMs = if (itemActual.vivo) 0 else pos
            arrancarExo(probeHls)
            return
        }
        // Cortes de red en vivo: se reintenta un par de veces antes de cambiar de motor.
        val deRed = error is PlaybackException && error.errorCode in 2000..2999 && !rechazado
        if (deRed && itemActual.vivo && reintentos < 3) {
            reintentos++
            mostrarEstado("Reconectando… ($reintentos)", 0)
            handler.postDelayed({ exo?.let { it.seekToDefaultPosition(); it.prepare() } }, 1500L * reintentos)
            return
        }
        // Último recurso: VLC, con todos sus códecs.
        mostrarEstado("Cambiando a VLC…", 0)
        inicioMs = if (itemActual.vivo) 0 else p.currentPosition
        liberarExo()
        arrancarVlc()
    }

    /**
     * Vigía:
     *  - Si un canal que YA estaba reproduciendo se traba cargando más de 12 s, se lo
     *    reinicia desde el vivo (corte momentáneo de la señal).
     *  - Si un enlace nunca llega a arrancar en 20 s, no se insiste más con ExoPlayer:
     *    se pasa a VLC (antes se reiniciaba para siempre y nunca cambiaba de motor).
     */
    private val vigia = object : Runnable {
        override fun run() {
            val p = exo
            val cargando = if (cargandoDesde > 0) System.currentTimeMillis() - cargandoDesde else 0L
            if (p != null && cargando > 0) {
                if (yaReprodujo && itemActual.vivo && cargando > 12_000) {
                    cargandoDesde = System.currentTimeMillis()
                    mostrarEstado("Recuperando la señal…", 2500)
                    p.seekToDefaultPosition(); p.prepare()
                } else if (!yaReprodujo && cargando > 20_000) {
                    cargandoDesde = 0L
                    mostrarEstado("No arranca con este motor: cambiando a VLC…", 0)
                    inicioMs = if (itemActual.vivo) 0 else p.currentPosition
                    liberarExo()
                    arrancarVlc()
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    private fun cambiarAspecto() {
        val v = vista ?: return
        modoAspecto = (modoAspecto + 1) % 3
        v.resizeMode = when (modoAspecto) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        mostrarEstado(arrayOf("Imagen: ajustada", "Imagen: recortada (llena la pantalla)", "Imagen: estirada")[modoAspecto], 1800)
    }

    // ===================== VLC =====================

    private fun arrancarVlc() {
        try {
            val opciones = arrayListOf(
                "--network-caching=3000", "--live-caching=3000", "--file-caching=3000",
                "--http-reconnect", "--clock-jitter=0", "--clock-synchro=0",
                "--audio-time-stretch", "--avcodec-skiploopfilter=1", "--no-stats"
            )
            val lib = LibVLC(this, opciones)
            libVlc = lib
            val mp = org.videolan.libvlc.MediaPlayer(lib)
            vlc = mp
            val capa = VLCVideoLayout(this)
            vlcVista = capa
            armarVista(capa)
            mp.attachViews(capa, null, false, false)
            val m = Media(lib, Uri.parse(itemActual.url))
            m.setHWDecoderEnabled(true, false)
            m.addOption(":http-user-agent=$userAgent")
            m.addOption(":network-caching=3000")
            mp.media = m
            m.release()
            mp.setEventListener { e ->
                when (e.type) {
                    org.videolan.libvlc.MediaPlayer.Event.Buffering ->
                        cargando.visibility = if (e.buffering < 100f) View.VISIBLE else View.GONE
                    org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                        cargando.visibility = View.GONE; ocultarEstado()
                        if (inicioMs > 0) { mp.time = inicioMs; inicioMs = 0 }
                    }
                    org.videolan.libvlc.MediaPlayer.Event.EncounteredError ->
                        mostrarEstado("No se pudo reproducir este enlace. Probá otro servidor o canal.", 0)
                    org.videolan.libvlc.MediaPlayer.Event.EndReached ->
                        if (!itemActual.vivo && indice < items.size - 1) cambiarA(indice + 1)
                }
            }
            capa.setOnClickListener {
                if (mp.isPlaying) mp.pause() else mp.play()
                titulo.visibility = View.VISIBLE
                handler.postDelayed({ titulo.visibility = View.GONE }, 3000)
            }
            capa.setOnLongClickListener { cambiarAspectoVlc(); true }
            cargando.visibility = View.VISIBLE
            mp.play()
            mostrarEstado("Reproduciendo con VLC", 2000)
        } catch (e: Throwable) {
            mostrarEstado("No se pudo abrir el reproductor VLC.", 0)
        }
    }

    private fun cambiarAspectoVlc() {
        val mp = vlc ?: return
        modoAspecto = (modoAspecto + 1) % 3
        when (modoAspecto) {
            1 -> mp.videoScale = org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_FILL
            2 -> mp.videoScale = org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> mp.videoScale = org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
        mostrarEstado(arrayOf("Imagen: ajustada", "Imagen: llena la pantalla", "Imagen: estirada")[modoAspecto], 1800)
    }

    // ===================== Cambio de canal / episodio =====================

    private fun cambiarA(nuevo: Int) {
        if (nuevo !in items.indices) return
        // Con ExoPlayer la playlist ya lo resuelve.
        val p = exo
        if (p != null) { p.seekTo(nuevo, if (items[nuevo].vivo) C.TIME_UNSET else 0L); p.prepare(); p.play(); return }
        indice = nuevo; inicioMs = 0
        arrancar()
        mostrarEstado(itemActual.nombre, 2500)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val controlesVisibles = vista?.isControllerFullyVisible == true
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { cambiarA(indice + 1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { cambiarA(indice - 1); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // En vivo, con los controles escondidos, arriba/abajo cambia de canal (como un TV).
                if (itemActual.vivo && !controlesVisibles && items.size > 1) {
                    cambiarA(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) indice - 1 else indice + 1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val mp = vlc
                if (mp != null && !itemActual.vivo) {
                    mp.time = (mp.time + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000 else -10_000).coerceAtLeast(0)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val mp = vlc
                if (mp != null) { if (mp.isPlaying) mp.pause() else mp.play(); return true }
                if (vista != null && !controlesVisibles) { vista?.showController(); return true }
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { if (vlc != null) cambiarAspectoVlc() else cambiarAspecto(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ===================== Vista y avisos =====================

    private fun armarVista(v: View) {
        raiz.removeAllViews()
        raiz.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        raiz.addView(titulo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        raiz.addView(estado, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(70) })
        raiz.addView(cargando, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        titulo.visibility = View.GONE
    }

    private val ocultar = Runnable { estado.visibility = View.GONE }
    private fun mostrarEstado(texto: String, duracionMs: Long) {
        estado.text = texto
        estado.visibility = View.VISIBLE
        handler.removeCallbacks(ocultar)
        if (duracionMs > 0) handler.postDelayed(ocultar, duracionMs)
    }
    private fun ocultarEstado() { handler.removeCallbacks(ocultar); estado.visibility = View.GONE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun pantallaCompleta() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pantallaCompleta()
    }

    // ===================== Ciclo de vida =====================

    override fun onPause() {
        super.onPause()
        exo?.pause()
        vlc?.pause()
    }

    override fun onResume() {
        super.onResume()
        pantallaCompleta()
        exo?.play()
        vlc?.play()
    }

    override fun finish() {
        // Posición para "Continuar viendo" (sólo películas y episodios).
        try {
            if (items.isNotEmpty() && !itemActual.vivo) {
                val pos: Long; val dur: Long
                val p = exo; val mp = vlc
                if (p != null) { pos = p.currentPosition; dur = p.duration } else if (mp != null) { pos = mp.time; dur = mp.length } else { pos = 0; dur = 0 }
                setResult(RESULT_OK, Intent().putExtra("url", itemActual.url).putExtra("pos", pos / 1000.0).putExtra("dur", if (dur > 0) dur / 1000.0 else 0.0))
            }
        } catch (e: Exception) { }
        super.finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        liberarTodo()
        super.onDestroy()
    }

    private fun liberarExo() {
        exo?.release(); exo = null
        vista?.player = null; vista = null
    }

    private fun liberarVlc() {
        try { vlc?.stop(); vlc?.detachViews(); vlc?.release() } catch (e: Throwable) { }
        vlc = null
        try { libVlc?.release() } catch (e: Throwable) { }
        libVlc = null
        vlcVista = null
    }

    private fun liberarTodo() { liberarExo(); liberarVlc() }
}
