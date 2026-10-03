package me.rerere.rikkahub.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.utils.SystemPermissions

/** One durable alarm per task; inspired by Chevey339/kelivo (AGPL-3.0).
 * The database decides whether a delivered occurrence is still valid.
 */
object ScheduledTaskScheduler {
    const val TAG = "scheduled_task_v2" // Tag of persisted work from earlier releases.
    const val ACTION_FIRE = "me.rerere.rikkahub.action.SCHEDULED_TASK_FIRE"
    const val EXTRA_REVISION = "revision"
    const val EXTRA_SCHEDULED_AT = "scheduled_at"

    const val ACTION_RESUME = "me.rerere.rikkahub.action.SCHEDULED_TASK_RESUME"
    suspend fun scheduleResume(context: Context, waiting: Boolean) = withContext(Dispatchers.IO) {
        val intent = Intent(context, ScheduledTaskClockReceiver::class.java).setAction(ACTION_RESUME)
        val pending = PendingIntent.getBroadcast(context, 2100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending)
        if (waiting && SystemPermissions.canScheduleExactAlarms(context)) runCatching {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 900_000L, pending)
        }.onFailure { android.util.Log.e(TAG, "Unable to schedule waiting-task check", it) }
    }

    private fun pendingIntent(context: Context, id: String, revision: String = "", at: Long = 0L,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? {
        val intent = Intent(context, ScheduledTaskClockReceiver::class.java).setAction(ACTION_FIRE)
            .setData(Uri.Builder().scheme("rikkaplus").authority("scheduled-task").appendPath(id).build())
            .putExtra(EXTRA_REVISION, revision).putExtra(EXTRA_SCHEDULED_AT, at)
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    suspend fun enqueue(context: Context, task: ScheduledTaskEntity) = withContext(Dispatchers.IO) {
        val at = task.nextRunAt ?: return@withContext
        if (!task.enabled || !SystemPermissions.canScheduleExactAlarms(context)) return@withContext
        // Special access can be revoked between the check and the binder call. Keep saved results/configuration.
        runCatching {
            context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at, requireNotNull(pendingIntent(context, task.id, task.revision, at)),
            )
        }.onFailure { android.util.Log.e(TAG, "Unable to schedule exact alarm", it) }
    }

    suspend fun cancelPending(context: Context, id: String) = withContext(Dispatchers.IO) {
        val pending = pendingIntent(context, id, flags = PendingIntent.FLAG_NO_CREATE) ?: return@withContext
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    suspend fun cancelPending(context: Context, task: ScheduledTaskEntity) = cancelPending(context, task.id)

    suspend fun cancelAll(context: Context, id: String) = cancelPending(context, id)

    /** Old WorkManager requests must never start a second execution after an app upgrade. */
    suspend fun cancelLegacyWork(context: Context, ids: List<String>) = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(TAG).result.get()
        ids.forEach { manager.cancelUniqueWork("${TAG}_${it}_manual").result.get() }
    }
}
