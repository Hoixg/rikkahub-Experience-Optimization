package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.SystemPermissions
import org.koin.android.ext.android.inject

/** Opt-in idle-agent service. The wake lock expires even if the renewal coroutine stops. */
class KeepAliveService : Service() {
    companion object {
        const val CHANNEL_ID = "scheduled_keep_alive"
        private const val NOTIFICATION_ID = 2003
        private const val ACTION_STOP = "me.rerere.rikkahub.KEEP_ALIVE_STOP"
        private const val LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val RENEW_INTERVAL_MS = 20 * 60 * 1000L
        private val runningState = MutableStateFlow(false)
        val running: kotlinx.coroutines.flow.StateFlow<Boolean> = runningState
        fun start(context: Context) = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
        }.onFailure { Log.e("KeepAliveService", "Unable to start", it) }.isSuccess
        fun stop(context: Context) { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }

    private val settingsStore: SettingsStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lock: PowerManager.WakeLock? = null
    private var renewal: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { settingsStore.update { it.copy(keepAwakeEnabled = false) }; stopSelf() }
            return START_NOT_STICKY
        }
        if (!enterForeground()) return START_NOT_STICKY
        renewal?.cancel()
        renewal = scope.launch {
            var renewAt = 0L
            try {
                settingsStore.settingsFlowRaw.first()
                lock = lock ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RikkaPlus::KeepAlive")
                .apply { setReferenceCounted(false) }
                while (isActive) {
                    val enabled = settingsStore.settingsFlowRaw.first().keepAwakeEnabled
                    if (!enabled || !SystemPermissions.isNotificationEnabled(this@KeepAliveService) ||
                        !SystemPermissions.isIgnoringBatteryOptimizations(this@KeepAliveService)) {
                        stopSelf()
                        return@launch
                    }
                    if (android.os.SystemClock.elapsedRealtime() >= renewAt || lock?.isHeld != true) {
                        runCatching { if (lock?.isHeld == true) lock?.release() }
                        lock?.acquire(LOCK_TIMEOUT_MS)
                        renewAt = android.os.SystemClock.elapsedRealtime() + RENEW_INTERVAL_MS
                    }
                    delay(30_000L)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e("KeepAliveService", "Wake lock failed", e); stopSelf() }
        }
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, RouteActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, NOTIFICATION_ID, Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle("RikkaPlus 后台保活").setContentText("后台保活已开启，会增加耗电")
            .setOngoing(true).setSilent(true).setContentIntent(open).addAction(0, "关闭保活", stop).build()
        if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
        runningState.value = true
        true
    } catch (e: Exception) {
        Log.e("KeepAliveService", "Unable to enter foreground", e)
        stopSelf()
        false
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (lock?.isHeld == true) lock?.release() }
        lock = null
        runningState.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
