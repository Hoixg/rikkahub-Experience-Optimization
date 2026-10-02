package me.rerere.rikkahub.service

import android.app.Application
import androidx.lifecycle.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.SystemPermissions

class KeepAliveController(private val context: Application, private val settingsStore: SettingsStore, private val scope: AppScope) {
    init {
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) scope.launch { reconcile() }
            })
            settingsStore.settingsFlowRaw.map { it.keepAwakeEnabled }.distinctUntilChanged().collect { reconcile() }
        }
    }

    suspend fun reconcile() {
        val allowed = settingsStore.settingsFlowRaw.first().keepAwakeEnabled && SystemPermissions.isNotificationEnabled(context) &&
            SystemPermissions.isIgnoringBatteryOptimizations(context)
        if (!allowed) KeepAliveService.stop(context)
        else if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !KeepAliveService.running.value) {
            KeepAliveService.start(context)
        }
    }
}
