package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.KeepAliveController
import me.rerere.rikkahub.service.KeepAliveService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.*
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.SystemPermissions
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingPermissionsPage() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val store: SettingsStore = koinInject()
    val controller: KeepAliveController = koinInject()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val running by KeepAliveService.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberPermissionState(if (Build.VERSION.SDK_INT >= 33) setOf(PermissionNotification) else emptySet())
    PermissionManager(permission)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { refresh++; scope.launch { controller.reconcile() } }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val notifications = remember(refresh, permission.allPermissionsGranted) { SystemPermissions.isNotificationEnabled(context) }
    val battery = remember(refresh) { SystemPermissions.isIgnoringBatteryOptimizations(context) }
    fun open(intent: android.content.Intent) { if (!SystemPermissions.openSettings(context, intent)) error = "无法打开系统设置" }
    Scaffold(topBar = { TopAppBar(title = { Text("权限管理") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.topBarColors.containerColor) { padding ->
        LazyColumn(contentPadding = padding + PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                CardGroup {
                    item(headlineContent = { Text("通知权限") }, supportingContent = { Text("任务完成或需要审批时发送通知") },
                        trailingContent = { Text(if (notifications) "已开启" else "未开启") }, onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && !permission.allPermissionsGranted) permission.requestPermissions()
                            else open(SystemPermissions.notificationSettingsIntent(context))
                        })
                    item(headlineContent = { Text("忽略电池优化") }, supportingContent = { Text("改善锁屏后的后台网络与唤醒能力") },
                        trailingContent = { Text(if (battery) "已豁免" else "未豁免") }, onClick = { open(SystemPermissions.batteryOptimizationIntent(context)) })
                    item(headlineContent = { Text("后台保活") }, supportingContent = { Text(if (running) "保活运行中 · 常驻通知与 CPU 唤醒会增加耗电" else if (settings.keepAwakeEnabled && (!notifications || !battery)) "当前授权条件不满足，保活已停止" else if (settings.keepAwakeEnabled) "保活未运行，可关闭后重新开启" else "默认关闭，需开启通知与电池优化豁免") },
                        trailingContent = { Switch(settings.keepAwakeEnabled, onCheckedChange = { enabled ->
                            if (enabled && (!notifications || !battery)) error = "请先开启通知权限并豁免电池优化"
                            else scope.launch { store.update { it.copy(keepAwakeEnabled = enabled) }; controller.reconcile() }
                        }) })
                }
            }
            item { Text("拒绝权限仍可保存和调度任务。Android 省电策略可能使任务延迟，后台保活也不能保证准点执行。", style = MaterialTheme.typography.bodySmall) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
