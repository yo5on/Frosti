package com.frosti.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/*
 * FROSTI's character on the phone: a Kotlin port of core/frosti_core/anim/frosti.js, playing the same
 * frosti_anims.json (copied into the APK's assets by app/build.gradle.kts). Keep the two in step:
 *   Rig        draws one frame of the penguin plus props and effects, pixel-snapped
 *   Player     plays one animation: enter -> loop (repeat) -> exit, always starting and ending on rest
 *   Controller turns FROSTI events into the animation that should be on screen, by priority
 *   CoreView   a View that draws the shared Controller (one penguin, shown on whichever screen is up)
 */

class Prop(val name: String, val x: Float, val y: Float, val flags: String) {
    override fun equals(other: Any?) = other is Prop && other.name == name && other.x == x && other.y == y && other.flags == flags
    override fun hashCode() = name.hashCode()
}

/** One frame, already merged over its animation's pose. `n` (ticks) is left out of equality, as in frosti.js. */
data class Frame(val side: Float, val dx: Float, val dy: Float, val crouch: Float, val lx: Float, val ly: Float, val eyes: String,
                 val fl: List<Float>, val ft: List<Float>, val beak: Float, val props: List<Prop>, val fx: List<List<Any>>) {
    var n = 1
}

private fun JSONArray.floats() = List(length()) { getDouble(it).toFloat() }

private fun parseFrame(o: JSONObject, b: Frame?): Frame {
    fun f(k: String, d: Float?) = if (o.has(k)) o.getDouble(k).toFloat() else d ?: 0f
    return Frame(f("side", b?.side), f("dx", b?.dx), f("dy", b?.dy), f("crouch", b?.crouch), f("lx", b?.lx), f("ly", b?.ly),
        if (o.has("eyes")) o.getString("eyes") else b?.eyes ?: "open",
        o.optJSONArray("fl")?.floats() ?: b?.fl ?: listOf(0f, 0f),
        o.optJSONArray("ft")?.floats() ?: b?.ft ?: listOf(0f, 0f),
        f("beak", b?.beak),
        o.optJSONArray("props")?.let { a -> List(a.length()) { a.getJSONArray(it).let { p ->
            Prop(p.getString(0), p.getDouble(1).toFloat(), p.getDouble(2).toFloat(), p.optString(3, "")) } } } ?: b?.props ?: emptyList(),
        o.optJSONArray("fx")?.let { a -> List(a.length()) { a.getJSONArray(it).let { e ->
            List(e.length()) { k -> e.get(k).let { v -> if (v is Number) v.toFloat() else v.toString() } } } } } ?: b?.fx ?: emptyList()
    ).also { it.n = o.optInt("n", 1) }
}

class Anim(val prio: Int, val repeat: Int?, val glow: Int, val parts: Map<String, List<Frame>>) {
    /** loop frames that show exactly the pose: safe places to leave */
    lateinit var seam: BooleanArray
}

