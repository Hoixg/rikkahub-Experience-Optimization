package me.rerere.rikkahub.service

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository

data class BackgroundRuntimeState(
    val services: Set<String> = emptySet(), val wakeLocks: Set<String> = emptySet(),
)

/** Service and wake-lock values come from live owners. */
object BackgroundRuntime {
    private val mutable = MutableStateFlow(BackgroundRuntimeState())
    val state = mutable.asStateFlow()

    fun initialize(repository: ScheduledTaskRepository, scope: AppScope) {
        scope.launch {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) scope.launch {
                    repository.initialize()
                    if (!ScheduledTaskExecutionService.processingAlarm) repository.requestDispatch()
                }
            })
        }
    }
    fun service(owner: String, running: Boolean) = mutable.update {
        it.copy(services = if (running) it.services + owner else it.services - owner)
    }
    fun wakeLock(owner: String, held: Boolean) = mutable.update {
        it.copy(wakeLocks = if (held) it.wakeLocks + owner else it.wakeLocks - owner)
    }
}

/** A bounded lease renewed only while a service actually owns generation work. */
class GenerationWakeLease(private val context: Context, private val owner: String, private val scope: CoroutineScope) {
    private var lock: PowerManager.WakeLock? = null
    private var renewal: Job? = null
    fun update(count: Int) {
        if (count == 0) { close(); return }
        if (renewal != null) return
        lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "RikkaPlus::$owner").apply { setReferenceCounted(false) }
        fun renew() = runCatching {
            lock?.acquire(120_000L)
            BackgroundRuntime.wakeLock(owner, lock?.isHeld == true)
        }.onFailure { android.util.Log.e("GenerationWakeLease", "Unable to acquire wake lock", it) }
        renew()
        renewal = scope.launch { while (isActive) { delay(60_000L); renew() } }
    }
    fun close() {
        renewal?.cancel(); renewal = null
        lock?.let { if (it.isHeld) it.release() }; lock = null
        BackgroundRuntime.wakeLock(owner, false)
    }
}
