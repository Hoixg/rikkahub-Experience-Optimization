// Adapted from xiaoyuili/Yuihub, AGPL-3.0.
package me.rerere.rikkahub.ui.pages.automation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.Task01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ScheduleType
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * 定时任务（自动化）列表页。
 *
 * 卡片展示任务名、调度描述、最近运行状态；开关控制启用；卡片菜单可编辑/删除。
 * [assistantId] 非空时为「某助手的任务」视图：只列出该助手的任务，新建时默认选中它。
 */
@Composable
fun ScheduledTasksPage(
    assistantId: String? = null,
    vm: ScheduledTasksVM = koinViewModel(parameters = { parametersOf(assistantId) }),
) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val navController = LocalNavController.current
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var historyTask by remember { mutableStateOf<ScheduledTaskEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<ScheduledTaskEntity?>(null) }

    // 助手视图下，标题用助手名，新建任务时把助手作为默认值带进编辑页
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val filterAssistant = assistantId?.let { id ->
        settings.assistants.find { it.id.toString() == id }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (assistantId != null) {
                        // 助手视图用两行标题，避免与助手详情页标题混淆
                        Column {
                            Text(
                                text = stringResource(R.string.automation_page_title),
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = filterAssistant?.name?.ifBlank {
                                    stringResource(R.string.assistant_page_default_assistant)
                                } ?: "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(stringResource(R.string.automation_page_title))
                    }
                },
                navigationIcon = { BackButton() },
                actions = { TextButton(onClick = { navController.navigate(Screen.SettingPermissions) }) { Text("后台与通知") } },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // 助手视图下，新建任务默认归属该助手
                    navController.navigate(Screen.ScheduledTaskEdit(null, assistantId))
                },
            ) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.automation_page_add))
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (tasks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.Task01,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.automation_page_empty_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.automation_page_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = { navController.navigate(Screen.ScheduledTaskEdit(null, assistantId)) },
                ) {
                    Text(stringResource(R.string.automation_page_add))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                items(tasks, key = { it.id }) { task ->
                    ScheduledTaskCard(
                        task = task,
                        nextTriggerText = vm.nextTriggerText(task),
                        onToggle = { enabled -> vm.setEnabled(task, enabled) },
                        onEdit = { navController.navigate(Screen.ScheduledTaskEdit(task.id)) },
                        onDelete = { pendingDelete = task },
                        onRunNow = { vm.runNow(task) },
                        onHistory = { historyTask = task },
                        onCancel = { vm.cancelRun(task) },
                        onOpenConversation = { navController.navigate(Screen.Chat(task.lastConversationId)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    historyTask?.let { selected ->
        val history by remember(selected.id) { vm.historyFlow(selected.id) }.collectAsStateWithLifecycle(emptyList())
        AlertDialog(onDismissRequest = { historyTask = null }, title = { Text("${selected.name} · 运行历史") }, text = {
            LazyColumn(Modifier.heightIn(max = 450.dp)) {
                if (history.isEmpty()) item { Text("暂无运行记录") }
                items(history, key = { it.id }) { run ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        val date = java.text.SimpleDateFormat("MM-dd HH:mm", locale).format(java.util.Date(run.dueAt))
                        Text("$date · ${if (run.source == "MANUAL") "立即执行" else "定时"} · ${runStatusText(run.status)}")
                        if (run.error.isNotBlank()) Text(run.error, color = MaterialTheme.colorScheme.error)
                        if (run.preview.isNotBlank()) Text(run.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        run.conversationId?.let { id -> TextButton(onClick = { historyTask = null; navController.navigate(Screen.Chat(id)) }) {
                            Text(if (run.status == "WAITING_APPROVAL") "处理审批" else "查看会话")
                        } }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { historyTask = null }) { Text("关闭") } })
    }
    RikkaConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.automation_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDelete?.let { vm.delete(it) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    ) {
        Text(
            text = stringResource(
                R.string.automation_page_delete_message,
                pendingDelete?.name.orEmpty(),
            ),
        )
    }
}

@Composable
private fun ScheduledTaskCard(
    task: ScheduledTaskEntity,
    nextTriggerText: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRunNow: () -> Unit,
    onHistory: () -> Unit,
    onCancel: () -> Unit,
    onOpenConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val scheduleType = runCatching { ScheduleType.valueOf(task.scheduleType) }
        .getOrDefault(ScheduleType.DAILY)
    val accent = scheduleAccent(scheduleType)
    val statusText = runStatusText(task.lastRunStatus)
    val statusColor = runStatusColor(task.lastRunStatus)

    Card(
        onClick = onEdit,
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = scheduleIcon(scheduleType),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(21.dp),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = taskModeText(task.mode) + " · " + scheduleDescription(task, scheduleType),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggle,
                )
            }

            // 最近运行 / 下次触发
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.automation_page_last_run),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                    }
                    if (task.enabled && nextTriggerText.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.automation_page_next_run),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = nextTriggerText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = task.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { menu = true }) { Text("操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("立即执行") }, onClick = { menu = false; onRunNow() })
                        DropdownMenuItem(text = { Text("运行历史") }, onClick = { menu = false; onHistory() })
                        DropdownMenuItem(text = { Text("取消当前执行") }, enabled = task.activeRunId != null, onClick = { menu = false; onCancel() })
                        DropdownMenuItem(text = { Text("删除") }, enabled = task.activeRunId == null, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (task.lastError.isNotBlank()) {
                Text(task.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (task.lastConversationId.isNotBlank()) {
                androidx.compose.material3.TextButton(onClick = onOpenConversation) {
                    Text(if (task.lastRunStatus == "WAITING_APPROVAL") "打开会话处理审批" else "查看结果会话")
                }
            }
        }
    }
}

@Composable
private fun scheduleAccent(type: ScheduleType): Color {
    val colors = MaterialTheme.extendColors
    return when (type) {
        ScheduleType.ONCE -> colors.orange6
        ScheduleType.DAILY -> colors.blue6
        ScheduleType.INTERVAL -> colors.green6
        ScheduleType.WEEKLY -> colors.blue6
    }
}

private fun scheduleIcon(type: ScheduleType) = when (type) {
    ScheduleType.ONCE -> HugeIcons.Calendar03
    ScheduleType.DAILY -> HugeIcons.Clock01
    ScheduleType.INTERVAL -> HugeIcons.Repeat
    ScheduleType.WEEKLY -> HugeIcons.Calendar03
}

@Composable
private fun scheduleDescription(task: ScheduledTaskEntity, type: ScheduleType): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return when (type) {
    ScheduleType.ONCE -> {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
        stringResource(R.string.automation_page_type_once_at, fmt.format(java.util.Date(task.triggerAt)))
    }

    ScheduleType.DAILY -> stringResource(
        R.string.automation_page_type_daily_at,
        "%02d:%02d".format(
            ScheduledTasksVM.minutesToHour(task.timeOfDayMinutes),
            ScheduledTasksVM.minutesToMinute(task.timeOfDayMinutes),
        ),
    ) + listOfNotNull(task.startDate?.let { "从$it" }, task.endDate?.let { "至$it" }).joinToString(" ", prefix = " ").trimEnd()

    ScheduleType.INTERVAL -> stringResource(
        R.string.automation_page_type_interval,
        task.intervalMinutes.coerceAtLeast(15),
    )
    ScheduleType.WEEKLY -> {
        val days = listOf("一", "二", "三", "四", "五", "六", "日")
            .filterIndexed { index, _ -> task.weekdaysMask and (1 shl index) != 0 }.joinToString("")
        "每周$days %02d:%02d".format(
            ScheduledTasksVM.minutesToHour(task.timeOfDayMinutes),
            ScheduledTasksVM.minutesToMinute(task.timeOfDayMinutes),
        ) + listOfNotNull(task.startDate?.let { "从$it" }, task.endDate?.let { "至$it" }).joinToString(" ", prefix = " ").trimEnd()
    }
}

}

@Composable
private fun runStatusText(status: String): String = when (status) {
    ScheduledTaskRunStatus.WAITING_IDLE.name -> "等待空闲"
    ScheduledTaskRunStatus.RUNNING.name -> stringResource(R.string.automation_page_status_running)
    ScheduledTaskRunStatus.WAITING_APPROVAL.name -> "等待审批"
    ScheduledTaskRunStatus.CANCELLED.name -> "已取消"
    ScheduledTaskRunStatus.SUCCESS.name -> stringResource(R.string.automation_page_status_success)
    ScheduledTaskRunStatus.FAILED.name -> stringResource(R.string.automation_page_status_failed)
    ScheduledTaskRunStatus.SKIPPED.name -> stringResource(R.string.automation_page_status_skipped)
    else -> stringResource(R.string.automation_page_status_never)
}

@Composable
private fun runStatusColor(status: String): Color = when (status) {
    ScheduledTaskRunStatus.SUCCESS.name -> MaterialTheme.extendColors.green6
    ScheduledTaskRunStatus.FAILED.name -> MaterialTheme.colorScheme.error
    ScheduledTaskRunStatus.RUNNING.name -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
