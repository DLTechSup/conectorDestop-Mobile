package com.dltechsup.conector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Mantém a conexão com o PC viva em segundo plano (serviço em primeiro plano).
 * A sessão só termina quando o usuário toca em "Sair e desconectar".
 */
class RemoteService : Service() {

    enum class State { IDLE, CONNECTING, CONNECTED, RECONNECTING }

    interface Listener {
        fun onState(state: State, text: String) {}
        fun onFrame(bmp: Bitmap, vx: Float, vy: Float, vw: Float, vh: Float, cx: Float, cy: Float) {}
        fun onInfo(pcName: String, monitors: Int, control: Boolean) {}
    }

    companion object {
        const val ACTION_CONNECT = "com.dltechsup.conector.CONNECT"
        const val ACTION_DISCONNECT = "com.dltechsup.conector.DISCONNECT"
        private const val CH_SESSION = "session"
        private const val CH_PC = "pc_notifs"
        private const val NOTIF_ID = 1
        val QUALITY = listOf(Triple(960, 12, 40), Triple(1280, 15, 55), Triple(1920, 20, 70))
        val QUALITY_NAMES = listOf("Baixa", "Média", "Alta")
        /** Áudio por qualidade: (taxa, canais) — baixa usa menos banda. */
        val AUDIO = listOf(16000 to 1, 32000 to 2, 48000 to 2)

        val listeners = CopyOnWriteArrayList<Listener>()
        @Volatile var instance: RemoteService? = null
        @Volatile var state = State.IDLE
        @Volatile var stateText = "Desconectado"
        @Volatile var pcName = ""
        @Volatile var monitors = 1
        @Volatile var control = true
        @Volatile var audioSupported = true
        /** Zoom: região visível da tela do PC (x, y, w, h) normalizada. O PC recorta em alta resolução. */
        @Volatile var viewport = floatArrayOf(0f, 0f, 1f, 1f)

        fun start(ctx: Context) {
            val i = Intent(ctx, RemoteService::class.java).setAction(ACTION_CONNECT)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun disconnect(ctx: Context) {
            val svc = instance
            if (svc != null) svc.userDisconnect() else Prefs(ctx).sessionActive = false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var nm: NotificationManager
    private var ws: WebSocket? = null
    private var generation = 0
    private var attempt = 0
    private var hostIndex = 0
    private var videoWanted = false
    private var seenFingerprint = ""
    private var certMismatch = false
    private var currentHost = ""
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var pcNotifId = 100

    private val audio = PcAudioPlayer()
    private val latest = AtomicReference<Frame?>()
    private val posted = AtomicBoolean(false)

    // ---------------------------------------------------------------- ciclo de vida
    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildOngoing(stateText),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (intent?.action == ACTION_DISCONNECT) {
            userDisconnect()
            return START_NOT_STICKY
        }
        if (!prefs.sessionActive && intent?.action != ACTION_CONNECT) {
            shutdown()
            return START_NOT_STICKY
        }
        prefs.sessionActive = true
        acquireLocks()
        registerNetworkCallback()
        if (ws == null) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        audio.stop()
        generation++
        ws?.cancel()
        releaseLocks()
        unregisterNetworkCallback()
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- API para as telas
    fun setVideo(on: Boolean) {
        videoWanted = on
        sendVideo()
    }

    /** Pede (ou para) o áudio do PC conforme as preferências e se a tela está visível. */
    fun sendAudio() {
        val want = prefs.audioOn && audioSupported && (videoWanted || prefs.audioBg)
        if (want) audio.start() else audio.stop()
        val (rate, ch) = AUDIO[prefs.quality.coerceIn(0, AUDIO.size - 1)]
        send(JSONObject().put("t", "audio").put("on", want).put("rate", rate).put("ch", ch))
    }

    fun sendVideo() {
        val (w, fps, q) = QUALITY[prefs.quality.coerceIn(0, QUALITY.size - 1)]
        send(JSONObject().put("t", "video").put("on", videoWanted).put("maxw", w)
            .put("fps", fps).put("q", q).put("monitor", prefs.monitor)
            .put("vx", viewport[0].toDouble()).put("vy", viewport[1].toDouble())
            .put("vw", viewport[2].toDouble()).put("vh", viewport[3].toDouble()))
        sendAudio()
    }

    fun send(o: JSONObject) {
        if (state == State.CONNECTED) ws?.send(o.toString())
    }

    /** "Sair e desconectar": único caminho que encerra a sessão. */
    fun userDisconnect() {
        prefs.sessionActive = false
        generation++
        ws?.let {
            it.send("""{"t":"bye"}""")
            it.close(1000, "bye")
        }
        ws = null
        setState(State.IDLE, "Desconectado")
        shutdown()
    }

    private fun shutdown() {
        audio.stop()
        releaseLocks()
        unregisterNetworkCallback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- conexão
    private fun connect() {
        val hosts = prefs.hostList()
        if (hosts.isEmpty()) {
            fail("Endereço do PC não configurado")
            return
        }
        val gen = ++generation
        ws?.cancel()
        currentHost = hosts[hostIndex % hosts.size]
        certMismatch = false
        seenFingerprint = ""
        setState(
            if (attempt == 0) State.CONNECTING else State.RECONNECTING,
            (if (attempt == 0) "Conectando a " else "Reconectando a ") + currentHost + "…"
        )
        val expected = prefs.fingerprint
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (expected.isNotEmpty() && !fp.equals(expected, ignoreCase = true)) {
                    certMismatch = true
                    throw CertificateException("Certificado do PC não confere com o pareamento")
                }
                seenFingerprint = fp
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // autenticidade garantida pelo pin do certificado
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        val host = if (currentHost.contains(":")) "[$currentHost]" else currentHost
        val req = Request.Builder().url("https://$host:${prefs.port}/").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                webSocket.send(
                    JSONObject().put("t", "auth").put("key", prefs.key)
                        .put("name", Build.MODEL ?: "Android").toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen == generation) onBinary(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) main.post { lost("Conexão encerrada pelo PC") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) main.post { lost(t.message ?: "falha de rede") }
            }
        })
    }

    private fun lost(reason: String) {
        if (!prefs.sessionActive) return
        ws = null
        if (certMismatch) {
            prefs.sessionActive = false
            setState(State.IDLE, "O certificado do PC mudou. Pareie novamente lendo o QR.")
            shutdown()
            return
        }
        audio.stop()
        attempt++
        hostIndex++
        val delay = minOf(1000L * (1 shl minOf(attempt, 4)), 15000L)
        setState(State.RECONNECTING, "Reconectando… ($reason)")
        val gen = generation
        main.postDelayed({ if (gen == generation && prefs.sessionActive && ws == null) connect() }, delay)
    }

    private fun fail(text: String) {
        prefs.sessionActive = false
        setState(State.IDLE, text)
        shutdown()
    }

    private fun onText(text: String) {
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        when (j.optString("t")) {
            "auth_ok" -> main.post {
                attempt = 0
                if (prefs.fingerprint.isEmpty() && seenFingerprint.isNotEmpty()) prefs.fingerprint = seenFingerprint
                pcName = j.optString("host", "PC")
                prefs.pcName = pcName
                monitors = j.optJSONArray("monitors")?.length() ?: 1
                control = j.optBoolean("control", true)
                audioSupported = j.optBoolean("audio", false)
                setState(State.CONNECTED, "Conectado a $pcName")
                listeners.forEach { it.onInfo(pcName, monitors, control) }
                sendVideo()
            }
            "auth_fail" -> main.post { fail("Chave de acesso inválida. Confira a chave no PC.") }
            "notif" -> main.post { onPcNotification(j) }
        }
    }

    /** Pacotes: 0x02 = quadro de vídeo; 0x03 = áudio (taxa uint32, canais uint8, PCM 16 bits). */
    private fun onBinary(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt()) {
            2 -> onVideo(data)
            3 -> if (data.size > 6) {
                val bb = java.nio.ByteBuffer.wrap(data, 1, 5)
                val rate = bb.int
                val ch = bb.get().toInt()
                audio.push(rate, ch, data.copyOfRange(6, data.size))
            }
        }
    }

