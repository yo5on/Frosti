package com.sparky.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The core orb: colour and motion show what SPARKY is doing. Plain Canvas, cheap on a Mali-T830. */
class OrbView(ctx: Context) : View(ctx) {
    var state = "offline"
        set(v) { field = v; invalidate() }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f }
    private val start = System.nanoTime()

    private fun color() = when (state) {
        "listening" -> Color.rgb(0, 229, 255)
        "thinking" -> Color.rgb(255, 176, 32)
        "speaking" -> Color.rgb(170, 245, 255)
        "offline" -> Color.rgb(90, 90, 100)
        else -> Color.rgb(0, 140, 170)  // idle
    }

    override fun onDraw(c: Canvas) {
        val t = (System.nanoTime() - start) / 1e9f
        val cx = width / 2f; val cy = height / 2f
        val base = minOf(width, height) * 0.30f
        val speed = when (state) { "listening" -> 4f; "speaking" -> 6f; "thinking" -> 2f; else -> 1f }
        val r = base * (1f + 0.06f * sin(t * speed))
        val col = color()
        glow.shader = RadialGradient(cx, cy, r * 1.6f, intArrayOf(col, Color.argb(90, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.6f, glow)
        ring.color = col
        if (state == "thinking") {  // orbiting dots
            for (i in 0 until 3) {
                val a = t * 3f + i * 2f * PI.toFloat() / 3f
                c.drawCircle(cx + cos(a) * r * 1.25f, cy + sin(a) * r * 1.25f, 10f, ring)
            }
        }
        c.drawCircle(cx, cy, r * 1.15f, ring)
        postInvalidateOnAnimation()
    }
}