class Spec(j: JSONObject) {
    val tick = j.getInt("tick").toFloat()
    val canvas = j.getJSONArray("canvas").floats()
    val origin = j.getJSONArray("origin").floats()
    val palette: Map<Char, Int> = j.getJSONObject("palette").let { p -> p.keys().asSequence().associate { it[0] to Color.parseColor(p.getString(it)) } }
    /** penguin geometry: front, and side-on (facing right) for frames with side=1 */
    class Shape(b: JSONObject) {
        val body = b.getJSONArray("body").floats()
        val flippers = List(2) { b.getJSONArray("flippers").getJSONArray(it).floats() }
        val belly = b.getJSONArray("belly").floats()
        val beak = b.getJSONArray("beak").floats()
        val feet = List(2) { b.getJSONArray("feet").getJSONArray(it).floats() }
        val eyes = b.getJSONArray("eyes").floats()
        /** optional flipper colours (palette keys), e.g. the far flipper shaded side-on */
        val fc = b.optJSONArray("fc")?.let { a -> List(a.length()) { a.getString(it)[0] } }
    }
    val front = Shape(j.getJSONObject("body"))
    val side = Shape(j.getJSONObject("body").getJSONObject("side"))
    /** side=-1: the side-on penguin mirrored to face left (sprite is 16 cells wide) */
    val left = Shape(j.getJSONObject("body").getJSONObject("side").let { b ->
        fun m(a: JSONArray) = JSONArray(listOf(16 - a.getDouble(0) - a.getDouble(2), a.getDouble(1), a.getDouble(2), a.getDouble(3)))
        fun ms(a: JSONArray) = JSONArray(List(a.length()) { m(a.getJSONArray(it)) })
        JSONObject(b.toString()).apply {
            put("body", m(b.getJSONArray("body"))); put("belly", m(b.getJSONArray("belly"))); put("beak", m(b.getJSONArray("beak")))
            put("flippers", ms(b.getJSONArray("flippers"))); put("feet", ms(b.getJSONArray("feet")))
            put("eyes", b.getJSONArray("eyes").let { e -> JSONArray(List(e.length()) { 15 - e.getDouble(it) }) })
        }
    })
    val props: Map<String, List<String>> = j.getJSONObject("props").let { p ->
        p.keys().asSequence().associateWith { k -> p.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } } }
    val map: JSONObject = j.getJSONObject("map")
    val anims: Map<String, Anim> = j.getJSONObject("anims").let { all ->
        all.keys().asSequence().associateWith { id ->
            val a = all.getJSONObject(id); val pose = parseFrame(a.getJSONObject("pose"), null); val ps = a.getJSONObject("parts")
            val parts = ps.keys().asSequence().associateWith { k -> ps.getJSONArray(k).let { fs -> List(fs.length()) { parseFrame(fs.getJSONObject(it), pose) } } }
            Anim(a.getInt("prio"), if (a.isNull("repeat")) null else a.getInt("repeat"), palette.getValue(a.getString("glow")[0]), parts)
                .also { an -> an.seam = parts.getValue("loop").map { it == pose }.toBooleanArray() }
        }
    }

    companion object {
        @Volatile private var cached: Spec? = null
        fun load(ctx: Context) = cached ?: Spec(JSONObject(ctx.assets.open("frosti_anims.json").bufferedReader().use { it.readText() })).also { cached = it }
    }
}

class Rig(private val s: Spec) {
    private val p = Paint()
    private lateinit var c: Canvas
    private var u = 1f; private var x0 = 0f; private var y0 = 0f; private var glitchT = -1

    /** Walks are authored as 24 cells (off the 32-cell canvas); stretch anything past 2 cells so 24 reaches the view's edge
     *  (`edge`: cells from centre until the 16-wide penguin is fully out). Small sways stay as authored. Same as frosti.js. */
    private fun reach(dx: Float, edge: Float): Float {
        val a = abs(dx); if (a <= 2f) return dx
        return sign(dx) * (2 + (a - 2) * (max(24f, edge) - 2) / 22)
    }

