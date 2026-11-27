package com.jhaket.iptv

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build

/**
 * Control de la reproducción en la barra de notificaciones y en el panel rápido de Android
 * (el mismo cuadro que muestra YouTube): título, logo, anterior / pausa / siguiente y cerrar.
 * Lo usan el reproductor nativo y el reproductor web (código iframe).
 */
class ControlMedia(
    private val act: Activity,
    private val idAviso: Int,
    private val acciones: Acciones,
) {
    interface Acciones {
        fun reproducir()
        fun pausar()
        fun anterior() {}
        fun siguiente() {}
        fun cerrar()
        /** Tocar el aviso: volver a la pantalla del reproductor. */
        fun abrir() {}
    }

    private val nm = act.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val sesion = MediaSession(act, "JhaKet-$idAviso")
    private val prefijo = "${act.packageName}.media$idAviso."
    private var registrado = false
    private var ultimaClave = ""
    private var ultimoEstado = ""
    private var visible = false

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action?.removePrefix(prefijo)) {
                "play" -> acciones.reproducir()
                "pausa" -> acciones.pausar()
                "ant" -> acciones.anterior()
                "sig" -> acciones.siguiente()
                "cerrar" -> acciones.cerrar()
                "abrir" -> {
                    // Con la ventana flotante: se agranda de nuevo.
                    try { (act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).moveTaskToFront(act.taskId, 0) } catch (e: Throwable) { }
                    acciones.abrir()
                }
            }
        }
    }

    init {
        sesion.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = acciones.reproducir()
            override fun onPause() = acciones.pausar()
            override fun onSkipToNext() = acciones.siguiente()
            override fun onSkipToPrevious() = acciones.anterior()
            override fun onStop() = acciones.cerrar()
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("reproduccion", "Reproducción", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controles de lo que se está viendo"
                setShowBadge(false)
            })
        }
        val f = IntentFilter()
        for (a in listOf("play", "pausa", "ant", "sig", "cerrar", "abrir")) f.addAction(prefijo + a)
        try {
            if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(receptor, f, Context.RECEIVER_NOT_EXPORTED)
            else act.registerReceiver(receptor, f)
            registrado = true
        } catch (e: Throwable) { }
    }

    fun pi(accion: String, codigo: Int): PendingIntent =
        PendingIntent.getBroadcast(act, idAviso * 10 + codigo, Intent(prefijo + accion).setPackage(act.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Suppress("DEPRECATION")
    private fun accion(icono: Int, titulo: String, accion: String, codigo: Int) =
        Notification.Action.Builder(icono, titulo, pi(accion, codigo)).build()

    /**
     * Muestra o actualiza el control. [duracionMs] 0 si es en vivo.
     * [abrir] PendingIntent propio para tocar el aviso (si es null, se usa el de volver a la pantalla).
     */
    fun mostrar(
        titulo: String, detalle: String, imagen: Bitmap?, andando: Boolean,
        posicionMs: Long, duracionMs: Long, conAnteriorSiguiente: Boolean, abrir: PendingIntent? = null,
    ) {
        visible = true
        var acc = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (conAnteriorSiguiente) acc = acc or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val estado = "$andando|$conAnteriorSiguiente|${posicionMs / 5000}"
        if (estado != ultimoEstado) {
            ultimoEstado = estado
            sesion.setPlaybackState(PlaybackState.Builder()
                .setActions(acc)
                .setState(if (andando) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    if (duracionMs > 0) posicionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (andando) 1f else 0f)
                .build())
        }
        val clave = "$titulo|$detalle|${imagen?.hashCode()}|$andando|$conAnteriorSiguiente|$duracionMs"
        if (clave == ultimaClave) return
        ultimaClave = clave
        sesion.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, detalle)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (duracionMs > 0) duracionMs else -1L)
            .apply { if (imagen != null) putBitmap(MediaMetadata.METADATA_KEY_ART, imagen) }
            .build())
        val tocar = abrir ?: pi("abrir", 6)
        sesion.setSessionActivity(tocar)
        sesion.isActive = true

        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(act, "reproduccion") else Notification.Builder(act)
        b.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titulo)
            .setContentText(detalle)
            .setContentIntent(tocar)
            .setDeleteIntent(pi("cerrar", 7))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(andando)
            .setShowWhen(false)
        if (imagen != null) b.setLargeIcon(imagen)
        val compactas = mutableListOf<Int>()
        if (conAnteriorSiguiente) { b.addAction(accion(R.drawable.ic_rep_anterior, "Anterior", "ant", 1)); compactas.add(0) }
        b.addAction(if (andando) accion(R.drawable.ic_rep_pausa, "Pausar", "pausa", 2) else accion(R.drawable.ic_rep_play, "Reproducir", "play", 3))
        compactas.add(compactas.size)
        if (conAnteriorSiguiente) { b.addAction(accion(R.drawable.ic_rep_siguiente, "Siguiente", "sig", 4)); compactas.add(compactas.size) }
        b.addAction(accion(R.drawable.ic_rep_cerrar, "Cerrar", "cerrar", 5))
        b.setStyle(Notification.MediaStyle().setMediaSession(sesion.sessionToken).setShowActionsInCompactView(*compactas.toIntArray()))
        try { nm.notify(idAviso, b.build()) } catch (e: Throwable) { }
    }

    fun ocultar() {
        if (!visible) return
        visible = false
        ultimaClave = ""; ultimoEstado = ""
        try { nm.cancel(idAviso) } catch (e: Throwable) { }
        sesion.isActive = false
    }

    fun liberar() {
        ocultar()
        try { sesion.release() } catch (e: Throwable) { }
        if (registrado) { try { act.unregisterReceiver(receptor) } catch (e: Throwable) { }; registrado = false }
    }
}
