package me.rerere.rikkahub.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ScheduledTaskClockReceiver : BroadcastReceiver(), KoinComponent {
    private val scope: AppScope by inject()
    private val repository: ScheduledTaskRepository by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val pending = goAsync()
        scope.launch {
            try { repository.clockChanged() }
            catch (e: Exception) { android.util.Log.e("ScheduledTaskClock", "Unable to reconcile clock change", e) }
            finally { pending.finish() }
        }
    }
}