    fun draw(c: Canvas, w: Int, h: Int, fr: Frame, pt: Float, glow: Int, lvl: Float, time: Float) {
        val cw = s.canvas[0]; val ch = s.canvas[1]
        this.c = c; glitchT = -1
        u = max(1f, floor(min(w / cw, h / ch)))
        x0 = ((w - cw * u) / 2).roundToInt() + s.origin[0] * u; y0 = ((h - ch * u) / 2).roundToInt() + s.origin[1] * u
        val bx = reach(fr.dx, w / (2 * u) + 8); val by = fr.dy; val cr = fr.crouch; val g = if (fr.side > 0f) s.side else if (fr.side < 0f) s.left else s.front
        for (pr in fr.props) if ('b' in pr.flags) prop(pr, bx, by)
        if (fr.fx.any { it[0] == "glitch" }) glitchT = floor(pt).toInt()
        val k = s.palette.getValue('K'); val wc = s.palette.getValue('W'); val o = s.palette.getValue('O')
        g.feet.forEachIndexed { i, f -> rect(f[0] + bx, f[1] + by + fr.ft[i], f[2], f[3], o) }
        rect(g.body[0] + bx, g.body[1] + by + cr, g.body[2], g.body[3], k)
        g.flippers.forEachIndexed { i, f -> rect(f[0] + bx, f[1] + by + cr + fr.fl[i], f[2], f[3], g.fc?.let { s.palette.getValue(it[i]) } ?: k) }
        rect(g.belly[0] + bx, g.belly[1] + by + cr, g.belly[2], g.belly[3], wc)
        val bk = if (fr.beak == -1f) lvl else fr.beak; val kx = g.beak[0]; val ky = g.beak[1]; val kw = g.beak[2]
        if (bk > .05f) { rect(kx + bx, ky + by + cr, kw, .5f, o); rect(kx + bx, ky + .5f + bk * .6f + by + cr, kw, .5f, o) }
        else rect(kx + bx, ky + by + cr, kw, 1f, o)
        g.eyes.forEachIndexed { i, ex -> eye(fr.eyes, i, ex + fr.lx + bx, by + cr + fr.ly, wc) }
        glitchT = -1
        for (pr in fr.props) if ('b' !in pr.flags) prop(pr, bx, by)
        for (f in fr.fx) fx(f, pt, glow, lvl, time)
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, a: Float = 1f) {
        p.color = color; p.alpha = (a.coerceIn(0f, 1f) * 255).toInt()
        if (glitchT >= 0) {  // slice into rows and shift them, like a corrupted frame
            var r = floor(y).toInt()
            while (r < y + h) {
                val top = max(y, r.toFloat()); val bot = min(y + h, r + 1f); val sh = ((hash(r * 31 + glitchT) % 3) - 1) * .5f
                px(x + sh, top, w, bot - top); r++
            }
            return
        }
        px(x, y, w, h)
    }

    private fun px(x: Float, y: Float, w: Float, h: Float) {
        c.drawRect((x0 + x * u).roundToInt().toFloat(), (y0 + y * u).roundToInt().toFloat(),
            (x0 + (x + w) * u).roundToInt().toFloat(), (y0 + (y + h) * u).roundToInt().toFloat(), p)
    }

    private fun eye(shape0: String, i: Int, x: Float, oy: Float, col: Int) {
        fun r(dx: Float, y: Float, w: Float, h: Float) = rect(x + dx, y + oy, w, h, col)
        val shape = when (shape0) { "wink" -> if (i > 0) "closed" else "open"; "confused" -> if (i > 0) "half" else "tall"; else -> shape0 }
        when (shape) {
            "blink" -> r(0f, 3.4f, 1f, .4f)
            "half" -> r(0f, 2.8f, 1f, 1.2f)
            "tall" -> r(0f, 1.6f, 1f, 2.6f)
            "up" -> r(0f, 1.6f, 1f, 1.4f)
            "down" -> r(0f, 3f, 1f, 1.2f)
            "happy" -> { r(-1f, 3f, 1f, 1f); r(0f, 2f, 1f, 1f); r(1f, 3f, 1f, 1f) }
            "closed" -> r(-.5f, 3f, 2f, .6f)
            "x" -> { r(-1f, 2f, 1f, 1f); r(1f, 2f, 1f, 1f); r(0f, 3f, 1f, 1f); r(-1f, 4f, 1f, 1f); r(1f, 4f, 1f, 1f) }
            "wide" -> r(-.5f, 1.5f, 2f, 2.5f)
            else -> r(0f, 2f, 1f, 2f)  // open
        }
    }

    private fun prop(pr: Prop, bx: Float, by: Float) =
        if ('h' in pr.flags) glyph(pr.name, pr.x + bx, pr.y + by) else glyph(pr.name, pr.x, pr.y)

    private fun glyph(name: String, x: Float, y: Float, a: Float = 1f) {
        s.props.getValue(name).forEachIndexed { r, row ->
            var c0 = 0
            while (c0 < row.length) {  // one rect per run of the same colour
                val ch = row[c0]; var e = c0 + 1
                while (e < row.length && row[e] == ch) e++
                if (ch != '.') rect(x + c0, y + r, (e - c0).toFloat(), 1f, s.palette.getValue(ch), a)
                c0 = e
            }
        }
    }

