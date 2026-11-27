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

    private fun soap(t: Tele, accion: String, args: String): Boolean = soapTexto(t, accion, args) != null

    /** Respuesta de la tele (null si no respondió bien). */
    private fun soapTexto(t: Tele, accion: String, args: String): String? = try {
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
        val texto = try { (if (ok) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } } catch (e: Throwable) { null }
        if (ok) texto ?: "" else null
    } catch (e: Throwable) {
        null
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
    /** Estado de la tele: PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT (null si no responde). */
    fun estado(t: Tele): String? {
        val r = soapTexto(t, "GetTransportInfo", "") ?: return null
        return Regex("<CurrentTransportState>([^<]*)</CurrentTransportState>").find(r)?.groupValues?.get(1)?.trim()?.uppercase() ?: ""
    }

    /** [rele]: el enlace es el del puente del celular (video continuo, como una transmisión de TV). */
    fun reproducir(t: Tele, url: String, titulo: String, vivo: Boolean, rele: Boolean = false): Boolean {
        val m = if (rele) "video/mpeg" else mime(url, vivo)
        val extra = if (rele) "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000" else "*"
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>${esc(titulo)}</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$m:$extra\">${esc(url)}</res></item></DIDL-Lite>"
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


/**
 * Puente del celular para las teles Smart (DLNA): la tele le pide el video al celular y el
 * celular se lo entrega como una transmisión continua en MPEG-TS, el formato más simple, que
 * entienden hasta las teles viejas.
 *  - Canales HLS (.m3u8): las teles viejas reproducen las primeras partes (un minuto, más o
 *    menos), no piden las siguientes y se cierran. El puente va bajando las partes nuevas y
 *    se las pasa una detrás de otra, sin cortes (también las cifradas con AES-128).
 *  - Canales .ts y demás: se pasan tal cual; si el servidor corta, se reconecta solo.
 *  - Se presenta al servidor del canal como el reproductor de la app (y como VLC si lo rechaza),
 *    y entrega por http aunque el canal sea https (muchas teles viejas no abren https).
 */
object Rele {
    private class TeleCerrada : Exception()
    private class ErrorHttp(val codigo: Int) : java.io.IOException("HTTP $codigo")

    private const val UA_VLC = "VLC/3.0.20 LibVLC/3.0.20"
    private var servidor: java.net.ServerSocket? = null
    @Volatile private var origen = ""
    @Volatile private var agente = ""
    @Volatile private var generacion = 0
    private val pedido = java.util.concurrent.atomic.AtomicInteger(0)
    /** Última vez que se le mandó video a la tele. */
    @Volatile var ultimoDato = 0L
    private var candadoWifi: WifiManager.WifiLock? = null
    private var candadoCpu: android.os.PowerManager.WakeLock? = null

    private fun ipLocal(): String? {
        try {
            val lista = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (ni in lista.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList())
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) return a.hostAddress
            }
        } catch (e: Throwable) { }
        return null
    }

    /** Deja listo el puente para [url] y devuelve el enlace que hay que pasarle a la tele (null si no hay WiFi). */
    @Synchronized
    fun publicar(ctx: Context, url: String, ua: String): String? {
        val ip = ipLocal() ?: return null
        val s = servidor ?: try {
            java.net.ServerSocket(0).also { servidor = it; escuchar(it) }
        } catch (e: Throwable) { return null }
        origen = url; agente = ua; generacion++
        bloquear(ctx)
        return "http://$ip:${s.localPort}/jhaket/$generacion.ts"
    }

    @Synchronized
    fun detener() {
        generacion++; origen = ""
        try { servidor?.close() } catch (e: Throwable) { }
        servidor = null
        try { candadoWifi?.let { if (it.isHeld) it.release() } } catch (e: Throwable) { }
        try { candadoCpu?.let { if (it.isHeld) it.release() } } catch (e: Throwable) { }
    }

    /** Con la pantalla apagada, Android no corta el WiFi ni frena el puente. */
    @Suppress("DEPRECATION")
    private fun bloquear(ctx: Context) {
        try {
            if (candadoWifi == null) {
                val w = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                candadoWifi = w.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jhaket:puente").apply { setReferenceCounted(false) }
            }
            if (candadoCpu == null) {
                val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                candadoCpu = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jhaket:puente").apply { setReferenceCounted(false) }
            }
            candadoWifi?.let { if (!it.isHeld) it.acquire() }
            candadoCpu?.let { if (!it.isHeld) it.acquire(6 * 60 * 60 * 1000L) }
        } catch (e: Throwable) { }
    }

    private fun escuchar(s: java.net.ServerSocket) {
        Thread {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Throwable) { break }
                Thread { atender(c) }.start()
            }
        }.start()
    }

    private fun atender(c: java.net.Socket) {
        try {
            c.soTimeout = 15000
            val ent = java.io.BufferedReader(java.io.InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
            val linea = ent.readLine() ?: return
            while (true) { val l = ent.readLine() ?: break; if (l.isEmpty()) break }
            val partes = linea.split(" ")
            val metodo = partes.getOrNull(0) ?: ""
            val gen = Regex("/jhaket/(\\d+)\\.ts").find(partes.getOrNull(1) ?: "")?.groupValues?.get(1)?.toIntOrNull()
            val out = c.getOutputStream()
            if (gen == null || gen != generacion || origen.isEmpty()) {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush()
                return
            }
            out.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: video/mpeg\r\n" +
                "Connection: close\r\n" +
                "Accept-Ranges: none\r\n" +
                "Cache-Control: no-cache\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                "Server: Android UPnP/1.0 DLNADOC/1.50 JhaKet/1.0\r\n\r\n").toByteArray())
            out.flush()
            if (metodo.equals("HEAD", true)) return
            c.soTimeout = 0
            c.sendBufferSize = 256 * 1024
            // Un solo pedido a la vez: si la tele vuelve a pedir, el anterior se suelta
            // (muchos servidores de IPTV permiten una sola conexión por cuenta).
            val mio = pedido.incrementAndGet()
            val url = origen; val ua = agente
            transmitir(url, ua, out) { generacion == gen && pedido.get() == mio }
        } catch (e: Throwable) {
        } finally {
            try { c.close() } catch (e: Throwable) { }
        }
    }

    private fun escribir(out: java.io.OutputStream, b: ByteArray, desde: Int = 0, largo: Int = b.size) {
        try { out.write(b, desde, largo) } catch (e: java.io.IOException) { throw TeleCerrada() }
        ultimoDato = System.currentTimeMillis()
    }

    private fun abrir(url: String, ua: String): Pair<HttpURLConnection, URL> {
        var u = URL(url)
        for (i in 0 until 6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "*/*")
            val codigo = c.responseCode
            if (codigo in 300..399) {
                val loc = c.getHeaderField("Location"); c.disconnect()
                if (loc.isNullOrEmpty()) throw java.io.IOException("redirección sin destino")
                u = URL(u, loc); continue
            }
            if (codigo !in 200..299) { c.disconnect(); throw ErrorHttp(codigo) }
            return c to u
        }
        throw java.io.IOException("demasiadas redirecciones")
    }

    private fun leer(url: String, ua: String): Pair<ByteArray, URL> {
        val (c, u) = abrir(url, ua)
        try { return c.inputStream.use { it.readBytes() } to u } finally { c.disconnect() }
    }

    private fun transmitir(url: String, uaPrimero: String, out: java.io.OutputStream, sigue: () -> Boolean) {
        var ua = uaPrimero
        var fallas = 0
        val buf = ByteArray(64 * 1024)
        while (sigue()) {
            try {
                val (c, u) = abrir(url, ua)
                try {
                    val ins = c.inputStream
                    val n = ins.read(buf)
                    if (n <= 0) throw java.io.IOException("vacío")
                    val inicio = String(buf, 0, minOf(n, 32), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\r', '\n', '\t')
                    if (inicio.startsWith("#EXTM3U")) {
                        val texto = String(buf, 0, n, Charsets.UTF_8) + String(ins.readBytes(), Charsets.UTF_8)
                        c.disconnect()
                        if (hls(texto, url, u, ua, out, sigue)) return   // película o serie: terminó
                        fallas++
                    } else {
                        escribir(out, buf, 0, n)
                        while (sigue()) {
                            val k = ins.read(buf)
                            if (k < 0) break
                            escribir(out, buf, 0, k)
                            fallas = 0
                        }
                    }
                } finally {
                    try { c.disconnect() } catch (e: Throwable) { }
                }
            } catch (e: TeleCerrada) {
                return
            } catch (e: ErrorHttp) {
                if (e.codigo in 400..499 && ua != UA_VLC) { ua = UA_VLC; continue }
                fallas++
            } catch (e: Throwable) {
                fallas++
            }
            if (fallas > 10) return
            try { Thread.sleep(minOf(1000L * fallas, 5000L)) } catch (e: InterruptedException) { return }
        }
    }

    private data class Parte(val seq: Long, val uri: String, val metodo: String, val clave: String?, val iv: String?)

    private fun ivDe(hex: String?, seq: Long): ByteArray {
        val b = ByteArray(16)
        if (hex != null) {
            val h = hex.padStart(32, '0').takeLast(32)
            for (i in 0 until 16) b[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } else {
            var v = seq
            for (i in 15 downTo 8) { b[i] = (v and 0xFF).toByte(); v = v shr 8 }
        }
        return b
    }

    /** Devuelve true si era una película o serie y se mandó completa. */
    private fun hls(texto0: String, fuente0: String, base0: URL, ua: String, out: java.io.OutputStream, sigue: () -> Boolean): Boolean {
        var texto = texto0
        var fuente = fuente0
        var base = base0
        // Lista maestra: se elige la mejor calidad hasta 6 Mbps (lo que aguanta una tele HD por WiFi).
        if (texto.contains("#EXT-X-STREAM-INF")) {
            val lineas = texto.lines()
            val variantes = mutableListOf<Pair<Long, String>>()
            for (i in lineas.indices) {
                if (!lineas[i].startsWith("#EXT-X-STREAM-INF")) continue
                val bw = Regex("BANDWIDTH=(\\d+)").find(lineas[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val uri = lineas.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.trim() ?: continue
                variantes.add(bw to uri)
            }
            val elegida = variantes.filter { it.first in 1..6_000_000 }.maxByOrNull { it.first }
                ?: variantes.minByOrNull { it.first } ?: return false
            fuente = URL(base, elegida.second).toString()
            val (b, u) = leer(fuente, ua)
            texto = String(b, Charsets.UTF_8); base = u
        }
        var ultimo = -1L
        var fallas = 0
        val claves = HashMap<String, ByteArray>()
        while (sigue()) {
            val primera = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(texto)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val objetivo = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(texto)?.groupValues?.get(1)?.toLongOrNull() ?: 6L
            val fin = texto.contains("#EXT-X-ENDLIST")
            val partes = mutableListOf<Parte>()
            var metodo = "NONE"; var clave: String? = null; var iv: String? = null; var n = primera
            for (l0 in texto.lines()) {
                val l = l0.trim()
                if (l.startsWith("#EXT-X-KEY")) {
                    metodo = Regex("METHOD=([A-Z0-9-]+)").find(l)?.groupValues?.get(1) ?: "NONE"
                    clave = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                    iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(l)?.groupValues?.get(1)
                } else if (l.isNotEmpty() && !l.startsWith("#")) {
                    partes.add(Parte(n, l, metodo, clave, iv)); n++
                }
            }
            // En vivo se arranca cerca del momento actual (las últimas 3 partes).
            if (ultimo < 0 && !fin) ultimo = primera + partes.size - 4
            // El servidor reinició la numeración.
            if (partes.isNotEmpty() && partes.last().seq < ultimo - 20) ultimo = partes.first().seq - 1
            for (p in partes) {
                if (!sigue()) return false
                if (p.seq <= ultimo) continue
                try {
                    val u = URL(base, p.uri).toString()
                    when {
                        p.metodo == "AES-128" && p.clave != null -> {
                            val k = claves.getOrPut(p.clave) { leer(URL(base, p.clave).toString(), ua).first }
                            val datos = leer(u, ua).first
                            val ci = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                            ci.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(k, "AES"), javax.crypto.spec.IvParameterSpec(ivDe(p.iv, p.seq)))
                            escribir(out, ci.doFinal(datos))
                        }
                        p.metodo == "NONE" -> {
                            val (c, _) = abrir(u, ua)
                            try {
                                val ins = c.inputStream
                                val buf = ByteArray(64 * 1024)
                                while (true) { val k = ins.read(buf); if (k < 0) break; escribir(out, buf, 0, k) }
                            } finally { try { c.disconnect() } catch (e: Throwable) { } }
                        }
                        else -> { }   // cifrados que no se pueden abrir: se saltean
                    }
                    fallas = 0
                } catch (e: TeleCerrada) {
                    throw e
                } catch (e: Throwable) {
                    fallas++
                }
                ultimo = p.seq
            }
            if (fin) return true
            try { Thread.sleep(maxOf(1000L, objetivo * 1000L / 2)) } catch (e: InterruptedException) { return false }
            try {
                val (b, u) = leer(fuente, ua)
                texto = String(b, Charsets.UTF_8); base = u
            } catch (e: Throwable) {
                fallas++
                if (fallas > 10) return false
                try { Thread.sleep(2000) } catch (x: InterruptedException) { return false }
            }
        }
        return false
    }
}
