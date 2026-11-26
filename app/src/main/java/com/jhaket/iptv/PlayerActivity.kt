package com.jhaket.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
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
 * Reproductor nativo de JhaKet: un solo reproductor, con la misma cara que el de antes.
 *
 * Motores: ExoPlayer (Media3) con los decodificadores de audio de FFmpeg y, si no puede con
 * un enlace, VLC (libVLC) automáticamente. Arriba a la derecha se ve cuál está andando
 * (EXO o VLC).
 *
 * Pantalla: arriba volver, título, si es en vivo o película y la calidad real; en el medio
 * anterior, -10 s, pausa, +10 s y siguiente; abajo la barra de tiempo (o EN VIVO), audio y
 * subtítulos, formato de imagen y bloqueo. En el teléfono, deslizar del lado izquierdo
 * cambia el volumen y del derecho el brillo (sólo se ve el porcentaje en el medio).
 *
 * Recibe en el extra "datos": { items:[{u,n,live}], index, start, ua }.
 * Devuelve al salir: url, pos y dur (segundos) para "Continuar viendo".
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private data class Item(val url: String, val nombre: String, val vivo: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var raiz: FrameLayout
    private lateinit var capaVideo: FrameLayout
    private lateinit var cargando: ProgressBar
    private lateinit var aviso: TextView          // mensajes de abajo ("Reconectando…")
    private lateinit var indicador: TextView      // porcentaje de volumen o brillo, y avisos centrales

    // Controles
    private lateinit var controles: FrameLayout
    private lateinit var tTitulo: TextView
    private lateinit var tTipo: TextView
    private lateinit var tCalidad: TextView
    private lateinit var tMotor: TextView
    private lateinit var bPlay: ImageButton
    private lateinit var bAtras10: ImageButton
    private lateinit var bAdelante10: ImageButton
    private lateinit var bAnterior: ImageButton
    private lateinit var bSiguiente: ImageButton
    private lateinit var filaTiempo: LinearLayout
    private lateinit var barra: SeekBar
    private lateinit var tActual: TextView
    private lateinit var tTotal: TextView
    private lateinit var bVivo: TextView
    private lateinit var bPistas: ImageButton
    private lateinit var bAspecto: ImageButton
    private lateinit var bBloquear: ImageButton
    private lateinit var candado: ImageView       // el que aparece con la pantalla bloqueada
    private var arrastrandoBarra = false

    private var items = listOf<Item>()
    private var indice = 0
    private var inicioMs = 0L
    private val itemActual get() = items[indice]

    /* Se presenta como el navegador del teléfono (lo que ya funcionaba con estas listas);
       si el canal lo rechaza, se prueba como VLC. */
    private var userAgent = ""
    private val uaVlc = "VLC/3.0.20 LibVLC/3.0.20"

    private var esTv = false
    private var bloqueado = false
    private var toqueEnCandado = false
    private var modoAspecto = 0

    // Gestos (sólo teléfono): 1 volumen, 2 brillo
    private var gesto = 0
    private var gx = 0f
    private var gy = 0f
    private var gInicio = 0f
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    // ExoPlayer
    private var exo: ExoPlayer? = null
    private var vista: PlayerView? = null
    private var reintentos = 0
    private var probeHls = false
    private var probeUa = false
    private var yaReprodujo = false
    private var cargandoDesde = 0L

    // VLC
    private var libVlc: LibVLC? = null
    private var vlc: org.videolan.libvlc.MediaPlayer? = null
    /** Decodificar la imagen por software (más compatible; usa más batería). Se elige desde el menú. */
    private var porSoftware = false

    // ============================================================
    // Inicio
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        userAgent = try {
            android.webkit.WebSettings.getDefaultUserAgent(this)
        } catch (e: Throwable) {
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }
        esTv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")

        leerDatos()
        if (items.isEmpty()) { finish(); return }

        armarPantalla()
        setContentView(raiz)
        pantallaCompleta()
        arrancar()
        handler.postDelayed(vigia, 2000)
        handler.post(actualizar)
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

    // ============================================================
    // Pantalla: la misma disposición que el reproductor de antes
    // ============================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun circulo(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun pastilla(color: Int) = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(color) }

    private fun boton(icono: Int, desc: String, tam: Int, fondo: Int = 0x40000000, accion: () -> Unit) = ImageButton(this).apply {
        setImageResource(icono)
        background = circulo(fondo)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = (tam * 0.24f).toInt()
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        contentDescription = desc
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(tam), dp(tam)).apply { leftMargin = dp(9); rightMargin = dp(9) }
        setOnClickListener { accion(); mostrarControles() }
        setOnFocusChangeListener { v, f -> v.scaleX = if (f) 1.15f else 1f; v.scaleY = v.scaleX; if (f) mostrarControles() }
    }

    private fun texto(sp: Float, negrita: Boolean = false) = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (negrita) typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 1f, Color.BLACK)
    }

    private fun armarPantalla() {
        raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        capaVideo = FrameLayout(this).apply {
            // La imagen no consume los toques: los recibe esta capa y muestra u oculta los controles.
            isClickable = true
            setOnClickListener { alternarControles() }
        }
        raiz.addView(capaVideo, FrameLayout.LayoutParams(-1, -1))

        cargando = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        raiz.addView(cargando, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))

        // ---- Controles ----
        controles = FrameLayout(this).apply { visibility = View.GONE }

        // Sombras arriba y abajo para que se lean los textos
        controles.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP))
        controles.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(150), Gravity.BOTTOM))

        // Arriba: volver · título (EN VIVO / Película · calidad) · motor
        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(18), 0)
        }
        arriba.addView(boton(R.drawable.ic_rep_volver, "Volver", 46, 0x00000000) { finish() })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, 0, 0) }
        tTitulo = texto(17f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        info.addView(tTitulo)
        val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
        tTipo = texto(11f, true).apply { setPadding(dp(7), dp(2), dp(7), dp(2)); setShadowLayer(0f, 0f, 0f, 0) }
        tCalidad = texto(11f, true).apply {
            setPadding(dp(7), dp(2), dp(7), dp(2)); setShadowLayer(0f, 0f, 0f, 0)
            background = pastilla(0x33FFFFFF); visibility = View.GONE
        }
        sub.addView(tTipo)
        sub.addView(tCalidad, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        info.addView(sub)
        arriba.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        tMotor = texto(12f, true).apply {
            setPadding(dp(9), dp(4), dp(9), dp(4)); setShadowLayer(0f, 0f, 0f, 0)
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(1), 0x99FFFFFF.toInt()); setColor(0x55000000) }
            contentDescription = "Motor de reproducción"
        }
        arriba.addView(tMotor)
        controles.addView(arriba, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Medio: anterior · -10 · pausa · +10 · siguiente
        val medio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bAnterior = boton(R.drawable.ic_rep_anterior, "Anterior", 54) { cambiarA(indice - 1) }
        bAtras10 = boton(R.drawable.ic_rep_atras10, "Retroceder 10 segundos", 54) { saltar(-10_000) }
        bPlay = boton(R.drawable.ic_rep_pausa, "Reproducir o pausar", 78) { alternarPausa() }.apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF3B47.toInt(), 0xFFFF7A45.toInt())).apply { shape = GradientDrawable.OVAL }
        }
        bAdelante10 = boton(R.drawable.ic_rep_adelante10, "Adelantar 10 segundos", 54) { saltar(10_000) }
        bSiguiente = boton(R.drawable.ic_rep_siguiente, "Siguiente", 54) { cambiarA(indice + 1) }
        listOf(bAnterior, bAtras10, bPlay, bAdelante10, bSiguiente).forEach { medio.addView(it) }
        controles.addView(medio, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Abajo: barra de tiempo (o EN VIVO) · audio y subtítulos · formato · bloquear
        val abajo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(12)) }
        filaTiempo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tActual = texto(13f).apply { text = "0:00" }
        tTotal = texto(13f).apply { text = "0:00" }
        barra = SeekBar(this).apply {
            max = 1000
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, delUsuario: Boolean) {
                    if (delUsuario) { tActual.text = tiempo((duracion() * p / 1000)); mostrarControles() }
                }
                override fun onStartTrackingTouch(s: SeekBar) { arrastrandoBarra = true; handler.removeCallbacks(ocultarControlesR) }
                override fun onStopTrackingTouch(s: SeekBar) { arrastrandoBarra = false; irA(duracion() * s.progress / 1000); mostrarControles() }
            })
        }
        filaTiempo.addView(tActual)
        filaTiempo.addView(barra, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        filaTiempo.addView(tTotal)
        abajo.addView(filaTiempo)

        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        bVivo = texto(12f, true).apply {
            text = "●  EN VIVO"
            setPadding(dp(12), dp(6), dp(12), dp(6)); setShadowLayer(0f, 0f, 0f, 0)
            background = pastilla(0xFFE53935.toInt())
            isFocusable = true; isClickable = true
            contentDescription = "Ir al momento actual"
            setOnClickListener { irAlVivo(); mostrarControles() }
        }
        fila.addView(bVivo)
        fila.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bPistas = boton(R.drawable.ic_rep_pistas, "Audio y subtítulos", 44) { elegirPistas() }
        bAspecto = boton(R.drawable.ic_rep_aspecto, "Formato de imagen", 44) { cambiarAspecto() }
        bBloquear = boton(R.drawable.ic_candado_abierto, "Bloquear la pantalla", 44) { alternarBloqueo() }
        fila.addView(bPistas); fila.addView(bAspecto); fila.addView(bBloquear)
        abajo.addView(fila)
        controles.addView(abajo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Tocar un lugar vacío de los controles los esconde
        controles.isClickable = true
        controles.setOnClickListener { ocultarControles() }
        raiz.addView(controles, FrameLayout.LayoutParams(-1, -1))

        // Aviso de abajo y porcentaje del medio
        aviso = texto(14f).apply {
            setBackgroundColor(0xAA000000.toInt()); setPadding(dp(14), dp(8), dp(14), dp(8)); visibility = View.GONE; setShadowLayer(0f, 0f, 0f, 0)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(118) })
        indicador = texto(22f, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xAA000000.toInt()) }
            setPadding(dp(26), dp(14), dp(26), dp(14)); visibility = View.GONE; setShadowLayer(0f, 0f, 0f, 0)
        }
        raiz.addView(indicador, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Candado de la pantalla bloqueada
        candado = ImageView(this).apply {
            setImageResource(R.drawable.ic_candado)
            background = circulo(0x99000000.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "Desbloquear la pantalla"
            visibility = View.GONE
            isClickable = true
            setOnClickListener { alternarBloqueo() }
        }
        raiz.addView(candado, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
    }

    /** Se acomodan los controles según lo que se reproduce (en vivo o película). */
    private fun acomodarControles() {
        val vivo = itemActual.vivo
        tTitulo.text = itemActual.nombre
        tTipo.text = if (vivo) "EN VIVO" else "PELÍCULA / SERIE"
        tTipo.background = pastilla(if (vivo) 0xFFE53935.toInt() else 0x33FFFFFF)
        bAtras10.visibility = if (vivo) View.GONE else View.VISIBLE
        bAdelante10.visibility = if (vivo) View.GONE else View.VISIBLE
        filaTiempo.visibility = if (vivo) View.GONE else View.VISIBLE
        bVivo.visibility = if (vivo && exo != null) View.VISIBLE else View.GONE
        val varios = items.size > 1
        bAnterior.visibility = if (varios) View.VISIBLE else View.GONE
        bSiguiente.visibility = if (varios) View.VISIBLE else View.GONE
        bAnterior.isEnabled = indice > 0; bAnterior.alpha = if (indice > 0) 1f else 0.35f
        bSiguiente.isEnabled = indice < items.size - 1; bSiguiente.alpha = if (indice < items.size - 1) 1f else 0.35f
        tMotor.text = if (vlc != null) (if (porSoftware) "VLC · SW" else "VLC") else "EXO"
        tCalidad.visibility = View.GONE
    }

    private val ocultarControlesR = Runnable { ocultarControles() }
    private fun mostrarControles() {
        if (bloqueado) return
        controles.visibility = View.VISIBLE
        handler.removeCallbacks(ocultarControlesR)
        if (!arrastrandoBarra) handler.postDelayed(ocultarControlesR, 4000)
    }
    private fun ocultarControles() {
        controles.visibility = View.GONE
        handler.removeCallbacks(ocultarControlesR)
        pantallaCompleta()
    }
    private fun alternarControles() { if (controles.visibility == View.VISIBLE) ocultarControles() else mostrarControles() }

    /** Cada medio segundo: pausa/reproducir, tiempo y calidad real de la imagen. */
    private val actualizar = object : Runnable {
        override fun run() {
            val reproduciendo = exo?.isPlaying ?: (vlc?.isPlaying ?: false)
            bPlay.setImageResource(if (reproduciendo || exo?.playWhenReady == true && vlc == null && exo?.playbackState == Player.STATE_BUFFERING) R.drawable.ic_rep_pausa else R.drawable.ic_rep_play)
            if (!itemActual.vivo && !arrastrandoBarra) {
                val d = duracion(); val pos = posicion()
                if (d > 0) {
                    barra.progress = (pos * 1000 / d).toInt()
                    tActual.text = tiempo(pos); tTotal.text = tiempo(d)
                }
            }
            val (w, h) = tamanoVideo()
            val q = etiquetaCalidad(w, h)
            if (q.isNotEmpty()) { tCalidad.text = q; tCalidad.visibility = View.VISIBLE }
            handler.postDelayed(this, 500)
        }
    }

    private fun tiempo(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%d:%02d", m, ss)
    }

    /** Igual que el reproductor de antes: sale del tamaño real de la imagen que llega. */
    private fun etiquetaCalidad(w: Int, h: Int): String {
        if (h <= 0) return ""
        val m = maxOf(h, w * 9 / 16)
        return when {
            m >= 2000 -> "4K"
            m >= 1300 -> "2K"
            m >= 1000 -> "FHD 1080p"
            m >= 700 -> "HD 720p"
            else -> "SD ${h}p"
        }
    }

    private fun tamanoVideo(): Pair<Int, Int> {
        exo?.let { return Pair(it.videoSize.width, it.videoSize.height) }
        try {
            val t = vlc?.currentVideoTrack
            if (t != null) return Pair(t.width, t.height)
        } catch (e: Throwable) { }
        return Pair(0, 0)
    }

    // ============================================================
    // Acciones comunes a los dos motores
    // ============================================================

    private fun posicion(): Long = exo?.currentPosition ?: (vlc?.time ?: 0L)
    private fun duracion(): Long {
        exo?.let { val d = it.duration; return if (d == C.TIME_UNSET || d < 0) 0L else d }
        return vlc?.length?.coerceAtLeast(0L) ?: 0L
    }
    private fun irA(ms: Long) { exo?.seekTo(ms) ?: vlc?.let { it.time = ms } }
    private fun saltar(delta: Long) {
        if (itemActual.vivo) return
        irA((posicion() + delta).coerceIn(0L, maxOf(0L, duracion())))
        mostrarIndicador(if (delta > 0) "+10 s" else "−10 s", 600)
    }
    private fun alternarPausa() {
        exo?.let { if (it.isPlaying) it.pause() else it.play(); return }
        vlc?.let { if (it.isPlaying) it.pause() else it.play() }
    }
    private fun irAlVivo() { exo?.let { it.seekToDefaultPosition(); it.play() } }

    private fun cambiarAspecto() {
        modoAspecto = (modoAspecto + 1) % 3
        vista?.resizeMode = when (modoAspecto) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        vlc?.videoScale = when (modoAspecto) {
            1 -> org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_FILL
            2 -> org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
        mostrarIndicador(arrayOf("Ajustada", "Llenar pantalla", "Estirada")[modoAspecto], 1200)
    }

    /** Audio y subtítulos: lista simple con las pistas que trae el canal o la película. */
    private fun elegirPistas() {
        val nombres = mutableListOf<String>()
        val acciones = mutableListOf<() -> Unit>()
        val p = exo
        val mp = vlc
        if (p != null) {
            for (g in p.currentTracks.groups) {
                if (g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    val nombre = f.label ?: f.language?.let { java.util.Locale(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } } ?: "Pista ${i + 1}"
                    val elegido = if (g.isTrackSelected(i)) "  ✓" else ""
                    val tipo = if (g.type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtítulos"
                    nombres.add("$tipo: $nombre$elegido")
                    val grupo = g.mediaTrackGroup; val idx = i; val t = g.type
                    acciones.add {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(t, false)
                            .setOverrideForType(TrackSelectionOverride(grupo, idx))
                            .build()
                    }
                }
            }
            if (nombres.any { it.startsWith("Subtítulos") }) {
                nombres.add("Subtítulos: desactivados")
                acciones.add { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
            }
        } else if (mp != null) {
            try {
                mp.audioTracks?.forEach { t ->
                    nombres.add("Audio: ${t.name}" + if (t.id == mp.audioTrack) "  ✓" else ""); acciones.add { mp.audioTrack = t.id }
                }
                mp.spuTracks?.forEach { t ->
                    nombres.add("Subtítulos: ${t.name}" + if (t.id == mp.spuTrack) "  ✓" else ""); acciones.add { mp.spuTrack = t.id }
                }
            } catch (e: Throwable) { }
        }
        // Para canales que se ven con cuadros verdes o rayas: VLC decodificando por software.
        if (!(vlc != null && porSoftware)) {
            nombres.add("Imagen con cuadros verdes o rayas: decodificar por software")
            acciones.add {
                porSoftware = true
                if (vlc != null) {
                    inicioMs = if (itemActual.vivo) 0 else posicion()
                    liberarVlc(); arrancarVlc(); acomodarControles()
                    mostrarAviso("Decodificando por software", 2500)
                } else pasarAVlc("Decodificando por software (VLC)…")
            }
        }
        handler.removeCallbacks(ocultarControlesR)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Audio, subtítulos e imagen")
            .setItems(nombres.toTypedArray()) { _, i -> acciones[i](); mostrarControles() }
            .setOnDismissListener { pantallaCompleta(); mostrarControles() }
            .show()
    }

    // ============================================================
    // Bloqueo de pantalla
    // ============================================================

    private val ocultarCandado = Runnable { if (bloqueado) candado.visibility = View.GONE }

    private fun alternarBloqueo() {
        if (!bloqueado) {
            bloqueado = true
            ocultarControles()
            candado.visibility = View.GONE
            mostrarIndicador("Pantalla bloqueada", 1200)
        } else {
            bloqueado = false
            handler.removeCallbacks(ocultarCandado)
            candado.visibility = View.GONE
            mostrarIndicador("Pantalla desbloqueada", 1000)
            mostrarControles()
        }
    }

    // ============================================================
    // Toques: bloqueo, gestos de volumen/brillo y mostrar controles
    // ============================================================

    private val ocultarIndicador = Runnable { indicador.visibility = View.GONE }
    private fun mostrarIndicador(t: String, duracionMs: Long) {
        indicador.text = t
        indicador.visibility = View.VISIBLE
        handler.removeCallbacks(ocultarIndicador)
        if (duracionMs > 0) handler.postDelayed(ocultarIndicador, duracionMs)
    }

    private fun dentroDe(v: View, ev: MotionEvent): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val r = android.graphics.Rect()
        v.getGlobalVisibleRect(r)
        return r.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    private fun brilloActual(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (e: Exception) { 0.5f }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Pantalla bloqueada: sólo el candado responde; tocar en otro lado lo muestra.
        if (bloqueado) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                toqueEnCandado = dentroDe(candado, ev)
                if (!toqueEnCandado) {
                    candado.visibility = View.VISIBLE
                    handler.removeCallbacks(ocultarCandado)
                    handler.postDelayed(ocultarCandado, 3000)
                }
            }
            return if (toqueEnCandado) super.dispatchTouchEvent(ev) else true
        }
        if (!esTv) {
            val alto = raiz.height.toFloat().coerceAtLeast(1f)
            val ancho = raiz.width.toFloat().coerceAtLeast(1f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { gx = ev.x; gy = ev.y; gesto = 0 }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - gy; val dx = ev.x - gx
                    if (gesto == 0 && Math.abs(dy) > dp(18) && Math.abs(dy) > Math.abs(dx) * 1.6f &&
                        gy > alto * 0.15f && gy < alto * 0.80f && Math.abs(gx - ancho / 2) > ancho * 0.12f
                    ) {
                        gesto = if (gx < ancho / 2) 1 else 2
                        gy = ev.y
                        gInicio = if (gesto == 1) audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() else brilloActual()
                        val cancelar = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancelar)
                        cancelar.recycle()
                    }
                    if (gesto != 0) {
                        val fraccion = -(ev.y - gy) / (alto * 0.75f)
                        if (gesto == 1) {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val v = (gInicio + fraccion * max).toInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            mostrarIndicador("Volumen\n" + (v * 100 / maxOf(1, max)) + "%", 0)
                        } else {
                            val b = (gInicio + fraccion).coerceIn(0.01f, 1f)
                            val lp = window.attributes
                            lp.screenBrightness = b
                            window.attributes = lp
                            mostrarIndicador("Brillo\n" + Math.round(b * 100) + "%", 0)
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gesto != 0) {
                        gesto = 0
                        handler.removeCallbacks(ocultarIndicador)
                        handler.postDelayed(ocultarIndicador, 700)
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ============================================================
    // Teclas (control remoto de la TV)
    // ============================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (bloqueado && keyCode != KeyEvent.KEYCODE_BACK) return true
        val visibles = controles.visibility == View.VISIBLE
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { cambiarA(indice + 1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { cambiarA(indice - 1); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { alternarPausa(); mostrarControles(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { saltar(10_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { saltar(-10_000); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { cambiarAspecto(); return true }
        }
        if (!visibles) {
            when (keyCode) {
                // Controles escondidos: arriba/abajo cambia de canal en vivo (como un TV);
                // izquierda/derecha atrasa o adelanta en películas; OK muestra los controles.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (itemActual.vivo && items.size > 1) { cambiarA(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) indice - 1 else indice + 1); return true }
                    mostrarControles(); bPlay.requestFocus(); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!itemActual.vivo) { saltar(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000 else -10_000); return true }
                    mostrarControles(); bPlay.requestFocus(); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { mostrarControles(); bPlay.requestFocus(); return true }
            }
        } else if (keyCode != KeyEvent.KEYCODE_BACK) {
            mostrarControles()
        }
        return super.onKeyDown(keyCode, event)
    }

    // ============================================================
    // Elección del motor
    // ============================================================

    private fun arrancar() {
        liberarTodo()
        reintentos = 0; probeHls = false; probeUa = false; yaReprodujo = false; cargandoDesde = 0L
        val esquema = Uri.parse(itemActual.url).scheme?.lowercase() ?: ""
        if (esquema in setOf("udp", "rtp", "mms", "mmsh", "srt", "rtmps")) arrancarVlc() else arrancarExo(null)
        acomodarControles()
        mostrarControles()
    }

    private fun ponerVideo(v: View) {
        capaVideo.removeAllViews()
        capaVideo.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    // ============================================================
    // ExoPlayer
    // ============================================================

    /** Reintentos con espera creciente; ante 403/404 o formato inválido, no se insiste. */
    private inner class Reintentos : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (itemActual.vivo) 6 else 4
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = loadErrorInfo.exception
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
            else -> null
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
            // Se espera un cuadro completo antes de mostrar imagen: arrancar sin él daba cuadros verdes.
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        val fuentes = DefaultMediaSourceFactory(datos, extractores).setLoadErrorHandlingPolicy(Reintentos())
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val bufer = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(fuentes).setLoadControl(bufer).build()
        exo = p

        val v = PlayerView(this).apply {
            player = p
            useController = false                 // los controles son los propios de JhaKet
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
            resizeMode = when (modoAspecto) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
        vista = v
        ponerVideo(v)

        val medios = items.mapIndexed { i, it ->
            MediaItem.Builder()
                .setUri(it.url)
                .setMimeType(tipoPorUrl(it.url, i == indice && forzarHls == true))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.nombre).build())
                .build()
        }
        p.setMediaItems(medios, indice, if (itemActual.vivo) C.TIME_UNSET else inicioMs)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = errorExo(error)
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_BUFFERING) { if (cargandoDesde == 0L) cargandoDesde = System.currentTimeMillis() } else cargandoDesde = 0L
                if (s == Player.STATE_READY) { reintentos = 0; yaReprodujo = true; ocultarAviso() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = p.currentMediaItemIndex
                if (i in items.indices && i != indice) {
                    indice = i; inicioMs = 0; reintentos = 0; probeHls = false; probeUa = false; yaReprodujo = false
                    cargandoDesde = System.currentTimeMillis()
                    acomodarControles()
                    mostrarIndicador(itemActual.nombre, 1500)
                }
            }
        })
        p.playWhenReady = true
        p.prepare()
    }

    private fun errorExo(error: PlaybackException) {
        val p = exo ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || error.cause is BehindLiveWindowException) {
            p.seekToDefaultPosition(); p.prepare(); return
        }
        val noReconocido = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (noReconocido && !probeHls) {
            probeHls = true
            mostrarAviso("Probando otro formato…", 0)
            reiniciarExo(true); return
        }
        val rechazado = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        if (rechazado && !probeUa && userAgent != uaVlc) {
            probeUa = true
            userAgent = uaVlc
            mostrarAviso("Probando otra conexión…", 0)
            reiniciarExo(probeHls); return
        }
        val deRed = error.errorCode in 2000..2999 && !rechazado
        if (deRed && itemActual.vivo && reintentos < 3) {
            reintentos++
            mostrarAviso("Reconectando… ($reintentos)", 0)
            handler.postDelayed({ exo?.let { it.seekToDefaultPosition(); it.prepare() } }, 1500L * reintentos)
            return
        }
        pasarAVlc("Cambiando a VLC…")
    }

    private fun reiniciarExo(forzarHls: Boolean) {
        val pos = exo?.currentPosition ?: 0L
        liberarExo()
        inicioMs = if (itemActual.vivo) 0 else pos
        arrancarExo(forzarHls)
        acomodarControles()
    }

    private fun pasarAVlc(motivo: String) {
        mostrarAviso(motivo, 0)
        inicioMs = if (itemActual.vivo) 0 else (exo?.currentPosition ?: 0L)
        liberarExo()
        arrancarVlc()
        acomodarControles()
    }

    /**
     * Vigía: si un canal que ya estaba andando se traba más de 12 s, se lo reinicia desde
     * el vivo. Si un enlace nunca arranca en 20 s, se pasa a VLC.
     */
    private val vigia = object : Runnable {
        override fun run() {
            val p = exo
            val espera = if (cargandoDesde > 0) System.currentTimeMillis() - cargandoDesde else 0L
            if (p != null && espera > 0) {
                if (yaReprodujo && itemActual.vivo && espera > 12_000) {
                    cargandoDesde = System.currentTimeMillis()
                    mostrarAviso("Recuperando la señal…", 2500)
                    p.seekToDefaultPosition(); p.prepare()
                } else if (!yaReprodujo && espera > 20_000) {
                    cargandoDesde = 0L
                    pasarAVlc("No arranca con EXO: cambiando a VLC…")
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    // ============================================================
    // VLC
    // ============================================================

    private fun arrancarVlc() {
        try {
            val lib = LibVLC(this, arrayListOf(
                "--network-caching=3000", "--live-caching=3000", "--file-caching=3000",
                "--http-reconnect", "--clock-jitter=0", "--clock-synchro=0",
                "--audio-time-stretch", "--avcodec-skiploopfilter=1", "--no-stats"
            ))
            libVlc = lib
            val mp = org.videolan.libvlc.MediaPlayer(lib)
            vlc = mp
            val capa = VLCVideoLayout(this)
            ponerVideo(capa)
            mp.attachViews(capa, null, false, false)
            val m = Media(lib, Uri.parse(itemActual.url))
            m.setHWDecoderEnabled(!porSoftware, false)
            m.addOption(":http-user-agent=$userAgent")
            m.addOption(":network-caching=3000")
            mp.media = m
            m.release()
            mp.videoScale = when (modoAspecto) {
                1 -> org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_FILL
                2 -> org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                else -> org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
            mp.setEventListener { e ->
                when (e.type) {
                    org.videolan.libvlc.MediaPlayer.Event.Buffering -> cargando.visibility = if (e.buffering < 100f) View.VISIBLE else View.GONE
                    org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                        cargando.visibility = View.GONE; ocultarAviso()
                        if (inicioMs > 0) { mp.time = inicioMs; inicioMs = 0 }
                    }
                    org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                        cargando.visibility = View.GONE
                        mostrarAviso("No se pudo reproducir este enlace. Probá otro servidor o canal.", 0)
                    }
                    org.videolan.libvlc.MediaPlayer.Event.EndReached ->
                        if (!itemActual.vivo && indice < items.size - 1) cambiarA(indice + 1)
                }
            }
            cargando.visibility = View.VISIBLE
            mp.play()
        } catch (e: Throwable) {
            mostrarAviso("No se pudo abrir VLC.", 0)
        }
    }

    // ============================================================
    // Cambio de canal o episodio
    // ============================================================

    private fun cambiarA(nuevo: Int) {
        if (nuevo !in items.indices) return
        val p = exo
        if (p != null) { p.seekTo(nuevo, if (items[nuevo].vivo) C.TIME_UNSET else 0L); p.prepare(); p.play(); return }
        indice = nuevo; inicioMs = 0
        arrancar()                     // con VLC andando, el nuevo vuelve a probar EXO primero
        mostrarIndicador(itemActual.nombre, 1500)
    }

    // ============================================================
    // Avisos, pantalla completa y ciclo de vida
    // ============================================================

    private val ocultarAvisoR = Runnable { aviso.visibility = View.GONE }
    private fun mostrarAviso(t: String, duracionMs: Long) {
        aviso.text = t
        aviso.visibility = View.VISIBLE
        handler.removeCallbacks(ocultarAvisoR)
        if (duracionMs > 0) handler.postDelayed(ocultarAvisoR, duracionMs)
    }
    private fun ocultarAviso() { handler.removeCallbacks(ocultarAvisoR); aviso.visibility = View.GONE }

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

    override fun onPause() { super.onPause(); exo?.pause(); vlc?.pause() }
    override fun onResume() { super.onResume(); pantallaCompleta(); exo?.play(); vlc?.play() }

    override fun finish() {
        try {
            if (items.isNotEmpty() && !itemActual.vivo) {
                setResult(RESULT_OK, Intent()
                    .putExtra("url", itemActual.url)
                    .putExtra("pos", posicion() / 1000.0)
                    .putExtra("dur", duracion() / 1000.0))
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
    }

    private fun liberarTodo() { liberarExo(); liberarVlc() }
}