    /** Quadro: 0x02 + região (4 floats) + cursor (2 floats, -1 = desconhecido) + JPEG. */
    private fun onVideo(data: ByteArray) {
        if (data.size < 26) return
        val bb = java.nio.ByteBuffer.wrap(data, 1, 24)
        val info = FloatArray(6) { bb.float }
        val bmp = BitmapFactory.decodeByteArray(data, 25, data.size - 25) ?: return
        latest.set(Frame(bmp, info))
        if (posted.compareAndSet(false, true)) {
            main.post {
                posted.set(false)
                latest.getAndSet(null)?.let { f ->
                    listeners.forEach { it.onFrame(f.bmp, f.r[0], f.r[1], f.r[2], f.r[3], f.r[4], f.r[5]) }
                }
            }
        }
    }

    private class Frame(val bmp: Bitmap, val r: FloatArray)

    private fun setState(s: State, text: String) {
        state = s
        stateText = text
        main.post {
            if (s != State.IDLE) nm.notify(NOTIF_ID, buildOngoing(text))
            listeners.forEach { it.onState(s, text) }
        }
    }

    // ---------------------------------------------------------------- notificações do PC
    private fun onPcNotification(j: JSONObject) {
        val app = j.optString("app")
        val title = j.optString("title")
        val body = j.optString("body")
        val blocked = prefs.blockedList()
        if (blocked.any { app.lowercase().contains(it) }) return
        SoundPlayer.play(this, prefs)
        val n = NotificationCompat.Builder(this, CH_PC)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("$app · PC")
            .setContentText(if (body.isEmpty()) title else "$title: $body")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (body.isEmpty()) title else "$title\n$body"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        pcNotifId = if (pcNotifId >= 900) 100 else pcNotifId + 1
        nm.notify(pcNotifId, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(
            NotificationChannel(CH_SESSION, "Sessão remota", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Mantém a conexão com o PC ativa" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PC, "Notificações do PC", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notificações recebidas do seu PC (o som é tocado pelo app)"
                setSound(null, null)
                enableVibration(true)
            }
        )
    }

    private fun openAppIntent(): PendingIntent {
        val target = if (state == State.CONNECTED) ViewerActivity::class.java else MainActivity::class.java
        return PendingIntent.getActivity(
            this, 0, Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildOngoing(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_SESSION)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("DeskLink")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Sair e desconectar", stop)
            .build()
    }

    // ---------------------------------------------------------------- locks / rede
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "conector:session").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (wifi == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "conector:wifi").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wake = null
        wifi = null
    }

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // rede voltou/trocou (ex.: Wi-Fi <-> dados): reconecta na hora
                main.post {
                    if (prefs.sessionActive && state == State.RECONNECTING) {
                        attempt = 0
                        connect()
                    }
                }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        netCallback = null
    }
}
