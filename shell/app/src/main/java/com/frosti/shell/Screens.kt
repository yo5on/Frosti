package com.frosti.shell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------- shared pieces ----------
private fun MainActivity.page(title: String, sub: String): Pair<ScrollView, LinearLayout> {
    val box = vbox().apply { setPadding(dp(20), dp(10), dp(20), dp(24)) }
    box.addView(text(30f, Ui.TEXT, Ui.SERIF).apply { text = title })
    box.addView(text(14f, Ui.MUTED).apply { text = sub }, lp().margins(t = dp(6), b = dp(8)))
    val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; defaultFocusHighlightEnabled = false; addView(box) }
    return sv to box
}

private fun MainActivity.section(box: LinearLayout, title: String) =
    box.addView(label(title, Ui.ACCENT_DIM), lp().margins(t = dp(26), b = dp(6)))

/** A tappable line: title, mono hint, and a tag on the right (amber when the Core will ask first). */
private fun MainActivity.row(box: LinearLayout, title: String, hint: String = "", tag: String = "›", amber: Boolean = false, onTap: (() -> Unit)? = null): TextView {
    val r = hbox().apply { setPadding(0, dp(12), 0, dp(12)); minimumHeight = dp(52) }
    val left = vbox()
    left.addView(text(16f).apply { text = title })
    if (hint.isNotEmpty()) left.addView(label(hint, Ui.MUTED, 10f).apply { letterSpacing = 0.04f; text = hint }, lp().margins(t = dp(5)))
    r.addView(left, lp(0, -2, 1f))
    val t = if (tag == "›") text(20f, Ui.DIM).apply { text = tag } else label(tag, if (amber) Ui.AMBER else Ui.ACCENT, 10f)
    r.addView(t)
    if (onTap != null) r.pressable(onTap)
    box.addView(r)
    return t
}

/** Name on the left, value on the right, optional meter under both. */
private class Stat(a: MainActivity, box: LinearLayout, name: String, withMeter: Boolean, val dot: Boolean = false) {
    val value: TextView = a.text(15f, Ui.TEXT)
    val meter: Meter? = if (withMeter) Meter(a) else null
    val led = Dot(a)
    init {
        val r = a.hbox().apply { setPadding(0, a.dp(10), 0, if (withMeter) a.dp(8) else a.dp(10)) }
        if (dot) r.addView(led, lp(a.dp(7), a.dp(7)).margins(r = a.dp(12)))
        r.addView(a.text(15f, Ui.MUTED).apply { text = name }, lp(0, -2, 1f))
        r.addView(value)
        box.addView(r)
        meter?.let { box.addView(it, lp(-1, a.dp(3))) }
    }
    fun set(text: String, v: Float = -1f, ok: Boolean? = null) {
        value.text = text; meter?.value = v
        if (ok != null) led.set(if (ok) Ui.ACCENT else Ui.AMBER)
        value.setTextColor(if (v > 0.85f) Ui.AMBER else Ui.TEXT)
    }
}

private fun gb(b: Long) = "%.1f".format(b / 1073741824.0)
private fun ago(ts: Double): String {
    val s = (System.currentTimeMillis() / 1000.0 - ts).toLong()
    return when { s < 60 -> "just now"; s < 3600 -> "${s / 60}m ago"; s < 86400 -> "${s / 3600}h ago"; else -> "${s / 86400}d ago" }
}
private fun dur(s: Long) = if (s >= 3600) "${s / 3600}h ${s % 3600 / 60}m" else "${s / 60}m ${s % 60}s"

// ---------- HOME ----------
class HomeScreen(private val a: MainActivity) : Screen {
    private val greet = a.text(26f, Ui.TEXT, Ui.SERIF).apply { gravity = Gravity.CENTER }
    private val core = a.newCore()
    private val hint = a.text(13f, Ui.MUTED).apply { gravity = Gravity.CENTER }

