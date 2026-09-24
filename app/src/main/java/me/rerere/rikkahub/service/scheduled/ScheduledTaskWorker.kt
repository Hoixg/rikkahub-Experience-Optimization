package me.rerere.rikkahub.service.scheduled

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ScheduledTaskWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    companion object {
        const val TASK_ID = "taskId"
    }

    private val manager: ScheduledTaskManager by inject()

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(TASK_ID) ?: return Result.failure()
        return try {
            try {
                setForeground(foregroundInfo(taskId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Inexact alarms may not grant background foreground-service start access.
                // WorkManager can still execute the job within its normal runtime budget.
                Log.w("ScheduledTaskWorker", "Foreground start unavailable", e)
            }
            manager.executeQueued(taskId)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            manager.failQueued(taskId, e.message)
            Result.failure()
        }
    }

    private fun foregroundInfo(taskId: String): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(
            applicationContext, SCHEDULED_TASK_PROGRESS_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(applicationContext.getString(R.string.scheduled_task_running))
            .setContentText(applicationContext.getString(R.string.scheduled_task_running_detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(
                applicationContext, taskId.hashCode(),
                Intent(applicationContext, RouteActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("openScheduledTasks", true)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()
        val id = taskId.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }
}
