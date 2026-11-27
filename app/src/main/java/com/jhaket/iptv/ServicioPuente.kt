package com.jhaket.iptv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Mientras el celular le pasa el video a una Smart TV (el puente), este servicio le avisa a
 * Android que JhaKet está transmitiendo. Sin él, al salir de la app o bloquear el teléfono,
 * Android (sobre todo en los Samsung) congela la app para ahorrar batería y la tele se corta.
 * Muestra una notificación fija "Enviando a la tele"; tocarla vuelve al reproductor.
 */
class ServicioPuente : Service() {

    companion object {
        private const val ID_AVISO = 4720

        fun iniciar(ctx: Context, tele: String, titulo: String) {
            val i = Intent(ctx, ServicioPuente::class.java).putExtra("tele", tele).putExtra("titulo", titulo)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Throwable) { }
        }

        fun detener(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ServicioPuente::class.java)) } catch (e: Throwable) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tele = intent?.getStringExtra("tele") ?: "la tele"
        val titulo = intent?.getStringExtra("titulo") ?: ""
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("puente", "Enviar a la tele", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo mientras JhaKet le pasa el video a la tele"
                setShowBadge(false)
            })
        }
        val abrir = PendingIntent.getActivity(this, 4720, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "puente") else Notification.Builder(this)
        val aviso = b.setSmallIcon(R.drawable.ic_rep_enviar)
            .setContentTitle("Enviando a $tele")
            .setContentText(if (titulo.isNotEmpty()) "$titulo · podés bloquear el teléfono" else "Podés bloquear el teléfono")
            .setContentIntent(abrir)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_AVISO, aviso, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID_AVISO, aviso)
        } catch (e: Throwable) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }
}