    // Claude's "new chat" screen: the character, a greeting, an ask box and a few starters, centred
    override val view: View = a.vbox().apply {
        gravity = Gravity.CENTER
        setPadding(a.dp(24), a.dp(8), a.dp(24), a.dp(8))
        core.setOnClickListener { if (!a.send(JSONObject().put("type", "listen"))) hint.text = "Not linked to the Core yet." }
        core.setOnLongClickListener { a.go("talk"); true }
        addView(core, lp(-1, a.dp(110)))  // full width: walks reach the screen edge instead of vanishing mid-screen
        addView(greet, lp(-2, -2))
        addView(hint, lp(-2, -2).margins(t = a.dp(4)))
        addView(askBox(), lp(if (a.portrait) -1 else a.dp(420), a.dp(48)).margins(t = a.dp(14)))
        val chips = a.hbox().apply { gravity = Gravity.CENTER }
        chips.addView(chip("Apps") { a.showApps() })
        chips.addView(chip("System") { a.go("system") })
        chips.addView(chip("Focus 25 min") { a.command("set a timer for 25 minutes for focus") })
        addView(chips, lp(-2, -2).margins(t = a.dp(12)))
    }

    private fun askBox(): View = a.hbox().apply {
        background = pill(Ui.SURFACE, a.dp(1), Ui.LINE, a.dpf(24f))
        setPadding(a.dp(18), 0, a.dp(4), 0)
        addView(a.text(15f, Ui.DIM).apply { text = "How can I help you today?" }.pressable { a.go("talk") }, lp(0, -2, 1f))
        val mic = FrameLayout(a).apply { background = pill(Ui.ACCENT) }
        mic.addView(Icon(a, "mic", Ui.TEXT), FrameLayout.LayoutParams(a.dp(20), a.dp(20), Gravity.CENTER))
        addView(mic.pressable { a.go("talk"); a.send(JSONObject().put("type", "listen")) }, lp(a.dp(40), a.dp(40)))
    }

    private fun chip(name: String, onTap: () -> Unit): View =
        a.text(13f, Ui.MUTED).apply {
            text = name; setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
            background = pill(Ui.BG, a.dp(1), Ui.LINE, a.dpf(12f))
        }.pressable(onTap).also { it.layoutParams = lp(-2, -2).margins(l = a.dp(4), r = a.dp(4)) }

    private fun refresh() {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greet.text = when { hr < 5 -> "Still up?"; hr < 12 -> "Good morning"; hr < 18 -> "Good afternoon"; else -> "Good evening" }
        hint.text = a.caption().hint
    }

    init { refresh() }

    override fun onEvent(m: JSONObject) { if (m.optString("type") in setOf("ui_state", "conn")) refresh() }
    override fun onShow() = refresh()
    override fun tick() = refresh()
}

