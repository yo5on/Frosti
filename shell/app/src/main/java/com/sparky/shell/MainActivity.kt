package com.sparky.shell

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject

/** SPARKY's home screen: orb, what it heard, what it said, push-to-talk, typed commands, app drawer. */
class MainActivity : Activity() {
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var reply: TextView
    private lateinit var detail: TextView

    private val cyan = Color.rgb(0, 229, 255)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        saveProvisioning(intent)
        setContentView(build())
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        else SparkyService.start(this)
        intent.getStringExtra("confirm")?.let { showConfirm(JSONObject(it)) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        SparkyService.start(this)  // starts even if denied: typed commands still work
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (saveProvisioning(i)) SparkyService.instance?.reconnect()
        i.getStringExtra("confirm")?.let { showConfirm(JSONObject(it)) }
    }

    override fun onResume() {
        super.onResume()
        SparkyService.ui = ::onEvent
        val s = SparkyService.instance
        orb.state = s?.lastState ?: "offline"
        status.text = if (s?.connected == true) "LINKED" else "OFFLINE"
    }

    override fun onPause() {
        SparkyService.ui = null
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* home screen: back does nothing */ }

    /** `adb shell am start -n com.sparky.shell/.MainActivity --es token X [--es host H]` (scripts/provision-phone.ps1) */
    private fun saveProvisioning(i: Intent): Boolean {
        val token = i.getStringExtra("token") ?: return false
        getSharedPreferences(SparkyService.PREFS, MODE_PRIVATE).edit().putString("token", token)
            .putString("host", i.getStringExtra("host") ?: "127.0.0.1").apply()
        return true
    }

    private fun onEvent(m: JSONObject) {
        when (m.optString("type")) {
            "conn" -> {
                status.text = if (m.optBoolean("ok")) "LINKED" else "OFFLINE · ${m.optString("why")}"
                if (!m.optBoolean("ok")) orb.state = "offline"
            }
            "state" -> orb.state = m.optString("state")
            "heard" -> heard.text = "“${m.optString("text")}”"
            "reply" -> {
                reply.text = m.optString("text")
                detail.text = m.optString("detail") + "\n${m.optString("path")} · ${m.optDouble("seconds")}s"
            }
            "notify" -> reply.text = m.optString("text")
            "confirm" -> showConfirm(m)
        }
    }

    private fun showConfirm(m: JSONObject) {
        val id = m.getInt("id")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (m.optInt("tier") >= 3) "Confirm — irreversible" else "Confirm")
            .setMessage(m.optString("summary"))
            .setPositiveButton("Do it") { _, _ -> SparkyService.instance?.answerConfirm(id, true) }
            .setNegativeButton("Cancel") { _, _ -> SparkyService.instance?.answerConfirm(id, false) }
            .setOnCancelListener { SparkyService.instance?.answerConfirm(id, false) }
            .show()
    }

    private fun text(size: Float, color: Int = Color.WHITE, mono: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(32, 24, 32, 24)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        status = text(12f, cyan, mono = true).apply { gravity = Gravity.START; text = "OFFLINE" }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextClock(this).apply { format12Hour = "HH:mm"; format24Hour = "HH:mm"; textSize = 14f; setTextColor(cyan) })
        root.addView(top)

        orb = OrbView(this)
        orb.setOnClickListener { send(JSONObject().put("type", "listen")) }
        root.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f))

        heard = text(16f, Color.rgb(150, 150, 160))
        reply = text(22f).apply { text = "Say \"Hey Sparky\" or tap the orb." }
        detail = text(11f, Color.rgb(110, 110, 120), mono = true).apply { maxLines = 8 }
        root.addView(heard); root.addView(reply); root.addView(detail)

        val input = EditText(this).apply {
            hint = "Type a command"; setHintTextColor(Color.DKGRAY); setTextColor(Color.WHITE); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { v, action, ev ->
                if (action == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    val t = v.text.toString().trim()
                    if (t.isNotEmpty()) { send(JSONObject().put("type", "text").put("text", t)); heard.text = "“$t”"; v.text = "" }
                    true
                } else false
            }
        }
        root.addView(input)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val talk = Button(this).apply { text = "Hold to talk" }
        talk.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> send(JSONObject().put("type", "ptt").put("on", true))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { send(JSONObject().put("type", "ptt").put("on", false)); v.performClick() }
            }
            true
        }
        val apps = Button(this).apply { text = "Apps"; setOnClickListener { showApps() } }
        row.addView(talk, LinearLayout.LayoutParams(0, -2, 2f))
        row.addView(apps, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        return root
    }

    private fun send(m: JSONObject) {
        if (SparkyService.instance?.send(m) != true) reply.text = "Not linked to SPARKY Core."
    }

    private fun showApps() {
        val list = Actions(this).apps()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setItems(list.map { it.label }.toTypedArray()) { _, i -> startActivity(list[i].intent) }
            .show()
    }
}
