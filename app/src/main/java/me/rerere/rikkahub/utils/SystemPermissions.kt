package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper

/** Special permissions share the existing local-tools permission checks. */
object SystemPermissions {
    fun isNotificationEnabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun isIgnoringBatteryOptimizations(context: Context) = PermissionHelper.ignoresBatteryOptimizations(context)
    fun notificationSettingsIntent(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

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