// ---------- TALK ----------
class TalkScreen(private val a: MainActivity) : Screen {
    private val core = a.newCore()
    private val state = a.label("", Ui.ACCENT, 13f).apply { letterSpacing = 0.3f }
    private val hint = a.text(13f, Ui.MUTED)
    private val micDot = Dot(a); private val micMeter = Meter(a); private val micText = a.label("Mic")
    private val linkDot = Dot(a); private val linkText = a.label("Link")
    private val meta = a.label("", Ui.DIM)
    private val log = a.vbox()
    private val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; defaultFocusHighlightEnabled = false; addView(log) }
    private val pttLabel = a.label("Hold to talk", Ui.MUTED, 10f)

    // landscape: character, status and push-to-talk on the left; the conversation on the right
    // portrait: character and status on top, the conversation below, push-to-talk beside the input
    override val view: View = (if (a.portrait) a.vbox() else a.hbox()).apply {
        val port = a.portrait
        setPadding(a.dp(if (port) 16 else 12), 0, a.dp(if (port) 16 else 20), a.dp(6))
        val left = a.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        core.setOnClickListener { a.send(JSONObject().put("type", "listen")) }
        left.addView(core, if (port) lp(-1, a.dp(140)).margins(t = a.dp(12)) else lp(-1, 0, 1f))
        left.addView(state.apply { gravity = Gravity.CENTER }, lp())
        val ind = a.hbox().apply { gravity = Gravity.CENTER }
        ind.addView(micDot, lp(a.dp(6), a.dp(6)).margins(r = a.dp(6)))
        ind.addView(micText, lp(-2, -2).margins(r = a.dp(6)))
        ind.addView(micMeter, lp(a.dp(28), a.dp(3)).margins(r = a.dp(12)))
        ind.addView(linkDot, lp(a.dp(6), a.dp(6)).margins(r = a.dp(6)))
        ind.addView(linkText)
        left.addView(ind, lp().margins(t = a.dp(8), b = a.dp(8)))
        if (!port) left.addView(ptt(), lp())
        addView(left, if (port) lp() else lp(a.dp(220), -1))
        val right = a.vbox()
        if (port) hint.gravity = Gravity.CENTER_HORIZONTAL
        right.addView(hint, lp(-1, -2).margins(t = a.dp(4), b = if (port) a.dp(8) else 0))
        right.addView(meta, lp().margins(t = a.dp(4)))
        right.addView(scroll, lp(-1, 0, 1f))
        if (port) right.addView(a.hbox().apply {
            addView(inputRow(), lp(0, -2, 1f).margins(r = a.dp(10)))
            addView(ptt(), lp(-2, -2))
        }, lp().margins(t = a.dp(6)))
        else right.addView(inputRow(), lp().margins(t = a.dp(6)))
        addView(right, if (port) lp(-1, 0, 1f) else lp(0, -1, 1f).margins(l = a.dp(12)))
    }

    init {
        entry("Frosti", "Hold the button and speak, or type below. Everything runs on your laptop; nothing goes to the cloud.", Ui.ACCENT_DIM)
        refresh()
    }

    private fun inputRow(): View = a.hbox().apply {
        background = pill(Ui.SURFACE, radius = a.dpf(24f))
        setPadding(a.dp(18), 0, a.dp(6), 0)
        val input = EditText(a).apply {
            hint = "Type a command"; setHintTextColor(Ui.DIM); setTextColor(Ui.TEXT); isSingleLine = true; typeface = Ui.LIGHT
            textSize = 15f; background = null; inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        val go = { val t = input.text.toString().trim(); if (t.isNotEmpty()) { a.command(t, show = false); input.setText("") } }
        input.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(); true } else false
        }
        addView(input, lp(0, a.dp(44), 1f))
        val send = FrameLayout(a).apply { addView(Icon(a, "send"), FrameLayout.LayoutParams(a.dp(20), a.dp(20), Gravity.CENTER)) }
        addView(send.pressable { go() }, lp(a.dp(44), a.dp(44)))
    }

    private fun ptt(): View = a.vbox().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val ring = FrameLayout(a).apply { background = pill(Ui.BG, a.dp(2), Ui.ACCENT_DIM) }
        val icon = Icon(a, "mic")
        ring.addView(icon, FrameLayout.LayoutParams(a.dp(30), a.dp(30), Gravity.CENTER))
        ring.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val ok = a.send(JSONObject().put("type", "ptt").put("on", true))
                    v.background = pill(android.graphics.Color.argb(50, 217, 119, 87), a.dp(2), Ui.ACCENT)
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                    pttLabel.text = if (ok) "RELEASE TO SEND" else "NOT LINKED"
                    pttLabel.setTextColor(if (ok) Ui.ACCENT else Ui.AMBER)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    a.send(JSONObject().put("type", "ptt").put("on", false))
                    v.background = pill(Ui.BG, a.dp(2), Ui.ACCENT_DIM)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    pttLabel.text = "HOLD TO TALK"; pttLabel.setTextColor(Ui.MUTED)
                    v.performClick()
                }
            }
            true
        }
        addView(ring, lp(a.dp(60), a.dp(60)))
        addView(pttLabel, lp(-2, -2).margins(t = a.dp(6)))
    }

    private fun entry(who: String, text: String, whoColor: Int, detail: String = "") {
        val e = a.vbox().apply { setPadding(0, a.dp(10), 0, a.dp(10)) }
        val you = who.startsWith("You")
        if (who != "You" && who != "Frosti") e.addView(a.label(who, whoColor, 9.5f), lp().margins(b = a.dp(5)))  // Claude-style: only unusual sources get a label
        e.addView(a.text(if (you) 15f else 17f, Ui.TEXT, if (you) Ui.LIGHT else Ui.SERIF).apply {
            this.text = text; setLineSpacing(0f, 1.15f)
            if (you) { background = pill(Ui.SURFACE, radius = a.dpf(16f)); setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8)) }
        }, lp(-2, -2))
        if (you) e.gravity = Gravity.END
        if (detail.isNotBlank()) e.addView(a.label(detail, Ui.DIM, 9.5f).apply { letterSpacing = 0.02f; maxLines = 8; isAllCaps = false; this.text = detail }, lp().margins(t = a.dp(6)))
        e.alpha = 0f; e.translationY = a.dpf(8f)
        log.addView(e)
        e.animate().alpha(1f).translationY(0f).setDuration(200).start()
        while (log.childCount > 40) log.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refresh() {
        val c = a.caption()
        state.text = c.label.uppercase(); hint.text = c.hint
        state.setTextColor(when (a.coreState) { "offline" -> Ui.MUTED; "error" -> Ui.AMBER; else -> Ui.ACCENT })
        val micGranted = a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && a.micOk
        micDot.set(if (!micGranted) Ui.AMBER else if (a.coreState == "listening") Ui.ACCENT else Ui.DIM)
        micText.text = if (micGranted) "MIC" else "MIC OFF"
        linkDot.set(if (a.linked) Ui.ACCENT else if (a.coreState == "error") Ui.AMBER else Ui.DIM)
        linkText.text = if (a.linked) "LINKED" else "OFFLINE"
    }

    private val live = object : Runnable {  // mic meter at ~12 fps, only while Talk is on screen
        override fun run() {
            if (view.visibility != View.VISIBLE) return
            micMeter.value = FrostiService.instance?.micLevel ?: 0f
            view.postDelayed(this, 80)
        }
    }

    override fun onShow() { refresh(); view.post(live) }

    override fun onEvent(m: JSONObject) {
        when (m.optString("type")) {
            "ui_state", "conn" -> refresh()
            "you" -> entry("You", m.optString("text"), Ui.DIM).also { if (!m.optBoolean("sent")) entry("Frosti", "Not linked to the Core, so that wasn't sent.", Ui.AMBER) }
            "heard" -> m.optString("text").takeIf { it.isNotBlank() }?.let { entry("You · voice", "“$it”", Ui.DIM) }
            "reply" -> {
                entry("Frosti", m.optString("text"), Ui.ACCENT, m.optString("detail"))
                meta.text = "${m.optString("path")} path · ${m.optDouble("seconds")} s".uppercase()
            }
            "notify" -> entry("Frosti · notice", m.optString("text"), Ui.AMBER)
            "new" -> log.removeAllViews()
        }
    }
}

