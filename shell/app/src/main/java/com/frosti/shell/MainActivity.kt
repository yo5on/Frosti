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
 * FROSTI's Home launcher: header, six screens behind a bottom nav, a boot sequence and an ambient idle clock.
 * The Core link, audio and phone tools live in FrostiService; this is only the face.
 */
class MainActivity : Activity() {
    val prefs by lazy { getSharedPreferences(FrostiService.PREFS, MODE_PRIVATE) }
    private val h = Handler(Looper.getMainLooper())

    var linked = false; private set
    var coreState = "offline"; private set
    var stats: JSONObject? = null; private set
    var memory: JSONObject? = null; private set
    var micOk = true; private set

    private val cores = mutableListOf<CoreView>()
    /** the one penguin every CoreView draws (CoreView.kt; same animations as the laptop console) */
    val frosti by lazy { Controller(Spec.load(this), walkIn = prefs.getBoolean("boot", true) && !reduceMotion()) }
    private lateinit var screens: LinkedHashMap<String, Screen>
    private var current = "home"
    private lateinit var content: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var brand: SparkView
    private lateinit var linkDot: Dot
    private lateinit var linkText: TextView
    private lateinit var battText: TextView
    private val navItems = mutableMapOf<String, Pair<View, TextView>>()
    private var idle: View? = null
    private lateinit var idleInfo: TextView

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        saveProvisioning(intent)
        applyOrientation()
        @Suppress("DEPRECATION") registerReceiver(recents, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        setContentView(build())
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
        if (i.hasCategory(Intent.CATEGORY_HOME)) { hideIdle(); go("home") }  // Home button returns to Home
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        FrostiService.ui = ::onEvent
        val s = FrostiService.instance
        setLink(s?.connected == true, null)
        setState(if (s?.connected == true) s.lastState else "offline")
        if (linked) { send(JSONObject().put("type", "memory")); requestStats() }
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
        if (idle?.visibility == View.VISIBLE) hideIdle() else if (drawer?.visibility == View.VISIBLE) openDrawer(false) else if (current != "home") go("home")
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
                AlertDialog.Builder(this).setMessage("Join $ssid in Wi-Fi settings to finish wireless pairing.")
                    .setPositiveButton("OK", null).show()
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
                    AlertDialog.Builder(this).setMessage("Could not join $ssid. Select it in Wi-Fi settings.")
                        .setPositiveButton("OK", null).show()
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
                if (m.optBoolean("ok")) { send(JSONObject().put("type", "memory")); requestStats() }
            }
            "state" -> {
                setState(m.optString("state"))
                if (coreState == "listening" || coreState == "speaking") hideIdle()
            }
            "stats" -> { stats = m; val ps = m.optJSONObject("phone_stats")
                frosti.stats(m.optDouble("cpu").takeIf { !it.isNaN() }, ps?.optInt("battery", -1)?.takeIf { it >= 0 }, ps?.optBoolean("charging") == true) }
            "memory" -> memory = m
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
        brand.color = when (coreState) { "offline" -> Ui.DIM; "error" -> Ui.AMBER; else -> Ui.ACCENT }
        brand.spinning = coreState == "thinking" || coreState == "listening" || coreState == "speaking"
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
    fun applyOrientation() { requestedOrientation = if (prefs.getBoolean("portrait", false)) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }

    fun go(name: String) {
        openDrawer(false)
        if (name == current || !makers.containsKey(name)) return
        screens[current]!!.view.visibility = View.GONE
        current = name
        val v = screen(name).view
        v.visibility = View.VISIBLE; v.alpha = 0f; v.translationY = dpf(12f)
        v.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        navItems.forEach { (k, p) ->
            p.first.background = if (k == name) pill(Ui.SURFACE, radius = dpf(10f)) else null
            p.second.setTextColor(if (k == name) Ui.TEXT else Ui.MUTED)
        }
        screens[name]!!.onShow()
    }

    fun styled(d: AlertDialog, amber: Boolean = false): AlertDialog {
        d.show()
        d.window?.setBackgroundDrawable(pill(Ui.SURFACE, dp(1), if (amber) Ui.AMBER else Ui.LINE, dpf(18f)))
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
            .setCustomTitle(dialogTitle(if (tier3) "Confirm · irreversible" else "Confirm action", Ui.AMBER))
            .setMessage(m.optString("summary"))
            .setPositiveButton("Do it") { _, _ -> frosti.decided(true); FrostiService.instance?.answerConfirm(id, true) }
            .setNegativeButton("Cancel") { _, _ -> frosti.decided(false); FrostiService.instance?.answerConfirm(id, false) }
            .setOnCancelListener { frosti.decided(false); FrostiService.instance?.answerConfirm(id, false) }
            .create()
        styled(d, amber = true)
        frosti.confirm()  // the penguin holds up its sign until the answer
    }

