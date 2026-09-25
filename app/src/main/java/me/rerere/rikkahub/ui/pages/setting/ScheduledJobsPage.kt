package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ScheduledJobCatchup
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobMode
import me.rerere.rikkahub.data.db.entity.ScheduledJobOutcome
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobType
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.scheduled.DirectModeActionRunner
import me.rerere.rikkahub.service.scheduled.ScheduledJobManager
import me.rerere.rikkahub.service.scheduled.ScheduledToolDescriptor
import me.rerere.rikkahub.ui.components.ai.AssistantPickerSheet
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

private val jobDateTimeFormat = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm")
private val jobDateFormat = DateTimeFormatter.ISO_LOCAL_DATE
private const val MAX_NOTIFICATION_MESSAGE_CODE_POINTS = 60

private fun String.takeCodePoints(maxCodePoints: Int): String {
    if (codePointCount(0, length) <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}
private val weekLabels = listOf(
    R.string.scheduled_task_monday, R.string.scheduled_task_tuesday,
    R.string.scheduled_task_wednesday, R.string.scheduled_task_thursday,
    R.string.scheduled_task_friday, R.string.scheduled_task_saturday,
    R.string.scheduled_task_sunday,
)

@Composable
fun ScheduledJobsPage(manager: ScheduledJobManager = koinInject()) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val jobs by remember(manager) { manager.observeJobs() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var permissionGranted by remember { mutableStateOf(NotificationUtil.hasNotificationPermission(context)) }
    var filter by remember { mutableStateOf("all") }
    var deleteJob by remember { mutableStateOf<ScheduledJobEntity?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = NotificationUtil.hasNotificationPermission(context)
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = NotificationUtil.hasNotificationPermission(context)
                scope.launch { manager.reconcile(recoverInterrupted = true, applyCatchup = true) }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    deleteJob?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteJob = null },
            title = { Text(stringResource(R.string.scheduled_task_delete)) },
            text = { Text(stringResource(R.string.scheduled_job_confirm_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { manager.delete(job.id) }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                    deleteJob = null
                }) { Text(stringResource(R.string.scheduled_task_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteJob = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    val filteredJobs = when (filter) {
        "enabled" -> jobs.filter { it.enabled }
        "paused" -> jobs.filterNot { it.enabled }
        else -> jobs
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scheduled_tasks_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { nav.navigate(Screen.ScheduledJobEditor()) }) {
                        Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.scheduled_task_add))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val needsNotificationPermission = jobs.any { it.enabled && it.notificationEnabled }
            if (Build.VERSION.SDK_INT >= 33 && needsNotificationPermission && !permissionGranted) item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.scheduled_task_notification_missing))
                        TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text(stringResource(R.string.scheduled_task_notification_allow))
                        }
                    }
                }
            }
            if (jobs.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.scheduled_job_empty_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.scheduled_job_empty_desc), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { nav.navigate(Screen.ScheduledJobEditor()) }) {
                            Text(stringResource(R.string.scheduled_task_add))
                        }
                    }
                }
            }
            if (jobs.isNotEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "all" to R.string.scheduled_job_filter_all,
                        "enabled" to R.string.scheduled_job_filter_enabled,
                        "paused" to R.string.scheduled_job_filter_paused,
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = filter == value,
                            onClick = { filter = value },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
            if (filteredJobs.isEmpty() && jobs.isNotEmpty()) item {
                Text(stringResource(R.string.scheduled_job_no_filtered_tasks), Modifier.padding(12.dp))
            }
            items(filteredJobs, key = { it.id }) { job ->
                ScheduledJobCard(
                    job = job,
                    manager = manager,
                    onEdit = { nav.navigate(Screen.ScheduledJobEditor(job.id)) },
                    onHistory = { nav.navigate(Screen.ScheduledJobHistory(job.id)) },
                    onDelete = { deleteJob = job },
                    onToggle = { enabled ->
                        scope.launch {
                            runCatching { manager.setEnabled(job.id, enabled) }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }
                    },
                    onRun = {
                        scope.launch {
                            val started = runCatching { manager.runNow(job.id) }.getOrDefault(false)
                            if (!started) Toast.makeText(context, R.string.scheduled_task_already_running, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ScheduledJobCard(
    job: ScheduledJobEntity,
    manager: ScheduledJobManager,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    val runs by remember(job.id, manager) { manager.observeRuns(job.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val latest = runs.firstOrNull()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(job.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(jobModeLabel(job.mode), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Switch(checked = job.enabled, onCheckedChange = onToggle)
            }
            job.description?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            job.tags?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Text(scheduleSummary(job), style = MaterialTheme.typography.bodyMedium)
            Text(
                job.nextRunAtMs?.let { stringResource(R.string.scheduled_task_next, formatJobTime(it, job.timezone)) }
                    ?: stringResource(R.string.scheduled_task_no_next),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (latest != null) {
                HorizontalDivider()
                Text(
                    "${jobRunStatus(latest.outcome)} · ${formatJobTime(latest.startedAtMs, job.timezone)}" +
                        (latest.errorMessage?.takeIf(String::isNotBlank)?.let { "\n$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (latest.outcome == ScheduledJobOutcome.SUCCESS) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRun) { Text(stringResource(R.string.scheduled_task_run_now)) }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.scheduled_task_edit)) }
                TextButton(onClick = onHistory) { Text(stringResource(R.string.scheduled_job_history)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text(stringResource(R.string.scheduled_task_delete)) }
            }
        }
    }
}

@Composable
fun ScheduledJobHistoryPage(jobId: String, manager: ScheduledJobManager = koinInject()) {
    val nav = LocalNavController.current
    val runs by remember(jobId, manager) { manager.observeRuns(jobId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var job by remember(jobId) { mutableStateOf<ScheduledJobEntity?>(null) }
    LaunchedEffect(jobId) { job = manager.getJob(jobId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.name ?: stringResource(R.string.scheduled_job_run_history)) },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (runs.isEmpty()) item {
                Text(stringResource(R.string.scheduled_job_no_runs), Modifier.padding(12.dp))
            }
            items(runs, key = { it.id }) { run ->
                ScheduledJobRunCard(run, job?.timezone, onOpenConversation = { id -> nav.navigate(Screen.Chat(id)) })
            }
        }
    }
}

@Composable
private fun ScheduledJobRunCard(
    run: ScheduledJobRunEntity,
    timezone: String?,
    onOpenConversation: (String) -> Unit,
) {
    val actionSteps = remember(run.actionResultsJson) {
        run.actionResultsJson?.let { encoded ->
            runCatching { JsonInstant.decodeFromString<List<DirectModeActionRunner.StepResult>>(encoded) }.getOrNull()
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(jobRunStatus(run.outcome), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (run.manual) Text(stringResource(R.string.scheduled_job_manual), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                stringResource(R.string.scheduled_job_run_scheduled_at, formatJobTime(run.scheduledAtMs, timezone)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (run.startedAtMs != run.scheduledAtMs) Text(
                stringResource(R.string.scheduled_job_run_started_at, formatJobTime(run.startedAtMs, timezone)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            run.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            run.resultPreview?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!actionSteps.isNullOrEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                actionSteps.forEach { step ->
                    Text("${step.index + 1}. ${step.tool} · ${step.outcome}", style = MaterialTheme.typography.labelLarge)
                    step.output?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else run.actionResultsJson?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            run.conversationId?.let { conversationId ->
                TextButton(onClick = { onOpenConversation(conversationId) }) {
                    Text(stringResource(R.string.scheduled_job_open_conversation))
                }
            }
        }
    }
}

private data class DirectActionDraft(val tool: String, val arguments: String)

private data class SimpleCronDraft(
    val preset: String,
    val weekdayMask: Int,
    val hour: Int,
    val minute: Int,
)

private data class ScheduleFields(
    val type: String,
    val atUnixMs: Long?,
    val cronExpression: String?,
    val timezone: String?,
    val startAtUnixMs: Long?,
    val endAtUnixMs: Long?,
    val maxRuns: Int?,
    val catchup: String,
)

private fun ScheduledJobEntity.toScheduleFields() = ScheduleFields(
    type = scheduleType,
    atUnixMs = atUnixMs,
    cronExpression = cronExpression,
    timezone = timezone,
    startAtUnixMs = startAtUnixMs,
    endAtUnixMs = endAtUnixMs,
    maxRuns = maxRuns,
    catchup = catchup,
)

private fun parseSimpleCron(expression: String?): SimpleCronDraft? {
    val fields = expression?.trim()?.split(Regex("\\s+")) ?: return null
    if (fields.size != 5) return null
    val minute = fields[0].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val hour = fields[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    if (fields[2] != "*" || fields[3] != "*") return null
    return when (val days = fields[4]) {
        "*" -> SimpleCronDraft("daily", 0b0011111, hour, minute)
        "1-5" -> SimpleCronDraft("weekdays", 0b0011111, hour, minute)
        else -> {
            val values = days.split(",").map { it.toIntOrNull() ?: return null }
            if (values.isEmpty() || values.any { it !in 0..7 }) return null
            val mask = values.fold(0) { result, value ->
                val dayIndex = if (value == 0 || value == 7) 6 else value - 1
                result or (1 shl dayIndex)
            }
            SimpleCronDraft("weekly", mask, hour, minute)
        }
    }
}

private fun buildSimpleSchedule(
    preset: String,
    date: LocalDate,
    hour: Int,
    minute: Int,
    weekdayMask: Int,
): ScheduleFields {
    if (preset == "once") {
        return ScheduleFields(
            type = ScheduledJobType.ONCE,
            atUnixMs = date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            cronExpression = null,
            timezone = null,
            startAtUnixMs = null,
            endAtUnixMs = null,
            maxRuns = null,
            catchup = ScheduledJobCatchup.FIRE_ONCE,
        )
    }
    val days = when (preset) {
        "weekdays" -> "1-5"
        "weekly" -> (1..7).filter { weekdayMask and (1 shl (it - 1)) != 0 }.joinToString(",")
        else -> "*"
    }
    return ScheduleFields(
        type = ScheduledJobType.CRON,
        atUnixMs = null,
        cronExpression = "$minute $hour * * $days",
        timezone = null,
        startAtUnixMs = null,
        endAtUnixMs = null,
        maxRuns = null,
        catchup = ScheduledJobCatchup.FIRE_ONCE,
    )
}

@Composable
fun ScheduledJobEditorPage(jobId: String?, manager: ScheduledJobManager = koinInject()) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore: SettingsStore = koinInject()
    val json: Json = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val zoneToday = remember { LocalDate.now() }

    var ready by remember(jobId) { mutableStateOf(jobId == null) }
    var saveAttempted by remember(jobId) { mutableStateOf(false) }
    var name by remember(jobId) { mutableStateOf("") }
    var description by remember(jobId) { mutableStateOf("") }
    var tags by remember(jobId) { mutableStateOf("") }
    var mode by remember(jobId) { mutableStateOf(ScheduledJobMode.LLM) }
    var prompt by remember(jobId) { mutableStateOf("") }
    var assistantId by remember(jobId) { mutableStateOf(settings.assistantId.toString()) }
    var modelId by remember(jobId) { mutableStateOf<String?>(null) }
    var actions by remember(jobId) { mutableStateOf(listOf<DirectActionDraft>()) }
    var tools by remember(jobId) { mutableStateOf<List<ScheduledToolDescriptor>>(emptyList()) }
    var reminderTitle by remember(jobId) { mutableStateOf("") }
    var reminderBody by remember(jobId) { mutableStateOf("") }
    var completionNotificationMessage by remember(jobId) { mutableStateOf("") }
    var schedulePreset by remember(jobId) { mutableStateOf("daily") }
    var cronExpression by remember(jobId) { mutableStateOf("") }
    var weekdayMask by remember(jobId) { mutableStateOf(0b0011111) }
    var date by remember(jobId) { mutableStateOf(zoneToday.plusDays(1)) }
    var hour by remember(jobId) { mutableStateOf(9) }
    var minute by remember(jobId) { mutableStateOf(0) }
    var scheduleChanged by remember(jobId) { mutableStateOf(false) }
    var enabled by remember(jobId) { mutableStateOf(true) }
    var notify by remember(jobId) { mutableStateOf(true) }
    var moreExpanded by remember(jobId) { mutableStateOf(false) }
    var showAssistantPicker by remember(jobId) { mutableStateOf(false) }
    var showDatePicker by remember(jobId) { mutableStateOf(false) }
    var showTimePicker by remember(jobId) { mutableStateOf(false) }
    var selectedJob by remember(jobId) { mutableStateOf<ScheduledJobEntity?>(null) }

    LaunchedEffect(settings.assistantId, jobId) {
        if (jobId == null && settings.assistants.none { it.id.toString() == assistantId }) {
            assistantId = settings.assistantId.toString()
        }
    }
    LaunchedEffect(assistantId) {
        tools = manager.getDirectTools(assistantId)
        if (actions.isEmpty() && tools.isNotEmpty()) actions = listOf(DirectActionDraft(tools.first().name, "{}"))
    }
    LaunchedEffect(jobId) {
        if (jobId != null) {
            manager.getJob(jobId)?.let { job ->
                selectedJob = job
                name = job.name
                description = job.description.orEmpty()
                tags = job.tags.orEmpty()
                mode = job.mode
                prompt = job.prompt.orEmpty()
                assistantId = job.assistantId
                modelId = job.modelId
                reminderTitle = job.notificationTitle
                if (job.mode == ScheduledJobMode.REMINDER) reminderBody = job.notificationBody
                if (job.mode == ScheduledJobMode.LLM) completionNotificationMessage = job.notificationBody
                enabled = job.enabled
                notify = job.notificationEnabled
                if (job.scheduleType == ScheduledJobType.ONCE) {
                    schedulePreset = "once"
                    job.atUnixMs?.let {
                        val local = Instant.ofEpochMilli(it).atZone(zoneFor(job.timezone))
                        date = local.toLocalDate()
                        hour = local.hour
                        minute = local.minute
                    }
                } else {
                    cronExpression = job.cronExpression.orEmpty()
                    val simple = parseSimpleCron(cronExpression)
                    if (simple == null) {
                        schedulePreset = "legacy"
                    } else {
                        schedulePreset = simple.preset
                        weekdayMask = simple.weekdayMask
                        hour = simple.hour
                        minute = simple.minute
                    }
                }
                val parsedActions = runCatching { json.parseToJsonElement(job.actionsJson.orEmpty()) as JsonArray }.getOrNull()
                if (parsedActions != null) {
                    actions = parsedActions.mapNotNull { element ->
                        val item = element.jsonObject
                        val tool = item["tool"]?.toString()?.trim('"') ?: return@mapNotNull null
                        val args = item["args"]?.toString() ?: "{}"
                        DirectActionDraft(tool, args)
                    }
                }
            }
            scheduleChanged = false
            ready = true
        }
    }

    val assistantExists = settings.assistants.any { it.id.toString() == assistantId }
    val selectedAssistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
    val selectedModelId = modelId ?: selectedAssistant?.chatModelId?.toString() ?: settings.chatModelId.toString()
    val selectedModelAvailable = settings.providers.asSequence().flatMap { it.models.asSequence() }
        .any { it.id.toString() == selectedModelId && it.type == ModelType.CHAT }
    val nameError = saveAttempted && name.isBlank()
    val assistantError = saveAttempted && mode != ScheduledJobMode.REMINDER && !assistantExists
    val promptError = saveAttempted && mode == ScheduledJobMode.LLM && prompt.isBlank()
    val modelError = saveAttempted && mode == ScheduledJobMode.LLM && assistantExists && !selectedModelAvailable
    val actionListError = saveAttempted && mode == ScheduledJobMode.DIRECT && actions.isEmpty()
    val reminderBodyError = saveAttempted && mode == ScheduledJobMode.REMINDER && reminderBody.isBlank()
    val oneTimeAt = remember(date, hour, minute) {
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val dateError = saveAttempted && (selectedJob == null || scheduleChanged) &&
        schedulePreset == "once" && oneTimeAt <= System.currentTimeMillis()
    val weekdayError = saveAttempted && schedulePreset == "weekly" && weekdayMask == 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (jobId == null) R.string.scheduled_task_add else R.string.scheduled_task_edit)) },
                navigationIcon = { BackButton() },
            )
        },
        bottomBar = {
            androidx.compose.material3.Surface(tonalElevation = 2.dp) {
                Button(
                    onClick = {
                        saveAttempted = true
                        val invalidAssistant = mode != ScheduledJobMode.REMINDER && !assistantExists
                        val invalidPrompt = mode == ScheduledJobMode.LLM && prompt.isBlank()
                        val invalidModel = mode == ScheduledJobMode.LLM && assistantExists && !selectedModelAvailable
                        val invalidActions = mode == ScheduledJobMode.DIRECT && actions.isEmpty()
                        val invalidDirectAction = mode == ScheduledJobMode.DIRECT && actions.any { draft ->
                            tools.none { it.name == draft.tool } ||
                                runCatching { Json.parseToJsonElement(draft.arguments).jsonObject }.isFailure
                        }
                        val invalidReminder = mode == ScheduledJobMode.REMINDER && reminderBody.isBlank()
                        val invalidDate = (selectedJob == null || scheduleChanged) &&
                            schedulePreset == "once" && oneTimeAt <= System.currentTimeMillis()
                        val invalidWeekdays = schedulePreset == "weekly" && weekdayMask == 0
                        val invalid = name.isBlank() || invalidAssistant || invalidPrompt || invalidModel ||
                            invalidActions || invalidDirectAction || invalidReminder || invalidDate || invalidWeekdays
                        if (invalid) return@Button
                        scope.launch {
                            val job = runCatching {
                                val actionJson = if (mode == ScheduledJobMode.DIRECT) buildActionsJson(actions, json) else null
                                val preserveSchedule = selectedJob != null && !scheduleChanged
                                val schedule = if (preserveSchedule) {
                                    selectedJob!!.toScheduleFields()
                                } else buildSimpleSchedule(
                                    preset = schedulePreset,
                                    date = date,
                                    hour = hour,
                                    minute = minute,
                                    weekdayMask = weekdayMask,
                                )
                                val now = System.currentTimeMillis()
                                ScheduledJobEntity(
                                    id = jobId ?: Uuid.random().toString(),
                                    name = name.trim(),
                                    description = description.trim().takeIf(String::isNotEmpty),
                                    tags = tags.split(',').map(String::trim).filter(String::isNotEmpty).distinct().joinToString(",")
                                        .takeIf(String::isNotEmpty),
                                    mode = mode,
                                    prompt = if (mode == ScheduledJobMode.LLM) prompt.trim() else null,
                                    actionsJson = actionJson,
                                    assistantId = assistantId,
                                    modelId = if (mode == ScheduledJobMode.LLM) modelId else null,
                                    scheduleType = schedule.type,
                                    atUnixMs = schedule.atUnixMs,
                                    cronExpression = schedule.cronExpression,
                                    timezone = schedule.timezone,
                                    startAtUnixMs = schedule.startAtUnixMs,
                                    endAtUnixMs = schedule.endAtUnixMs,
                                    maxRuns = schedule.maxRuns,
                                    runsSoFar = selectedJob?.runsSoFar ?: 0,
                                    enabled = enabled,
                                    lastRunAtMs = selectedJob?.lastRunAtMs,
                                    nextRunAtMs = null,
                                    catchup = schedule.catchup,
                                    notificationEnabled = mode == ScheduledJobMode.REMINDER || notify,
                                    notificationTitle = reminderTitle.trim(),
                                    notificationBody = when (mode) {
                                        ScheduledJobMode.REMINDER -> reminderBody.trim()
                                        ScheduledJobMode.LLM -> completionNotificationMessage.trim()
                                            .takeCodePoints(MAX_NOTIFICATION_MESSAGE_CODE_POINTS)
                                        else -> ""
                                    },
                                    createdAtMs = selectedJob?.createdAtMs ?: now,
                                    updatedAtMs = now,
                                )
                            }.getOrElse {
                                Toast.makeText(context, it.message ?: "Invalid task", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            runCatching { manager.save(job) }
                                .onSuccess { nav.popBackStack() }
                                .onFailure { Toast.makeText(context, it.message ?: "Unable to save task", Toast.LENGTH_LONG).show() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    enabled = ready,
                ) {
                    Text(stringResource(R.string.scheduled_task_save))
                }
            }
        },
    ) { padding ->
        if (!ready) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EditorSection(title = stringResource(R.string.scheduled_job_editor_task_content)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.scheduled_task_name)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        isError = nameError,
                        supportingText = if (nameError) ({ Text(stringResource(R.string.scheduled_job_error_name)) }) else null,
                    )
                    val modes = listOf(
                            ScheduledJobMode.LLM to R.string.scheduled_task_mode_ai,
                            ScheduledJobMode.DIRECT to R.string.scheduled_job_mode_direct,
                            ScheduledJobMode.REMINDER to R.string.scheduled_task_mode_reminder,
                        )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(modes.size) { index ->
                            val (value, label) = modes[index]
                            FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(stringResource(label)) })
                        }
                    }
                    if (mode != ScheduledJobMode.REMINDER) {
                        OutlinedButton(
                            onClick = { showAssistantPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                selectedAssistant?.name?.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
                                    ?: stringResource(R.string.scheduled_task_assistant),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (assistantError) Text(
                            stringResource(R.string.scheduled_job_error_assistant),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when (mode) {
                        ScheduledJobMode.LLM -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.scheduled_task_model),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.weight(1f))
                                ModelSelector(
                                    modelId = modelId?.let { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() }
                                        ?: selectedAssistant?.chatModelId
                                        ?: settings.chatModelId,
                                    providers = settings.providers,
                                    type = ModelType.CHAT,
                                    modifier = Modifier.weight(1f),
                                    onSelect = { selected ->
                                        modelId = selected.id.toString().takeIf { selected.modelId.isNotBlank() }
                                    },
                                )
                            }
                            if (modelId == null) Text(
                                stringResource(R.string.scheduled_task_follow_assistant_model),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ) else TextButton(onClick = { modelId = null }) {
                                Text(stringResource(R.string.scheduled_task_follow_assistant_model))
                            }
                            if (modelError) Text(
                                stringResource(R.string.scheduled_job_error_model),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = prompt, onValueChange = { prompt = it },
                                label = { Text(stringResource(R.string.scheduled_task_prompt)) },
                                modifier = Modifier.fillMaxWidth(), minLines = 5,
                                isError = promptError,
                                supportingText = if (promptError) ({ Text(stringResource(R.string.scheduled_job_error_prompt)) }) else null,
                            )
                        }
                        ScheduledJobMode.DIRECT -> {
                            Text(stringResource(R.string.scheduled_job_actions), style = MaterialTheme.typography.titleSmall)
                            if (actionListError) Text(
                                stringResource(R.string.scheduled_job_error_actions),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (tools.isEmpty()) Text(stringResource(R.string.scheduled_job_no_tools), color = MaterialTheme.colorScheme.error)
                            actions.forEachIndexed { index, action ->
                                DirectActionEditor(
                                    index = index,
                                    action = action,
                                    tools = tools,
                                    onChange = { changed -> actions = actions.toMutableList().also { it[index] = changed } },
                                    onMove = { delta ->
                                        val target = (index + delta).coerceIn(0, actions.lastIndex)
                                        if (target != index) actions = actions.toMutableList().also {
                                            val item = it.removeAt(index); it.add(target, item)
                                        }
                                    },
                                    onRemove = { actions = actions.filterIndexed { actionIndex, _ -> actionIndex != index } },
                                    errorMessage = if (saveAttempted && (
                                            tools.none { it.name == action.tool } ||
                                                runCatching { Json.parseToJsonElement(action.arguments).jsonObject }.isFailure
                                            )) stringResource(R.string.scheduled_job_error_action_arguments) else null,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    tools.firstOrNull()?.let { tool ->
                                        actions = actions + DirectActionDraft(tool.name, argumentTemplate(tool.parametersJson))
                                    }
                                },
                                enabled = tools.isNotEmpty() && actions.size < DirectModeActionRunner.MAX_ACTIONS,
                            ) { Text(stringResource(R.string.scheduled_job_add_action)) }
                        }
                        ScheduledJobMode.REMINDER -> {
                            OutlinedTextField(
                                value = reminderBody, onValueChange = { reminderBody = it },
                                label = { Text(stringResource(R.string.scheduled_task_notification_body)) },
                                modifier = Modifier.fillMaxWidth(), minLines = 4,
                                isError = reminderBodyError,
                                supportingText = if (reminderBodyError) ({ Text(stringResource(R.string.scheduled_job_error_reminder)) }) else null,
                            )
                            OutlinedTextField(
                                value = reminderTitle, onValueChange = { reminderTitle = it },
                                label = { Text(stringResource(R.string.scheduled_task_notification_title)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                        }
                    }
            }

            EditorSection(title = stringResource(R.string.scheduled_job_editor_schedule)) {
                if (schedulePreset == "legacy") Text(
                    stringResource(R.string.scheduled_job_preserve_custom_schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val scheduleOptions = listOf(
                        "once" to R.string.scheduled_job_once,
                        "daily" to R.string.scheduled_job_daily,
                        "weekdays" to R.string.scheduled_job_weekdays,
                        "weekly" to R.string.scheduled_job_weekly,
                    )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(scheduleOptions.size) { index ->
                        val (value, label) = scheduleOptions[index]
                        FilterChip(
                            selected = schedulePreset == value,
                            onClick = {
                                schedulePreset = value
                                scheduleChanged = true
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                if (schedulePreset == "once") {
                    ScheduleValueButton(
                        label = stringResource(R.string.scheduled_task_date),
                        value = date.format(jobDateFormat),
                        onClick = { showDatePicker = true },
                    )
                    ScheduleValueButton(
                        label = stringResource(R.string.scheduled_task_time),
                        value = "%02d:%02d".format(hour, minute),
                        onClick = { showTimePicker = true },
                    )
                    if (dateError) Text(
                        stringResource(R.string.scheduled_job_error_future_time),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (schedulePreset != "legacy") {
                    ScheduleValueButton(
                        label = stringResource(R.string.scheduled_task_time),
                        value = "%02d:%02d".format(hour, minute),
                        onClick = { showTimePicker = true },
                    )
                    if (schedulePreset == "weekly") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(weekLabels.size) { index ->
                                val bit = 1 shl index
                                FilterChip(
                                    selected = weekdayMask and bit != 0,
                                    onClick = {
                                        weekdayMask = weekdayMask xor bit
                                        scheduleChanged = true
                                    },
                                    label = { Text(stringResource(weekLabels[index])) },
                                )
                            }
                        }
                    }
                    if (weekdayError) Text(
                        stringResource(R.string.scheduled_task_select_day),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.scheduled_task_enabled), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(if (enabled) R.string.scheduled_job_filter_enabled else R.string.scheduled_job_filter_paused),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            EditorSection(title = stringResource(R.string.scheduled_job_editor_notifications)) {
                if (mode == ScheduledJobMode.REMINDER) {
                    Text(
                        stringResource(R.string.scheduled_job_reminder_notification_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.scheduled_task_notifications), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.scheduled_job_notification_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = notify, onCheckedChange = { notify = it })
                    }
                    if (notify) OutlinedTextField(
                        value = reminderTitle, onValueChange = { reminderTitle = it },
                        label = { Text(stringResource(R.string.scheduled_task_notification_title)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    if (notify && mode == ScheduledJobMode.LLM) {
                        val messageLength = completionNotificationMessage.codePointCount(0, completionNotificationMessage.length)
                        OutlinedTextField(
                            value = completionNotificationMessage,
                            onValueChange = { completionNotificationMessage = it.takeCodePoints(MAX_NOTIFICATION_MESSAGE_CODE_POINTS) },
                            label = { Text(stringResource(R.string.scheduled_job_completion_notification_message)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3,
                            supportingText = {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.scheduled_job_completion_notification_hint))
                                    Text("$messageLength/$MAX_NOTIFICATION_MESSAGE_CODE_POINTS")
                                }
                            },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.scheduled_job_more_details), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.scheduled_job_more_details_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { moreExpanded = !moreExpanded }) {
                        Text(stringResource(if (moreExpanded) R.string.scheduled_job_collapse else R.string.scheduled_job_expand))
                    }
                }
                if (moreExpanded) {
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text(stringResource(R.string.scheduled_job_description)) },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                    OutlinedTextField(
                        value = tags, onValueChange = { tags = it },
                        label = { Text(stringResource(R.string.scheduled_job_tags)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                }
            }
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.scheduled_job_choose_time)) },
            text = { TimeInput(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    hour = timeState.hour
                    minute = timeState.minute
                    scheduleChanged = true
                    showTimePicker = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showAssistantPicker) {
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = selectedAssistant ?: Assistant(),
            onAssistantSelected = { assistant ->
                assistantId = assistant.id.toString()
                modelId = null
                showAssistantPicker = false
            },
            onDismiss = { showAssistantPicker = false },
        )
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = date.toUtcMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { date = dateFromUtcMillis(it) }
                    scheduleChanged = true
                    showDatePicker = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state = dateState)
        }
    }
}

@Composable
private fun DirectActionEditor(
    index: Int,
    action: DirectActionDraft,
    tools: List<ScheduledToolDescriptor>,
    onChange: (DirectActionDraft) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    errorMessage: String?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onMove(-1) }) { Text("↑") }
                TextButton(onClick = { onMove(1) }) { Text("↓") }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.scheduled_task_delete)) }
            }
            SelectorField(
                label = stringResource(R.string.scheduled_job_tool_picker),
                selectedLabel = tools.firstOrNull { it.name == action.tool }?.name ?: action.tool,
                options = tools.map { it.name to it.name },
                onSelected = { selected ->
                    val tool = tools.firstOrNull { it.name == selected }
                    onChange(action.copy(tool = selected, arguments = argumentTemplate(tool?.parametersJson)))
                },
            )
            tools.firstOrNull { it.name == action.tool }?.let { tool ->
                if (tool.description.isNotBlank()) Text(tool.description, style = MaterialTheme.typography.bodySmall)
                tool.parametersJson?.let { schema ->
                    Text("${stringResource(R.string.scheduled_job_action_schema)}: $schema", style = MaterialTheme.typography.labelSmall)
                }
            }
            OutlinedTextField(
                value = action.arguments, onValueChange = { onChange(action.copy(arguments = it)) },
                label = { Text(stringResource(R.string.scheduled_job_arguments)) },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                isError = errorMessage != null,
                supportingText = errorMessage?.let { message -> ({ Text(message) }) },
            )
        }
    }
}

@Composable
private fun SelectorField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = options.isNotEmpty()) {
            Text(selectedLabel.ifBlank { label }, Modifier.weight(1f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { onSelected(value); expanded = false })
            }
        }
    }
}

private fun argumentTemplate(parametersJson: String?): String = runCatching {
    val properties = parametersJson?.let { Json.parseToJsonElement(it) as? JsonObject }
        ?.get("properties") as? JsonObject ?: return@runCatching "{}"
    buildJsonObject {
        properties.forEach { (name, definition) ->
            val type = (definition as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.content }
            val value = when (type) {
                "string" -> JsonPrimitive("")
                "integer", "number" -> JsonPrimitive(0)
                "boolean" -> JsonPrimitive(false)
                "array" -> kotlinx.serialization.json.buildJsonArray {}
                "object" -> buildJsonObject {}
                else -> JsonNull
            }
            put(name, value)
        }
    }.toString()
}.getOrDefault("{}")

private fun buildActionsJson(actions: List<DirectActionDraft>, json: Json): String {
    val array = buildJsonArray {
        actions.forEach { action ->
            val args = json.parseToJsonElement(action.arguments).jsonObject
            add(buildJsonObject {
                put("tool", action.tool)
                put("args", args)
            })
        }
    }
    return array.toString()
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun dateFromUtcMillis(value: Long): LocalDate =
    Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun EditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ScheduleValueButton(label: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text("  ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun jobModeLabel(mode: String): String = stringResource(when (mode) {
    ScheduledJobMode.LLM -> R.string.scheduled_task_mode_ai
    ScheduledJobMode.DIRECT -> R.string.scheduled_job_mode_direct
    else -> R.string.scheduled_task_mode_reminder
})

@Composable
private fun jobRunStatus(status: String): String = stringResource(when (status) {
    "running" -> R.string.scheduled_job_status_running
    ScheduledJobOutcome.SUCCESS -> R.string.scheduled_job_status_success
    ScheduledJobOutcome.TIMED_OUT -> R.string.scheduled_job_status_timed_out
    ScheduledJobOutcome.WAITING_APPROVAL -> R.string.scheduled_job_status_waiting_approval
    ScheduledJobOutcome.INTERRUPTED -> R.string.scheduled_job_status_interrupted
    ScheduledJobOutcome.CONCURRENT_SKIP -> R.string.scheduled_job_status_concurrent_skip
    ScheduledJobOutcome.SKIPPED_CATCHUP -> R.string.scheduled_job_status_skipped_catchup
    ScheduledJobOutcome.PROCESS_KILLED_REPLAY -> R.string.scheduled_job_status_process_killed
    else -> R.string.scheduled_job_status_failed
})

private fun scheduleSummary(job: ScheduledJobEntity): String {
    val zone = zoneFor(job.timezone)
    return if (job.scheduleType == ScheduledJobType.ONCE) {
        job.atUnixMs?.let { formatJobTime(it, job.timezone) }.orEmpty()
    } else {
        job.cronExpression.orEmpty()
    } + " · ${zone.id}"
}

private fun formatJobTime(epochMs: Long, timezone: String?): String = runCatching {
    Instant.ofEpochMilli(epochMs).atZone(zoneFor(timezone)).format(jobDateTimeFormat)
}.getOrDefault("—")

private fun zoneFor(timezone: String?): ZoneId =
    timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