// ---------- SYSTEM (dashboard) ----------
class SystemScreen(private val a: MainActivity) : Screen {
    private lateinit var cpu: Stat; private lateinit var ram: Stat; private lateinit var batt: Stat; private lateinit var net: Stat
    private lateinit var pBatt: Stat; private lateinit var pRam: Stat; private lateinit var pNet: Stat; private lateinit var pLink: Stat
    private lateinit var model: Stat; private lateinit var uptime: Stat
    private lateinit var sGate: Stat; private lateinit var sLlm: Stat; private lateinit var sVoice: Stat; private lateinit var sTools: Stat; private lateinit var sMic: Stat
    private var lastNet: Triple<Long, Long, Long>? = null

    override val view: View = a.page("System", "Laptop core and phone, live.").let { (sv, box) ->
        a.section(box, "Laptop core")
        cpu = Stat(a, box, "Processor", true); ram = Stat(a, box, "Memory", true)
        batt = Stat(a, box, "Battery", true); net = Stat(a, box, "Network", false)
        a.section(box, "Phone")
        pBatt = Stat(a, box, "Battery", true); pRam = Stat(a, box, "Memory", true)
        pNet = Stat(a, box, "Network", false); pLink = Stat(a, box, "Link to laptop", false, dot = true)
        a.section(box, "Intelligence")
        model = Stat(a, box, "Active model", false); uptime = Stat(a, box, "Core uptime", false)
        a.section(box, "Services")
        sGate = Stat(a, box, "Gateway", false, true); sLlm = Stat(a, box, "Language model", false, true)
        sVoice = Stat(a, box, "Speech + wake word", false, true); sTools = Stat(a, box, "Phone tools", false, true)
        sMic = Stat(a, box, "Microphone", false, true)
        sv
    }

