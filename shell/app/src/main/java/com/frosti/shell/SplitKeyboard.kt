package com.frosti.shell

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * FROSTI's own keyboard. Landscape: QWERTY split into a left and a right block for the thumbs, with Frosti
 * standing in the see-through gap between them. Portrait: the same keys joined into one full-width block.
 * Enable: adb shell ime enable com.frosti.shell/.SplitKeyboard && adb shell ime set com.frosti.shell/.SplitKeyboard
 */
class SplitKeyboard : InputMethodService() {
    // each row is (left half, right half); ⇧ shift, ⌫ delete, ⏎ enter, ␣ space, ⇄ letters/symbols
    private val letters = listOf(
        "qwert" to "yuiop", "asdfg" to "hjkl'", "⇧zxcv" to "bnm⌫", "⇄,␣" to "␣.⏎")
    private val symbols = listOf(
        "12345" to "67890", "@#$%&" to "*-+()", "!\"/:;" to "?_=⌫", "⇄,␣" to "␣.⏎")
    private var shift = false; private var sym = false
    private val repeat = Handler(Looper.getMainLooper())
    private val frosti by lazy { Controller(Spec.load(this)) }  // own penguin: the IME has no MainActivity to share with

    override fun onCreateInputView(): View = build()
    override fun onEvaluateFullscreenMode() = false  // never cover the app with the extract editor

    companion object { @Volatile var open = false }  // MainActivity holds off the sleep screen while we're up

