package com.jhaket.iptv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Notificaciones del administrador.
 *
 * El administrador escribe el aviso en JhaKet y queda guardado en el servidor. Esta parte
 * de la app lo busca cada 15 minutos, aunque la app esté cerrada, y lo muestra como
 * notificación del teléfono. Para preguntar necesita la sesión del usuario, que la página
 * le pasa por el puente (AndroidApp.setSession).
 */
object Avisos {
    private const val PREFS = "jhaket"
    private const val CANAL = "avisos"
    private const val URL_AVISOS = "https://jhaketiptv.floot.app/_api/ext/notifications"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ahoraIso(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    /** La página avisa quién inició sesión (o "" si cerró la sesión). */
    fun guardarSesion(ctx: Context, token: String) {
        val p = prefs(ctx)
        val ed = p.edit().putString("token", token)
        // La primera vez se arranca desde ahora: no se muestran avisos viejos de golpe.
        if (p.getString("since", null) == null) ed.putString("since", ahoraIso())
        ed.apply()
        if (token.isNotEmpty()) revisarYa(ctx)
    }

    /** Revisión cada 15 minutos (lo mínimo que permite Android), sólo con internet. */
    fun programar(ctx: Context) {
        val conInternet = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodico = PeriodicWorkRequestBuilder<RevisarAvisos>(15, TimeUnit.MINUTES)
            .setConstraints(conInternet)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("avisos", ExistingPeriodicWorkPolicy.KEEP, periodico)
    }

    /** Una revisión ahora mismo (al abrir la app). */
    fun revisarYa(ctx: Context) {
        val conInternet = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val una = OneTimeWorkRequestBuilder<RevisarAvisos>().setConstraints(conInternet).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("avisos-ya", ExistingWorkPolicy.REPLACE, una)
    }

    fun crearCanal(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val canal = NotificationChannel(CANAL, "Avisos de JhaKet", NotificationManager.IMPORTANCE_HIGH)
            canal.description = "Estrenos, novedades y avisos del administrador"
            nm.createNotificationChannel(canal)
        }
    }

    @Suppress("DEPRECATION")
    fun mostrar(ctx: Context, id: Int, titulo: String, texto: String) {
        crearCanal(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) return

        val abrir = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(ctx, id, abrir, flags)

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(ctx, CANAL)
        else Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        b.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titulo)
            .setAutoCancel(true)
            .setContentIntent(pi)
        if (texto.isNotEmpty()) b.setContentText(texto).setStyle(Notification.BigTextStyle().bigText(texto))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) b.setColor(0xFFFF5A45.toInt())
        nm.notify(id, b.build())
    }

    /** Pregunta al servidor si hay avisos nuevos y los muestra. */
    fun revisar(ctx: Context): Boolean {
        val p = prefs(ctx)
        val token = p.getString("token", "") ?: ""
        if (token.isEmpty()) return true
        val desde = p.getString("since", null) ?: ahoraIso().also { p.edit().putString("since", it).apply() }

        val url = URL(URL_AVISOS + "?t=" + URLEncoder.encode(token, "UTF-8") + "&since=" + URLEncoder.encode(desde, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        try {
            // 401/403: sesión vencida o dispositivo sin aprobar. No hay nada que mostrar.
            if (c.responseCode != 200) return true
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val items = j.optJSONArray("items") ?: return true
            var ultimo = desde
            for (i in 0 until items.length()) {
                val n = items.getJSONObject(i)
                mostrar(ctx, n.optString("id").hashCode(), n.optString("title", "JhaKet"), n.optString("body", ""))
                ultimo = n.optString("at", ultimo)
            }
            p.edit().putString("since", ultimo).apply()
            return true
        } finally {
            c.disconnect()
        }
    }
}

class RevisarAvisos(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        Avisos.revisar(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