    private fun fx(f: List<Any>, pt: Float, glow: Int, lvl: Float, time: Float) {
        fun n(i: Int) = f[i] as Float
        fun snap(v: Float) = (v * 2).roundToInt() / 2f
        when (f[0]) {
            "bars" -> for (side in intArrayOf(-1, 1)) for (k in 0 until 3) {
                val h = .6f + 2.4f * lvl * (.5f + .5f * abs(sin(time * 9 + k * 1.7f + side)))
                rect(8 + side * (9.5f + k * 1.4f) - .35f, 4 - h / 2, .7f, h, glow)
            }
            "rise" -> { val cnt = n(7).toInt()
                for (i in 0 until cnt) { val q = (pt / n(6) + i.toFloat() / cnt) % 1f
                    glyph(f[1] as String, snap(n(2) + n(4) * q), snap(n(3) + n(5) * q), min(1f, q * 5) * (1 - q)) } }
            "orbit" -> { val cnt = n(7).toInt()
                for (i in 0 until cnt) { val t = 2 * PI.toFloat() * (pt / n(6) + i.toFloat() / cnt)
                    glyph(f[1] as String, snap(n(2) + n(4) * cos(t) - 1), snap(n(3) + n(5) * sin(t) - 1)) } }
            "burst" -> { val q = (pt % n(3)) / n(3); val cnt = n(4).toInt(); val cols = "YRINW"
                for (k in 0 until cnt) { val t = 2 * PI.toFloat() * k / cnt; val r = .5f + 3 * q
                    rect(snap(n(1) + cos(t) * r), snap(n(2) + sin(t) * r), 1f, 1f, s.palette.getValue(cols[k % 5]), 1 - q) } }
            "ping" -> { val q = (pt % n(3)) / n(3); val r = .5f + 2 * q; val cx = n(1) + .5f; val cy = n(2) + .5f; val a = 1 - q
                rect(cx - r, cy - r, 2 * r, .5f, glow, a); rect(cx - r, cy + r - .5f, 2 * r, .5f, glow, a)
                rect(cx - r, cy - r, .5f, 2 * r, glow, a); rect(cx + r - .5f, cy - r, .5f, 2 * r, glow, a) }
        }
    }

    private fun hash(n0: Int): Long {
        var n = (n0 xor 61) xor (n0 ushr 16); n += n shl 3; n = n xor (n ushr 4); n *= 0x27d4eb2d
        return (n xor (n ushr 15)).toLong() and 0xffffffffL
    }
}

class Player(spec: Spec, val id: String) {
    val a = spec.anims.getValue(id)
    private val p = a.parts
    var part = if (p.getValue("enter").isNotEmpty()) "enter" else "loop"; private set
    private var i = 0; private var t = 0f; var pt = 0f; private set
    private var loops = 0; private var leaving: String? = null; var done = false; private set
    private var speed = 1f; private var cover = 0f
    val prio get() = a.prio

    fun frame(): Frame { val f = p.getValue(part)[i]; return if (cover > 0) f.copy(eyes = "blink") else f }

    /** Normal: carry on to the next seam, then exit. Urgent: cut to the exit now, behind a blink, at double speed. */
    fun leave(variant: String = "exit", urgent: Boolean = false) {
        if (done || part.startsWith("exit")) return
        val v = if (p.containsKey(variant)) variant else "exit"; leaving = v
        if (urgent) { cover = 1f; speed = 2f; go(v) }
        else if (part == "loop" && a.seam[i]) go(v)
    }

    private fun go(part: String) {
        this.part = part; i = 0; t = 0f; pt = 0f
        if (p.getValue(part).isEmpty()) { this.part = "loop"; i = p.getValue("loop").size - 1; done = true }
    }