    private fun render() {
        val s = a.stats.takeIf { a.linked }
        if (s == null) {
            for (x in listOf(cpu, ram, batt, net, model, uptime)) x.set("—")
        } else {
            cpu.set("${s.optDouble("cpu").toInt()}% · ${s.optInt("cores")} cores", s.optDouble("cpu").toFloat() / 100)
            ram.set("${s.optDouble("ram").toInt()}% · ${gb(s.optLong("ram_used"))} / ${gb(s.optLong("ram_total"))} GB", s.optDouble("ram").toFloat() / 100)
            if (s.isNull("battery")) batt.set("No battery") else
                batt.set("${s.optInt("battery")}%" + if (s.optBoolean("plugged")) " · plugged in" else "", s.optInt("battery") / 100f)
            val now = System.currentTimeMillis()
            lastNet?.let { (t, rx, tx) ->
                val dt = (now - t) / 1000.0
                if (dt > 0.5) net.set("↓ %.0f KB/s   ↑ %.0f KB/s".format((s.optLong("net_recv") - rx) / 1024 / dt, (s.optLong("net_sent") - tx) / 1024 / dt))
            } ?: net.set("measuring…")
            lastNet = Triple(now, s.optLong("net_recv"), s.optLong("net_sent"))
            model.set(s.optString("model"))
            uptime.set(dur(s.optLong("uptime")))
        }
        val p = a.phoneStats()
        pBatt.set("${p.optInt("battery")}%" + if (p.optBoolean("charging")) " · charging" else "", p.optInt("battery") / 100f)
        val used = p.optLong("ram_total") - p.optLong("ram_free")
        pRam.set("${gb(used)} / ${gb(p.optLong("ram_total"))} GB", used.toFloat() / p.optLong("ram_total").coerceAtLeast(1))
        pNet.set(if (p.optString("net") == "none") "No network · USB link" else p.optString("net").lowercase().replaceFirstChar { it.uppercase() })
        pLink.set(if (a.linked) "Linked · ${p.optString("link")}" else "Searching · ${p.optString("link")}", ok = a.linked)
        sGate.set(if (a.linked) "Online" else "Unreachable", ok = a.linked)
        sLlm.set(when { s == null -> "—"; s.optBoolean("llm") -> "Ready"; else -> "Warming up" }, ok = s?.optBoolean("llm") == true)
        sVoice.set(when { s == null -> "—"; s.optBoolean("voice") -> "Ready"; else -> "Off" },
            ok = s?.optBoolean("voice") == true)
        sTools.set(if (a.linked) "Available" else "Waiting for link", ok = a.linked)
        val mic = a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && a.micOk
        sMic.set(if (mic) "Streaming" else "No permission", ok = mic)
    }

    override fun onShow() { a.requestStats(); render() }
    override fun tick() { a.requestStats() }
    override fun onEvent(m: JSONObject) { if (m.optString("type") in setOf("stats", "conn", "ui_state") && view.visibility == View.VISIBLE) render() }
}

// ---------- TOOLS ----------
class ToolsScreen(private val a: MainActivity) : Screen {
    private val projects = a.vbox()

