package com.frosti.shell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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

private fun gb(b: Long) = "%.1f".format(b / 1073741824.0)

// ---------- CHAT: one centred column. Empty, it is the "new chat" page (penguin, greeting, status, starters);
// once you talk, the penguin shrinks into a header row and the conversation fills the column. ----------
class TalkScreen(private val a: MainActivity) : Screen {
    private val greet = a.text(if (a.portrait) 28f else 30f, Ui.TEXT, Ui.SERIF).apply { gravity = Gravity.CENTER }
    private val bigCore = a.newCore().apply { setOnClickListener { a.send(JSONObject().put("type", "listen")) } }
    private val welcomeHint = a.text(14f, Ui.MUTED).apply { gravity = Gravity.CENTER }
    private val chips = a.hbox().apply {
        gravity = Gravity.CENTER
        addView(chip("Apps") { a.showApps() })
        addView(chip("Music") { a.go("music") })
        addView(chip("Focus 25 min") { a.timer("25", "focus") })
    }
    private val welcome: View = a.vbox().apply {
        gravity = Gravity.CENTER
        addView(bigCore, lp(-1, a.dp(if (a.portrait) 130 else 104)))  // full width: walks reach the edges
        addView(greet, lp(-2, -2).margins(t = a.dp(4)))
        addView(welcomeHint, lp(-2, -2).margins(t = a.dp(6)))
        addView(chips, lp(-2, -2).margins(t = a.dp(16)))
    }

    private fun chip(name: String, onTap: () -> Unit): View =
        a.text(13f, Ui.MUTED).apply {
            text = name; setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
            background = pill(Ui.BG, a.dp(1), Ui.LINE, a.dpf(12f))
        }.pressable(onTap).also { it.layoutParams = lp(-2, -2).margins(l = a.dp(4), r = a.dp(4)) }

    private fun empty() {
        val e = log.childCount == 0
        welcome.visibility = if (e) View.VISIBLE else View.GONE
        scroll.visibility = if (e) View.GONE else View.VISIBLE
        header.visibility = if (e) View.GONE else View.VISIBLE
    }

    private val core = a.newCore().apply { setOnClickListener { a.send(JSONObject().put("type", "listen")) } }
    private val state = a.label("", Ui.ACCENT, 11f).apply { letterSpacing = 0.25f }
    private val hint = a.text(13f, Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val micDot = Dot(a); private val micMeter = Meter(a); private val micText = a.label("Mic", Ui.DIM, 9.5f)
    private val linkDot = Dot(a); private val linkText = a.label("Link", Ui.DIM, 9.5f)
    private val meta = a.label("", Ui.DIM, 9.5f)
    private val log = a.vbox()
    private val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; defaultFocusHighlightEnabled = false; addView(log) }

    /** While chatting: small penguin · state and hint · mic and link, over a hairline. */
    private val header: View = a.vbox().apply {
        val row = a.hbox()
        row.addView(core, lp(a.dp(92), a.dp(40)).margins(r = a.dp(10)))
        val words = a.vbox()
        words.addView(state)
        words.addView(hint, lp().margins(t = a.dp(4)))
        row.addView(words, lp(0, -2, 1f))
        val ind = a.vbox().apply { gravity = Gravity.END }
        ind.addView(a.hbox().apply {
            addView(micDot, lp(a.dp(6), a.dp(6)).margins(r = a.dp(5))); addView(micText, lp(-2, -2).margins(r = a.dp(6)))
            addView(micMeter, lp(a.dp(24), a.dp(3)).margins(r = a.dp(12)))
            addView(linkDot, lp(a.dp(6), a.dp(6)).margins(r = a.dp(5))); addView(linkText)
        })
        ind.addView(meta, lp(-2, -2).margins(t = a.dp(5)))
        row.addView(ind, lp(-2, -2).margins(l = a.dp(8)))
        addView(row, lp().margins(b = a.dp(8)))
        addView(View(a).apply { setBackgroundColor(Ui.LINE) }, lp(-1, 1))
    }

