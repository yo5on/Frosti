package com.frosti.shell

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject

/** One step of the screen: what a tap does, in two words and a sentence. */
data class Caption(val label: String, val hint: String)

interface Screen {
    val view: View
    fun onEvent(m: JSONObject) {}
    fun onShow() {}
    fun tick() {}
}

/**
 * FROSTI's Home launcher: header, seven screens behind a bottom nav, a boot sequence and an ambient idle clock.
 * The Core link, audio and phone tools live in FrostiService; this is only the face.
 */
class MainActivity : Activity() {
    val prefs by lazy { getSharedPreferences(FrostiService.PREFS, MODE_PRIVATE) }
    private val h = Handler(Looper.getMainLooper())

    var linked = false; private set
    var coreState = "offline"; private set
    var stats: JSONObject? = null; private set
    var media: JSONObject? = null; private set  // what the laptop plays; mediaAt: when it arrived (elapsedRealtime)
    var mediaAt = 0L; private set
    var micOk = true; private set

    private val cores = mutableListOf<CoreView>()
    /** the one penguin every CoreView draws (CoreView.kt; same animations as the laptop console) */
    val frosti by lazy { Controller(Spec.load(this), walkIn = prefs.getBoolean("boot", true) && !reduceMotion()) }
    private lateinit var screens: LinkedHashMap<String, Screen>
    private var current = "talk"
    private lateinit var content: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var linkDot: Dot
    private lateinit var linkText: TextView
    private lateinit var battText: TextView
    private val navItems = mutableMapOf<String, Triple<View, Icon, TextView>>()
    private var idle: View? = null
    private lateinit var idleInfo: TextView

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        saveProvisioning(intent)
        applyOrientation()
        @Suppress("DEPRECATION") registerReceiver(recents, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        setContentView(build())
        // a Fullscreen theme ignores adjustResize, so lift the content above the keyboard ourselves
        val frame = findViewById<View>(android.R.id.content); val seen = android.graphics.Rect()
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            window.decorView.getWindowVisibleDisplayFrame(seen)
            // under a fifth of the screen is just the navigation bar, not a keyboard
            val kb = (window.decorView.height - seen.bottom).let { if (it > window.decorView.height / 5) it else 0 }
            if (frame.paddingBottom != kb) {
                frame.setPadding(0, 0, 0, kb)
                (screens["talk"] as? TalkScreen)?.keyboard(kb > 0)  // the keyboard has its own Frosti in its gap
                side.forEach { it.visibility = if (kb > 0) View.GONE else View.VISIBLE }  // landscape rail steps aside too
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        else FrostiService.start(this)
        if (b == null && prefs.getBoolean("boot", true) && !reduceMotion()) bootSequence()
        intent.getStringExtra("confirm")?.let { showConfirm(JSONObject(it)) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        FrostiService.start(this)  // starts even if denied: typed commands still work
        screens.values.forEach { it.onShow() }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (saveProvisioning(i)) FrostiService.instance?.reconnect()
        i.getStringExtra("confirm")?.let { showConfirm(JSONObject(it)) }
        if (i.hasCategory(Intent.CATEGORY_HOME)) { hideIdle(); go("talk") }  // Home button returns to Chat
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        FrostiService.ui = ::onEvent
        val s = FrostiService.instance
        setLink(s?.connected == true, null)
        setState(if (s?.connected == true) s.lastState else "offline")
        if (linked) requestStats()
        h.post(ticker); bumpIdle()
        OsMode.apply(this)
    }

    override fun onPause() {
        resumed = false
        if (backFromRecents) { backFromRecents = false; flipBack() }
        FrostiService.ui = null
        h.removeCallbacks(ticker); h.removeCallbacks(goIdle)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (idle?.visibility == View.VISIBLE) hideIdle() else if (current != "talk") go("talk")
    }

    /**
     * The recents key flips landscape / portrait. No app can catch that key on this Samsung, so listen for the
     * system's "recentapps" broadcast, and come straight back the moment recents covers us.
     */
    private var resumed = false
    private var backFromRecents = false
    private val recents = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getStringExtra("reason") != "recentapps") return
            // the broadcast can land before or after recents opens; flip and come back only once recents covers us
            if (resumed) backFromRecents = true else flipBack()
        }
    }
    private fun flipBack() {
        prefs.edit().putBoolean("portrait", !prefs.getBoolean("portrait", false)).apply(); applyOrientation()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    override fun onDestroy() { unregisterReceiver(recents); super.onDestroy() }

    /** Rotation only rebuilds the layout (configChanges in the manifest): a full recreate took ~0.6 s on the J7. */
    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        hideIdle(); cores.clear(); navItems.clear()
        setContentView(build())
        setLink(linked, null); setState(coreState)
    }

    /**
     * Volume buttons, shown on FROSTI's own bar instead of the system one: while the laptop is playing music they
     * drive the laptop's volume (the Core replies with the level), otherwise the phone's.
     */
    override fun onKeyDown(code: Int, e: android.view.KeyEvent): Boolean {
        val step = when (code) { android.view.KeyEvent.KEYCODE_VOLUME_UP -> 1; android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> -1; else -> return super.onKeyDown(code, e) }
        bumpIdle()
        if (linked && media?.optBoolean("playing") == true && send(JSONObject().put("type", "volume").put("step", step))) return true
        val am = getSystemService(android.media.AudioManager::class.java)
        val s = android.media.AudioManager.STREAM_MUSIC
        am.adjustStreamVolume(s, if (step > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, 0)  // no FLAG_SHOW_UI
        showVolume("Phone", am.getStreamVolume(s) * 100 / am.getStreamMaxVolume(s))
        return true
    }

    private lateinit var volBox: View
    private lateinit var volWho: TextView
    private lateinit var volMeter: Meter
    private lateinit var volPct: TextView
    private val hideVol = Runnable { volBox.animate().alpha(0f).setDuration(250).withEndAction { volBox.visibility = View.GONE }.start() }

    private fun buildVolume(): View {
        volWho = label("", Ui.MUTED); volMeter = Meter(this, warn = false); volPct = text(13f).apply { gravity = Gravity.END }
        volBox = hbox().apply {
            background = pill(Ui.SURFACE, dp(1), Ui.LINE, dpf(16f)); setPadding(dp(16), dp(12), dp(16), dp(12)); visibility = View.GONE
            addView(volWho, lp(dp(64), -2)); addView(volMeter, lp(dp(180), dp(4))); addView(volPct, lp(dp(48), -2))
        }
        return volBox
    }

    private fun showVolume(who: String, level: Int) {
        volWho.text = who.uppercase(); volMeter.value = level / 100f; volPct.text = "$level%"
        volBox.animate().cancel(); volBox.alpha = 1f; volBox.visibility = View.VISIBLE
        h.removeCallbacks(hideVol); h.postDelayed(hideVol, 1500)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) bumpIdle()
        return super.dispatchTouchEvent(e)
    }

    /** `adb shell am start -n com.frosti.shell/.MainActivity --es token X [--es host H]` (scripts/provision-phone.ps1) */
    private fun saveProvisioning(i: Intent): Boolean {
        val token = i.getStringExtra("token") ?: return false
        getSharedPreferences(FrostiService.PREFS, MODE_PRIVATE).edit().putString("token", token)
            .putString("tls_cert", i.getStringExtra("tls_cert") ?: "")
            .putInt("port", i.getIntExtra("port", 8765))
            .putString("wifi_ssid", i.getStringExtra("wifi_ssid") ?: "")
            .putInt("wifi_network_id", -1)
            .putString("host", i.getStringExtra("host") ?: "127.0.0.1").apply()
        val ssid = i.getStringExtra("wifi_ssid")
        val password = i.getStringExtra("wifi_password")
        if (ssid != null && password != null) {
            // shortcut: legacy Wi-Fi provisioning is for this Android 9 phone; use network suggestions on Android 10+.
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                styled(AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage("Join $ssid in Wi-Fi settings to finish wireless pairing.")
                    .setPositiveButton("OK", null).create())
            } else {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wifi.isWifiEnabled = true
                val network = WifiConfiguration().apply {
                    SSID = JSONObject.quote(ssid)
                    preSharedKey = JSONObject.quote(password)
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                val id = wifi.addNetwork(network)
                if (id >= 0) prefs.edit().putInt("wifi_network_id", id).apply()
                if (id < 0 || !wifi.enableNetwork(id, true)) {
                    styled(AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage("Could not join $ssid. Select it in Wi-Fi settings.")
                        .setPositiveButton("OK", null).create())
                }
            }
        }
        return true
    }

    // ---------- core events ----------
    private fun onEvent(m: JSONObject) {
        when (m.optString("type")) {
            "conn" -> {
                setLink(m.optBoolean("ok"), m.optString("why"))
                if (m.optBoolean("ok")) requestStats()
            }
            "state" -> {
                setState(m.optString("state"))
                if (coreState == "listening" || coreState == "speaking") hideIdle()
            }
            "stats" -> { stats = m; val ps = m.optJSONObject("phone_stats")
                frosti.stats(m.optDouble("cpu").takeIf { !it.isNaN() }, ps?.optInt("battery", -1)?.takeIf { it >= 0 }, ps?.optBoolean("charging") == true) }
            "now_playing" -> { media = m.optJSONObject("media"); mediaAt = android.os.SystemClock.elapsedRealtime(); idleMedia() }
            "volume" -> showVolume("Laptop", m.optInt("level"))
            "activity" -> frosti.activity(m.optString("name"), m.optJSONObject("args") ?: JSONObject())
            "reply" -> frosti.reply(if (m.isNull("tool")) null else m.optString("tool"), m.optString("detail"),
                m.optDouble("seconds", 0.0), spoken = stats?.optBoolean("voice") != false)
            "notify" -> { frosti.alert(); if (m.optString("text").startsWith("Microphone permission")) micOk = false }
            "confirm" -> { showConfirm(m); return }
        }
        screens.values.forEach { it.onEvent(m) }
    }

    private fun setLink(ok: Boolean, why: String?) {
        linked = ok
        // display stays on only while linked to the laptop; offline, the phone sleeps as usual
        if (ok) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        linkDot.set(if (ok) Ui.ACCENT else if (why == "bad token") Ui.AMBER else Ui.DIM)
        linkText.text = if (ok) "Linked" else if (why == "bad token") "Bad token" else "Offline"
        frosti.setLink(ok, why == "bad token")
        if (!ok) setState(if (why == "bad token") "error" else "offline")
    }

    private val verbs = listOf("Pondering…", "Noodling…", "Mulling it over…", "Waddling through it…", "Chilling on it…",
        "Defrosting…", "Brewing an answer…", "Flippering…")
    private var verb = verbs[0]

    private fun setState(s: String) {
        if (s == "thinking" && coreState != "thinking") verb = verbs.random()  // Claude Code-style, one per think
        coreState = if (!linked && s != "error") "offline" else s
        if (linked) frosti.state(coreState)  // offline / error come from the link (setLink)
        screens.values.forEach { it.onEvent(JSONObject().put("type", "ui_state")) }
    }

    fun caption(): Caption = when (coreState) {
        "listening" -> Caption("Listening", "Go ahead, I'm listening.")
        "thinking" -> Caption("Thinking", verb)
        "speaking" -> Caption("Speaking", "Replying through the phone speaker.")
        "error" -> Caption("Link error", "The Core rejected the pairing token. Run provision-phone.ps1.")
        "offline" -> Caption("Offline", "Can't reach the laptop Core. Retrying…")
        else -> Caption("Ready", "Say “Frosti”, wait for “Yes?”, then speak.")
    }

    // ---------- actions screens use ----------
    fun send(m: JSONObject): Boolean = FrostiService.instance?.send(m) == true

    fun timer(minutes: String, label: String = "") {
        val result = Actions(this).run("set_timer", JSONObject().put("minutes", minutes).put("label", label))
        android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show()
    }

    fun showTimers() {
        val result = Actions(this).run("show_timers", JSONObject())
        android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show()
    }

    /** A typed or tapped command: goes through the Core's router like speech, and shows up in Talk. */
    fun command(text: String, show: Boolean = true) {
        val ok = send(JSONObject().put("type", "text").put("text", text))
        screens["talk"]?.onEvent(JSONObject().put("type", "you").put("text", text).put("sent", ok))
        if (show) go("talk")
    }

    fun requestStats() { send(JSONObject().put("type", "stats").put("phone", phoneStats())) }

    fun phoneStats(): JSONObject {
        val bm = getSystemService(BatteryManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        @Suppress("DEPRECATION") val net = getSystemService(ConnectivityManager::class.java).activeNetworkInfo
        return JSONObject().put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging", bm.isCharging)
            .put("ram_free", mi.availMem).put("ram_total", mi.totalMem)
            .put("net", if (net?.isConnected == true) net.typeName else "none")
            .put("link", "${prefs.getString("host", "127.0.0.1")}:${prefs.getInt("port", 8765)}")
    }

    fun level(): Float = FrostiService.instance?.level() ?: 0f

    fun reduceMotion() = prefs.getBoolean("reduce_motion", false)

    fun newCore(): CoreView = CoreView(this, frosti).also {
        it.level = ::level; it.reduceMotion = reduceMotion(); cores += it
    }

    fun applyMotion() = cores.forEach { it.reduceMotion = reduceMotion() }
    // either way up; changing it recreates the activity, which rebuilds the layout
    val portrait get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    fun applyOrientation() { requestedOrientation = if (prefs.getBoolean("portrait", false)) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }  // fixed: no auto-rotate

    fun go(name: String) {
        markNav(name)
        if (name == current || !makers.containsKey(name)) return
        screens[current]!!.view.visibility = View.GONE
        current = name
        val v = screen(name).view
        v.visibility = View.VISIBLE; v.alpha = 0f; v.translationY = dpf(12f)
        v.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        screens[name]!!.onShow()
    }

    private fun markNav(name: String) = navItems.forEach { (k, n) ->
        n.first.background = if (k == name) pill(Ui.SURFACE, radius = dpf(12f)) else null
        n.second.color = if (k == name) Ui.ACCENT else Ui.MUTED; n.second.invalidate()
        n.third.setTextColor(if (k == name) Ui.TEXT else Ui.MUTED)
    }

    fun styled(d: AlertDialog, amber: Boolean = false): AlertDialog {
        d.show()
        d.window?.setBackgroundDrawable(pill(Ui.SURFACE, dp(1), if (amber) Ui.AMBER else Ui.LINE, dpf(18f)))
        d.findViewById<TextView>(android.R.id.message)?.apply { setTextColor(Ui.TEXT); typeface = Ui.LIGHT }  // Material's grey body text otherwise
        for (w in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL))
            d.getButton(w)?.setTextColor(if (w == AlertDialog.BUTTON_POSITIVE) (if (amber) Ui.AMBER else Ui.ACCENT) else Ui.MUTED)
        return d
    }

    private fun dialogTitle(s: String, color: Int) = label(s, color, 12f).apply { setPadding(dp(24), dp(22), dp(24), dp(4)) }

    fun prompt(title: String, hint: String, numeric: Boolean = false, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint; setHintTextColor(Ui.DIM); setTextColor(Ui.TEXT); isSingleLine = true; typeface = Ui.LIGHT
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN  // landscape keyboard otherwise covers the app with a white editor
            background = pill(Ui.BG, radius = dpf(12f)); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        styled(AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setCustomTitle(dialogTitle(title, Ui.ACCENT)).setView(box)
            .setPositiveButton("Go") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Cancel", null).create())
        input.requestFocus()
    }

    fun ask(title: String, message: String, yes: String, amber: Boolean = false, onYes: () -> Unit) {
        styled(AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setCustomTitle(dialogTitle(title, if (amber) Ui.AMBER else Ui.ACCENT)).setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }.setNegativeButton("Cancel", null).create(), amber)
    }

    fun choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
        styled(AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setCustomTitle(dialogTitle(title, Ui.ACCENT)).setItems(items.toTypedArray()) { _, i -> onPick(i) }.create())
    }

    /** FROSTI's own calculator (replaces the disabled Samsung one): display on the left, keypad on the right. */
    fun showCalculator() {
        var expr = ""
        val exprV = text(18f, Ui.MUTED).apply { gravity = Gravity.END; maxLines = 2 }
        val resV = text(46f, Ui.TEXT, Ui.THIN).apply { gravity = Gravity.END; isSingleLine = true }
        fun refresh(done: Boolean) {
            exprV.text = expr
            val v = Calc.eval(expr)
            resV.text = if (v != null) Calc.fmt(v) else if (done && expr.isNotEmpty()) "Error" else ""
            resV.setTextColor(if (done) Ui.ACCENT else Ui.TEXT)
        }
        val keys = vbox()
        for (row in listOf("C()÷", "789×", "456−", "123+", "0.⌫=")) {
            val h = hbox()
            keys.addView(h, lp())
            for (k in row) {
                val b = text(22f, if (k.isDigit() || k == '.' || k == '=') Ui.TEXT else Ui.ACCENT).apply {
                    text = k.toString(); gravity = Gravity.CENTER
                    background = pill(if (k == '=') Ui.ACCENT_DIM else Ui.BG, radius = dpf(12f))
                }
                h.addView(b, lp(dp(64), dp(46)).margins(dp(3), dp(3), dp(3), dp(3)))
                b.pressable {
                    bumpIdle()  // dialog touches skip the activity's dispatchTouchEvent, so keep the idle clock away by hand
                    when (k) {
                        'C' -> expr = ""
                        '⌫' -> expr = expr.dropLast(1)
                        '=' -> Calc.eval(expr)?.let { expr = Calc.fmt(it) }
                        else -> expr += k
                    }
                    refresh(k == '=')
                }
            }
        }
        val display = vbox().apply { gravity = Gravity.BOTTOM; addView(exprV, lp()); addView(resV, lp().margins(t = dp(8))) }
        val body = (if (portrait) vbox() else hbox()).apply {
            setPadding(dp(20), dp(8), dp(20), dp(16))
            if (portrait) { gravity = Gravity.CENTER_HORIZONTAL; addView(display, lp(-1, -2).margins(b = dp(12))) }
            else addView(display, lp(dp(260), -1).margins(r = dp(20)))
            addView(keys, lp(-2))
        }
        styled(AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setCustomTitle(dialogTitle("Calculator", Ui.ACCENT)).setView(body).create())
            .window?.setLayout(if (portrait) -1 else dp(640), -2)
    }

    fun showApps() {
        val list = Actions(this).apps()
        choose("Apps · ${list.size}", list.map { it.label }) { startActivity(list[it].intent) }
    }

    private fun showConfirm(m: JSONObject) {
        hideIdle()
        val id = m.getInt("id")
        val tier3 = m.optInt("tier") >= 3
        val d = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setCustomTitle(dialogTitle(if (tier3) "Confirm · irreversible" else "Confirm action", Ui.ACCENT))
            .setMessage(m.optString("summary"))
            .setPositiveButton("Do it") { _, _ -> frosti.decided(true); FrostiService.instance?.answerConfirm(id, true) }
            .setNegativeButton("Cancel") { _, _ -> frosti.decided(false); FrostiService.instance?.answerConfirm(id, false) }
            .setOnCancelListener { frosti.decided(false); FrostiService.instance?.answerConfirm(id, false) }
            .create()
        styled(d)
        frosti.confirm()  // the penguin holds up its sign until the answer
    }

    // ---------- layout: a slim icon rail on the left (landscape), or a header and bottom tabs (portrait) ----------
    private var side: List<View> = emptyList()  // landscape rail and its divider, hidden while typing
    private fun build(): View {
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        content = FrameLayout(this)
        side = emptyList()
        if (portrait) {
            val col = vbox()
            col.addView(hbox().apply {
                setPadding(dp(16), dp(8), dp(10), dp(8))
                addView(text(18f, Ui.TEXT, Ui.SERIF).apply { text = "Frosti" }, lp(0, -2, 1f))
                addView(linkRow(), lp(-2, -2))
            }, lp())
            col.addView(content, lp(-1, 0, 1f))
            col.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, lp(-1, 1))
            col.addView(hbox().apply {
                setBackgroundColor(Ui.RAIL); setPadding(dp(4), dp(4), dp(4), dp(4))
                for ((key, name, icon) in nav) addView(navItem(key, name, icon), lp(0, -2, 1f))
            }, lp())
            root.addView(col)
        } else {
            val row = hbox().apply { gravity = Gravity.NO_GRAVITY }
            side = listOf(rail(), View(this).apply { setBackgroundColor(Ui.LINE) })
            row.addView(side[0], lp(dp(68), -1))
            row.addView(side[1], lp(1, -1))
            row.addView(content, lp(0, -1, 1f))
            root.addView(row)
        }
        // all six took ~0.4 s on the J7, so build the shown one (and Talk, which keeps the chat log) now and the rest a frame at a time
        screens = LinkedHashMap()
        screen(current).view.visibility = View.VISIBLE; screen("talk")
        val box = content
        // delayed, not posted: a plain post runs before the first frame and the rotation waits for it
        makers.keys.forEachIndexed { i, k -> h.postDelayed({ if (content === box) screen(k) }, 800L + i * 120) }
        markNav(current)
        root.addView(buildIdle())
        root.addView(buildVolume(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })
        return root
    }

    private val makers = linkedMapOf<String, () -> Screen>("talk" to { TalkScreen(this) },
        "music" to { MusicScreen(this) }, "processes" to { ProcessesScreen(this) },
        "performance" to { PerformanceScreen(this) }, "todo" to { TodoScreen(this) }, "setup" to { SetupScreen(this) })

    private fun screen(name: String): Screen = screens.getOrPut(name) {
        makers[name]!!().also { it.view.visibility = View.GONE; content.addView(it.view) }
    }

    private val nav = listOf(Triple("talk", "Chat", "chat"), Triple("music", "Music", "note"), Triple("processes", "Processes", "list"),
        Triple("performance", "Performance", "pulse"), Triple("todo", "To-do", "check"), Triple("setup", "Setup", "sliders"))

    /** Link dot and phone battery: one line in the portrait header, stacked at the foot of the rail. */
    private fun linkRow(stacked: Boolean = false): View = (if (stacked) vbox().apply { gravity = Gravity.CENTER_HORIZONTAL } else hbox()).apply {
        linkDot = Dot(this@MainActivity)
        addView(linkDot, lp(dp(6), dp(6)).margins(r = if (stacked) 0 else dp(6), b = if (stacked) dp(5) else 0))
        linkText = text(if (stacked) 10f else 12f, Ui.MUTED).apply { text = "Offline" }
        addView(linkText, lp(-2, -2).margins(r = if (stacked) 0 else dp(10)))
        battText = text(if (stacked) 10f else 12f, Ui.MUTED).apply { text = "—" }
        addView(battText, lp(-2, -2).margins(t = if (stacked) dp(4) else 0))
    }

    /** One nav target: icon over a small label; the current one sits on a raised pill with a clay icon. */
    private fun navItem(key: String, name: String, icon: String): View {
        val box = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8), 0, dp(7)) }
        val ic = Icon(this, icon, Ui.MUTED)
        box.addView(ic, lp(dp(20), dp(20)))
        val t = text(9.5f, Ui.MUTED).apply {
            text = name; gravity = Gravity.CENTER; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(7, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)  // "Performance" fits the rail
        }
        box.addView(t, lp(-1, dp(14)).margins(t = dp(4)))
        navItems[key] = Triple(box, ic, t)
        return box.pressable { go(key) }
    }

    private fun rail(): View = vbox().apply {
        setBackgroundColor(Ui.RAIL); gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), dp(12), dp(6), dp(8))
        for ((key, name, icon) in nav) addView(navItem(key, name, icon), lp().margins(b = dp(6)))
        addView(View(context), lp(-1, 0, 1f))
        addView(linkRow(stacked = true).also { linkText.visibility = View.GONE })  // no room: the dot says it, the idle clock spells it out
    }

    /** Claude's "New chat": a clear Chat (back to its greeting), and the Core drops the follow-up context. */
    fun newChat() {
        send(JSONObject().put("type", "new"))
        screens["talk"]?.onEvent(JSONObject().put("type", "new"))
        go("talk")
    }

    // ---------- ticker: header battery, live screens ----------
    private val ticker = object : Runnable {
        override fun run() {
            val bm = getSystemService(BatteryManager::class.java)
            battText.text = "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" + if (bm.isCharging) "+" else ""
            battText.setTextColor(if (bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) <= 15) Ui.AMBER else Ui.MUTED)
            screens[current]?.tick()
            if (idle?.visibility == View.VISIBLE) idleInfo.text = "${linkText.text} · ${battText.text}"
            h.postDelayed(this, 2000)
        }
    }

    // ---------- boot ----------
    private fun bootSequence() {
        val overlay = FrameLayout(this).apply { setBackgroundColor(Ui.BG); isClickable = true }
        val box = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val core = newCore()  // the penguin walks in (Controller walkIn)
        box.addView(core, lp(dp(320), dp(140)))
        val word = label("Frosti", Ui.TEXT, 22f).apply { letterSpacing = 0.9f; alpha = 0f; gravity = Gravity.CENTER }
        box.addView(word, lp(-2, -2).margins(t = dp(8)))
        val sub = label("Personal core", Ui.ACCENT_DIM, 10f).apply { alpha = 0f }
        box.addView(sub, lp(-2, -2).margins(t = dp(8), b = dp(14)))
        val lines = listOf("Core" to "online", "Link" to if (FrostiService.instance?.connected == true) "linked" else "searching", "Voice" to "standby")
        val logs = lines.map { (k, v) ->
            label("${k.padEnd(6, ' ')} ········ ${v.padStart(9, ' ')}", Ui.MUTED, 10f).apply { alpha = 0f }.also { box.addView(it, lp(-2, -2).margins(t = dp(6))) }
        }
        overlay.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(overlay)
        h.postDelayed({
            word.animate().alpha(1f).setDuration(600).setUpdateListener { word.letterSpacing = 0.9f - 0.45f * it.animatedFraction }.start()
        }, 700)
        h.postDelayed({ sub.animate().alpha(1f).setDuration(400).start() }, 1100)
        logs.forEachIndexed { i, v -> h.postDelayed({ v.animate().alpha(1f).setDuration(200).start() }, 1300L + i * 250) }
        var done = false
        val finish = {
            if (!done) {
                done = true
                overlay.animate().alpha(0f).setDuration(350).withEndAction { root.removeView(overlay); cores.remove(core) }.start()
            }
        }
        overlay.setOnClickListener { finish() }
        h.postDelayed(finish, 2600)
    }

    // ---------- idle ----------
    private val goIdle = Runnable { showIdle() }

    private fun idleSeconds() = prefs.getInt("idle_s", 45)

    private fun bumpIdle() {
        h.removeCallbacks(goIdle)
        if (idleSeconds() > 0) h.postDelayed(goIdle, idleSeconds() * 1000L)
    }

    private fun buildIdle(): View {
        val o = FrameLayout(this).apply { setBackgroundColor(Ui.SLEEP); visibility = View.GONE; isClickable = true }
        val box = (if (portrait) vbox() else hbox()).apply { gravity = Gravity.CENTER }
        box.addView(newCore(), lp(dp(240), dp(105)).margins(r = if (portrait) 0 else dp(24), b = if (portrait) dp(16) else 0))
        val col = vbox().apply { if (portrait) gravity = Gravity.CENTER_HORIZONTAL }  // portrait col is full width: centre its children
        col.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 96f; typeface = Ui.THIN; setTextColor(Ui.TEXT); includeFontPadding = false
            if (portrait) gravity = Gravity.CENTER
        }, lp(dp(300), -2))  // fixed width: the clock never clips when digits widen
        col.addView(TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"; textSize = 17f; typeface = Ui.SERIF; setTextColor(Ui.MUTED)
        }, lp(-2, -2).margins(t = dp(4)))
        // now playing: art and track; a touch here opens Music, anywhere else just wakes
        idleMusic = text(16f, Ui.TEXT, Ui.SERIF).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(300) }
        idleArtist = text(13f, Ui.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(300) }
        idleArt = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = pill(Ui.SURFACE, radius = dpf(8f)) }
        idleSong = hbox().apply {
            background = pill(Ui.SURFACE, dp(1), Ui.LINE, dpf(14f)); setPadding(dp(8), dp(8), dp(16), dp(8))
            addView(idleArt, lp(dp(52), dp(52)).margins(r = dp(12)))
            addView(vbox().apply {
                addView(label("Now playing", Ui.ACCENT, 9.5f))
                addView(idleMusic, lp(-2, -2).margins(t = dp(4)))
                addView(idleArtist, lp(-2, -2).margins(t = dp(2)))
            })
        }
        idleSong.pressable { hideIdle(); go("music") }
        col.addView(idleSong, lp(-2, -2).margins(t = dp(16)))
        idleInfo = label("", Ui.DIM)
        col.addView(idleInfo, lp(-2, -2).margins(t = dp(18)))
        box.addView(col)
        o.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))  // full width: the clock never clips when digits widen
        o.setOnClickListener { hideIdle() }
        idle = o
        idleArtKey = null  // a rebuilt sleep screen has an empty art view
        idleMedia()
        return o
    }

    private lateinit var idleMusic: TextView
    private lateinit var idleArtist: TextView
    private lateinit var idleArt: android.widget.ImageView
    private lateinit var idleSong: View
    private var idleArtKey: String? = null

    /** While the laptop plays something, the sleep screen shows its art and track. */
    private fun idleMedia() {
        val m = media?.takeIf { it.optBoolean("playing") }
        idleSong.visibility = if (m == null) View.GONE else View.VISIBLE
        if (m == null) return
        idleMusic.text = m.optString("title"); idleArtist.text = m.optString("artist")
        val key = if (m.isNull("art")) null else m.optString("art")
        if (key != idleArtKey) {  // decode only when the track's art changes
            idleArtKey = key
            val bytes = key?.substringAfter("base64,", "")?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
            idleArt.setImageBitmap(bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) })
        }
    }

    private fun showIdle() {
        // the Music panel is the desk display: it stays up instead of the idle screen
        if (coreState == "listening" || coreState == "thinking" || coreState == "speaking" || current == "music" || SplitKeyboard.open) { bumpIdle(); return }
        val o = idle ?: return
        if (o.visibility == View.VISIBLE) return
        idleInfo.text = "${linkText.text} · ${battText.text}"
        o.alpha = 0f; o.visibility = View.VISIBLE; frosti.sleep(true)
        o.animate().alpha(1f).setDuration(600).start()
        if (prefs.getBoolean("idle_dim", false))
            window.attributes = window.attributes.apply { screenBrightness = 0.3f } // lower and this AMOLED tints the charcoal blue
    }

    fun hideIdle() {
        val o = idle ?: return
        if (o.visibility != View.VISIBLE) return
        frosti.sleep(false)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        o.animate().alpha(0f).setDuration(250).withEndAction { o.visibility = View.GONE }.start()
        bumpIdle()
    }

    fun idleChanged() = bumpIdle()
}
