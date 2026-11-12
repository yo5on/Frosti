package com.frosti.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.util.Base64

/**
 * Keeps the link to FROSTI Core alive: streams the mic (16 kHz PCM), plays the Core's speech,
 * runs phone tools, and relays events to the UI. Foreground so Samsung doesn't kill it.
 */
class FrostiService : Service() {

    companion object {
        var instance: FrostiService? = null
        var ui: ((JSONObject) -> Unit)? = null
        const val PREFS = "frosti"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, FrostiService::class.java))
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile var connected = false; private set
    @Volatile private var running = true
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private lateinit var actions: Actions
    private var tts: TextToSpeech? = null  // Satellite mode's voice: the phone's own offline TTS
    private lateinit var cpuLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    @Volatile var lastState = "offline"; private set
    @Volatile private var streaming = false
    private var failures = 0
    private var lastReply = 0L
    private var wifiNetwork: Network? = null
    private var wifiAddresses = ""
    private val retry = Runnable { connect() }
    private val networks by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { wifiNetwork = network; reconnect() } }
        override fun onLost(network: Network) { main.post {
            if (wifiNetwork == network) { wifiNetwork = null; wifiAddresses = ""; reconnect() }
        } }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { main.post {
            val addresses = properties.linkAddresses.toString()
            if (wifiNetwork == network && addresses != wifiAddresses) { wifiAddresses = addresses; reconnect() }
        } }
    }
    @Volatile var micLevel = 0f; private set          // 0..1 RMS of the last 100 ms of mic audio
    @Volatile private var speechEnv = FloatArray(0)   // 0..1 RMS per 40 ms of the sentence being played
    @Volatile private var speechAt = 0L

    /** What the Core should pulse to: the speech being played, else the mic. */
    fun level(): Float {
        val i = ((android.os.SystemClock.uptimeMillis() - speechAt) / 40).toInt()
        val env = speechEnv
        return if (i in env.indices) env[i] else micLevel
    }

    private fun rms(a: ByteArray, off: Int, len: Int): Float {
        var sum = 0.0; var n = 0; var i = off
        while (i + 1 < off + len) { val v = ((a[i + 1].toInt() shl 8) or (a[i].toInt() and 0xff)).toShort().toDouble(); sum += v * v; n++; i += 2 }
        return if (n == 0) 0f else (Math.sqrt(sum / n) / 4000.0).toFloat().coerceIn(0f, 1f)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        actions = Actions(this)
        tts = TextToSpeech(this) { if (it != TextToSpeech.SUCCESS) android.util.Log.w("FrostiVoice", "System TTS unavailable") }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("core", "FROSTI link", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, "core").setContentTitle("FROSTI")
            .setContentText("Listening for \"Frosti\"").setSmallIcon(android.R.drawable.ic_btn_speak_now).build())
        // On-device wake-word detection must keep working with the screen off; release with the service.
        cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "frosti:voice").apply { acquire() }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL, "frosti:voice").apply { acquire() }
        networks.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
        connect()
        Thread(::micLoop, "mic").start()
        Thread(::playLoop, "play").start()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        networks.unregisterNetworkCallback(networkCallback)
        if (::cpuLock.isInitialized && cpuLock.isHeld) cpuLock.release()
        if (::wifiLock.isInitialized && wifiLock.isHeld) wifiLock.release()
        ws?.close(1000, "bye")
        tts?.shutdown()
        instance = null
        super.onDestroy()
    }

    private fun emit(m: JSONObject) = main.post { ui?.invoke(m) }

    fun send(m: JSONObject): Boolean {
        when (m.optString("type")) {
            "ptt" -> streaming = m.optBoolean("on")
            "listen" -> streaming = true
        }
        return ws?.send(m.toString()) ?: false
    }

    fun reconnect() { main.post {
        if (!running) return@post
        val previous = ws
        ws = null
        previous?.cancel()
        connected = false; streaming = false; lastState = "offline"
        emit(JSONObject().put("type", "conn").put("ok", false))
        main.removeCallbacks(retry)
        main.postDelayed(retry, 500)
    } }

    private fun connect() {
        if (!running) return
        main.removeCallbacks(retry)
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val cert = p.getString("tls_cert", "") ?: ""
        val host = p.getString("host", "127.0.0.1")
        if (cert.isEmpty() && host != "127.0.0.1") {
            lost("Wireless pairing certificate missing")
            return
        }
        val client = try {
            if (cert.isEmpty()) http else {
                val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                    Base64.decode(cert, Base64.DEFAULT).inputStream())
                val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null); setCertificateEntry("frosti", certificate)
                }
                val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
                val manager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
                val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
                http.newBuilder().sslSocketFactory(tls.socketFactory, manager).build()
            }
        } catch (e: Exception) { lost("Invalid wireless certificate"); return }
        val networkClient = if (host != "127.0.0.1" && wifiNetwork != null)
            client.newBuilder().socketFactory(wifiNetwork!!.socketFactory).build() else client
        val url = "${if (cert.isEmpty()) "ws" else "wss"}://$host:${p.getInt("port", 8765)}"
        ws = networkClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) { main.post {
                if (ws !== w) { w.cancel(); return@post }
                w.send(JSONObject().put("type", "hello").put("local_wake", true).put("last_reply", lastReply)
                    .put("token", p.getString("token", "")).toString())
            } }
            override fun onMessage(w: WebSocket, text: String) { main.post {
                if (ws === w) {
                    try { onCore(JSONObject(text)) }
                    catch (e: Exception) { w.cancel(); lost("Invalid Core message") }
                }
            } }
            override fun onMessage(w: WebSocket, bytes: ByteString) { main.post {
                if (ws === w) playQueue.offer(bytes.toByteArray())
            } }
            override fun onClosing(w: WebSocket, code: Int, reason: String) { w.close(1000, null) }
            override fun onClosed(w: WebSocket, code: Int, reason: String) { main.post {
                if (ws === w) lost(if (code == 4001) "bad token" else reason)
            } }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { main.post {
                if (ws === w) lost(t.message ?: "no connection")
            } }
        })
    }

    private fun lost(why: String) {
        android.util.Log.w("FrostiLink", why)
        if (connected || lastState != "offline") emit(JSONObject().put("type", "conn").put("ok", false).put("why", why))
        connected = false
        ws = null
        streaming = false
        lastState = "offline"
        playQueue.clear()
        failures++
        // Android 9 can keep a stale DHCP lease after the laptop's access point restarts.
        if (failures % 4 == 0 && android.os.Build.VERSION.SDK_INT < 29) {
            val savedNetwork = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("wifi_network_id", -1)
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            if (savedNetwork >= 0 && wifi.connectionInfo.networkId == savedNetwork) {
                android.util.Log.i("FrostiLink", "Renewing saved hotspot connection")
                wifi.disconnect()
                main.postDelayed({ if (running) wifi.reconnect() }, 1000)
            }
        }
        main.removeCallbacks(retry)
        if (running) main.postDelayed(retry, 3000L)
    }

    private fun onCore(m: JSONObject) {
        when (m.optString("type")) {
            "ready" -> {
                if (!m.optBoolean("local_wake")) { ws?.cancel(); lost("Core update required for local wake detection"); return }
                connected = true; failures = 0
                emit(JSONObject().put("type", "conn").put("ok", true))
            }
            "state" -> { lastState = m.optString("state"); streaming = lastState == "listening" }
            "reply" -> lastReply = maxOf(lastReply, m.optLong("rid"))  // sent in hello: the Core resends one lost in a Wi-Fi drop
            "audio_end" -> playQueue.offer(ByteArray(0))
            "tool" -> {  // run off the socket thread; answer with the spoken result
                Thread {
                    val text = actions.run(m.getString("name"), m.optJSONObject("args") ?: JSONObject())
                    send(JSONObject().put("type", "result").put("id", m.getInt("id")).put("text", text))
                }.start()
                return
            }
            "confirm" -> if (ui == null) {  // bring the shell up so the user can tap
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("confirm", m.toString()))
                return
            }
        }
        emit(m)
    }

    fun answerConfirm(id: Int, ok: Boolean) = send(JSONObject().put("type", "confirm").put("id", id).put("ok", ok))

    // ---- audio ----
    private fun micLoop() {
        val sr = 16000
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            // VOICE_RECOGNITION: far-field friendly; VOICE_COMMUNICATION's call-style noise suppression ate distant speech
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200 * 4))
        } catch (e: SecurityException) {
            emit(JSONObject().put("type", "notify").put("text", "Microphone permission missing.")); return
        }
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        val wake = try {
            WakeWord(assets, assets.open("wake/commands.txt").bufferedReader().readLines()
                .filter { it.isNotBlank() }.joinToString("/")).also { android.util.Log.i("FrostiVoice", "On-phone wake detector ready") }
        } catch (e: Exception) {
            android.util.Log.e("FrostiVoice", "Wake detector unavailable", e)
            emit(JSONObject().put("type", "notify").put("text", "Wake detector unavailable. Tap to talk.")); null
        } catch (e: LinkageError) {
            android.util.Log.e("FrostiVoice", "Wake runtime unavailable", e)
            emit(JSONObject().put("type", "notify").put("text", "Wake runtime unavailable. Tap to talk.")); null
        }
        rec.startRecording()
        val buf = ByteArray(3200)  // 100 ms
        var wasIdle = false
        var commandUntil = 0L  // Satellite mode: listening for a command until this uptime
        val beep = ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
        // remove mic recordings left by an old wake-tuning debug build
        listOf("mic.pcm", "mic-prev.pcm").forEach { java.io.File(cacheDir, it).delete() }
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                micLevel = rms(buf, 0, n)
                // Offline = laptop unreachable: wake and a fixed command list run on the phone (Satellite mode).
                val offline = !connected && tts?.isSpeaking != true
                val idle = (connected && lastState == "idle" && !streaming) || offline
                if (idle && !wasIdle) { wake?.reset(); commandUntil = 0 }
                val now = android.os.SystemClock.uptimeMillis()
                if (connected && streaming) {
                    ws?.send(buf.toByteString(0, n))
                } else if (offline && now < commandUntil) {
                    wake?.command(buf, n)?.let { label ->
                        commandUntil = 0; wake?.reset()
                        android.util.Log.i("FrostiVoice", "Satellite command $label")
                        Thread { satellite(label) }.start()
                    }
                } else if (idle && wake?.feed(buf, n) == true) {
                    android.util.Log.i("FrostiVoice", "Local wake detected")
                    if (offline) { commandUntil = now + 5000; wake?.reset(); beep.startTone(ToneGenerator.TONE_PROP_BEEP, 150) }
                    else { lastState = "thinking"; send(JSONObject().put("type", "wake")) }
                }
                wasIdle = idle
            }
        } finally {
            beep.release()
            rec.stop(); rec.release(); wake?.release()
        }
    }

    private fun satellite(label: String) {
        val text = actions.satellite(label)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "frosti")
        emit(JSONObject().put("type", "reply").put("tool", label.substringBefore("=")).put("detail", ""))
    }

    private fun playLoop() {
        while (running) {
            val wav = playQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (wav.isEmpty()) send(JSONObject().put("type", "played")) else play(wav)
        }
    }

    private fun play(wav: ByteArray) {
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val rate = bb.getInt(24)
        var off = 12  // walk RIFF chunks to "data"
        while (off + 8 <= wav.size && String(wav, off, 4) != "data") off += 8 + bb.getInt(off + 4)
        if (off + 8 > wav.size) return
        val pcm = wav.copyOfRange(off + 8, minOf(wav.size, off + 8 + bb.getInt(off + 4)))
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
        track.write(pcm, 0, pcm.size)
        val step = rate * 2 / 25  // 40 ms of 16-bit mono
        speechEnv = FloatArray(pcm.size / step) { rms(pcm, it * step, step) * 1.6f }
        speechAt = android.os.SystemClock.uptimeMillis()
        track.play()
        Thread.sleep(pcm.size * 1000L / (rate * 2) + 50)
        track.release()
    }
}