    // one column, capped in width on landscape so lines stay readable, the input bar always at the bottom
    override val view: View = FrameLayout(a).apply {
        val col = a.vbox().apply { setPadding(a.dp(if (a.portrait) 16 else 8), a.dp(10), a.dp(if (a.portrait) 16 else 8), a.dp(10)) }
        col.addView(header, lp())
        col.addView(scroll, lp(-1, 0, 1f))
        col.addView(welcome, lp(-1, 0, 1f))
        col.addView(inputRow(), lp().margins(t = a.dp(8), b = a.dp(18)))  // lifted off the screen edge
        addView(col, FrameLayout.LayoutParams(if (a.portrait) -1 else a.dp(560), -1, Gravity.CENTER_HORIZONTAL))
    }

    init { refresh(); empty() }

    /** While the keyboard is up its own Frosti stands between the key blocks, so ours step aside. */
    fun keyboard(up: Boolean) {
        val v = if (up) View.GONE else View.VISIBLE
        bigCore.visibility = v; core.visibility = v
        if (!a.portrait) chips.visibility = v  // landscape has no room for them beside the keyboard
    }

    /** The input bar: + (quick actions) · text · mic (hold to talk), which turns into send once you type. */
    private fun inputRow(): View = a.hbox().apply {
        background = pill(Ui.SURFACE, a.dp(1), Ui.LINE, a.dpf(16f))
        setPadding(a.dp(4), 0, a.dp(4), 0)
        val plus = a.text(24f, Ui.TEXT, Ui.THIN).apply { text = "+"; gravity = Gravity.CENTER }
        addView(plus.pressable { quick() }, lp(a.dp(44), a.dp(48)))
        val idleHint = "Ask Frosti, or hold the mic"
        val input = EditText(a).apply {
            hint = idleHint; setHintTextColor(Ui.DIM); setTextColor(Ui.TEXT); isSingleLine = true; typeface = Ui.LIGHT
            textSize = 15f; background = null; setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        val go = { val t = input.text.toString().trim(); if (t.isNotEmpty()) { a.command(t, show = false); input.setText("") } }
        input.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(); true } else false
        }
        addView(input, lp(0, a.dp(48), 1f))
        val icon = Icon(a, "mic", Ui.TEXT)
        val btn = FrameLayout(a).apply { addView(icon, FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER)) }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable) { a.idleChanged()  // keyboard taps never reach the activity's touch handler
                icon.kind = if (s.isBlank()) "mic" else "send"; icon.color = if (s.isBlank()) Ui.TEXT else Ui.ACCENT; icon.invalidate() }
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence, st: Int, b: Int, c: Int) {}
        })
        btn.setOnTouchListener { v, e ->
            if (icon.kind == "send") { if (e.action == MotionEvent.ACTION_UP) go(); return@setOnTouchListener true }
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val ok = a.send(JSONObject().put("type", "ptt").put("on", true))
                    v.background = pill(Ui.ACCENT_DIM); icon.color = Ui.TEXT; icon.invalidate()
                    input.hint = if (ok) "Listening… release to send" else "Not linked to the laptop"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    a.send(JSONObject().put("type", "ptt").put("on", false))
                    v.background = null; input.hint = idleHint
                    v.performClick()
                }
            }
            true
        }
        addView(btn, lp(a.dp(44), a.dp(44)))
    }

    /** What the old Tools screen did that is still worth a tap: a short menu behind +. */
    private fun quick() {
        val acts = listOf<Pair<String, () -> Unit>>(
            "New chat" to { a.newChat() },
            "Phone apps" to { a.showApps() },
            "Calculator" to { a.showCalculator() },
            "Timer…" to { a.prompt("Timer", "Minutes", numeric = true) { a.timer(it) } },
            "Focus 25 min" to { a.timer("25", "focus") },
            "Phone timers" to { a.showTimers() },
            "Open on laptop…" to { a.prompt("Open on laptop", "App name") { a.command("open $it on the laptop", show = false) } })
        a.choose("Quick actions", acts.map { it.first }) { acts[it].second() }
    }

    /** Now-playing preview under the reply that started it (art arrives as data: the hotspot has no internet). */
    private fun media(md: JSONObject) {
        val card = a.hbox().apply { background = pill(Ui.SURFACE, a.dp(1), Ui.LINE, a.dpf(12f)); setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10)) }
        fillMedia(card, md, true)
        (log.getChildAt(log.childCount - 1) as? LinearLayout)?.addView(card, lp().margins(t = a.dp(10)))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        mediaCard = card
    }

    /** Spotify search results under the reply; the play button plays that exact track on the laptop. */
    private fun tracks(list: org.json.JSONArray) {
        val box = a.vbox()
        for (i in 0 until list.length()) {
            val t = list.getJSONObject(i)
            val row = a.hbox().apply { gravity = Gravity.CENTER_VERTICAL; background = pill(Ui.SURFACE, a.dp(1), Ui.LINE, a.dpf(12f)); setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6)) }
            fillMedia(row, t, true)
            (row.getChildAt(1) as LinearLayout).removeViewAt(0)  // no "Now playing" label on a result
            row.addView(FrameLayout(a).apply {
                background = pill(Ui.ACCENT); contentDescription = "Play ${t.optString("title")}"
                addView(Icon(a, "play", Ui.TEXT), FrameLayout.LayoutParams(a.dp(18), a.dp(18), Gravity.CENTER))
            }.pressable {
                if (!a.send(JSONObject().put("type", "play_track").put("uri", t.optString("uri"))))
                    android.widget.Toast.makeText(a, "Not linked to the laptop.", android.widget.Toast.LENGTH_SHORT).show()
            }, lp(a.dp(44), a.dp(44)).margins(l = a.dp(10)))
            box.addView(row, lp().margins(t = a.dp(6)))
        }
        (log.getChildAt(log.childCount - 1) as? LinearLayout)?.addView(box, lp().margins(t = a.dp(4)))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private var mediaCard: LinearLayout? = null
    private var lastMedia: JSONObject? = null

    /** Core's now_playing: only the latest card follows the laptop; when playback stops it keeps the last track, paused. */
    private fun liveMedia(md: JSONObject?) {
        if (md != null) lastMedia = md
        val card = mediaCard ?: return
        lastMedia?.let { fillMedia(card, it, md?.optBoolean("playing") == true) }
    }

    private fun fillMedia(card: LinearLayout, md: JSONObject, playing: Boolean) {
        card.removeAllViews()
        val art = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = pill(Ui.LINE, radius = a.dpf(8f)); clipToOutline = true }
        md.optString("art").substringAfter("base64,", "").takeIf { it.isNotEmpty() }?.let {
            val bytes = Base64.decode(it, Base64.DEFAULT)
            art.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        card.addView(art, lp(a.dp(64), a.dp(64)).margins(r = a.dp(14)))
        val t = a.vbox()
        t.addView(a.label("${if (playing) "Now playing" else "Paused"} · ${md.optString("app")}", Ui.ACCENT, 9.5f), lp())
        t.addView(a.text(16f, Ui.TEXT, Ui.SERIF).apply { text = md.optString("title"); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp().margins(t = a.dp(4)))
        t.addView(a.text(13f, Ui.MUTED).apply {
            text = listOf(md.optString("artist"), md.optString("album")).filter { it.isNotBlank() }.joinToString(" · ")
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp())
        card.addView(t, lp(0, -2, 1f))
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
        empty()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refresh() {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greet.text = when { hr < 5 -> "Still up?"; hr < 12 -> "Good morning"; hr < 18 -> "Good afternoon"; else -> "Good evening" }
        val c = a.caption()
        state.text = c.label.uppercase(); hint.text = c.hint; welcomeHint.text = c.hint
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
    override fun tick() = refresh()

    override fun onEvent(m: JSONObject) {
        when (m.optString("type")) {
            "ui_state", "conn" -> refresh()
            "you" -> entry("You", m.optString("text"), Ui.DIM).also { if (!m.optBoolean("sent")) entry("Frosti", "Not linked to the Core, so that wasn't sent.", Ui.AMBER) }
            "heard" -> m.optString("text").takeIf { it.isNotBlank() }?.let { entry("You · voice", "“$it”", Ui.DIM) }
            "reply" -> {
                entry("Frosti", m.optString("text"), Ui.ACCENT, m.optString("detail"))
                m.optJSONObject("media")?.let { md -> md.optJSONArray("tracks")?.let { tracks(it) } ?: media(md) }
                meta.text = "${m.optString("path")} path · ${m.optDouble("seconds")} s".uppercase()
            }
            "now_playing" -> liveMedia(m.optJSONObject("media"))
            "notify" -> entry("Frosti · notice", m.optString("text"), Ui.AMBER)
            "new" -> { log.removeAllViews(); empty() }
        }
    }
}

// ---------- PROCESSES: what the laptop is busy with, busiest first; tap one to close it ----------
class ProcessesScreen(private val a: MainActivity) : Screen {
    private val list = a.vbox()
    private var key: String? = null

    override val view: View = a.page("Processes", "Busiest programs on the laptop. Tap one to close it; FROSTI asks first.").let { (sv, box) ->
        box.addView(list, lp().margins(t = a.dp(8)))
        sv
    }

    private fun render() {
        val s = a.stats.takeIf { a.linked }
        val top = s?.optJSONArray("top")
        val k = top?.toString() ?: if (s == null) "offline" else "empty"
        if (k == key) return  // rebuild only when the list changed
        key = k; list.removeAllViews()
        if (top == null || top.length() == 0) { a.row(list, "—", if (s == null) "Not linked to the laptop" else "Measuring…", ""); return }
        val most = (0 until top.length()).maxOf { top.getJSONObject(it).optLong("ram") }.coerceAtLeast(1)
        for (i in 0 until top.length()) {
            val p = top.getJSONObject(i); val name = p.optString("name").removeSuffix(".exe")
            a.row(list, name, "CPU %.0f%% · RAM %d MB".format(p.optDouble("cpu"), p.optLong("ram") / 1048576), "Close", amber = true) {
                a.command("kill $name")  // the Core asks for confirmation (tier 2)
            }
            // memory share against the hungriest program, so the list reads at a glance
            list.addView(Meter(a, warn = false).apply { value = p.optLong("ram").toFloat() / most }, lp(-1, a.dp(2)).margins(b = a.dp(4)))
        }
    }

    override fun onShow() { a.requestStats(); render() }
    override fun tick() { a.requestStats() }
    override fun onEvent(m: JSONObject) { if (m.optString("type") in setOf("stats", "conn") && view.visibility == View.VISIBLE) render() }
}

/** A stat tile: small label, one big number, a detail line, and an optional meter (amber above 85 % only when high is bad). */
private class Tile(a: MainActivity, name: String, meter: Boolean, private val warn: Boolean = true) {
    private val big = a.text(34f, Ui.TEXT, Ui.THIN).apply { maxLines = 1 }
    private val sub = a.text(13f, Ui.MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val bar = if (meter) Meter(a, warn) else null
    val view: View = a.vbox().apply {
        background = pill(Ui.SURFACE, a.dp(1), Ui.LINE, a.dpf(14f))
        setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(16))
        addView(a.label(name, Ui.MUTED, 10f))
        addView(big, lp().margins(t = a.dp(10)))
        addView(sub, lp().margins(t = a.dp(4)))
        addView(bar ?: View(a), lp(-1, a.dp(4)).margins(t = a.dp(12)))  // same slot either way, so tiles in a row line up
    }
    fun set(value: String, detail: String = "", v: Float = -1f) {
        big.text = value; sub.text = detail; bar?.value = v
        big.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        big.setTextColor(if (warn && v > 0.85f) Ui.AMBER else Ui.TEXT)
    }
}

// ---------- PERFORMANCE: the laptop's processor, memory, battery, network and uptime, as tiles ----------
class PerformanceScreen(private val a: MainActivity) : Screen {
    private val cpu = Tile(a, "Processor", true); private val ram = Tile(a, "Memory", true)
    private val batt = Tile(a, "Battery", true, warn = false); private val net = Tile(a, "Network", false); private val up = Tile(a, "Core uptime", false)
    private var lastNet: Triple<Long, Long, Long>? = null

    override val view: View = a.page("Performance", "The laptop, live.").let { (sv, box) ->
        val tiles = listOf(cpu, ram, batt, net, up)
        for (row in tiles.chunked(if (a.portrait) 2 else 3)) {
            val h = a.hbox().apply { gravity = Gravity.NO_GRAVITY }
            row.forEachIndexed { i, t -> h.addView(t.view, lp(0, -2, 1f).margins(l = if (i == 0) 0 else a.dp(12))) }
            box.addView(h, lp().margins(t = a.dp(12)))
        }
        sv
    }

    private fun render() {
        val s = a.stats.takeIf { a.linked }
        if (s == null) { for (t in listOf(cpu, ram, batt, net, up)) t.set("—", "Not linked"); return }
        cpu.set("${s.optDouble("cpu").toInt()}%", "${s.optInt("cores")} cores", s.optDouble("cpu").toFloat() / 100)
        ram.set("${s.optDouble("ram").toInt()}%", "${gb(s.optLong("ram_used"))} of ${gb(s.optLong("ram_total"))} GB", s.optDouble("ram").toFloat() / 100)
        if (s.isNull("battery")) batt.set("—", "No battery") else
            batt.set("${s.optInt("battery")}%", if (s.optBoolean("plugged")) "Plugged in" else "On battery", s.optInt("battery") / 100f)
        val now = System.currentTimeMillis()
        lastNet?.let { (t, rx, tx) ->
            val dt = (now - t) / 1000.0
            if (dt > 0.5) {
                val down = (s.optLong("net_recv") - rx) / 1024 / dt; val upk = (s.optLong("net_sent") - tx) / 1024 / dt
                net.set("↓ %.0f".format(down), "KB/s down · ↑ %.0f KB/s up".format(upk))
            }
        } ?: net.set("…", "Measuring")
        lastNet = Triple(now, s.optLong("net_recv"), s.optLong("net_sent"))
        val u = s.optLong("uptime")
        up.set(if (u >= 3600) "${u / 3600}h ${u % 3600 / 60}m" else "${u / 60}m", "Since the Core started")
    }

    override fun onShow() { a.requestStats(); render() }
    override fun tick() { a.requestStats() }
    override fun onEvent(m: JSONObject) { if (m.optString("type") in setOf("stats", "conn") && view.visibility == View.VISIBLE) render() }
}

// ---------- TO-DO: a list kept on the phone, emptied at midnight. Tap to tick or untick. ----------
class TodoScreen(private val a: MainActivity) : Screen {
    private val list = a.vbox()

    override val view: View = a.page("To-do", "Kept on this phone. Tap an item to tick it off.").let { (sv, box) ->
        a.row(box, "Add a to-do", "", "+ Add") { a.prompt("New to-do", "What needs doing?") { t -> if (t.isNotBlank()) { save(load().put(JSONObject().put("t", t.trim()).put("done", false))); render() } } }
        box.addView(list, lp().margins(t = a.dp(8)))
        sv
    }

    init { render() }  // a rotation rebuilds the shown screen without onShow

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private var shownDay = today()

    // the list empties at midnight: anything saved on an earlier day is dropped
    private fun load() = if (a.prefs.getString("todos_day", null) == today()) org.json.JSONArray(a.prefs.getString("todos", "[]")) else org.json.JSONArray()
    private fun save(j: org.json.JSONArray) = a.prefs.edit().putString("todos", j.toString()).putString("todos_day", today()).apply()

    private fun render() {
        list.removeAllViews()
        val j = load()
        if (j.length() == 0) { a.row(list, "Nothing to do", "Add one above", ""); return }
        for (i in 0 until j.length()) {
            val it = j.getJSONObject(i); val done = it.optBoolean("done")
            val r = a.hbox().apply { setPadding(0, a.dp(12), 0, a.dp(12)); minimumHeight = a.dp(52) }
            r.addView(Icon(a, if (done) "check" else "box", if (done) Ui.ACCENT else Ui.MUTED), lp(a.dp(22), a.dp(22)).margins(r = a.dp(14)))
            r.addView(a.text(16f, if (done) Ui.MUTED else Ui.TEXT).apply {
                text = it.optString("t")
                if (done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }, lp(0, -2, 1f))
            list.addView(r.pressable { it.put("done", !done); save(j); render() })
        }
    }

    override fun onShow() = render()
    override fun tick() { if (shownDay != today()) { shownDay = today(); render() } }  // clears on screen if left open past midnight
}

// ---------- SETUP ----------
class SetupScreen(private val a: MainActivity) : Screen {
    private lateinit var voice: TextView
    private lateinit var core: TextView; private lateinit var token: TextView; private lateinit var link: TextView
    private lateinit var mic: TextView; private lateinit var sys: TextView; private lateinit var os: TextView
    private lateinit var boot: TextView; private lateinit var idle: TextView; private lateinit var dim: TextView; private lateinit var motion: TextView; private lateinit var orient: TextView
    private val idleSteps = intArrayOf(30, 45, 60, 120, 300, 0)

    override val view: View = a.page("Setup", "Pairing, permissions and look.").let { (sv, box) ->
        a.section(box, "Voice")
        voice = a.row(box, "Voice", "Offline text-to-speech", "—")

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

        a.section(box, "Appearance")
        boot = a.row(box, "Boot animation", "When FROSTI starts") { flip("boot", true) }
        idle = a.row(box, "Ambient clock", "After no touches for") {
            val cur = a.prefs.getInt("idle_s", 45)
            a.prefs.edit().putInt("idle_s", idleSteps[(idleSteps.indexOf(cur) + 1) % idleSteps.size]).apply()
            a.idleChanged(); render()
        }
        dim = a.row(box, "Dim on ambient", "Backlight down to 30 %") { flip("idle_dim", false) }
        motion = a.row(box, "Reduce motion", "Calmer core, no boot animation") { flip("reduce_motion", false); a.applyMotion() }
        orient = a.row(box, "Orientation", "Tap to switch landscape / portrait") { flip("portrait", false); a.applyOrientation() }

        sv
    }

    private fun flip(key: String, def: Boolean) { a.prefs.edit().putBoolean(key, !a.prefs.getBoolean(key, def)).apply(); render() }

    private fun TextView.on(v: Boolean, yes: String = "On", no: String = "Off") { text = (if (v) yes else no).uppercase(); setTextColor(if (v) Ui.ACCENT else Ui.MUTED) }
    private fun TextView.value(s: String, warn: Boolean = false) { text = s.uppercase(); setTextColor(if (warn) Ui.AMBER else Ui.ACCENT) }

    private fun render() {
        val s = a.stats
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
        dim.on(a.prefs.getBoolean("idle_dim", false))
        motion.on(a.prefs.getBoolean("reduce_motion", false))
        orient.on(a.prefs.getBoolean("portrait", false), "Portrait", "Landscape")
    }

    override fun onShow() { a.requestStats(); render() }
    override fun onEvent(m: JSONObject) { if (m.optString("type") in setOf("stats", "conn")) render() }
}

// ---------- MUSIC: desk now-playing panel for whatever the laptop plays ----------
class MusicScreen(private val a: MainActivity) : Screen {
    private val art = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        background = pill(Ui.SURFACE, radius = a.dpf(10f)) }
    private val source = a.label("", Ui.ACCENT_DIM, 11f)
    private val title = a.text(if (a.portrait) 26f else 30f, Ui.TEXT, Ui.SERIF).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val artist = a.text(18f, Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val bar = Meter(a, warn = false)
    private val elapsed = a.label("", Ui.MUTED, 11f)
    private val remain = a.label("", Ui.MUTED, 11f)
    private val shuffle = Icon(a, "shuffle", Ui.MUTED)
    private val play = Icon(a, "play", Ui.BG)
    private var artKey: String? = null
    private var playing = false
    private var ticking = false

    // landscape: big art on the left, one column on the right (words, progress, controls, search);
    // portrait: art on top, the same column underneath, centred
    override val view: View = FrameLayout(a).apply {
        val p = a.portrait
        val side = if (p) a.dp(260) else a.dp(220)
        val words = a.vbox().apply {
            if (p) gravity = Gravity.CENTER_HORIZONTAL
            addView(source)
            addView(title.apply { if (p) gravity = Gravity.CENTER }, lp().margins(t = a.dp(10)))
            addView(artist.apply { if (p) gravity = Gravity.CENTER }, lp().margins(t = a.dp(6)))
        }
        val controls = a.hbox().apply { gravity = if (p) Gravity.CENTER else Gravity.CENTER_VERTICAL }
        fun button(icon: View, size: Int, bg: Int?, box: Int, onTap: () -> Unit) = controls.addView(FrameLayout(a).apply {
            if (bg != null) background = pill(bg)
            addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }.pressable(onTap), lp(box, box).margins(r = a.dp(if (p) 6 else 10), l = if (p) a.dp(6) else 0))
        button(shuffle, a.dp(20), null, a.dp(52)) { act("shuffle") }
        button(Icon(a, "prev", Ui.TEXT), a.dp(24), null, a.dp(52)) { act("previous") }
        button(play, a.dp(26), Ui.ACCENT, a.dp(64)) { act(if (playing) "pause" else "play"); playing = !playing; render() }  // instant; Core corrects it
        button(Icon(a, "next", Ui.TEXT), a.dp(24), null, a.dp(52)) { act("next") }
        button(Icon(a, "mic", Ui.MUTED), a.dp(20), null, a.dp(52)) { a.go("talk"); a.send(JSONObject().put("type", "listen")) }
        val search = a.label("Search Spotify", Ui.TEXT, 11f).apply {
            gravity = Gravity.CENTER; background = pill(0, a.dp(1), Ui.LINE); setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10))
        }.pressable { a.prompt("Spotify", "Song, artist or album") { a.command("search $it on spotify") } }  // results (with play buttons) land in Talk
        val column = a.vbox().apply {
            if (p) gravity = Gravity.CENTER_HORIZONTAL
            addView(words, lp(-1, -2))
            addView(bar, lp(-1, a.dp(4)).margins(t = a.dp(if (p) 24 else 28)))
            addView(a.hbox().apply { addView(elapsed, lp(0, -2, 1f)); addView(remain) }, lp(-1, -2).margins(t = a.dp(8)))
            addView(controls, lp(-1, -2).margins(t = a.dp(14)))
            addView(search, lp(-2, -2).margins(t = a.dp(14)))
        }
        val body = if (p) a.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL
            addView(art, lp(side, side).margins(b = a.dp(26))); addView(column, lp(-1, -2))
        } else a.hbox().apply { gravity = Gravity.CENTER_VERTICAL
            addView(art, lp(side, side).margins(r = a.dp(36))); addView(column, lp(0, -2, 1f))
        }
        art.elevation = a.dpf(8f)  // lifts the cover off the page
        addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            setMargins(a.dp(if (p) 24 else 40), a.dp(16), a.dp(if (p) 24 else 40), a.dp(16)) })
    }

    private fun act(action: String) {
        if (!a.send(JSONObject().put("type", "media").put("action", action)))
            android.widget.Toast.makeText(a, "Not linked to the laptop.", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun clock(s: Double) = "%d:%02d".format(s.toLong() / 60, s.toLong() % 60)

    private fun render() {
        val m = a.media
        if (m == null) {
            source.text = "Laptop"; title.text = "Nothing playing"; artist.text = "Say “play a song on Spotify”."
            art.setImageDrawable(null); artKey = null; bar.value = -1f; elapsed.text = ""; remain.text = ""
            play.kind = "play"; shuffle.visibility = View.INVISIBLE; return
        }
        source.text = "${m.optString("app")} · laptop".uppercase()
        title.text = m.optString("title"); artist.text = m.optString("artist")
        val key = if (m.isNull("art")) null else m.optString("art")
        if (key != artKey) {
            artKey = key
            val bytes = key?.substringAfter("base64,", "")?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            art.setImageBitmap(bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
        }
        play.kind = if (playing) "pause" else "play"
        shuffle.visibility = if (m.isNull("shuffle")) View.INVISIBLE else View.VISIBLE
        shuffle.color = if (m.optBoolean("shuffle")) Ui.ACCENT else Ui.MUTED; shuffle.invalidate()
        val len = m.optDouble("length")
        if (len.isNaN() || len <= 0) { bar.value = -1f; elapsed.text = ""; remain.text = ""; return }
        // the Core pushes position only on changes and seeks; run the clock here between pushes
        val pos = (m.optDouble("position", 0.0) + if (m.optBoolean("playing")) (android.os.SystemClock.elapsedRealtime() - a.mediaAt) / 1000.0 else 0.0)
            .coerceIn(0.0, len)
        bar.value = (pos / len).toFloat(); elapsed.text = clock(pos); remain.text = "-" + clock(len - pos)
    }

    private val run = object : Runnable {
        override fun run() { if (view.isShown) { render(); view.postDelayed(this, 1000) } else ticking = false }
    }

    init { playing = a.media?.optBoolean("playing") == true; render() }

    override fun onShow() { render(); if (!ticking) { ticking = true; view.postDelayed(run, 1000) } }
    override fun onEvent(m: JSONObject) {
        if (m.optString("type") == "now_playing") { playing = a.media?.optBoolean("playing") == true; render() }
    }
}
