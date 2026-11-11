package com.frosti.shell

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/** Device Owner receiver. Provisioned by `scripts/provision-phone.ps1 -OsMode` (dpm set-device-owner). */
class FrostiAdmin : DeviceAdminReceiver()

/**
 * OS mode: when FROSTI is Device Owner it takes the whole phone. Pinned as Home, lock task (no recents,
 * no notification shade, no system info), status bar and Samsung lock screen off. Home and the power menu stay.
 */
object OsMode {
    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)
    private fun admin(c: Context) = ComponentName(c, FrostiAdmin::class.java)

    fun active(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    /** Called on every resume, so apps installed since the last run can be opened from FROSTI too. */
    fun apply(a: Activity) {
        if (!active(a)) return
        val d = dpm(a); val who = admin(a)
        // shortcut: every installed package may run inside lock task, so open_app and links keep working; narrow to an allowlist if the phone is shared
        d.setLockTaskPackages(who, a.packageManager.getInstalledApplications(0).map { it.packageName }.toTypedArray())
        // OVERVIEW: otherwise Samsung drops the recents key before any app or accessibility service sees it; MainActivity uses it to flip orientation
        d.setLockTaskFeatures(who, DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        d.setStatusBarDisabled(who, true)
        d.setKeyguardDisabled(who, true)  // no-op if a PIN/pattern is set
        d.addPersistentPreferredActivity(who,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) },
            ComponentName(a, MainActivity::class.java))
        if (a.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) a.startLockTask()
    }

    /** Hands the phone back to plain Android and drops Device Owner, so the app can be uninstalled again. */
    fun release(a: Activity) {
        if (!active(a)) return
        val d = dpm(a); val who = admin(a)
        if (a.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) a.stopLockTask()
        d.setLockTaskPackages(who, emptyArray())
        d.setStatusBarDisabled(who, false)
        d.setKeyguardDisabled(who, false)
        d.clearPackagePersistentPreferredActivities(who, a.packageName)
        d.clearDeviceOwnerApp(a.packageName)
    }
}
