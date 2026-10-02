// Adapted from xiaoyuili/Yuihub, AGPL-3.0.
package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ScheduleType
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

/** scheduled_task 工具的 name，子代理过滤与 persona 白名单按名引用 */
const val SCHEDULED_TASK_TOOL_NAME = "scheduled_task"

/**
 * 让模型在对话中管理**当前助手自己的**定时任务（创建/编辑/删除/启用停用）。
 *
 * 与 UI 的约束对齐：
 * - 所有操作强制绑定 [assistantId]，模型无法读到也无法改到别的助手的任务；
 *   试图传别的助手 id 只会得到 “not found” 而不是越权成功。
 * - 新建任务的触发时刻与 UI 一致：DAILY 用 time_of_day（HH:mm），INTERVAL 用 interval_minutes，
 *   ONCE 用 trigger_at（"yyyy-MM-dd HH:mm"）。
 * - 写操作走 ScheduledTaskRepository，WorkManager 调度自动同步。
 *
 * 工具的 systemPrompt 会把「没有任务时也要知道可以建」以及字段约定告诉模型，
 * 避免它因为列表为空就以为功能不可用。
 */
fun createScheduledTaskTools(
    repository: ScheduledTaskRepository,
    assistantId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = SCHEDULED_TASK_TOOL_NAME,
        description = """
            Manage scheduled tasks that belong to THIS assistant only (tasks of other assistants are not accessible).
            `action`: list | create | update | delete | set_enabled.
            Schedule types: DAILY (`time_of_day` "HH:mm"), INTERVAL (`interval_minutes` >= 15), ONCE (`trigger_at` "yyyy-MM-dd HH:mm").
            create needs `name` + `prompt` (+ one schedule spec); update only needs the fields to change.
            Tasks run in a fresh conversation of this assistant and the result shows up in the chat list.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("list")
                                add("create")
                                add("update")
                                add("delete")
                                add("set_enabled")
                            },
                        )
                        put("description", "Operation to perform")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Task id (required for update/delete/set_enabled, or pass `name`)")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Task name (creates with this name; also used to locate a task when `id` is omitted)")
                    })
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "The prompt to send to the assistant on each run")
                    })
                    put("schedule_type", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("DAILY")
                                add("INTERVAL")
                                add("ONCE")
                            },
                        )
                        put("description", "Schedule type, defaults to DAILY")
                    })
                    put("time_of_day", buildJsonObject {
                        put("type", "string")
                        put("description", "For DAILY: time of day in HH:mm (local time)")
                    })
                    put("interval_minutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "For INTERVAL: minutes between runs, minimum 15")
                    })
                    put("trigger_at", buildJsonObject {
                        put("type", "string")
                        put("description", "For ONCE: trigger moment in \"yyyy-MM-dd HH:mm\" (local time)")
                    })
                    put("enabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "For set_enabled: whether the task is active")
                    })
                },
                required = listOf("action"),
            )
        },
        systemPrompt = { _, _ ->
            """
            You can manage this assistant's scheduled tasks with `$SCHEDULED_TASK_TOOL_NAME`
            (list / create / update / delete / set_enabled). Only this assistant's tasks are visible and editable.
            """.trimIndent()
        },
        execute = { args ->
            val obj = args.jsonObject
            val action = obj["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val tasks = repository.getTasksForAssistant(assistantId.toString())
            when (action) {
                "list" -> listOf(UIMessagePart.Text(renderTasks(tasks)))

                "create" -> createTask(repository, assistantId, obj, tasks)

                "update" -> updateTask(repository, obj, tasks)

                "delete" -> deleteTask(repository, obj, tasks)

                "set_enabled" -> setEnabled(repository, obj, tasks)

                else -> listOf(UIMessagePart.Text("Unknown action '$action'"))
            }
        },
    ),
)

private suspend fun createTask(
    repository: ScheduledTaskRepository,
    assistantId: Uuid,
    obj: JsonObject,
    existing: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    require(name.isNotEmpty()) { "name is required for action=create" }
    val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    require(prompt.isNotEmpty()) { "prompt is required for action=create" }
    require(existing.none { it.name == name }) {
        "A task named '$name' already exists. Use action=update to modify it."
    }

    val schedule = parseSchedule(obj)
    val now = System.currentTimeMillis()
    val task = ScheduledTaskEntity(
        id = Uuid.random().toString(),
        name = name,
        prompt = prompt,
        assistantId = assistantId.toString(),
        scheduleType = schedule.type.name,
        triggerAt = schedule.triggerAt,
        intervalMinutes = schedule.intervalMinutes,
        timeOfDayMinutes = schedule.timeOfDayMinutes,
        enabled = true,
        revision = Uuid.random().toString(),
        createdAt = now,
        updatedAt = now,
    )
    repository.upsert(task)
    return listOf(UIMessagePart.Text("Created task '${task.name}' (id=${task.id}, ${describe(task)})"))
}

private suspend fun updateTask(
    repository: ScheduledTaskRepository,
    obj: JsonObject,
    tasks: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val target = findTask(obj, tasks) ?: return listOf(UIMessagePart.Text("No matching task found"))

    var updated = target
    obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { newName ->
        require(tasks.none { it.id != target.id && it.name == newName }) {
            "A task named '$newName' already exists."
        }
        updated = updated.copy(name = newName)
    }
    obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { newPrompt ->
        updated = updated.copy(prompt = newPrompt)
    }
    // schedule 字段按需覆盖：给了任意一项就整体解析一次（parseSchedule 会用现有值兜底未给的项）
    if (obj.keys.any { it in SCHEDULE_KEYS }) {
        val schedule = parseSchedule(obj, fallback = target)
        updated = updated.copy(
            scheduleType = schedule.type.name,
            triggerAt = schedule.triggerAt,
            intervalMinutes = schedule.intervalMinutes,
            timeOfDayMinutes = schedule.timeOfDayMinutes,
        )
    }

    if (updated == target) {
        return listOf(UIMessagePart.Text("Nothing to update: no recognized fields were provided."))
    }
    repository.upsert(updated.copy(updatedAt = System.currentTimeMillis()))
    return listOf(UIMessagePart.Text("Updated task '${updated.name}' (id=${updated.id}, ${describe(updated)})"))
}

private suspend fun deleteTask(
    repository: ScheduledTaskRepository,
    obj: JsonObject,
    tasks: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val target = findTask(obj, tasks) ?: return listOf(UIMessagePart.Text("No matching task found"))
    repository.delete(target)
    return listOf(UIMessagePart.Text("Deleted task '${target.name}'"))
}

private suspend fun setEnabled(
    repository: ScheduledTaskRepository,
    obj: JsonObject,
    tasks: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val target = findTask(obj, tasks) ?: return listOf(UIMessagePart.Text("No matching task found"))
    val enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.trim()?.toBooleanStrictOrNull()
        ?: return listOf(UIMessagePart.Text("enabled (true/false) is required for action=set_enabled"))
    repository.setEnabled(target.id, enabled, System.currentTimeMillis())
    return listOf(
        UIMessagePart.Text("${if (enabled) "Enabled" else "Disabled"} task '${target.name}'"),
    )
}

/** 定位任务：优先 id，其次唯一 name；只在传入的（已按助手限定的）列表内查找 */
internal fun findTask(obj: JsonObject, tasks: List<ScheduledTaskEntity>): ScheduledTaskEntity? {
    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
    if (!id.isNullOrEmpty()) {
        return tasks.firstOrNull { it.id == id }
    }
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
    if (!name.isNullOrEmpty()) {
        return tasks.firstOrNull { it.name == name }
    }
    return null
}

internal data class ParsedSchedule(
    val type: ScheduleType,
    val triggerAt: Long,
    val intervalMinutes: Int,
    val timeOfDayMinutes: Int,
)

private val SCHEDULE_KEYS = setOf("schedule_type", "time_of_day", "interval_minutes", "trigger_at")

/**
 * 解析调度参数。[fallback] 为更新场景下的原任务（未给的字段沿用原值）；
 * 新建时兜底为 DAILY 09:00 / 24h。
 */
internal fun parseSchedule(obj: JsonObject, fallback: ScheduledTaskEntity? = null): ParsedSchedule {
    val type = obj["schedule_type"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        ScheduleType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("Invalid schedule_type '$raw' (expected DAILY / INTERVAL / ONCE)")
    } ?: fallback?.let { runCatching { ScheduleType.valueOf(it.scheduleType) }.getOrDefault(ScheduleType.DAILY) }
        ?: ScheduleType.DAILY

    var triggerAt = fallback?.triggerAt ?: 0L
    var intervalMinutes = fallback?.intervalMinutes ?: DEFAULT_INTERVAL_MINUTES
    var timeOfDayMinutes = fallback?.timeOfDayMinutes ?: DEFAULT_TIME_OF_DAY_MINUTES

    when (type) {
        ScheduleType.DAILY -> {
            obj["time_of_day"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                timeOfDayMinutes = parseTimeOfDay(text).coerceIn(0, 1439)
            }
        }

        ScheduleType.INTERVAL -> {
            obj["interval_minutes"]?.let { value ->
                val minutes = value.jsonPrimitive.intOrNull ?: error("interval_minutes must be an integer")
                require(minutes >= 15) { "interval_minutes must be at least 15" }
                intervalMinutes = minutes
            }
        }

        ScheduleType.ONCE -> {
            val text = obj["trigger_at"]?.jsonPrimitive?.contentOrNull?.trim()
            triggerAt = if (text != null) parseTriggerAt(text) else fallback?.triggerAt
                ?: error("trigger_at (yyyy-MM-dd HH:mm) is required for schedule_type=ONCE")
        }
    }

    return ParsedSchedule(
        type = type,
        triggerAt = triggerAt,
        intervalMinutes = intervalMinutes,
        timeOfDayMinutes = timeOfDayMinutes,
    )
}

internal fun parseTimeOfDay(text: String): Int {
    val match = Regex("^(\\d{1,2}):(\\d{1,2})$").find(text)
        ?: throw IllegalArgumentException("Invalid time_of_day '$text' (expected HH:mm)")
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    require(hour in 0..23 && minute in 0..59) { "Invalid time_of_day '$text' (expected HH:mm)" }
    return hour * 60 + minute
}

internal fun parseTriggerAt(text: String): Long {
    require(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$").matches(text)) { "Invalid trigger_at" }
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { isLenient = false }
    return runCatching { format.parse(text)?.time }
        .getOrNull()
        ?: throw IllegalArgumentException("Invalid trigger_at '$text' (expected \"yyyy-MM-dd HH:mm\")")
}

internal fun describe(task: ScheduledTaskEntity): String = when (
    runCatching { ScheduleType.valueOf(task.scheduleType) }.getOrDefault(ScheduleType.DAILY)
) {
    ScheduleType.DAILY -> "daily at %02d:%02d".format(task.timeOfDayMinutes / 60, task.timeOfDayMinutes % 60)

    ScheduleType.INTERVAL -> "every ${task.intervalMinutes.coerceAtLeast(15)} min"

    ScheduleType.ONCE -> "once at " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(task.triggerAt))
}

private fun renderTasks(tasks: List<ScheduledTaskEntity>): String {
    if (tasks.isEmpty()) {
        return "No scheduled tasks for this assistant yet. Use action=create to add one."
    }
    // id 用原始 JSON 数组输出，避免模型把 id 抄错
    val lines = tasks.map { task ->
        buildJsonObject {
            put("id", task.id)
            put("name", task.name)
            put("prompt", task.prompt)
            put("schedule", describe(task))
            put("enabled", task.enabled)
            put("last_run_status", task.lastRunStatus.ifBlank { "NEVER" })
        }
    }
    return buildString {
        appendLine("Scheduled tasks of this assistant (${tasks.size}):")
        appendLine(JsonInstantPretty.encodeToString(buildJsonArray { lines.forEach { add(it) } }))
    }
}

/** 与 ScheduledTasksVM 保持一致的默认值（此处不能依赖 UI 层，故独立声明） */
private const val DEFAULT_TIME_OF_DAY_MINUTES = 9 * 60
private const val DEFAULT_INTERVAL_MINUTES = 24 * 60
