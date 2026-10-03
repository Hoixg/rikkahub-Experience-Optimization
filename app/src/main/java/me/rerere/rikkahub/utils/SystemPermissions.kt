package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper

/** Special permissions share the existing local-tools permission checks. */
object SystemPermissions {
    fun isNotificationEnabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun isIgnoringBatteryOptimizations(context: Context) = PermissionHelper.ignoresBatteryOptimizations(context)
    fun canScheduleExactAlarms(context: Context) = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    @RequiresApi(31)
    fun exactAlarmSettingsIntent(context: Context) = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        "package:${context.packageName}".toUri())
    fun notificationSettingsIntent(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun appDetailsIntent(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    fun autoStartIntent(context: Context): Intent {
        val candidates = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi" -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
            "huawei", "honor" -> listOf("com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            "oppo", "oneplus", "realme" -> listOf("com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity", "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity")
            "vivo", "iqoo" -> listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            "samsung" -> listOf("com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity")
            else -> emptyList()
        }
        return candidates.map { (pkg, cls) -> Intent().setComponent(android.content.ComponentName(pkg, cls)) }
            .firstOrNull { it.resolveActivity(context.packageManager) != null } ?: appDetailsIntent(context)
    }

    fun batteryOptimizationIntent(context: Context): Intent {
        val request = PermissionHelper.requestIgnoreBatteryOptimizationsIntent(context)
        return if (request.resolveActivity(context.packageManager) != null) request else PermissionHelper.batteryOptimizationsListIntent()
    }

    fun openSettings(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
