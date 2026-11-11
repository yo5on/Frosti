package com.frosti.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** FROSTI design tokens (docs/UI.md) and the few view helpers every screen shares. */
object Ui {
    // Claude's own dark palette: warm charcoal, ivory text, clay accent
    val BG = Color.rgb(38, 38, 36)               // #262624
    val RAIL = Color.rgb(31, 30, 29)             // #1F1E1D sidebar
    val SURFACE = Color.rgb(48, 48, 46)          // #30302E raised: inputs, dialogs, pressed
    val LINE = Color.rgb(61, 61, 58)             // #3D3D3A
    val ACCENT = Color.rgb(217, 119, 87)        // Claude clay #D97757
    val ACCENT_DIM = Color.argb(140, 217, 119, 87)
    val AMBER = Color.rgb(232, 185, 49)          // warnings stay distinct from the clay accent
    val TEXT = Color.rgb(250, 249, 245)          // #FAF9F5 ivory
    val MUTED = Color.rgb(156, 154, 146)         // #9C9A92
    val DIM = Color.rgb(115, 114, 108)           // #73726C
    val SLEEP = Color.BLACK                      // idle screen stays AMOLED black: pixels off, no burn-in
    val THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val SERIF: Typeface = Typeface.SERIF
}

/** The spark: a starburst of 12 rounded rays of uneven length, drawn in one colour. */
fun drawSpark(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, rot: Float, color: Int) {
    p.color = color; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = r * 0.2f
    for (i in 0 until 12) {
        val a = rot + i * (Math.PI / 6).toFloat()
        val len = r * floatArrayOf(1f, 0.72f, 0.9f, 0.66f, 0.95f, 0.75f)[i % 6]
        c.drawLine(cx + kotlin.math.cos(a) * r * 0.12f, cy + kotlin.math.sin(a) * r * 0.12f,
            cx + kotlin.math.cos(a) * len, cy + kotlin.math.sin(a) * len, p)
    }
}

/** Header logo: the spark, slowly turning while FROSTI is busy. */
class SparkView(ctx: Context) : View(ctx) {
    var color = Ui.ACCENT; var spinning = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rot = 0f
    override fun onDraw(c: Canvas) {
        drawSpark(c, p, width / 2f, height / 2f, minOf(width, height) / 2f * 0.9f, rot, color)
        if (spinning) { rot += 0.06f; postInvalidateDelayed(33) }
    }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** Body text: light sans. */
fun Context.text(size: Float, color: Int = Ui.TEXT, face: Typeface = Ui.LIGHT) = TextView(this).apply {
    textSize = size; setTextColor(color); typeface = face; includeFontPadding = false
}

/** Uppercase mono label with wide tracking: section names, status, nav. */
fun Context.label(s: String, color: Int = Ui.MUTED, size: Float = 10.5f) = TextView(this).apply {
    text = s.uppercase(); textSize = size; setTextColor(color); typeface = Typeface.MONOSPACE
    letterSpacing = 0.15f; includeFontPadding = false
}

fun Context.vbox(pad: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
fun Context.hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

/** Pressed feedback without ripples or cards: shrink a touch, tint the background. */
fun View.pressable(onClick: () -> Unit): View {
    isClickable = true
    setOnTouchListener { v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }
    setOnClickListener { onClick() }
    return this
}

fun pill(color: Int, stroke: Int = 0, strokeColor: Int = 0, radius: Float = 999f) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

/** A small status dot. */
class Dot(ctx: Context, var color: Int = Ui.DIM) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    fun set(c: Int) { if (c != color) { color = c; invalidate() } }
    override fun onDraw(c: Canvas) {
        p.color = color; c.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, p)
    }
}

/** Thin meter: 3 dp track, accent fill that turns amber above 85 %. */
class Meter(ctx: Context) : View(ctx) {
    var value = -1f  // 0..1, negative = unknown
        set(v) { field = v; invalidate() }
    private val track = Paint().apply { color = Ui.LINE }
    private val fill = Paint()
    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), track)
        if (value < 0) return
        fill.color = if (value > 0.85f) Ui.AMBER else Ui.ACCENT
        c.drawRect(0f, 0f, width * value.coerceIn(0f, 1f), height.toFloat(), fill)
    }
}

/** Line icons drawn on Canvas (no image assets): mic, grid, pulse, timer, send. */
class Icon(ctx: Context, private val kind: String, var color: Int = Ui.ACCENT) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val o = android.graphics.RectF()
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat(); val x = (width - s) / 2; val y = (height - s) / 2
        fun X(f: Float) = x + f * s
        fun Y(f: Float) = y + f * s
        p.color = color; p.strokeWidth = s * 0.075f
        when (kind) {
            "mic" -> {
                o.set(X(0.38f), Y(0.14f), X(0.62f), Y(0.6f)); c.drawRoundRect(o, s * 0.12f, s * 0.12f, p)
                o.set(X(0.26f), Y(0.3f), X(0.74f), Y(0.72f)); c.drawArc(o, 0f, 180f, false, p)
                c.drawLine(X(0.5f), Y(0.72f), X(0.5f), Y(0.86f), p)
            }
            "grid" -> for (i in 0..1) for (j in 0..1) {
                o.set(X(0.18f + i * 0.36f), Y(0.18f + j * 0.36f), X(0.46f + i * 0.36f), Y(0.46f + j * 0.36f))
                c.drawRoundRect(o, s * 0.06f, s * 0.06f, p)
            }
            "pulse" -> {
                val pts = floatArrayOf(0.1f, 0.55f, 0.32f, 0.55f, 0.42f, 0.25f, 0.58f, 0.8f, 0.68f, 0.5f, 0.9f, 0.5f)
                for (i in 0 until pts.size - 2 step 2) c.drawLine(X(pts[i]), Y(pts[i + 1]), X(pts[i + 2]), Y(pts[i + 3]), p)
            }
            "timer" -> {
                c.drawCircle(X(0.5f), Y(0.55f), s * 0.32f, p)
                c.drawLine(X(0.5f), Y(0.55f), X(0.5f), Y(0.38f), p); c.drawLine(X(0.42f), Y(0.12f), X(0.58f), Y(0.12f), p)
            }
            "send" -> {
                c.drawLine(X(0.2f), Y(0.5f), X(0.8f), Y(0.5f), p)
                c.drawLine(X(0.58f), Y(0.28f), X(0.8f), Y(0.5f), p); c.drawLine(X(0.58f), Y(0.72f), X(0.8f), Y(0.5f), p)
            }
        }
    }
}