    // ---------- layout: Claude-app style, sidebar on the left (landscape) or header + bottom nav (portrait) ----------
    private fun build(): View {
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        content = FrameLayout(this)
        if (portrait) {
            // Claude mobile: ☰ opens the sidebar as a drawer, the title sits in the middle, new chat on the right
            val col = vbox()
            col.addView(FrameLayout(this).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                addView(text(22f, Ui.TEXT).apply { text = "☰"; gravity = Gravity.CENTER }.pressable { openDrawer(true) },
                    FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
                addView(brandRow(), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                addView(plusButton(18f).pressable { newChat() },
                    FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(5) })
            }, lp())
            col.addView(content, lp(-1, 0, 1f))
            root.addView(col)
            scrim = View(this).apply { setBackgroundColor(0x99000000.toInt()); visibility = View.GONE; setOnClickListener { openDrawer(false) } }
            root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            drawer = sidebar(header = false).apply { visibility = View.GONE; isClickable = true }
            root.addView(drawer, FrameLayout.LayoutParams(dp(260), -1))
        } else {
            drawer = null
            val row = hbox().apply { gravity = Gravity.NO_GRAVITY }
            row.addView(sidebar(), lp(dp(150), -1))
            row.addView(content, lp(0, -1, 1f))
            root.addView(row)
        }
        // all six took ~0.4 s on the J7, so build the shown one (and Talk, which keeps the chat log) now and the rest a frame at a time
        screens = LinkedHashMap()
        screen(current).view.visibility = View.VISIBLE; screen("talk")
        val box = content
        // delayed, not posted: a plain post runs before the first frame and the rotation waits for it
        makers.keys.forEachIndexed { i, k -> h.postDelayed({ if (content === box) screen(k) }, 800L + i * 120) }
        root.addView(buildIdle())
        return root
    }

    private val makers = linkedMapOf<String, () -> Screen>("home" to { HomeScreen(this) }, "talk" to { TalkScreen(this) },
        "system" to { SystemScreen(this) }, "tools" to { ToolsScreen(this) }, "memory" to { MemoryScreen(this) }, "setup" to { SetupScreen(this) })

    private fun screen(name: String): Screen = screens.getOrPut(name) {
        makers[name]!!().also { it.view.visibility = View.GONE; content.addView(it.view) }
    }

    private val nav = listOf("talk" to "Chat", "system" to "System", "tools" to "Tools", "memory" to "Memory", "setup" to "Setup")

    private fun brandRow(): View = hbox().apply {
        brand = SparkView(this@MainActivity)
        addView(brand, lp(dp(18), dp(18)).margins(r = dp(8)))
        addView(text(18f, Ui.TEXT, Ui.SERIF).apply { text = "Frosti" })
    }

    private fun linkRow(fill: Boolean): View = hbox().apply {
        linkDot = Dot(this@MainActivity)
        addView(linkDot, lp(dp(6), dp(6)).margins(r = dp(6)))
        linkText = text(12f, Ui.MUTED).apply { text = "Offline" }
        addView(linkText, if (fill) lp(0, -2, 1f) else lp(-2, -2).margins(r = dp(10)))
        battText = text(12f, Ui.MUTED).apply { text = "—" }
        addView(battText)
    }

    private fun plusButton(size: Float): View = FrameLayout(this).apply {
        background = pill(Ui.ACCENT)
        addView(text(size, Ui.BG, Ui.MEDIUM).apply { text = "+" }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private var drawer: View? = null
    private var scrim: View? = null

    private fun openDrawer(open: Boolean) {
        val d = drawer ?: return
        if (open == (d.visibility == View.VISIBLE)) return
        scrim?.visibility = if (open) View.VISIBLE else View.GONE
        if (open) { d.visibility = View.VISIBLE; d.translationX = -dpf(260f); d.animate().translationX(0f).setDuration(180).withEndAction(null).start() }
        else d.animate().translationX(-dpf(260f)).setDuration(150).withEndAction { d.visibility = View.GONE }.start()
    }

    private fun sidebar(header: Boolean = true): View = vbox().apply {
        setBackgroundColor(Ui.RAIL)
        setPadding(dp(10), dp(12), dp(10), dp(10))
        if (header) addView(brandRow().apply { setPadding(dp(6), 0, 0, dp(12)) })
        val newChat = hbox().apply { setPadding(dp(6), dp(7), dp(6), dp(7)) }
        newChat.addView(plusButton(15f), lp(dp(22), dp(22)).margins(r = dp(10)))
        newChat.addView(text(14f, Ui.TEXT).apply { text = "New chat" })
        addView(newChat.pressable { newChat() }, lp().margins(b = dp(6)))
        for ((key, name) in nav) {
            val t = text(14f, Ui.MUTED).apply { text = name; setPadding(dp(10), dp(8), dp(10), dp(8)) }
            navItems[key] = t to t
            addView(t.pressable { go(key) }, lp())
        }
        addView(View(context), lp(-1, 0, 1f))
        addView(linkRow(fill = true).apply { setPadding(dp(6), 0, 0, 0) })
    }

    /** Claude's "New chat": back to Home, a clear Talk log, and the Core drops the follow-up context. */
    private fun newChat() {
        send(JSONObject().put("type", "new"))
        screens["talk"]?.onEvent(JSONObject().put("type", "new"))
        go("home")
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
        idleInfo = label("", Ui.DIM)
        col.addView(idleInfo, lp(-2, -2).margins(t = dp(18)))
        box.addView(col)
        o.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))  // full width: the clock never clips when digits widen
        o.setOnClickListener { hideIdle() }
        idle = o
        return o
    }

    private fun showIdle() {
        if (coreState == "listening" || coreState == "thinking" || coreState == "speaking") { bumpIdle(); return }
        val o = idle ?: return
        if (o.visibility == View.VISIBLE) return
        idleInfo.text = "${linkText.text} · ${battText.text}"
        o.alpha = 0f; o.visibility = View.VISIBLE; frosti.sleep(true)
        o.animate().alpha(1f).setDuration(600).start()
        if (prefs.getBoolean("idle_dim", true))
            window.attributes = window.attributes.apply { screenBrightness = 0.05f }
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
