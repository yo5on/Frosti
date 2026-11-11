package com.frosti.shell

import android.app.ActivityManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject

/** Phone tools the Core forwards here. Each returns the sentence FROSTI speaks. */
class Actions(private val ctx: Context) {

    fun run(name: String, args: JSONObject): String = try {
        when (name) {
            "open_app" -> openApp(args.optString("name"))
            "get_status" -> status(args.optString("metric"))
            "set_brightness" -> brightness(args.optInt("level"))
            "set_volume" -> volume(args.optInt("level"))
            "play_music" -> playMusic(args.optString("query"))
            "media_control" -> media(args.optString("action"))
            else -> "The phone can't do $name yet."
        }
    } catch (e: Exception) {
        "That failed on the phone: ${e.message}"
    }

    /** Satellite mode (laptop unreachable): run a spotted command label like "set_volume=0" or "open_app=youtube". */
    fun satellite(label: String): String {
        val (tool, arg) = label.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (tool) {
            "time" -> "It's ${android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date())}."
            "volume_step" -> {
                ctx.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (arg == "1") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Okay."
            }
            else -> {
                val key = mapOf("get_status" to "metric", "set_volume" to "level", "set_brightness" to "level",
                    "media_control" to "action", "open_app" to "name")[tool] ?: return "I can't do that offline."
                run(tool, JSONObject().put(key, arg))
            }
        }
    }

    data class App(val label: String, val intent: Intent)

    fun apps(): List<App> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map {
                App(it.loadLabel(pm).toString(), Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(it.activityInfo.packageName, it.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun openApp(name: String): String {
        val n = name.lowercase().trim()
        val all = apps()
        val app = all.firstOrNull { it.label.lowercase() == n } ?: all.firstOrNull { it.label.lowercase().contains(n) }
            ?: all.firstOrNull { n.contains(it.label.lowercase()) }
            ?: return "I couldn't find $name on the phone."
        ctx.startActivity(app.intent)
        return "Opening ${app.label}."
    }

    private fun status(metric: String): String {
        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return when (metric) {
            "battery" -> {
                val bm = ctx.getSystemService(BatteryManager::class.java)
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                "Phone battery is at $pct percent${if (charging) ", charging" else ""}."
            }
            "ram" -> {
                val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                "Phone has %.1f gigabytes of memory free out of %.1f.".format(mi.availMem / 1e9, mi.totalMem / 1e9)
            }
            "storage" -> {
                val s = StatFs(Environment.getDataDirectory().path)
                "Phone has %.0f gigabytes free.".format(s.availableBytes / 1e9)
            }
            "temperature" -> {
                val t = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
                "Phone battery temperature is %.0f degrees.".format(t)
            }
            else -> "I can't read the phone's $metric on Android 9 without root."
        }
    }

    private fun brightness(level: Int): String {
        if (!Settings.System.canWrite(ctx)) return "I need permission to change system settings. Run the provisioning script."
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (level.coerceIn(0, 100) * 255 / 100).coerceAtLeast(1))
        return "Brightness set to ${level.coerceIn(0, 100)} percent."
    }

    private fun volume(level: Int): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, 100) * max / 100, AudioManager.FLAG_SHOW_UI)
        return if (level <= 0) "Muted." else "Volume set to ${level.coerceIn(0, 100)} percent."
    }

    /** Spotify, then YouTube Music, then any player that handles play-from-search; else a YouTube search. */
    private fun playMusic(query: String): String {
        val pm = ctx.packageManager
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val players = listOf("com.spotify.music" to "Spotify", "com.google.android.apps.youtube.music" to "YouTube Music")
        for ((pkg, label) in players) {
            val i = Intent(search).setPackage(pkg)
            if (i.resolveActivity(pm) != null) { ctx.startActivity(i); return "Playing $query on $label." }
        }
        if (search.resolveActivity(pm) != null) { ctx.startActivity(search); return "Playing $query." }
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "No music app here, so I searched YouTube for $query."
    }

    private fun media(action: String): String {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "I can't do $action on the music."
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return when (action) { "next" -> "Next track."; "previous" -> "Previous track."; else -> "Okay." }
    }
}