    fun step(ticks0: Float) {
        if (done) return
        val ticks = ticks0 * speed; t += ticks; pt += ticks
        if (cover > 0) cover -= ticks
        while (!done && t >= p.getValue(part)[i].n) {
            t -= p.getValue(part)[i].n
            if (++i < p.getValue(part).size) {
                val l = leaving
                if (l != null && part == "loop" && a.seam[i]) go(l)
                continue
            }
            when (part) {
                "enter" -> go(leaving ?: "loop")
                "loop" -> { loops++; val rep = a.repeat
                    if (leaving != null || (rep != null && loops >= rep)) go(leaving ?: "exit") else go("loop") }
                else -> { i = p.getValue(part).size - 1; done = true }
            }
        }
    }
}

class Controller(private val s: Spec, walkIn: Boolean = false, private val rand: () -> Float = { Random.nextFloat() }) {
    private val m = s.map; private val tm = m.getJSONObject("timing"); private val rig = Rig(s)
    private val states = m.getJSONObject("states"); private val reactions = m.getJSONObject("reactions")
    var now = 0L; private set
    private var st = "idle"; private var link = true; private var linkError = false; private var act: String? = null
    private var perm: Long? = null; private var thinkAt = 0L; private var sleeping = false; private var look: Float? = null
    private val queue = mutableListOf<String>(); private var exitVariant: String? = null
    var cur: Player? = null; private set
    private var curOneshot = false; private var nextPastime = pastimeDelay(); private val lastStat = mutableMapOf<String, Long>()
    private var lastUptime = 0L

    init { if (walkIn) react(reactions.getString("walk_in")) }

    private fun pastimeDelay() = now + tm.getLong("pastime_min_ms") + (rand() * (tm.getLong("pastime_max_ms") - tm.getLong("pastime_min_ms"))).toLong()

    // ---- events ----
    fun state(v: String) { if (v == "thinking" && st != "thinking") thinkAt = now; st = v }
    fun activity(tool: String, args: JSONObject) {
        val t = m.getJSONObject("tools")
        val sub = when (tool) { "git" -> args.optString("action"); "run_project" -> args.optString("task"); else -> "" }
        act = t.optString("$tool:$sub").ifEmpty { t.optString(tool).ifEmpty { "desktop" } }
    }
    fun reply(tool: String?, detail: String, seconds: Double, spoken: Boolean) {
        act = null
        react(when {
            detail.isNotEmpty() -> reactions.getString("failed")
            tool != null -> reactions.getString(if (seconds >= tm.getDouble("celebrate_s")) "celebrate" else "done")
            spoken -> null
            else -> reactions.getString("chat")
        })
    }
    fun confirm() { perm = now; exitVariant = null }
    fun decided(ok: Boolean) { perm = null; exitVariant = if (ok) "exit_ok" else "exit_no" }
    fun alert() = react(reactions.getString("notify"))  // not notify(): Object.notify clashes
    fun poke() = react(reactions.getString("poke"))
    fun surprised() = react(reactions.getString("surprised"))
    fun sleep(on: Boolean) { sleeping = on }
    fun setLink(up: Boolean, error: Boolean = false) {
        if (!up && error && !linkError) react(reactions.getString("glitch"))
        else if (!up && link && !error) react(reactions.getString("go_away"))
        link = up; linkError = !up && error
    }
    /** each reaction at most once every 10 minutes */
    fun stats(cpu: Double?, battery: Int?, charging: Boolean) {
        fun due(k: String) = (now - (lastStat[k] ?: -1_000_000_000L) >= 600_000).also { if (it) lastStat[k] = now }
        if (cpu != null && cpu > 90 && due("cpu")) react(reactions.getString("overheated"))
        if (battery != null && battery < 15 && !charging && due("bat")) react(reactions.getString("low_battery"))
    }
    private fun react(id0: String?) {
        if (id0 == null) return
        // sometimes play a sibling instead (the original included)
        val id = m.optJSONObject("variety")?.optJSONArray(id0)?.let { v ->
            val all = listOf(id0) + List(v.length()) { v.getString(it) }
            all[(rand() * all.size).toInt().coerceAtMost(all.size - 1)]
        } ?: id0
        queue += id
        queue.sortByDescending { s.anims.getValue(it).prio }
        while (queue.size > 3) queue.removeAt(queue.size - 1)
    }

