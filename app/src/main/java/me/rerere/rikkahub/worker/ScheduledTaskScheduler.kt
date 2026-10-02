package me.rerere.rikkahub.worker

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import java.util.concurrent.TimeUnit

/** Each scheduled occurrence has its own identity; rescheduling never replaces a running worker. */
object ScheduledTaskScheduler {
    const val TAG = "scheduled_task_v2"
    fun workName(task: ScheduledTaskEntity) = "scheduled_task_v2_${task.id}_${task.revision}_${task.nextRunAt}"
    suspend fun enqueue(context: Context, task: ScheduledTaskEntity) = withContext(Dispatchers.IO) {
        if (!task.enabled || task.nextRunAt == null) return@withContext
        val data = workDataOf("task_id" to task.id, "revision" to task.revision,
            "scheduled_at" to task.nextRunAt)
        val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInputData(data).addTag(TAG).addTag("$TAG:${task.id}")
            .setInitialDelay((task.nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(task), ExistingWorkPolicy.KEEP, request,
        ).result.get()
    }

    /** Cancel requests queued by builds that still exposed manual execution. */
    suspend fun cancelLegacyManualRequest(context: Context, id: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork("scheduled_task_v2_${id}_manual").result.get()
    }

    suspend fun cancelPending(context: Context, task: ScheduledTaskEntity) = withContext(Dispatchers.IO) {
        if (!task.activeManual && task.activeScheduledAt == task.nextRunAt && task.activeRunId != null) return@withContext
        WorkManager.getInstance(context).cancelUniqueWork(workName(task)).result.get()
    }

    suspend fun cancelAll(context: Context, id: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelAllWorkByTag("$TAG:$id").result.get()
    }
}