    override val view: View = a.page("Tools", "Everything FROSTI can do. Tap to run, or just ask.").let { (sv, box) ->
        a.section(box, "Launch")
        a.row(box, "Phone apps", "Open the app drawer") { a.showApps() }
        a.row(box, "Manage apps", "Every app · tap one to uninstall or disable it") {
            val list = Actions(a).apps()
            a.choose("Manage apps · ${list.size}", list.map { it.label }) {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + list[it].intent.component!!.packageName)))
            }
        }
        a.row(box, "Calculator", "FROSTI's own · + − × ÷ and brackets") { a.showCalculator() }
        a.row(box, "Open on laptop…", "\"open chrome on the laptop\"") { a.prompt("Open on laptop", "App name") { a.command("open $it on the laptop") } }

        a.section(box, "Phone controls")
        slider(box, "Brightness", brightness()) { a.command("set brightness to $it", show = false) }
        val am = a.getSystemService(AudioManager::class.java)
        slider(box, "Volume", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)) {
            a.command("set volume to $it", show = false)
        }
        a.row(box, "Mute phone", "\"mute the phone\"") { a.command("mute the phone", show = false) }

        a.section(box, "Status")
        a.row(box, "Laptop processor", "\"what's the laptop cpu\"") { a.command("what's the laptop cpu") }
        a.row(box, "Laptop memory", "\"laptop ram usage\"") { a.command("laptop ram usage") }
        a.row(box, "Laptop storage", "\"laptop storage\"") { a.command("laptop storage") }
        a.row(box, "Phone battery", "\"phone battery\"") { a.command("phone battery") }

        a.section(box, "Automation")
        a.row(box, "Timer…", "\"set a timer for 10 minutes\"") { a.prompt("Timer", "Minutes", numeric = true) { a.command("set a timer for $it minutes") } }
        a.row(box, "Focus session", "25 minutes, then a chime") { a.command("set a timer for 25 minutes for focus") }
        box.addView(projects)
        a.row(box, "PowerShell…", "Runs on the laptop", "Confirm", amber = true) {
            a.prompt("PowerShell", "Command") { a.command("use powershell to run: $it") }
        }
        a.row(box, "Close a laptop app…", "\"kill notepad\"", "Confirm", amber = true) { a.prompt("Close app", "Process name") { a.command("kill $it") } }

        a.section(box, "Memory")
        a.row(box, "Remember…", "\"remember that …\"") { a.prompt("Remember", "A fact about you") { a.command("remember that $it") } }
        a.row(box, "Recall…", "\"what do you know about …\"") { a.prompt("Recall", "Topic") { a.command("what do you know about $it") } }
        renderProjects()
        sv
    }

    private fun brightness() = try {
        Settings.System.getInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
    } catch (e: Settings.SettingNotFoundException) { 50 }

    private fun slider(box: LinearLayout, name: String, start: Int, onSet: (Int) -> Unit) {
        val head = a.hbox().apply { setPadding(0, a.dp(12), 0, a.dp(2)) }
        val value = a.label("$start%", Ui.ACCENT, 11f)
        head.addView(a.text(16f).apply { text = name }, lp(0, -2, 1f)); head.addView(value)
        box.addView(head)
        box.addView(SeekBar(a).apply {
            max = 100; progress = start
            progressTintList = ColorStateList.valueOf(Ui.ACCENT); thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.LINE)
            setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { value.text = "$p%" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = onSet(s.progress)
            })
        })
    }

    private fun renderProjects() {
        projects.removeAllViews()
        val list = a.memory?.optJSONArray("projects") ?: return
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i).optString("name")
            a.row(projects, "Run tests · $p", "\"run the tests for $p\"") { a.command("run the tests for $p") }
            a.row(projects, "Git status · $p", "\"git status for $p\"") { a.command("git status for $p") }
            a.row(projects, "Open $p in VS Code", "On the laptop") { a.command("open $p in vs code") }
        }
    }

    override fun onEvent(m: JSONObject) { if (m.optString("type") == "memory") renderProjects() }
}

// ---------- MEMORY ----------
class MemoryScreen(private val a: MainActivity) : Screen {
    private var tab = "Recent"
    private val chips = mutableMapOf<String, TextView>()
    private val list = a.vbox()

    override val view: View = a.page("Memory", "What FROSTI knows, has done, and is waiting on.").let { (sv, box) ->
        val row = a.hbox().apply { setPadding(0, a.dp(10), 0, a.dp(4)) }
        for (t in listOf("Recent", "Facts", "Projects", "Tasks")) {
            val c = a.label(t, Ui.MUTED, 10f).apply { gravity = Gravity.CENTER; setPadding(a.dp(14), a.dp(9), a.dp(14), a.dp(9)) }
            chips[t] = c
            row.addView(c.pressable { tab = t; render() }, lp(-2, -2).margins(r = a.dp(6)))
        }
        box.addView(row)
        box.addView(list)
        render()
        sv
    }

    private fun empty(s: String) = list.addView(a.text(15f, Ui.MUTED).apply { text = s }, lp().margins(t = a.dp(20)))

