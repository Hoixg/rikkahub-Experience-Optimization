// Adapted from xiaoyuili/Yuihub, AGPL-3.0.
package me.rerere.rikkahub.ui.pages.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ScheduleType
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Calendar
import kotlin.uuid.Uuid

/**
 * 定时任务编辑页（新建 / 修改共用）。
 *
 * 结构：基本信息（名称、提示词、助手）→ 调度（类型 + 时间/间隔）→ 保存。
 * 时间选择用 M3 的 TimePicker / DatePicker 弹窗，与系统观感一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTaskEditPage(
    taskId: String?,
    defaultAssistantId: String? = null,
    vm: ScheduledTasksVM = koinViewModel(),
) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val error by vm.error.collectAsStateWithLifecycle()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val allTasks by vm.tasks.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    val existing = remember(taskId, allTasks) {
        taskId?.let { id -> allTasks.find { it.id == id } }
    }

    // 表单状态：现有任务回填，新建用默认值
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var prompt by remember(existing?.id) { mutableStateOf(existing?.prompt.orEmpty()) }
    var assistantId by remember(existing?.id) {
        mutableStateOf(
            existing?.assistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: defaultAssistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: settings.assistantId
        )
    }
    var scheduleType by remember(existing?.id) {
        mutableStateOf(
            existing?.scheduleType?.let { runCatching { ScheduleType.valueOf(it) }.getOrNull() }
                ?: ScheduleType.DAILY
        )
    }
    var intervalMinutes by remember(existing?.id) {
        mutableIntStateOf(existing?.intervalMinutes ?: ScheduledTasksVM.DEFAULT_INTERVAL_MINUTES)
    }
    var intervalInput by remember(existing?.id) { mutableStateOf((existing?.intervalMinutes ?: ScheduledTasksVM.DEFAULT_INTERVAL_MINUTES).toString()) }
    val intervalValid = intervalInput.toIntOrNull()?.let { it >= 15 } == true
    var timeOfDayMinutes by remember(existing?.id) {
        mutableIntStateOf(existing?.timeOfDayMinutes ?: ScheduledTasksVM.DEFAULT_TIME_OF_DAY_MINUTES)
    }
    var triggerAt by remember(existing?.id) {
        mutableLongStateOf(existing?.triggerAt ?: ScheduledTasksVM.defaultTriggerAt())
    }

    var showTimePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showAssistantPicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(error) { if (error != null) saving = false }

    val selectedAssistant: Assistant = settings.assistants.find { it.id == assistantId }
        ?: settings.getCurrentAssistant()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (existing == null) R.string.automation_edit_title_create
                            else R.string.automation_edit_title_edit
                        )
                    )
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.automation_edit_name)) },
                        placeholder = { Text(stringResource(R.string.automation_edit_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.automation_edit_prompt)) },
                        placeholder = { Text(stringResource(R.string.automation_edit_prompt_hint)) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.automation_edit_assistant),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 只有设置中新建可以选助手：
                    // 助手页进入时已限定归属，编辑已有任务也不允许转移归属
                    val canChooseAssistant = taskId == null && defaultAssistantId == null
                    Surface(
                        onClick = { if (canChooseAssistant) showAssistantPicker = !showAssistantPicker },
                        shape = RoundedCornerShape(16.dp),
                        color = CustomColors.cardColorsOnSurfaceContainer.containerColor,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            UIAvatar(
                                name = selectedAssistant.name.ifBlank {
                                    stringResource(R.string.assistant_page_default_assistant)
                                },
                                value = selectedAssistant.avatar,
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                text = selectedAssistant.name.ifBlank {
                                    stringResource(R.string.assistant_page_default_assistant)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (canChooseAssistant) {
                                Icon(
                                    imageVector = HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (canChooseAssistant && showAssistantPicker) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            settings.assistants.forEach { assistant ->
                                val selected = assistant.id == assistantId
                                Surface(
                                    onClick = {
                                        assistantId = assistant.id
                                        showAssistantPicker = false
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    } else {
                                        CustomColors.cardColorsOnSurfaceContainer.containerColor
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        UIAvatar(
                                            name = assistant.name.ifBlank {
                                                stringResource(R.string.assistant_page_default_assistant)
                                            },
                                            value = assistant.avatar,
                                            modifier = Modifier.size(28.dp),
                                        )
                                        Text(
                                            text = assistant.name.ifBlank {
                                                stringResource(R.string.assistant_page_default_assistant)
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.automation_edit_schedule),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScheduleType.entries.forEach { type ->
                            FilterChip(
                                selected = scheduleType == type,
                                onClick = { scheduleType = type },
                                label = { Text(scheduleTypeLabel(type)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = when (type) {
                                            ScheduleType.ONCE -> HugeIcons.Calendar03
                                            ScheduleType.DAILY -> HugeIcons.Clock01
                                            ScheduleType.INTERVAL -> HugeIcons.Repeat
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }

                    when (scheduleType) {
                        ScheduleType.ONCE -> {
                            // 日期 + 时间两行
                            Surface(
                                onClick = { showDatePicker = true },
                                shape = RoundedCornerShape(14.dp),
                                color = CustomColors.cardColorsOnSurfaceContainer.containerColor,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Calendar03,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = java.text.SimpleDateFormat(
                                            "yyyy-MM-dd",
                                            locale
                                        ).format(java.util.Date(triggerAt)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Surface(
                                onClick = { showTimePicker = true },
                                shape = RoundedCornerShape(14.dp),
                                color = CustomColors.cardColorsOnSurfaceContainer.containerColor,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Clock01,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = java.text.SimpleDateFormat(
                                            "HH:mm",
                                            locale
                                        ).format(java.util.Date(triggerAt)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        ScheduleType.DAILY -> {
                            Surface(
                                onClick = { showTimePicker = true },
                                shape = RoundedCornerShape(14.dp),
                                color = CustomColors.cardColorsOnSurfaceContainer.containerColor,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Clock01,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.automation_edit_daily_at),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "%02d:%02d".format(
                                            ScheduledTasksVM.minutesToHour(timeOfDayMinutes),
                                            ScheduledTasksVM.minutesToMinute(timeOfDayMinutes),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        ScheduleType.INTERVAL -> {
                            FormItem(
                                label = { Text(stringResource(R.string.automation_edit_interval)) },
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf(30, 60, 180, 360, 720, 1440).forEach { minutes ->
                                        FilterChip(
                                            selected = intervalMinutes == minutes,
                                            onClick = { intervalMinutes = minutes; intervalInput = minutes.toString() },
                                            label = { Text(intervalLabel(minutes)) },
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = intervalInput,
                                onValueChange = { input ->
                                    intervalInput = input
                                    input.toIntOrNull()?.let { intervalMinutes = it }
                                },
                                isError = !intervalValid,
                                supportingText = { if (!intervalValid) Text("请输入至少 15 分钟的整数") },
                                label = { Text(stringResource(R.string.automation_edit_interval_custom)) },
                                suffix = { Text(stringResource(R.string.automation_edit_minutes)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(
                    onClick = {
                        // 防重复提交：保存期间按钮禁用，避免连点创建多条任务
                        if (saving) return@Button
                        vm.error.value = null
                        saving = true
                        if (existing == null) {
                            vm.create(
                                name = name.trim(),
                                prompt = prompt.trim(),
                                assistantId = assistantId,
                                scheduleType = scheduleType,
                                triggerAt = triggerAt,
                                intervalMinutes = intervalMinutes,
                                timeOfDayMinutes = timeOfDayMinutes,
                                onDone = {
                                    // 保存成功后回到任务列表，而不是停留在编辑页
                                    navController.popBackStack()
                                },
                            )
                        } else {
                            vm.update(
                                existing.copy(
                                    name = name.trim(),
                                    prompt = prompt.trim(),
                                    assistantId = assistantId.toString(),
                                    scheduleType = scheduleType.name,
                                    triggerAt = triggerAt,
                                    intervalMinutes = intervalMinutes,
                                    timeOfDayMinutes = timeOfDayMinutes,
                                ),
                                onDone = {
                                    navController.popBackStack()
                                },
                            )
                        }
                    },
                    enabled = !saving && name.isNotBlank() && prompt.isNotBlank() && (taskId == null || existing != null) && settings.assistants.any { it.id == assistantId } && (scheduleType != ScheduleType.INTERVAL || intervalValid),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.automation_edit_save))
                }
            }
        }
    }

    if (showTimePicker) {
        // 复用 M3 TimePicker 弹窗；已有任务可能同时含日期与时刻
        val initialHour = if (scheduleType == ScheduleType.DAILY) {
            ScheduledTasksVM.minutesToHour(timeOfDayMinutes)
        } else {
            remember(triggerAt) {
                Calendar.getInstance().apply { timeInMillis = triggerAt }.get(Calendar.HOUR_OF_DAY)
            }
        }
        val initialMinute = if (scheduleType == ScheduleType.DAILY) {
            ScheduledTasksVM.minutesToMinute(timeOfDayMinutes)
        } else {
            remember(triggerAt) {
                Calendar.getInstance().apply { timeInMillis = triggerAt }.get(Calendar.MINUTE)
            }
        }
        val timeState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (scheduleType == ScheduleType.DAILY) {
                            timeOfDayMinutes = ScheduledTasksVM.timeOfDayToMinutes(
                                timeState.hour, timeState.minute
                            )
                        } else {
                            triggerAt = Calendar.getInstance().apply {
                                timeInMillis = triggerAt
                                set(Calendar.HOUR_OF_DAY, timeState.hour)
                                set(Calendar.MINUTE, timeState.minute)
                                set(Calendar.SECOND, 0)
                            }.timeInMillis
                        }
                        showTimePicker = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            text = { TimePicker(state = timeState) },
        )
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = java.time.Instant.ofEpochMilli(triggerAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { selected ->
                            // 保留原有时刻，只替换日期部分
                            val old = Calendar.getInstance().apply { timeInMillis = triggerAt }
                            val date = java.time.Instant.ofEpochMilli(selected).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            triggerAt = date.atTime(old.get(Calendar.HOUR_OF_DAY), old.get(Calendar.MINUTE))
                                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}

@Composable
private fun scheduleTypeLabel(type: ScheduleType): String = stringResource(
    when (type) {
        ScheduleType.ONCE -> R.string.automation_edit_type_once
        ScheduleType.DAILY -> R.string.automation_edit_type_daily
        ScheduleType.INTERVAL -> R.string.automation_edit_type_interval
    }
)

@Composable
private fun intervalLabel(minutes: Int): String = when (minutes) {
    30 -> stringResource(R.string.automation_edit_interval_30m)
    60 -> stringResource(R.string.automation_edit_interval_1h)
    180 -> stringResource(R.string.automation_edit_interval_3h)
    360 -> stringResource(R.string.automation_edit_interval_6h)
    720 -> stringResource(R.string.automation_edit_interval_12h)
    else -> stringResource(R.string.automation_edit_interval_24h)
}
