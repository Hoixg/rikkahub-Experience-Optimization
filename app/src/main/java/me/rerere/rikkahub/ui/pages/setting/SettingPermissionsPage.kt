package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import me.rerere.rikkahub.service.BackgroundRuntime
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
    val repository: ScheduledTaskRepository = koinInject()
    val runtime by BackgroundRuntime.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var lastExact by remember { mutableStateOf(SystemPermissions.canScheduleExactAlarms(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberPermissionState(if (Build.VERSION.SDK_INT >= 33) setOf(PermissionNotification) else emptySet())
    PermissionManager(permission)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh++
                val currentExact = SystemPermissions.canScheduleExactAlarms(context)
                val changed = currentExact != lastExact
                lastExact = currentExact
                scope.launch { runCatching { repository.reconcile(recalculate = changed); repository.requestDispatch() }.onFailure { error = it.message } }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val notifications = remember(refresh, permission.allPermissionsGranted) { SystemPermissions.isNotificationEnabled(context) }
    val battery = remember(refresh) { SystemPermissions.isIgnoringBatteryOptimizations(context) }
    val exact = remember(refresh) { SystemPermissions.canScheduleExactAlarms(context) }
    fun open(intent: Intent) { if (!SystemPermissions.openSettings(context, intent)) error = "无法打开系统设置" }
    Scaffold(topBar = { TopAppBar(title = { Text("后台运行与通知") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.topBarColors.containerColor) { padding ->
        LazyColumn(contentPadding = padding + PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("系统授权", style = MaterialTheme.typography.titleSmall) }
            item {
                CardGroup {
                    item(headlineContent = { Text("通知权限") }, supportingContent = { Text("管理应用通知及各类通知设置") },
                        trailingContent = { Text(if (notifications) "已开启" else "未开启") }, onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !permission.allPermissionsGranted) permission.requestPermissions()
                        else open(SystemPermissions.notificationSettingsIntent(context))
                    })
                    item(headlineContent = { Text("精确闹钟") }, supportingContent = { Text("控制自动排程；未授权仍可立即执行") },
                        trailingContent = { Text(if (exact) "已允许" else "待授权") }, onClick = {
                            if (Build.VERSION.SDK_INT >= 31) open(SystemPermissions.exactAlarmSettingsIntent(context))
                        })
                    item(headlineContent = { Text("忽略电池优化") }, trailingContent = { Text(if (battery) "已豁免" else "未豁免") },
                        onClick = { open(SystemPermissions.batteryOptimizationIntent(context)) })
                    item(headlineContent = { Text("厂商自启动 · ${Build.MANUFACTURER}") }, supportingContent = { Text("系统无法可靠读取授权状态，请在设置中确认") },
                        trailingContent = { Text("未知") }, onClick = { open(SystemPermissions.autoStartIntent(context)) })
                }
            }
            item { Text("运行状态", style = MaterialTheme.typography.titleSmall) }
            item {
                CardGroup {
                    item(headlineContent = { Text("前台服务") }, trailingContent = { Text(if (runtime.services.isEmpty()) "已停止" else "运行中") })
                    item(headlineContent = { Text("CPU 唤醒锁") }, trailingContent = { Text(if (runtime.wakeLocks.isEmpty()) "已释放" else "持有中") })
                }
            }
            item { Text("仅生成期间保持后台运行，等待或完成后释放资源。通知、电池和自启动设置不阻止立即执行；系统省电仍可能造成延迟。", style = MaterialTheme.typography.bodySmall) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
