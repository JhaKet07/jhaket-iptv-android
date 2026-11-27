package com.jhaket.iptv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Rational
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
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
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

    companion object {
        /** El reproductor abierto: si se abre otro (por ejemplo, con éste en la ventana flotante), se cierra el anterior. */
        private var abierto: java.lang.ref.WeakReference<PlayerActivity>? = null
    }

    // Ventana flotante y control en la barra de notificaciones (sólo en el celular)
    private var control: ControlMedia? = null
    private var enPip = false
    private var entrandoPip = false
    private var detenido = false
    private var clavePip = ""

    private data class Prog(val titulo: String, val inicio: Long, val fin: Long)
    private data class Item(val url: String, val nombre: String, val vivo: Boolean, val logo: String = "", val guia: List<Prog> = emptyList(),
                            val guiaId: String = "", val guiaNombre: String = "", val guiaPais: String = "")

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var raiz: FrameLayout
    private lateinit var capaVideo: FrameLayout
    private lateinit var cargando: ProgressBar
    private lateinit var aviso: TextView          // mensajes de abajo ("Reconectando…")
    private lateinit var indicador: TextView      // porcentaje de volumen o brillo, y avisos centrales

    // Controles
    private lateinit var controles: FrameLayout
    private lateinit var tTitulo: TextView
    private lateinit var iLogo: ImageView
    private lateinit var tAhora: TextView
    private lateinit var tDespues: TextView
    /** Logos ya descargados (al cambiar de canal no se vuelven a bajar). */
    private val logos = HashMap<String, android.graphics.Bitmap?>()
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
    private var esAdmin = false
    private lateinit var bVerificar: ImageButton

    // Enviar a la tele (sólo en el celular)
    private lateinit var bEnviar: ImageButton
    private lateinit var barraTele: TextView
    private var destinoNombre: String? = null      // tele a la que se está enviando
    private var enCast = false
    private var teleDlna: Dlna.Tele? = null
    private var ultimoEnviado = ""
    private var remotaPausada = false
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
        abierto?.get()?.let { if (it !== this && !it.isFinishing) it.finish() }
        abierto = java.lang.ref.WeakReference(this)
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
        if (!esTv) Cast.iniciar(this)?.sessionManager?.addSessionManagerListener(oyenteCast, CastSession::class.java)

        armarPantalla()
        setContentView(raiz)
        if (!esTv) control = ControlMedia(this, 4711, object : ControlMedia.Acciones {
            override fun reproducir() {
                if (destinoNombre != null) { if (remotaPausada) pausaRemota() } else { exo?.play() ?: vlc?.play() }
            }
            override fun pausar() {
                if (destinoNombre != null) { if (!remotaPausada) pausaRemota() } else { exo?.pause() ?: vlc?.pause() }
            }
            override fun anterior() = cambiarA(indice - 1)
            override fun siguiente() = cambiarA(indice + 1)
            override fun cerrar() { finish() }
        })
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
                if (u.isNotEmpty()) {
                    val g = o.optJSONArray("g")
                    val progs = mutableListOf<Prog>()
                    if (g != null) for (k in 0 until g.length()) {
                        val pr = g.optJSONObject(k) ?: continue
                        progs.add(Prog(pr.optString("t", ""), pr.optLong("s", 0L), pr.optLong("e", 0L)))
                    }
                    lista.add(Item(u, o.optString("n", ""), o.optBoolean("live", false), o.optString("l", ""), progs,
                        o.optString("gid", ""), o.optString("gn", ""), o.optString("gp", "")))
                }
            }
            items = lista
            indice = j.optInt("index", 0).coerceIn(0, maxOf(0, lista.size - 1))
            inicioMs = (j.optDouble("start", 0.0) * 1000).toLong()
            esAdmin = j.optBoolean("admin", false)
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
        iLogo = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x26FFFFFF) }
            setPadding(dp(4), dp(3), dp(4), dp(3))
            visibility = View.GONE
        }
        arriba.addView(iLogo, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(2); rightMargin = dp(10) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), 0, 0, 0) }
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
        tAhora = texto(14f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0); visibility = View.GONE }
        tDespues = texto(12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(0xCCFFFFFF.toInt()); visibility = View.GONE }
        info.addView(tAhora)
        info.addView(tDespues)
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
        bVerificar = boton(R.drawable.ic_rep_verificar, "Verificar la guía con Google", 44) { verificarGuia() }
        fila.addView(bVerificar)
        bEnviar = boton(R.drawable.ic_rep_enviar, "Enviar a la tele", 44) { elegirTele() }
        if (esTv) bEnviar.visibility = View.GONE
        fila.addView(bEnviar)
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

        // "Viendo en la tele": arriba en el medio mientras se envía (con los controles escondidos)
        barraTele = texto(14f, true).apply {
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xE61E88E5.toInt()) }
            setPadding(dp(18), dp(9), dp(18), dp(9)); setShadowLayer(0f, 0f, 0f, 0)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { elegirTele() }
        }
        raiz.addView(barraTele, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })
    }

    /** Se acomodan los controles según lo que se reproduce (en vivo o película). */
    private fun acomodarControles() {
        val vivo = itemActual.vivo
        tTitulo.text = itemActual.nombre
        ponerLogo(itemActual.logo)
        mostrarGuia()
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
        bVerificar.visibility = if (esAdmin && vivo && itemActual.guia.isNotEmpty()) View.VISIBLE else View.GONE
        // Cambió el canal o el episodio mientras se envía a la tele: se manda el nuevo.
        if (destinoNombre != null && itemActual.url != ultimoEnviado) handler.post { reenviar() }
    }

    private fun hora(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    /** Guía de programación: lo que está al aire ahora y lo que sigue (sólo canales en vivo). */
    private var guiaMostrada = ""
    private fun mostrarGuia() {
        if (items.isEmpty() || !itemActual.vivo || itemActual.guia.isEmpty()) {
            tAhora.visibility = View.GONE; tDespues.visibility = View.GONE; guiaMostrada = ""; return
        }
        val ahora = System.currentTimeMillis()
        val g = itemActual.guia
        val i = g.indexOfFirst { it.inicio <= ahora && it.fin > ahora }
        val actual = if (i >= 0) g[i] else null
        val sigue = if (i >= 0) g.getOrNull(i + 1) else g.firstOrNull { it.inicio > ahora }
        val clave = itemActual.url + "|" + (actual?.inicio ?: 0) + "|" + (sigue?.inicio ?: 0)
        if (clave == guiaMostrada) return
        guiaMostrada = clave
        if (actual != null) {
            val bandera = if (esAdmin) banderaPais(itemActual.guiaPais) else ""
            tAhora.text = (if (bandera.isNotEmpty()) "$bandera  " else "") + actual.titulo + "   " + hora(actual.inicio) + " – " + hora(actual.fin)
            tAhora.visibility = View.VISIBLE
        } else tAhora.visibility = View.GONE
        if (sigue != null) {
            tDespues.text = "Después: " + sigue.titulo + " · " + hora(sigue.inicio)
            tDespues.visibility = View.VISIBLE
        } else tDespues.visibility = View.GONE
    }

    private fun banderaPais(c: String): String {
        if (c == "latam") return "🌎"
        if (c.length != 2) return ""
        val k = if (c == "uk") "GB" else c.uppercase()
        return k.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** Pregunta a Google (por el servidor de JhaKet) si la guía coincide con lo que se emite. */
    private fun verificarGuia() {
        val ahora = System.currentTimeMillis()
        val it0 = itemActual
        val actual = it0.guia.firstOrNull { it.inicio <= ahora && it.fin > ahora } ?: run {
            mostrarIndicador("La guía no tiene un programa\npara este momento", 1800); return
        }
        val token = getSharedPreferences("jhaket", MODE_PRIVATE).getString("token", "") ?: ""
        if (token.isEmpty()) { mostrarIndicador("Iniciá sesión de nuevo\npara verificar", 1800); return }
        mostrarIndicador("Consultando a Google…", 0)
        handler.removeCallbacks(ocultarControlesR)
        Thread {
            var titulo: String; var cuerpo: String
            try {
                val c = java.net.URL("https://jhaketiptv.floot.app/_api/ext/action?t=" + java.net.URLEncoder.encode(token, "UTF-8"))
                    .openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15000; c.readTimeout = 60000
                c.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                val datos = JSONObject()
                    .put("action", "epg.verify").put("channel", it0.nombre)
                    .put("epgId", it0.guiaId).put("epgName", it0.guiaNombre)
                    .put("title", actual.titulo).put("start", actual.inicio).put("stop", actual.fin)
                c.outputStream.use { it.write(datos.toString().toByteArray()) }
                val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
                val r = JSONObject(txt)
                if (r.has("error")) { titulo = "No se pudo verificar"; cuerpo = r.optString("error") }
                else {
                    val programa = r.optString("programa", "")
                    val explicacion = r.optString("explicacion", "")
                    val paisGuia = r.optString("paisGuia", "")
                    val fuentes = r.optJSONArray("fuentes")?.length() ?: 0
                    titulo = when {
                        r.isNull("coincide") -> "❔ No se pudo confirmar"
                        r.optBoolean("coincide") -> "✅ La guía coincide"
                        else -> "⚠️ La guía no coincide"
                    }
                    val sb = StringBuilder()
                    sb.append("Canal: ").append(it0.nombre).append("\n")
                    sb.append("Guía: ").append(actual.titulo).append(" (").append(hora(actual.inicio)).append(" – ").append(hora(actual.fin)).append(")\n")
                    if (it0.guiaNombre.isNotEmpty()) sb.append("Canal de la guía: ").append(it0.guiaNombre).append(if (it0.guiaPais.isNotEmpty()) "  " + banderaPais(it0.guiaPais) else "").append("\n")
                    if (programa.isNotEmpty()) sb.append("\nSegún Google, ahora en Argentina: ").append(programa).append("\n")
                    if (paisGuia.isNotEmpty()) sb.append("La guía parece ser de la señal de: ").append(paisGuia).append("\n")
                    if (explicacion.isNotEmpty()) sb.append("\n").append(explicacion).append("\n")
                    if (r.optBoolean("coincide", true) == false && !r.isNull("coincide"))
                        sb.append("\nPara corregirlo: en Agregar contenido → Editar, elegí otro canal de la guía (el de la señal de Argentina).")
                    sb.append("\n\nFuentes consultadas: ").append(fuentes)
                    cuerpo = sb.toString()
                }
            } catch (e: Throwable) {
                titulo = "No se pudo verificar"; cuerpo = "Revisá la conexión y probá de nuevo."
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                indicador.visibility = View.GONE
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(titulo).setMessage(cuerpo)
                    .setPositiveButton("Listo", null)
                    .setOnDismissListener { pantallaCompleta(); mostrarControles() }
                    .show()
            }
        }.start()
    }

    /** Logo del canal al lado del nombre. Se baja una sola vez y se guarda. */
    private fun ponerLogo(url: String) {
        if (!url.startsWith("http")) { iLogo.visibility = View.GONE; return }
        if (logos.containsKey(url)) {
            val b = logos[url]
            if (b != null) { iLogo.setImageBitmap(b); iLogo.visibility = View.VISIBLE } else iLogo.visibility = View.GONE
            return
        }
        iLogo.visibility = View.GONE
        val pedido = url
        Thread {
            val bmp = try {
                val c = java.net.URL(pedido).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", userAgent)
                c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: Throwable) { null }
            runOnUiThread {
                logos[pedido] = bmp
                if (!isFinishing && items.isNotEmpty() && itemActual.logo == pedido && bmp != null) {
                    iLogo.setImageBitmap(bmp); iLogo.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private val ocultarControlesR = Runnable { ocultarControles() }
    private fun mostrarControles() {
        if (bloqueado || enPip) return
        controles.visibility = View.VISIBLE
        handler.removeCallbacks(ocultarControlesR)
        if (!arrastrandoBarra) handler.postDelayed(ocultarControlesR, 4000)
        mostrarEstadoTele()
    }
    private fun ocultarControles() {
        controles.visibility = View.GONE
        handler.removeCallbacks(ocultarControlesR)
        pantallaCompleta()
        mostrarEstadoTele()
    }
    private fun alternarControles() { if (controles.visibility == View.VISIBLE) ocultarControles() else mostrarControles() }

    /** Cada medio segundo: pausa/reproducir, tiempo y calidad real de la imagen. */
    private val actualizar = object : Runnable {
        override fun run() {
            val reproduciendo = exo?.isPlaying ?: (vlc?.isPlaying ?: false)
            val andando = if (destinoNombre != null) !remotaPausada
                else reproduciendo || exo?.playWhenReady == true && vlc == null && exo?.playbackState == Player.STATE_BUFFERING
            bPlay.setImageResource(if (andando) R.drawable.ic_rep_pausa else R.drawable.ic_rep_play)
            if (!itemActual.vivo && !arrastrandoBarra) {
                val d = duracion(); val pos = posicion()
                if (d > 0) {
                    barra.progress = (pos * 1000 / d).toInt()
                    tActual.text = tiempo(pos); tTotal.text = tiempo(d)
                }
            }
            mostrarGuia()
            actualizarControlMedia()
            actualizarPip()
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
        if (destinoNombre != null) { pausaRemota(); return }
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
        if (enPip) return
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
            if (p != null && espera > 0 && destinoNombre == null) {
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
    // Enviar a la tele (Chromecast / Google TV, Smart TV por DLNA, duplicar pantalla)
    // ============================================================

    private val temaDialogo = android.R.style.Theme_DeviceDefault_Dialog_Alert

    private fun elegirTele() {
        handler.removeCallbacks(ocultarControlesR)
        val nombre = destinoNombre
        if (nombre != null) {
            AlertDialog.Builder(this, temaDialogo)
                .setTitle("Viendo en $nombre")
                .setItems(arrayOf("Dejar de enviar y ver en el celular", "Enviar a otra tele")) { _, i ->
                    if (i == 0) detenerEnvio(true) else { detenerEnvio(false); buscarTeles() }
                }
                .setOnDismissListener { pantallaCompleta(); mostrarControles() }
                .show()
            return
        }
        buscarTeles()
    }

    private fun buscarTeles() {
        val cast = Cast.iniciar(this)
        val router = if (cast != null) try { MediaRouter.getInstance(this) } catch (e: Throwable) { null } else null
        val oyenteRutas = object : MediaRouter.Callback() {}
        try { router?.addCallback(Cast.selector, oyenteRutas, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN) } catch (e: Throwable) { }
        var cancelado = false
        val espera = AlertDialog.Builder(this, temaDialogo)
            .setTitle("Buscando teles en tu WiFi…")
            .setView(ProgressBar(this).apply { isIndeterminate = true; setPadding(0, dp(20), 0, dp(20)) })
            .setNegativeButton("Cancelar") { _, _ -> cancelado = true }
            .setOnCancelListener { cancelado = true }
            .create()
        espera.setOnDismissListener { if (cancelado) { try { router?.removeCallback(oyenteRutas) } catch (e: Throwable) { }; pantallaCompleta() } }
        espera.show()
        Thread {
            val dlna = Dlna.buscar(this, 3500)
            runOnUiThread {
                if (cancelado || isFinishing) return@runOnUiThread
                espera.dismiss()
                val rutas = try {
                    router?.routes?.filter { !it.isDefault && it.isEnabled && it.matchesSelector(Cast.selector) } ?: emptyList()
                } catch (e: Throwable) { emptyList() }
                mostrarTeles(rutas, dlna, router, oyenteRutas)
            }
        }.start()
    }

    private fun mostrarTeles(rutas: List<MediaRouter.RouteInfo>, dlna: List<Dlna.Tele>, router: MediaRouter?, oyenteRutas: MediaRouter.Callback) {
        val nombres = mutableListOf<String>()
        val acciones = mutableListOf<() -> Unit>()
        val vistos = HashSet<String>()
        for (r in rutas) { vistos.add(r.name.lowercase()); nombres.add("📺  ${r.name}   ·  Chromecast / Google TV"); acciones.add { enviarCast(r) } }
        // Las Android TV aparecen también por DLNA: si ya están por Cast, no se repiten.
        for (t in dlna) if (vistos.add(t.nombre.lowercase())) { nombres.add("📺  ${t.nombre}   ·  Smart TV"); acciones.add { enviarDlna(t) } }
        nombres.add("📱  Duplicar la pantalla (cualquier tele compatible)"); acciones.add { duplicarPantalla() }
        AlertDialog.Builder(this, temaDialogo)
            .setTitle(if (rutas.isEmpty() && dlna.isEmpty()) "No encontré teles en tu WiFi" else "Enviar a la tele")
            .setItems(nombres.toTypedArray()) { _, i -> acciones[i]() }
            .setOnDismissListener {
                try { router?.removeCallback(oyenteRutas) } catch (e: Throwable) { }
                pantallaCompleta(); mostrarControles()
            }
            .show()
    }

    private fun pausarLocal() {
        exo?.pause(); vlc?.pause()
        cargandoDesde = 0L
    }

    private fun reanudarLocal(posMs: Long) {
        exo?.let {
            if (itemActual.vivo) it.seekToDefaultPosition() else if (posMs >= 0) it.seekTo(posMs)
            it.play(); return
        }
        vlc?.let {
            if (!itemActual.vivo && posMs >= 0) it.time = posMs
            it.play()
        }
    }

    private fun mostrarEstadoTele() {
        val nombre = destinoNombre
        if (!::barraTele.isInitialized) return
        bEnviar.background = circulo(if (nombre != null) 0xFF1E88E5.toInt() else 0x40000000)
        barraTele.visibility = if (nombre != null && controles.visibility != View.VISIBLE && !bloqueado && !enPip) View.VISIBLE else View.GONE
    }

    private fun ponerEstadoTele(t: String) {
        barraTele.text = "📺  $t"
        mostrarEstadoTele()
    }

    // ---- Google Cast ----

    private fun enviarCast(r: MediaRouter.RouteInfo) {
        val cast = Cast.iniciar(this) ?: return
        destinoNombre = r.name; teleDlna = null; enCast = true; remotaPausada = false
        ponerEstadoTele("Conectando con ${r.name}…")
        pausarLocal()
        val sesion = cast.sessionManager.currentCastSession
        if (sesion != null && sesion.isConnected && r.isSelected) { cargarEnCast(sesion); return }
        try { MediaRouter.getInstance(this).selectRoute(r) } catch (e: Throwable) {
            mostrarAviso("No se pudo conectar con ${r.name}", 4000); detenerEnvio(true)
        }
    }

    private fun cargarEnCast(sesion: CastSession) {
        val it0 = itemActual
        val (url, tipo) = Cast.medio(it0.url, it0.vivo)
        val meta = com.google.android.gms.cast.MediaMetadata(
            if (it0.vivo) com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_GENERIC else com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE
        ).apply {
            putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, it0.nombre)
            if (it0.logo.startsWith("http")) addImage(WebImage(Uri.parse(it0.logo)))
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(if (it0.vivo) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(tipo)
            .setMetadata(meta)
            .build()
        val pedido = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
            .apply { if (!it0.vivo) setCurrentTime(posicion()) }
            .build()
        ultimoEnviado = it0.url
        val cliente = sesion.remoteMediaClient
        if (cliente == null) { mostrarAviso("La tele no respondió", 4000); detenerEnvio(true); return }
        cliente.load(pedido).setResultCallback { r ->
            runOnUiThread {
                if (!enCast) return@runOnUiThread
                ponerEstadoTele("Viendo en $destinoNombre")
                if (!r.status.isSuccess) mostrarAviso("La tele no pudo abrir este enlace. Probá con «Duplicar la pantalla».", 6000)
            }
        }
    }

    private val oyenteCast = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStarted(s: CastSession, id: String) { if (enCast) cargarEnCast(s) }
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            if (enCast) { mostrarAviso("No se pudo conectar con la tele", 4000); detenerEnvio(true) }
        }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionEnded(s: CastSession, error: Int) {
            // Se cortó desde la tele o desde la notificación: se sigue viendo en el celular.
            if (enCast) { enCast = false; destinoNombre = null; ultimoEnviado = ""; mostrarEstadoTele(); reanudarLocal(-1) }
        }
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { if (enCast && itemActual.url != ultimoEnviado) cargarEnCast(s) }
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    // ---- DLNA ----

    private fun enviarDlna(t: Dlna.Tele) {
        destinoNombre = t.nombre; teleDlna = t; enCast = false; remotaPausada = false
        ponerEstadoTele("Conectando con ${t.nombre}…")
        pausarLocal()
        enviarDlnaActual()
    }

    private fun enviarDlnaActual() {
        val t = teleDlna ?: return
        val it0 = itemActual
        ultimoEnviado = it0.url
        Thread {
            val ok = Dlna.reproducir(t, it0.url, it0.nombre, it0.vivo)
            runOnUiThread {
                if (teleDlna !== t) return@runOnUiThread
                if (ok) ponerEstadoTele("Viendo en ${t.nombre}")
                else { mostrarAviso("${t.nombre} no aceptó el enlace. Probá con «Duplicar la pantalla».", 6000); detenerEnvio(true) }
            }
        }.start()
    }

    // ---- Comunes ----

    private fun reenviar() {
        if (destinoNombre == null) return
        pausarLocal()
        remotaPausada = false
        if (enCast) Cast.iniciar(this)?.sessionManager?.currentCastSession?.let { cargarEnCast(it) }
        else enviarDlnaActual()
        mostrarIndicador("En la tele: ${itemActual.nombre}", 1800)
    }

    /** El botón de pausa controla la tele mientras se envía. */
    private fun pausaRemota() {
        if (enCast) {
            val c = Cast.iniciar(this)?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
            c.togglePlayback()
            remotaPausada = !remotaPausada
        } else {
            val t = teleDlna ?: return
            val pausar = !remotaPausada
            remotaPausada = pausar
            Thread { if (pausar) Dlna.pausar(t) else Dlna.seguir(t) }.start()
        }
    }

    private fun detenerEnvio(verAca: Boolean) {
        var pos = -1L
        if (enCast) {
            enCast = false
            val sm = Cast.iniciar(this)?.sessionManager
            try { pos = sm?.currentCastSession?.remoteMediaClient?.approximateStreamPosition ?: -1L } catch (e: Throwable) { }
            try { sm?.endCurrentSession(true) } catch (e: Throwable) { }
        }
        teleDlna?.let { t -> Thread { Dlna.detener(t) }.start() }
        teleDlna = null; destinoNombre = null; ultimoEnviado = ""; remotaPausada = false
        mostrarEstadoTele()
        if (verAca) reanudarLocal(if (pos > 0) pos else -1L)
    }

    /** Para teles sin Android ni DLNA: la pantalla del celular se ve en la tele (Miracast, Smart View, Anyview Cast…). */
    private fun duplicarPantalla() {
        AlertDialog.Builder(this, temaDialogo)
            .setTitle("Duplicar la pantalla")
            .setMessage("1. En la tele, activá «Screen Mirroring», «Duplicar pantalla» o «Anyview Cast» (según la marca).\n\n" +
                "2. En la lista que se abre, elegí tu tele.\n\n" +
                "3. Volvé a JhaKet: lo que se ve en el celular se ve en la tele.\n\n" +
                "Si no se abre la lista, bajá la barra de notificaciones y tocá «Transmitir», «Smart View» o «Duplicar pantalla».")
            .setPositiveButton("Abrir") { _, _ -> abrirDuplicar() }
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { pantallaCompleta() }
            .show()
    }

    private fun abrirDuplicar() {
        val intentos = mutableListOf<Intent>()
        if (Build.MANUFACTURER.equals("samsung", true))
            intentos.add(Intent().setClassName("com.samsung.android.smartmirroring", "com.samsung.android.smartmirroring.CastingDialog"))
        intentos.add(Intent(Settings.ACTION_CAST_SETTINGS))
        intentos.add(Intent("android.settings.WIFI_DISPLAY_SETTINGS"))
        for (i in intentos) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Throwable) { }
        }
        mostrarAviso("Bajá la barra de notificaciones y tocá «Transmitir» o «Smart View»", 6000)
    }

    // ============================================================
    // Avisos, pantalla completa y ciclo de vida
    // ============================================================

    private val ocultarAvisoR = Runnable { aviso.visibility = View.GONE }
    private fun mostrarAviso(t: String, duracionMs: Long) {
        if (enPip) return
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

    override fun onPause() {
        super.onPause()
        // En la ventana flotante se sigue reproduciendo.
        val flotante = enPip || entrandoPip || (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)
        if (!flotante) { exo?.pause(); vlc?.pause() }
    }

    override fun onStart() { super.onStart(); detenido = false }
    override fun onStop() { super.onStop(); detenido = true }

    // ============================================================
    // Ventana flotante (Picture in Picture) y control en la barra
    // ============================================================

    private fun pipDisponible() = !esTv && Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Se está viendo en el celular (no en la tele) y no está en pausa. */
    private fun andandoLocal(): Boolean = destinoNombre == null && (exo?.playWhenReady ?: (vlc?.isPlaying ?: false))

    private fun actualizarControlMedia() {
        val c = control ?: return
        if (items.isEmpty()) return
        val it0 = itemActual
        val andando = if (destinoNombre != null) !remotaPausada else (exo?.playWhenReady ?: (vlc?.isPlaying ?: false))
        val ahora = System.currentTimeMillis()
        val detalle = when {
            destinoNombre != null -> "En la tele: $destinoNombre"
            it0.vivo -> it0.guia.firstOrNull { it.inicio <= ahora && it.fin > ahora }?.titulo ?: "En vivo · JhaKet IPTV"
            else -> "JhaKet IPTV"
        }
        c.mostrar(it0.nombre, detalle, logos[it0.logo], andando, posicion(), if (it0.vivo) 0L else duracion(), items.size > 1)
    }

    private fun accionPip(icono: Int, titulo: String, accion: String, codigo: Int): RemoteAction? {
        val c = control ?: return null
        if (Build.VERSION.SDK_INT < 26) return null
        return RemoteAction(Icon.createWithResource(this, icono), titulo, titulo, c.pi(accion, codigo))
    }

    private fun paramsPip(andando: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val (w, h) = tamanoVideo()
        var r = Rational(16, 9)
        if (w > 0 && h > 0) {
            val v = w.toFloat() / h
            r = when { v > 2.39f -> Rational(239, 100); v < 0.42f -> Rational(42, 100); else -> Rational(w, h) }
        }
        val acciones = ArrayList<RemoteAction>()
        if (items.size > 1) accionPip(R.drawable.ic_rep_anterior, "Anterior", "ant", 1)?.let { acciones.add(it) }
        (if (andando) accionPip(R.drawable.ic_rep_pausa, "Pausar", "pausa", 2) else accionPip(R.drawable.ic_rep_play, "Reproducir", "play", 3))?.let { acciones.add(it) }
        if (items.size > 1) accionPip(R.drawable.ic_rep_siguiente, "Siguiente", "sig", 4)?.let { acciones.add(it) }
        val b = PictureInPictureParams.Builder().setAspectRatio(r).setActions(acciones)
        // Android 12 en adelante: entra solo a la ventana flotante al ir al inicio.
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(andando && destinoNombre == null).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun actualizarPip() {
        if (!pipDisponible()) return
        val andando = if (enPip) (exo?.playWhenReady ?: (vlc?.isPlaying ?: false)) else andandoLocal()
        val (w, h) = tamanoVideo()
        val clave = "$andando|$w|$h|${items.size}|$destinoNombre"
        if (clave == clavePip) return
        clavePip = clave
        try { paramsPip(andando)?.let { setPictureInPictureParams(it) } } catch (e: Throwable) { }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!pipDisponible() || !andandoLocal() || bloqueado) return
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode) {
            entrandoPip = try { paramsPip(true)?.let { enterPictureInPictureMode(it) } ?: false } catch (e: Throwable) { false }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        enPip = isInPictureInPictureMode
        entrandoPip = false
        if (enPip) {
            ocultarControles()
            aviso.visibility = View.GONE; indicador.visibility = View.GONE; candado.visibility = View.GONE
            mostrarEstadoTele()
        } else {
            pantallaCompleta()
            mostrarEstadoTele()
            // Se cerró la ventana flotante con la X (la pantalla queda detenida): se termina.
            handler.postDelayed({ if (detenido && !enPip) finish() }, 600)
        }
    }
    override fun onResume() { super.onResume(); pantallaCompleta(); if (destinoNombre == null) { exo?.play(); vlc?.play() } }

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
        control?.liberar(); control = null
        if (abierto?.get() === this) abierto = null
        try { Cast.iniciar(this)?.sessionManager?.removeSessionManagerListener(oyenteCast, CastSession::class.java) } catch (e: Throwable) { }
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
