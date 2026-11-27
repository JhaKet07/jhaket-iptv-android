package com.jhaket.iptv

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/*
 * "Enviar a la tele" desde el reproductor del celular:
 *  - Chromecast, Android TV y Google TV: con Google Cast (la tele baja el enlace sola).
 *  - Teles Smart sin Android (Samsung, LG, Hisense, Philips…): con DLNA, el mismo sistema
 *    que usan para abrir videos de la red de la casa.
 *  - Cualquier otra: duplicar la pantalla del celular (eso lo hace Android, ver PlayerActivity).
 */

/** Configuración de Google Cast: se usa el receptor estándar de Google (no hace falta uno propio). */
class OpcionesCast : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val aviso = NotificationOptions.Builder()
            .setTargetActivityClassName(MainActivity::class.java.name)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(aviso).build())
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object Cast {
    private var contexto: CastContext? = null

    val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }

    /** null si el teléfono no tiene los servicios de Google (ahí queda DLNA y duplicar pantalla). */
    @Suppress("DEPRECATION")
    fun iniciar(ctx: Context): CastContext? {
        contexto?.let { return it }
        return try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) != ConnectionResult.SUCCESS) null
            else CastContext.getSharedInstance(ctx.applicationContext).also { contexto = it }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Enlace y tipo para el receptor de Google. Los canales en vivo .ts de servidores Xtream
     * se piden como .m3u8 (el receptor no abre .ts sueltos y esos servidores dan las dos).
     */
    fun medio(url: String, vivo: Boolean): Pair<String, String> {
        val ruta = (Uri.parse(url).path ?: url).lowercase()
        return when {
            ruta.endsWith(".m3u8") || ruta.endsWith(".m3u") || url.contains("type=m3u8") -> url to "application/x-mpegURL"
            ruta.endsWith(".mpd") -> url to "application/dash+xml"
            ruta.endsWith(".mp4") || ruta.endsWith(".m4v") -> url to "video/mp4"
            ruta.endsWith(".mkv") -> url to "video/x-matroska"
            ruta.endsWith(".webm") -> url to "video/webm"
            vivo && ruta.endsWith(".ts") && ruta.contains("/live/") -> url.replaceFirst(Regex("\\.ts(?=$|\\?)", RegexOption.IGNORE_CASE), ".m3u8") to "application/x-mpegURL"
            vivo -> url to "application/x-mpegURL"
            else -> url to "video/mp4"
        }
    }
}

object Dlna {
    data class Tele(val nombre: String, val control: String, val servicio: String)

    /** Busca en el WiFi las teles que aceptan videos por DLNA. Tarda unos segundos: llamar fuera del hilo principal. */
    fun buscar(ctx: Context, esperaMs: Int = 3500): List<Tele> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val candado = try {
            wifi?.createMulticastLock("jhaket-dlna")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Throwable) { null }
        val ubicaciones = LinkedHashSet<String>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 400
                val grupo = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:service:AVTransport:1", "urn:schemas-upnp-org:device:MediaRenderer:1")) {
                    val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n").toByteArray()
                    repeat(2) { s.send(DatagramPacket(msg, msg.size, grupo, 1900)) }
                }
                val fin = System.currentTimeMillis() + esperaMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < fin) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val t = String(p.data, 0, p.length)
                        Regex("(?im)^location:\\s*(\\S+)").find(t)?.groupValues?.get(1)?.let { ubicaciones.add(it.trim()) }
                    } catch (e: SocketTimeoutException) { }
                }
            }
        } catch (e: Throwable) {
        } finally {
            try { candado?.release() } catch (e: Throwable) { }
        }
        val teles = mutableListOf<Tele>()
        val vistas = HashSet<String>()
        for (u in ubicaciones) {
            val t = describir(u) ?: continue
            if (vistas.add(t.control)) teles.add(t)
        }
        return teles
    }

    private fun describir(ubicacion: String): Tele? = try {
        val c = URL(ubicacion).openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2500
        val xml = c.inputStream.use { it.readBytes() }
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (e: Throwable) { }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        fun texto(e: Element, tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim() ?: ""
        val r = doc.documentElement
        val nombre = texto(r, "friendlyName").ifEmpty { "Tele" }
        val base = texto(r, "URLBase").ifEmpty { ubicacion }
        val servicios = r.getElementsByTagName("service")
        var tele: Tele? = null
        for (i in 0 until servicios.length) {
            val s = servicios.item(i) as? Element ?: continue
            val tipo = texto(s, "serviceType")
            val ctrl = texto(s, "controlURL")
            if (tipo.contains("AVTransport") && ctrl.isNotEmpty()) {
                tele = Tele(nombre, URL(URL(base), ctrl).toString(), tipo)
                break
            }
        }
        tele
    } catch (e: Throwable) {
        null
    }

    private fun soap(t: Tele, accion: String, args: String): Boolean = try {
        val cuerpo = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$accion xmlns:u=\"${t.servicio}\"><InstanceID>0</InstanceID>$args</u:$accion></s:Body></s:Envelope>"
        val c = URL(t.control).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 3000; c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPAction", "\"${t.servicio}#$accion\"")
        c.outputStream.use { it.write(cuerpo.toByteArray()) }
        val ok = c.responseCode in 200..299
        try { (if (ok) c.inputStream else c.errorStream)?.close() } catch (e: Throwable) { }
        ok
    } catch (e: Throwable) {
        false
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun mime(url: String, vivo: Boolean): String {
        val ruta = (Uri.parse(url).path ?: url).lowercase()
        return when {
            ruta.endsWith(".m3u8") || ruta.endsWith(".m3u") -> "application/vnd.apple.mpegurl"
            ruta.endsWith(".mp4") || ruta.endsWith(".m4v") -> "video/mp4"
            ruta.endsWith(".mkv") -> "video/x-matroska"
            ruta.endsWith(".avi") -> "video/x-msvideo"
            ruta.endsWith(".ts") || vivo -> "video/mpeg"
            else -> "video/mp4"
        }
    }

    /** Le pasa el enlace a la tele y le da Play. Llamar fuera del hilo principal. */
    fun reproducir(t: Tele, url: String, titulo: String, vivo: Boolean): Boolean {
        val m = mime(url, vivo)
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>${esc(titulo)}</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$m:*\">${esc(url)}</res></item></DIDL-Lite>"
        soap(t, "Stop", "")
        var ok = soap(t, "SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
        // Algunas teles rechazan la descripción: se manda sólo el enlace.
        if (!ok) ok = soap(t, "SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        if (!ok) return false
        if (!soap(t, "Play", "<Speed>1</Speed>")) {
            Thread.sleep(900)
            soap(t, "Play", "<Speed>1</Speed>")
        }
        return true
    }

    fun pausar(t: Tele) = soap(t, "Pause", "")
    fun seguir(t: Tele) = soap(t, "Play", "<Speed>1</Speed>")
    fun detener(t: Tele) = soap(t, "Stop", "")
}