    private fun render() {
        chips.forEach { (k, c) ->
            c.setTextColor(if (k == tab) Ui.ACCENT else Ui.MUTED)
            c.background = if (k == tab) pill(Ui.SURFACE, a.dp(1), Ui.ACCENT_DIM) else null
        }
        list.removeAllViews()
        val m = a.memory
        if (m == null) { empty(if (a.linked) "Loading…" else "Memory lives on the laptop. Waiting for the link."); return }
        when (tab) {
            "Recent" -> {
                val h = m.optJSONArray("history")
                a.section(list, "Conversation")
                if (h == null || h.length() == 0) empty("Nothing said yet this session.")
                else for (i in 0 until h.length()) {
                    val e = h.getJSONObject(i); val you = e.optString("role") == "user"
                    list.addView(a.label(if (you) "You" else "Frosti", if (you) Ui.DIM else Ui.ACCENT, 9.5f), lp().margins(t = a.dp(12)))
                    list.addView(a.text(15f, if (you) Ui.MUTED else Ui.TEXT).apply { text = e.optString("content") }, lp().margins(t = a.dp(4)))
                }
                val r = m.optJSONArray("recent")
                a.section(list, "Actions")
                if (r == null || r.length() == 0) empty("No actions yet.")
                else for (i in 0 until r.length()) {
                    val e = r.getJSONObject(i)
                    val declined = !e.optBoolean("approved")
                    a.row(list, e.optString("result").ifBlank { e.optString("tool") },
                        "${e.optString("tool")} · ${e.optString("source")} · ${ago(e.optDouble("ts"))}",
                        if (declined) "Declined" else "T${e.optInt("tier")}", amber = declined || e.optInt("tier") >= 2)
                }
            }
            "Facts" -> {
                a.row(list, "Remember something new", "Tap to add", "+") { a.prompt("Remember", "A fact about you") { a.command("remember that $it", show = false); later() } }
                val f = m.optJSONArray("facts")
                if (f == null || f.length() == 0) empty("No stored facts yet. Try “remember that I prefer dark themes”.")
                else for (i in 0 until f.length()) {
                    val e = f.getJSONObject(i)
                    a.row(list, e.optString("fact"), ago(e.optDouble("ts")), "Forget") {
                        a.ask("Forget this?", e.optString("fact"), "Forget", amber = true) {
                            a.send(JSONObject().put("type", "forget").put("id", e.optInt("id")))
                        }
                    }
                }
            }
            "Projects" -> {
                val p = m.optJSONArray("projects")
                if (p == null || p.length() == 0) empty("No projects configured in core/config/frosti.toml.")
                else for (i in 0 until p.length()) {
                    val name = p.getJSONObject(i).optString("name")
                    a.row(list, name, p.getJSONObject(i).optString("path")) {
                        val acts = listOf("Git status" to "git status for $name", "Recent commits" to "show the recent commits in $name",
                            "Run tests" to "run the tests for $name", "Open in VS Code" to "open $name in vs code")
                        a.choose(name, acts.map { it.first }) { a.command(acts[it].second) }
                    }
                }
            }
            "Tasks" -> {
                a.row(list, "New timer", "Minutes, with an optional label", "+") {
                    a.prompt("Timer", "Minutes", numeric = true) { a.command("set a timer for $it minutes", show = false); later() }
                }
                val t = m.optJSONArray("timers")
                if (t == null || t.length() == 0) empty("No timers or reminders running.")
                else for (i in 0 until t.length()) {
                    val e = t.getJSONObject(i)
                    val left = (e.optDouble("due") - System.currentTimeMillis() / 1000.0).toLong().coerceAtLeast(0)
                    a.row(list, e.optString("label").ifBlank { "Timer" }, "${dur(left)} left", "●")
                }
            }
        }
    }

    private fun later() = view.postDelayed({ a.send(JSONObject().put("type", "memory")) }, 600)

    override fun onShow() { a.send(JSONObject().put("type", "memory")); render() }
    override fun tick() { if (tab == "Tasks") render() }
    override fun onEvent(m: JSONObject) { if (m.optString("type") == "memory" || m.optString("type") == "conn") render() }
}

// ---------- SETUP ----------
class SetupScreen(private val a: MainActivity) : Screen {
    private lateinit var model: TextView; private lateinit var llm: TextView; private lateinit var voice: TextView
    private lateinit var core: TextView; private lateinit var token: TextView; private lateinit var link: TextView
    private lateinit var mic: TextView; private lateinit var sys: TextView; private lateinit var os: TextView
    private lateinit var boot: TextView; private lateinit var idle: TextView; private lateinit var dim: TextView; private lateinit var motion: TextView; private lateinit var orient: TextView
    private val idleSteps = intArrayOf(30, 45, 60, 120, 300, 0)

