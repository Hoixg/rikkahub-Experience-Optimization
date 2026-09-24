package me.rerere.rikkahub.service.scheduled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class ScheduledTaskReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RUN = "me.rerere.rikkahub.action.RUN_SCHEDULED_TASK"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_EXPECTED_AT = "expectedAt"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val manager = GlobalContext.get().get<ScheduledTaskManager>()
                if (intent.action == ACTION_RUN) {
                    val id = intent.getStringExtra(EXTRA_TASK_ID)
                    val at = intent.getLongExtra(EXTRA_EXPECTED_AT, -1L)
                    if (id != null && at > 0) manager.onAlarm(id, at)
                } else {
                    manager.reconcile(recalculateWallClock = intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
                        intent.action == Intent.ACTION_TIME_CHANGED)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
