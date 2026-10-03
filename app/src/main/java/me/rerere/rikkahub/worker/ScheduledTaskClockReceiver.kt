package me.rerere.rikkahub.worker

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunStatus
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import me.rerere.rikkahub.service.ScheduledTaskExecutionService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/** Alarm delivery only starts the foreground owner; generation never runs in onReceive. */
class ScheduledTaskClockReceiver : BroadcastReceiver(), KoinComponent {
    private val scope: AppScope by inject()
    private val repository: ScheduledTaskRepository by inject()
    private val events: AppEventBus by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ScheduledTaskScheduler.ACTION_RESUME) {
            val pending = goAsync()
            scope.launch {
                try { repository.initialize(); repository.requestDispatch(); repository.refreshResumeAlarm() }
                finally { pending.finish() }
            }
            return
        }
        if (intent.action == ScheduledTaskScheduler.ACTION_FIRE) {
            val taskId = intent.data?.lastPathSegment ?: return
            val revision = intent.getStringExtra(ScheduledTaskScheduler.EXTRA_REVISION) ?: return
            val dueAt = intent.getLongExtra(ScheduledTaskScheduler.EXTRA_SCHEDULED_AT, 0L)
            if (dueAt <= 0L) return
            try {
                ContextCompat.startForegroundService(context, ScheduledTaskExecutionService.intent(context, taskId, revision, dueAt))
            } catch (e: Exception) {
                val pending = goAsync()
                scope.launch {
                    try {
                        val run = repository.claim(taskId, revision, dueAt, Uuid.random().toString(), Uuid.random().toString())
                        if (run != null && repository.finish(run, ScheduledTaskRunStatus.FAILED, "无法启动前台服务")) {
                            events.emit(AppEvent.ScheduledTaskEnded(null, run.name, ScheduledTaskRunStatus.FAILED.name, "无法启动前台服务", run.id, run.activeRunId.orEmpty(), run.notify, run.showPreview))
                        }
                    } catch (failure: Exception) {
                        Log.e("ScheduledTaskAlarm", "Unable to record service start failure", failure)
                    } finally { pending.finish() }
                }
            }
            return
        }
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        scope.launch {
            try { repository.systemEvent(intent.action) }
            catch (e: Exception) { Log.e("ScheduledTaskAlarm", "Unable to reconcile alarms", e) }
            finally { pending.finish() }
        }
    }
}