    // ---- resolution ----
    private fun base(): String {
        if (linkError) return states.getString("error")
        if (!link) return states.getString("offline")
        perm?.let { return m.getString(if (now - it > tm.getLong("permission_wait_ms")) "permission_wait" else "permission") }
        if (st == "listening" || st == "speaking") return states.getString(st)
        act?.let { return it }
        if (st == "thinking") return if (now - thinkAt > tm.getLong("think_hard_ms")) m.getString("think_hard") else states.getString("thinking")
        return states.getString(if (sleeping) "sleep" else "idle")
    }
    private fun want(): Pair<String, Boolean> {
        val b = base(); val bp = s.anims.getValue(b).prio
        while (queue.isNotEmpty() && s.anims.getValue(queue[0]).prio < bp) queue.removeAt(0)  // stale: something more important is on
        return if (queue.isNotEmpty()) queue[0] to true else b to false
    }
    private fun start(w: Pair<String, Boolean>) {
        if (w.second) queue.removeAt(0)
        cur = Player(s, w.first); curOneshot = w.second
    }
    fun tick(ms: Long) {
        now += ms
        if (base() != states.getString("idle")) nextPastime = pastimeDelay()
        else if (now >= nextPastime && queue.isEmpty()) {
            val p = m.getJSONArray("pastimes"); react(p.getString((rand() * p.length()).toInt().coerceAtMost(p.length() - 1))); nextPastime = pastimeDelay()
        }
        val w = want(); val c = cur
        if (c == null || c.done) start(w)
        else if (w.first != c.id || (w.second && !curOneshot)) {
            val wp = s.anims.getValue(w.first).prio; val cp = c.prio
            if (!curOneshot || wp > cp) {
                val isPerm = c.id == m.getString("permission") || c.id == m.getString("permission_wait")
                val ev = exitVariant
                c.leave(if (isPerm && perm == null && ev != null) ev else "exit", wp >= 90 && wp > cp)
            }
        }
        cur!!.step(ms / s.tick)
        if (cur!!.done) start(want())
    }
    /** Advance to the wall clock. Several views draw the same penguin, so this is safe to call from each. */
    fun tickTo(uptime: Long) {
        if (lastUptime != 0L && uptime > lastUptime) tick(min(250L, uptime - lastUptime))
        if (cur == null) tick(0)
        lastUptime = max(lastUptime, uptime)
    }
    val busy get() = st == "listening" || st == "thinking" || st == "speaking" || cur?.prio?.let { it > 20 } == true
    val asleep get() = sleeping && st == "idle"

    fun draw(c: Canvas, w: Int, h: Int, level: Float, time: Float) {
        val p = cur ?: return
        var f = p.frame()
        val lk = look
        if (lk != null && p.id == states.getString("idle") && f.eyes == "open") f = f.copy(lx = lk.roundToInt().toFloat())
        rig.draw(c, w, h, f, p.pt, p.a.glow, level, time)
    }
}

/** Draws the shared Controller; full rate only while it's busy (docs/UI.md budget), and it stops when hidden. */
class CoreView(ctx: Context, private val ctl: Controller) : View(ctx) {
    /** 0..1 audio level for listening/speaking; polled once per frame. */
    var level: () -> Float = { 0f }
    var reduceMotion = false
    private var lvl = 0f

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility); if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val up = SystemClock.uptimeMillis()
        ctl.tickTo(up)
        lvl += (level().coerceIn(0f, 1f) - lvl) * 0.3f
        ctl.draw(c, width, height, lvl, up / 1000f)
        when {
            reduceMotion -> postInvalidateDelayed(50)
            ctl.busy -> postInvalidateOnAnimation()
            ctl.asleep -> postInvalidateDelayed(66)
            else -> postInvalidateDelayed(40)
        }
    }
}
