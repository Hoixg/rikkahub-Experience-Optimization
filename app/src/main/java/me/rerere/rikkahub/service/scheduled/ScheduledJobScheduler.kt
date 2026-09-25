package me.rerere.rikkahub.service.scheduled

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity

/** Persists one delayed WorkManager request per scheduled job. */
class ScheduledJobScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun schedule(job: ScheduledJobEntity, runAtMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val request = request(job.id, scheduledAtMs = runAtMs, manual = false, catchup = false)
            .setInitialDelay((runAtMs - nowMs).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(regularWorkName(job.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleAfterCurrent(job: ScheduledJobEntity, runAtMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val request = request(job.id, scheduledAtMs = runAtMs, manual = false, catchup = false)
            .setInitialDelay((runAtMs - nowMs).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(regularWorkName(job.id), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun triggerNow(jobId: String) {
        val request = request(jobId, scheduledAtMs = System.currentTimeMillis(), manual = true, catchup = false).build()
        workManager.enqueueUniqueWork(manualWorkName(jobId), ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueCatchup(job: ScheduledJobEntity, scheduledAtMs: Long, delayMs: Long = 0L) {
        val request = request(job.id, scheduledAtMs, manual = false, catchup = true)
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(catchupWorkName(job.id, scheduledAtMs), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(jobId: String) {
        workManager.cancelUniqueWork(regularWorkName(jobId))
        workManager.cancelUniqueWork(manualWorkName(jobId))
        workManager.cancelAllWorkByTag(jobTag(jobId))
    }

    private fun request(jobId: String, scheduledAtMs: Long, manual: Boolean, catchup: Boolean) =
        OneTimeWorkRequestBuilder<ScheduledJobWorker>()
            .setInputData(workDataOf(
                ScheduledJobWorker.KEY_JOB_ID to jobId,
                ScheduledJobWorker.KEY_SCHEDULED_AT_MS to scheduledAtMs,
                ScheduledJobWorker.KEY_MANUAL to manual,
                ScheduledJobWorker.KEY_CATCHUP to catchup,
            ))
            .addTag(jobTag(jobId))

    private fun regularWorkName(jobId: String) = "scheduled_job_$jobId"
    private fun manualWorkName(jobId: String) = "scheduled_job_${jobId}_manual"
    private fun catchupWorkName(jobId: String, slotMs: Long) = "scheduled_job_${jobId}_catchup_$slotMs"

    companion object {
        fun jobTag(jobId: String) = "scheduled_job:$jobId"
    }
}