    override fun onFinishInputView(finishing: Boolean) { open = false; super.onFinishInputView(finishing) }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        open = true; super.onStartInputView(info, restarting); shift = false; sym = false; setInputView(build())
    }

    private fun build(): View {
        val split = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // the keyboard takes every touch itself and gives it to the nearest key of the block it landed in
        val root = object : LinearLayout(this) {
            override fun onInterceptTouchEvent(e: MotionEvent) = true
            override fun onTouchEvent(e: MotionEvent) = touch(this, e)
        }.apply { setBackgroundColor(Ui.BG) }  // the IME window can't show the app through it; match the app instead
        val rows = if (sym) symbols else letters
        fun half(pick: (Pair<String, String>) -> String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(Ui.RAIL, radius = dpf(if (split) 16f else 0f)); setPadding(dp(4), dp(6), dp(4), dp(6))
            for (r in rows) addView(LinearLayout(this@SplitKeyboard).apply { for (c in pick(r)) addView(key(c), lp(c)) },
                LinearLayout.LayoutParams(-1, dp(if (split) 52 else 46)))
        }
        if (split) {
            // two separate blocks, each 40% wide; Frosti stands in the middle, which types nothing
            root.addView(half { it.first }, LinearLayout.LayoutParams(0, -2, 4f))
            root.addView(CoreView(this, frosti), LinearLayout.LayoutParams(0, -1, 2f))
            root.addView(half { it.second }, LinearLayout.LayoutParams(0, -2, 4f))
        } else {
            // portrait: one row per pair, the two ␣ keys merge into one wide space bar
            root.addView(half { (it.first + it.second).replace("␣␣", "␣") }, LinearLayout.LayoutParams(-1, -2))
        }
        return root
    }

    // no margins: the gap is drawn inside each key, so every pixel belongs to a key and no tap lands in a dead zone
    private fun lp(c: Char) = LinearLayout.LayoutParams(0, -1, if (c == '␣') 3f else if (c in "⇧⌫⏎⇄") 1.5f else 1f)
    private fun keyBg(color: Int) = android.graphics.drawable.InsetDrawable(pill(color, radius = dpf(8f)), dp(3))

    private fun key(c: Char): View = TextView(this).apply {
        text = when (c) {
            '␣' -> ""; '⇄' -> if (sym) "ABC" else "?123"
            '⏎' -> enterLabel()
            else -> if (shift && c.isLetter()) c.uppercase() else c.toString()
        }
        val special = c in "⇧⌫⏎⇄"
        setTextColor(if (c == '⇧' && shift || c == '⏎') Ui.ACCENT else Ui.TEXT)
        textSize = if (special && c != '⇧' && c != '⌫') 13f else 19f; gravity = Gravity.CENTER; typeface = Ui.LIGHT
        background = keyBg(if (special) Ui.BG else Ui.SURFACE)
        contentDescription = when (c) { '⇧' -> "Shift"; '⌫' -> "Delete"; '⏎' -> "Enter"; '␣' -> "Space"; '⇄' -> "Switch symbols"; else -> null }
        tag = c
        setOnClickListener { press(c) }  // accessibility clicks; real touches go through touch()
    }

    private val held = HashMap<Int, View>()  // pointer id -> key it pressed; two thumbs can be down at once

    private fun touch(root: ViewGroup, e: MotionEvent): Boolean {
        val i = e.actionIndex; val id = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i)
                // only the block under the finger; a tap in the empty middle types nothing
                val block = (0 until root.childCount).map(root::getChildAt).firstOrNull { it is ViewGroup && x >= it.left && x < it.right }
                val k = nearest(root, block as? ViewGroup ?: return true, x, e.getY(i)) ?: return true
                val c = k.tag as Char
                held[id] = k; k.background = keyBg(Ui.LINE)
                k.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                press(c)
                if (c == '⌫') repeat.postDelayed(object : Runnable { override fun run() { press(c); repeat.postDelayed(this, 60) } }, 400)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> held.remove(id)?.let { release(it) }
            MotionEvent.ACTION_CANCEL -> { held.values.forEach(::release); held.clear() }
        }
        return true
    }

    private fun release(k: View) {
        val c = k.tag as Char
        if (c == '⌫') repeat.removeCallbacksAndMessages(null)
        k.background = keyBg(if (c in "⇧⌫⏎⇄") Ui.BG else Ui.SURFACE)
    }

    /** The key under (x, y), or else the closest one by edge distance. */
    private fun nearest(root: ViewGroup, block: ViewGroup, x: Float, y: Float): View? {
        val r = android.graphics.Rect(); var best: View? = null; var bestD = Float.MAX_VALUE
        fun walk(g: ViewGroup) {
            for (j in 0 until g.childCount) {
                val v = g.getChildAt(j)
                if (v.tag is Char) {
                    v.getDrawingRect(r); root.offsetDescendantRectToMyCoords(v, r)
                    val dx = maxOf(r.left - x, 0f, x - r.right); val dy = maxOf(r.top - y, 0f, y - r.bottom)
                    if (dx * dx + dy * dy < bestD) { bestD = dx * dx + dy * dy; best = v }
                } else if (v is ViewGroup) walk(v)
            }
        }
        walk(block); return best
    }

    private fun enterLabel() = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
        EditorInfo.IME_ACTION_SEND -> "send"; EditorInfo.IME_ACTION_GO -> "go"; EditorInfo.IME_ACTION_SEARCH -> "search"
        EditorInfo.IME_ACTION_DONE -> "done"; EditorInfo.IME_ACTION_NEXT -> "next"; else -> "⏎"
    }

    private fun press(c: Char) {
        val ic = currentInputConnection ?: return
        when (c) {
            '⇧' -> { shift = !shift; setInputView(build()) }
            '⇄' -> { sym = !sym; shift = false; setInputView(build()) }
            '⌫' -> if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
            '⏎' -> {
                val o = currentInputEditorInfo?.imeOptions ?: 0
                val action = o and EditorInfo.IME_MASK_ACTION
                if (o and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && action > EditorInfo.IME_ACTION_NONE) ic.performEditorAction(action)
                else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            '␣' -> ic.commitText(" ", 1)
            else -> {
                ic.commitText(if (shift) c.uppercase() else c.toString(), 1)
                if (shift) { shift = false; setInputView(build()) }  // one-shot shift
            }
        }
    }
}
