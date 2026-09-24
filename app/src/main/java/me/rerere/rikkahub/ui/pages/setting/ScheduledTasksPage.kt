package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunEntity
import me.rerere.rikkahub.data.db.entity.ScheduledExecutionMode
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.service.scheduled.ScheduledRunStatus
import me.rerere.rikkahub.service.scheduled.ScheduledTaskManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun ScheduledTasksPage(manager: ScheduledTaskManager = koinInject()) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val tasksFlow = remember(manager) { manager.observeTasks() }
    val tasks by tasksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var exactAllowed by remember { mutableStateOf(manager.canScheduleExactly()) }
    var notificationAllowed by remember { mutableStateOf(NotificationUtil.hasNotificationPermission(context)) }
    var expandedTask by remember { mutableStateOf<String?>(null) }
    var deleteTask by remember { mutableStateOf<ScheduledTaskEntity?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAllowed = NotificationUtil.hasNotificationPermission(context)
    }

    DisposableEffect(owner, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAllowed = manager.canScheduleExactly()
                notificationAllowed = NotificationUtil.hasNotificationPermission(context)
                scope.launch { manager.reconcile() }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    deleteTask?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTask = null },
            title = { Text(stringResource(R.string.scheduled_task_delete)) },
            text = { Text(stringResource(R.string.scheduled_task_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { manager.delete(task.id) }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                    deleteTask = null
                }) { Text(stringResource(R.string.scheduled_task_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTask = null }) { Text(androidx.compose.ui.res.stringResource(android.R.string.cancel)) }
            },
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.scheduled_tasks_title)) },
            navigationIcon = { BackButton() },
            actions = {
                IconButton(onClick = { nav.navigate(Screen.ScheduledTaskEditor()) }) {
                    Icon(HugeIcons.Add01, stringResource(R.string.scheduled_task_add))
                }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!exactAllowed && tasks.any { it.enabled }) item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.scheduled_task_exact_alarm_missing))
                        TextButton(onClick = {
                            runCatching { context.startActivity(manager.exactAlarmSettingsIntent()) }
                        }) { Text(stringResource(R.string.scheduled_task_exact_alarm_open)) }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33 && !notificationAllowed && tasks.any { it.notificationEnabled }) item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.scheduled_task_notification_missing))
                        TextButton(onClick = {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text(stringResource(R.string.scheduled_task_notification_allow)) }
                    }
                }
            }
            if (tasks.isEmpty()) item { Text(stringResource(R.string.scheduled_task_empty)) }
            items(tasks, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(task.name)
                            Switch(
                                checked = task.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        runCatching { manager.setEnabled(task.id, enabled) }
                                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                    }
                                },
                            )
                        }
                        Text(scheduleDescription(task))
                        Text(stringResource(if (task.executionMode == ScheduledExecutionMode.REMINDER)
                            R.string.scheduled_task_mode_reminder else R.string.scheduled_task_mode_ai))
                        Text(task.nextRunAt?.let {
                            context.getString(R.string.scheduled_task_next, formatTime(it))
                        } ?: stringResource(R.string.scheduled_task_no_next))
                        LatestRunStatus(task.id, manager)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { nav.navigate(Screen.ScheduledTaskEditor(task.id)) }) {
                                Text(stringResource(R.string.scheduled_task_edit))
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { manager.runNow(task.id) }
                                        .onSuccess { started ->
                                            if (!started) Toast.makeText(
                                                context, R.string.scheduled_task_already_running, Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }
                            }) { Text(stringResource(R.string.scheduled_task_run_now)) }
                            TextButton(onClick = {
                                expandedTask = if (expandedTask == task.id) null else task.id
                            }) { Text(stringResource(R.string.scheduled_task_history)) }
                            TextButton(onClick = { deleteTask = task }) {
                                Text(stringResource(R.string.scheduled_task_delete))
                            }
                        }
                        if (expandedTask == task.id) RunHistory(task.id, manager)
                    }
                }
            }
        }
    }
}

@Composable
private fun RunHistory(taskId: String, manager: ScheduledTaskManager) {
    val nav = LocalNavController.current
    val flow = remember(taskId, manager) { manager.observeRuns(taskId) }
    val runs by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    runs.forEach { run ->
        TextButton(
            onClick = { run.conversationId?.let { nav.navigate(Screen.Chat(it)) } },
            enabled = run.conversationId != null,
        ) {
            Text("${formatTime(run.scheduledAt)} · ${runStatusText(run)}")
        }
        run.error?.let { Text(it) }
    }
}

@Composable
private fun LatestRunStatus(taskId: String, manager: ScheduledTaskManager) {
    val flow = remember(taskId, manager) { manager.observeRuns(taskId) }
    val runs by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    runs.firstOrNull()?.let { Text(runStatusText(it)) }
}

