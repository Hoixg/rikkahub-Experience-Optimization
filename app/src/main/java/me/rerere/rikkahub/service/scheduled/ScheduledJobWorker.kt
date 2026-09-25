package me.rerere.rikkahub.service.scheduled

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

class ScheduledJobWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    companion object {
        const val KEY_JOB_ID = "scheduled_job_id"
        const val KEY_SCHEDULED_AT_MS = "scheduled_at_ms"
        const val KEY_MANUAL = "manual"
        const val KEY_CATCHUP = "catchup"
        private const val TAG = "ScheduledJobWorker"
    }

    private val manager: ScheduledJobManager by inject()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val scheduledAtMs = inputData.getLong(KEY_SCHEDULED_AT_MS, System.currentTimeMillis())
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val catchup = inputData.getBoolean(KEY_CATCHUP, false)
        try {
            setForeground(foregroundInfo(jobId))
        } catch (error: Exception) {
            Log.w(TAG, "Unable to start foreground notification", error)
        }
        return try {
            manager.execute(jobId, scheduledAtMs, manual, catchup)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Worker failed for $jobId", error)
            Result.failure()
        }
    }

    private fun foregroundInfo(jobId: String): ForegroundInfo {
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            jobId.hashCode(),
            Intent(applicationContext, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("openScheduledTasks", true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            SCHEDULED_JOB_PROGRESS_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(applicationContext.getString(R.string.scheduled_task_running))
            .setContentText(applicationContext.getString(R.string.scheduled_task_running_detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
        val id = jobId.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(id, notification)
    }
}
