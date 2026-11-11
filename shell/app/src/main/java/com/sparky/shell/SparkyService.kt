package com.sparky.shell

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
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

/**
 * Keeps the link to SPARKY Core alive: streams the mic (16 kHz PCM), plays the Core's speech,
 * runs phone tools, and relays events to the UI. Foreground so Samsung doesn't kill it.
 */
class SparkyService : Service() {

    companion object {
        var instance: SparkyService? = null
        var ui: ((JSONObject) -> Unit)? = null
        const val PREFS = "sparky"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, SparkyService::class.java))
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    @Volatile var connected = false; private set
    @Volatile private var running = true
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private lateinit var actions: Actions
    var lastState = "offline"; private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        actions = Actions(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("core", "SPARKY link", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, "core").setContentTitle("SPARKY")
            .setContentText("Listening for \"Hey Sparky\"").setSmallIcon(android.R.drawable.ic_btn_speak_now).build())
        connect()
        Thread(::micLoop, "mic").start()
        Thread(::playLoop, "play").start()
    }

    override fun onDestroy() {
        running = false
        ws?.close(1000, "bye")
        instance = null
        super.onDestroy()
    }

    private fun emit(m: JSONObject) = main.post { ui?.invoke(m) }

    fun send(m: JSONObject) = ws?.send(m.toString()) ?: false

    fun reconnect() { ws?.cancel() }   // onFailure schedules a fresh connect with the new prefs

    private fun connect() {
        if (!running) return
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val url = "ws://${p.getString("host", "127.0.0.1")}:${p.getInt("port", 8765)}"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("type", "hello").put("token", p.getString("token", "")).toString())
                connected = true
                emit(JSONObject().put("type", "conn").put("ok", true))
            }
            override fun onMessage(w: WebSocket, text: String) = onCore(JSONObject(text))
            override fun onMessage(w: WebSocket, bytes: ByteString) { playQueue.put(bytes.toByteArray()) }
            override fun onClosing(w: WebSocket, code: Int, reason: String) { w.close(1000, null) }
            override fun onClosed(w: WebSocket, code: Int, reason: String) = lost(if (code == 4001) "bad token" else reason)
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = lost(t.message ?: "no connection")
        })
    }

    private fun lost(why: String) {
        if (connected || lastState != "offline") emit(JSONObject().put("type", "conn").put("ok", false).put("why", why))
        connected = false
        lastState = "offline"
        main.postDelayed(::connect, 3000)
    }

    private fun onCore(m: JSONObject) {
        when (m.optString("type")) {
            "state" -> lastState = m.optString("state")
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
            // VOICE_COMMUNICATION engages the platform echo canceller/noise suppressor
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200 * 4))
        } catch (e: SecurityException) {
            emit(JSONObject().put("type", "notify").put("text", "Microphone permission missing.")); return
        }
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        rec.startRecording()
        val buf = ByteArray(3200)  // 100 ms
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0 && connected) ws?.send(buf.toByteString(0, n))
        }
        rec.stop(); rec.release()
    }

    private fun playLoop() {
        while (running) {
            val wav = playQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            play(wav)
            if (playQueue.isEmpty()) {
                Thread.sleep(400)  // the next sentence may still be synthesizing
                if (playQueue.isEmpty()) send(JSONObject().put("type", "played"))
            }
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
        track.play()
        Thread.sleep(pcm.size * 1000L / (rate * 2) + 50)
        track.release()
    }
}