@Composable
private fun runStatusText(run: ScheduledTaskRunEntity): String = stringResource(when (run.status) {
    ScheduledRunStatus.QUEUED -> R.string.scheduled_task_status_queued
    ScheduledRunStatus.RUNNING -> R.string.scheduled_task_status_running
    ScheduledRunStatus.WAITING_APPROVAL -> R.string.scheduled_task_status_waiting
    ScheduledRunStatus.SUCCEEDED -> R.string.scheduled_task_status_succeeded
    ScheduledRunStatus.INTERRUPTED -> R.string.scheduled_task_status_interrupted
    ScheduledRunStatus.SKIPPED -> R.string.scheduled_task_status_skipped
    else -> R.string.scheduled_task_status_failed
})

@Composable
fun ScheduledTaskEditorPage(taskId: String?, manager: ScheduledTaskManager = koinInject()) {
    val context = LocalContext.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val defaultDate = remember {
        val today = LocalDate.now()
        if (java.time.LocalTime.now().isBefore(java.time.LocalTime.of(13, 0))) today else today.plusDays(1)
    }
    var ready by remember(taskId) { mutableStateOf(taskId == null) }
    var name by remember(taskId) { mutableStateOf("") }
    var prompt by remember(taskId) { mutableStateOf("") }
    var assistantId by remember(taskId) { mutableStateOf(settings.assistantId.toString()) }
    var modelId by remember(taskId) { mutableStateOf<String?>(null) }
    var executionMode by remember(taskId) { mutableStateOf(ScheduledExecutionMode.AI) }
    var scheduleType by remember(taskId) { mutableStateOf("ONCE") }
    var date by remember(taskId) { mutableStateOf(defaultDate) }
    var hour by remember(taskId) { mutableStateOf(13) }
    var minute by remember(taskId) { mutableStateOf(0) }
    var weekdays by remember(taskId) { mutableStateOf(0b0011111) }
    var enabled by remember(taskId) { mutableStateOf(true) }
    var notify by remember(taskId) { mutableStateOf(true) }
    var notificationTitle by remember(taskId) { mutableStateOf("") }
    var notificationBody by remember(taskId) { mutableStateOf("") }
    var assistantMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    LaunchedEffect(taskId, settings.assistantId, settings.assistants) {
        if (taskId == null && settings.assistants.none { it.id.toString() == assistantId }) {
            assistantId = settings.assistantId.toString()
        }
    }

    LaunchedEffect(taskId) {
        if (taskId != null) {
            manager.getTask(taskId)?.let { task ->
                name = task.name
                prompt = task.prompt
                assistantId = task.assistantId
                modelId = task.modelId
                executionMode = task.executionMode
                scheduleType = task.scheduleType
                date = task.oneTimeDate?.let(LocalDate::parse) ?: defaultDate
                hour = task.hour
                minute = task.minute
                weekdays = task.weekdays
                enabled = task.enabled
                notify = task.notificationEnabled
                notificationTitle = task.notificationTitle
                notificationBody = task.notificationBody
            }
            ready = true
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (taskId == null) R.string.scheduled_task_add else R.string.scheduled_task_edit)) },
            navigationIcon = { BackButton() },
        )
    }) { padding ->
        if (!ready) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.scheduled_task_name)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = executionMode == ScheduledExecutionMode.AI,
                    onClick = { executionMode = ScheduledExecutionMode.AI },
                    label = { Text(stringResource(R.string.scheduled_task_mode_ai)) },
                )
                FilterChip(
                    selected = executionMode == ScheduledExecutionMode.REMINDER,
                    onClick = { executionMode = ScheduledExecutionMode.REMINDER },
                    label = { Text(stringResource(R.string.scheduled_task_mode_reminder)) },
                )
            }
            if (executionMode == ScheduledExecutionMode.AI) {
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.scheduled_task_prompt)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 5,
                )
                Text(stringResource(R.string.scheduled_task_assistant))
                androidx.compose.foundation.layout.Box {
                    val selected = settings.assistants.firstOrNull { it.id.toString() == assistantId }
                    Button(onClick = { assistantMenu = true }) {
                        Text(selected?.name?.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
                            ?: stringResource(R.string.scheduled_task_select_assistant))
                    }
                    DropdownMenu(expanded = assistantMenu, onDismissRequest = { assistantMenu = false }) {
                        settings.assistants.forEach { assistant ->
                            DropdownMenuItem(
                                text = { Text(assistant.name.ifBlank { context.getString(R.string.assistant_page_default_assistant) }) },
                                onClick = { assistantId = assistant.id.toString(); assistantMenu = false },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.scheduled_task_model))
                androidx.compose.foundation.layout.Box {
                    val models = settings.providers.flatMap { provider ->
                        provider.models.filter { it.type == ModelType.CHAT }.map { provider to it }
                    }
                    val selected = models.firstOrNull { it.second.id.toString() == modelId }
                    Button(onClick = { modelMenu = true }) {
                        Text(if (modelId == null) stringResource(R.string.scheduled_task_follow_assistant_model)
                            else selected?.let { "${it.second.displayName} (${it.first.name})" }
                                ?: stringResource(R.string.scheduled_task_model_unavailable))
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scheduled_task_follow_assistant_model)) },
                            onClick = { modelId = null; modelMenu = false },
                        )
                        models.forEach { (provider, model) ->
                            DropdownMenuItem(
                                text = { Text("${model.displayName} (${provider.name})") },
                                onClick = { modelId = model.id.toString(); modelMenu = false },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "ONCE" to R.string.scheduled_task_once,
                    "DAILY" to R.string.scheduled_task_daily,
                    "WEEKLY" to R.string.scheduled_task_weekly,
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = scheduleType == type,
                        onClick = { scheduleType = type },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            if (scheduleType == "ONCE") {
                Button(onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, day -> date = LocalDate.of(year, month + 1, day) },
                        date.year, date.monthValue - 1, date.dayOfMonth,
                    ).show()
                }) { Text("${stringResource(R.string.scheduled_task_date)}: $date") }
            }
            if (scheduleType == "WEEKLY") {
                val labels = listOf(
                    R.string.scheduled_task_monday, R.string.scheduled_task_tuesday,
                    R.string.scheduled_task_wednesday, R.string.scheduled_task_thursday,
                    R.string.scheduled_task_friday, R.string.scheduled_task_saturday,
                    R.string.scheduled_task_sunday,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { index, label ->
                        val bit = 1 shl index
                        FilterChip(
                            selected = weekdays and bit != 0,
                            onClick = { weekdays = weekdays xor bit },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
            Button(onClick = {
                TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show()
            }) { Text("${stringResource(R.string.scheduled_task_time)}: %02d:%02d".format(hour, minute)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.scheduled_task_enabled))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            if (executionMode == ScheduledExecutionMode.AI) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.scheduled_task_notifications))
                    Switch(checked = notify, onCheckedChange = { notify = it })
                }
            }
            if (executionMode == ScheduledExecutionMode.REMINDER || notify) {
                OutlinedTextField(
                    value = notificationTitle, onValueChange = { notificationTitle = it },
                    label = { Text(stringResource(R.string.scheduled_task_notification_title)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = notificationBody, onValueChange = { notificationBody = it },
                    label = { Text(stringResource(R.string.scheduled_task_notification_body)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
            }
            Button(
                onClick = {
                    if (scheduleType == "WEEKLY" && weekdays == 0) {
                        Toast.makeText(context, R.string.scheduled_task_select_day, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (executionMode == ScheduledExecutionMode.AI &&
                        settings.assistants.none { it.id.toString() == assistantId }) {
                        Toast.makeText(context, R.string.scheduled_task_select_assistant, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (executionMode == ScheduledExecutionMode.AI && modelId != null &&
                        settings.providers.none { provider -> provider.models.any {
                            it.id.toString() == modelId && it.type == ModelType.CHAT
                        } }) {
                        Toast.makeText(context, R.string.scheduled_task_model_unavailable, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        val now = System.currentTimeMillis()
                        runCatching {
                            manager.save(ScheduledTaskEntity(
                                id = taskId ?: Uuid.random().toString(),
                                name = name.trim(),
                                prompt = if (executionMode == ScheduledExecutionMode.AI) prompt.trim() else "",
                                assistantId = if (executionMode == ScheduledExecutionMode.AI) assistantId else "",
                                scheduleType = scheduleType,
                                oneTimeDate = if (scheduleType == "ONCE") date.toString() else null,
                                hour = hour, minute = minute, weekdays = weekdays, enabled = enabled,
                                notificationEnabled = executionMode == ScheduledExecutionMode.REMINDER || notify,
                                notificationTitle = notificationTitle.trim(),
                                notificationBody = notificationBody.trim(), nextRunAt = null,
                                scheduledZoneId = ZoneId.systemDefault().id,
                                createdAt = now, updatedAt = now,
                                executionMode = executionMode,
                                modelId = if (executionMode == ScheduledExecutionMode.AI) modelId else null,
                            ))
                        }.onSuccess { nav.popBackStack() }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                },
                enabled = name.isNotBlank() && if (executionMode == ScheduledExecutionMode.AI)
                    prompt.isNotBlank() else notificationBody.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.scheduled_task_save)) }
        }
    }
}

@Composable
private fun scheduleDescription(task: ScheduledTaskEntity): String = when (task.scheduleType) {
    "ONCE" -> "${stringResource(R.string.scheduled_task_once)} ${task.oneTimeDate} %02d:%02d".format(task.hour, task.minute)
    "DAILY" -> "${stringResource(R.string.scheduled_task_daily)} %02d:%02d".format(task.hour, task.minute)
    else -> {
        val labels = listOf(
            stringResource(R.string.scheduled_task_monday), stringResource(R.string.scheduled_task_tuesday),
            stringResource(R.string.scheduled_task_wednesday), stringResource(R.string.scheduled_task_thursday),
            stringResource(R.string.scheduled_task_friday), stringResource(R.string.scheduled_task_saturday),
            stringResource(R.string.scheduled_task_sunday),
        ).mapIndexedNotNull { index, label ->
            if (task.weekdays and (1 shl index) != 0) label else null
        }
        val days = labels.joinToString("、")
        "$days %02d:%02d".format(task.hour, task.minute)
    }
}

private fun formatTime(epochMillis: Long): String =
    dateTimeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
