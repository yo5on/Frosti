package com.sparky.shell

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import org.json.JSONObject

/** Phone tools the Core forwards here. Each returns the sentence SPARKY speaks. */
class Actions(private val ctx: Context) {

    fun run(name: String, args: JSONObject): String = try {
        when (name) {
            "open_app" -> openApp(args.optString("name"))
            "get_status" -> status(args.optString("metric"))
            "set_brightness" -> brightness(args.optInt("level"))
            "set_volume" -> volume(args.optInt("level"))
            else -> "The phone can't do $name yet."
        }
    } catch (e: Exception) {
        "That failed on the phone: ${e.message}"
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
}