    override val view: View = a.page("Setup", "Model, voice, pairing, privacy and look.").let { (sv, box) ->
        a.section(box, "AI model")
        model = a.row(box, "Model", "Local · llama.cpp on the laptop", "—")
        llm = a.row(box, "State", "Fast-path rules first, then the model", "—")
        a.row(box, "Change model", "Edit [llm] model in core/config/frosti.toml", "Laptop")

        a.section(box, "Voice")
        voice = a.row(box, "Voice", "Offline text-to-speech", "—")
        a.row(box, "Wake word", "“Frosti” or “Hey Frosti” · detected on this phone", "On")
        a.row(box, "Follow-up window", "Keep listening after a reply", "6 s")

        a.section(box, "Device pairing")
        core = a.row(box, "Core address", "Paired laptop connection", "—")
        token = a.row(box, "Pairing token", "Set by scripts/provision-phone.ps1", "—")
        link = a.row(box, "Re-link now", "Drop and reopen the connection", "—") { FrostiService.instance?.reconnect() }

        a.section(box, "Privacy & permissions")
        mic = a.row(box, "Microphone", "Streams to your laptop only", "—") {
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        sys = a.row(box, "Modify system settings", "Needed for brightness", "—") {
            a.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${a.packageName}")))
        }
        os = a.row(box, "OS mode", "FROSTI owns the phone: no status bar, shade, recents or lock screen", "—") {
            if (OsMode.active(a)) a.ask("Leave OS mode?", "FROSTI stops being Device Owner and the phone goes back to normal Android. Turning it back on needs a laptop: scripts/provision-phone.ps1 -OsMode.", "Leave", amber = true) { OsMode.release(a); render() }
        }
        a.row(box, "Confirmations", "Commits, kills and PowerShell always ask here", "Always", amber = true)
        a.row(box, "Audit log", "Every action is recorded on the laptop", "On")
        a.row(box, "Cloud AI", "No cloud services or paid APIs", "None")

        a.section(box, "Appearance")
        boot = a.row(box, "Boot animation", "When FROSTI starts") { flip("boot", true) }
        idle = a.row(box, "Ambient clock", "After no touches for") {
            val cur = a.prefs.getInt("idle_s", 45)
            a.prefs.edit().putInt("idle_s", idleSteps[(idleSteps.indexOf(cur) + 1) % idleSteps.size]).apply()
            a.idleChanged(); render()
        }
        dim = a.row(box, "Dim on ambient", "Backlight down to 5 %") { flip("idle_dim", true) }
        motion = a.row(box, "Reduce motion", "Calmer core, no boot animation") { flip("reduce_motion", false); a.applyMotion() }
        orient = a.row(box, "Orientation", "Tap to switch landscape / portrait") { flip("portrait", false); a.applyOrientation() }

        a.section(box, "About")
        a.row(box, "FROSTI Shell 0.2", "Android 9+ · AMOLED black · offline-first", "")
        sv
    }

    private fun flip(key: String, def: Boolean) { a.prefs.edit().putBoolean(key, !a.prefs.getBoolean(key, def)).apply(); render() }

    private fun TextView.on(v: Boolean, yes: String = "On", no: String = "Off") { text = (if (v) yes else no).uppercase(); setTextColor(if (v) Ui.ACCENT else Ui.MUTED) }
    private fun TextView.value(s: String, warn: Boolean = false) { text = s.uppercase(); setTextColor(if (warn) Ui.AMBER else Ui.ACCENT) }

    private fun render() {
        val s = a.stats
        model.value(s?.optString("model")?.ifBlank { null } ?: "—")
        llm.value(when { s == null -> "—"; s.optBoolean("llm") -> "Ready"; else -> "Warming" }, warn = s != null && !s.optBoolean("llm"))
        voice.value(s?.optString("voice_name")?.substringAfter("en_")?.ifBlank { null } ?: "—")
        core.value("${a.prefs.getString("host", "127.0.0.1")}:${a.prefs.getInt("port", 8765)}")
        val hasToken = !a.prefs.getString("token", "").isNullOrEmpty()
        token.value(if (hasToken) "Set ••••" else "Missing", warn = !hasToken)
        link.value(if (a.linked) "Linked" else "Offline", warn = !a.linked)
        val micOk = a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        mic.value(if (micOk) "Granted" else "Denied", warn = !micOk)
        os.value(if (OsMode.active(a)) "On" else "Off · set from laptop")
        sys.value(if (Settings.System.canWrite(a)) "Allowed" else "Not allowed", warn = !Settings.System.canWrite(a))
        boot.on(a.prefs.getBoolean("boot", true))
        val i = a.prefs.getInt("idle_s", 45)
        idle.value(if (i == 0) "Never" else if (i < 60) "$i s" else "${i / 60} min")
        dim.on(a.prefs.getBoolean("idle_dim", true))
        motion.on(a.prefs.getBoolean("reduce_motion", false))
        orient.on(a.prefs.getBoolean("portrait", false), "Portrait", "Landscape")
    }

    override fun onShow() { a.requestStats(); render() }
    override fun onEvent(m: JSONObject) { if (m.optString("type") in setOf("stats", "conn")) render() }
}
