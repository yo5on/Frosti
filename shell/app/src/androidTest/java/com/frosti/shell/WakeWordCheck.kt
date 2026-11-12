package com.frosti.shell

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock

/** Runs the real ARM wake detector on the phone; no test framework dependency. */
class WakeWordCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        try {
            val timer = Actions.timerIntent(5.0, "tea")
            check(timer.action == android.provider.AlarmClock.ACTION_SET_TIMER)
            check(timer.getIntExtra(android.provider.AlarmClock.EXTRA_LENGTH, -1) == 300)
            check(timer.getStringExtra(android.provider.AlarmClock.EXTRA_MESSAGE) == "tea")
            check(timer.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false))
            check(timer.resolveActivity(targetContext.packageManager) != null) { "No timer-capable Clock app" }
            for (minutes in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
                check(runCatching { Actions.timerIntent(minutes, "") }.exceptionOrNull() is IllegalArgumentException)
            }
            results.putString("timers", "Duration, label, Clock handler, and invalid input checks passed")
            val commands = targetContext.assets.open("wake/commands.txt").bufferedReader().readLines()
                .filter { it.isNotBlank() }.joinToString("/")
            val detector = WakeWord(targetContext.assets, commands)
            try {
                for ((name, expected) in listOf("frosti" to true, "hey-frosti" to true,
                        "unrelated" to false, "other-name" to false)) {
                    val pcm = context.assets.open("$name.pcm").use { it.readBytes() }
                    detector.reset()
                    var detected = false
                    val began = SystemClock.elapsedRealtime()
                    for (offset in pcm.indices step 3200) {
                        val chunk = pcm.copyOfRange(offset, minOf(offset + 3200, pcm.size))
                        detected = detector.feed(chunk, chunk.size) || detected
                    }
                    val rtf = (SystemClock.elapsedRealtime() - began) / (pcm.size / 32.0)
                    check(detected == expected) { "$name: detected=$detected, expected=$expected" }
                    check(rtf < 1.0) { "Detector cannot keep up with microphone: RTF=$rtf" }
                    results.putString(name, "detected=$detected, RTF=$rtf")
                }
                // Satellite mode: command phrases spotted on the phone when the laptop is unreachable.
                for ((name, expected) in listOf("cmd-battery" to "get_status=battery", "cmd-youtube" to "open_app=youtube",
                        "cmd-volume" to "volume_step=1", "cmd-timer-five" to "set_timer=5",
                        "cmd-timer-twenty-five" to "set_timer=25", "other-name" to null)) {
                    val pcm = context.assets.open("$name.pcm").use { it.readBytes() }
                    detector.reset()
                    var heard: String? = null
                    for (offset in pcm.indices step 3200) {
                        val chunk = pcm.copyOfRange(offset, minOf(offset + 3200, pcm.size))
                        heard = heard ?: detector.command(chunk, chunk.size)
                    }
                    check(heard == expected) { "$name: heard=$heard, expected=$expected" }
                    results.putString(name, "heard=$heard")
                }
            } finally { detector.release() }
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("failure", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
