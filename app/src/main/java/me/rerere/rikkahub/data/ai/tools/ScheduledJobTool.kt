package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ScheduledJobCatchup
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobMode
import me.rerere.rikkahub.data.db.entity.ScheduledJobType
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.scheduled.DirectModeActionRunner
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

internal fun buildScheduledJobTool(
    json: Json,
    assistant: Assistant,
    model: Model,
    availableTools: List<Tool>,
    saveJob: suspend (ScheduledJobEntity) -> Unit,
): Tool {
    val directToolHelp = availableTools
        .filter { DirectModeActionRunner.isHeadlessSafeTool(it.name) }
        .joinToString("\n") { tool ->
            val schema = runCatching { tool.parameters()?.let { json.encodeToString(it) } }.getOrNull()
            "- ${tool.name}: ${tool.description}" + (schema?.let { " Parameters: $it" } ?: "")
        }
        .ifBlank { "No headless local tools are enabled for this assistant." }

    return Tool(
        name = "create_scheduled_task",
        description = """
            Create a scheduled task on this device for the current assistant. Use this when the user asks to run an AI task later, make a reminder, or run enabled local tools on a schedule.
            Always clarify missing or ambiguous dates/times before calling this tool. For one-time jobs use an ISO-8601 run_at value; local timestamps use the supplied IANA timezone or the device timezone. For repeating jobs use a five-field Unix cron expression in that timezone, for example `0 9 * * 1-5` for weekdays at 09:00 or `*/15 * * * *` every 15 minutes.
            Modes: `ai` requires a clear prompt; `reminder` requires notification_message; `direct` requires actions, each containing `tool` and JSON `args`. Direct tasks can use only currently enabled, headless-safe local tools listed below, and tools that require interactive approval are rejected. Keep notification_message to at most 60 Unicode characters; it is shown on a phone notification. If omitted for AI mode, the app uses a short preview of the AI result.
            Creating a task schedules future automatic activity, so this tool always asks the user to approve the proposed task before saving it.
            Enabled local tools for this assistant:
            $directToolHelp
        """.trimIndent().replace("\n", " "),
        needsApproval = { true },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", stringProperty("Short task title."))
                    put("mode", enumProperty(listOf("ai", "direct", "reminder"), "Task execution mode."))
                    put("description", stringProperty("Optional note about the task."))
                    put("schedule_type", enumProperty(listOf("once", "cron"), "Run once at a time or repeat using cron."))
                    put("run_at", stringProperty("Required for once: ISO-8601 local or offset date-time, e.g. 2026-10-01T09:00:00+08:00."))
                    put("cron_expression", stringProperty("Required for cron: five-field Unix cron expression, e.g. 0 9 * * 1-5."))
                    put("timezone", stringProperty("Optional IANA timezone, e.g. Asia/Shanghai. Defaults to the device timezone."))
                    put("max_runs", buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional maximum number of successful scheduled runs for a repeating task.")
                    })
                    put("prompt", stringProperty("Required for ai mode: instructions the assistant should execute at each run."))
                    put("actions", buildJsonObject {
                        put("type", "array")
                        put("description", "Required for direct mode: ordered local tool calls. Each item has a tool name and args object.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("tool", stringProperty("Enabled local tool name."))
                                put("args", buildJsonObject {
                                    put("type", "object")
                                    put("description", "Arguments for the selected local tool.")
                                    put("additionalProperties", true)
                                })
                            })
                            put("required", buildJsonArray {
                                add("tool")
                                add("args")
                            })
                        })
                    })
                    put("notification_enabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether to show a notification when the task completes. Defaults to true.")
                    })
                    put("notification_message", stringProperty("Optional phone notification text (maximum 60 Unicode characters); required for reminder mode."))
                },
                required = listOf("name", "mode", "schedule_type"),
            )
        },
        execute = { input ->
            val args = input.jsonObject
            fun text(key: String) = args[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

            val name = text("name") ?: error("Task name is required")
            val description = text("description")?.take(500)
            val timezone = text("timezone") ?: ZoneId.systemDefault().id
            val zone = ZoneId.of(timezone)
            val scheduleType = when (text("schedule_type")) {
                "once" -> ScheduledJobType.ONCE
                "cron" -> ScheduledJobType.CRON
                else -> error("schedule_type must be once or cron")
            }
            val atUnixMs = when (scheduleType) {
                ScheduledJobType.ONCE -> parseRunAt(text("run_at") ?: error("run_at is required for a one-time task"), zone)
                else -> null
            }
            val cronExpression = when (scheduleType) {
                ScheduledJobType.CRON -> text("cron_expression") ?: error("cron_expression is required for a repeating task")
                else -> null
            }
            val maxRuns = args["max_runs"]?.jsonPrimitive?.intOrNull
            require(maxRuns == null || maxRuns > 0) { "max_runs must be a positive integer" }
            val enabled = args["notification_enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            val notificationMessage = text("notification_message")?.takeCodePoints(MAX_NOTIFICATION_MESSAGE_CODE_POINTS).orEmpty()
            val mode = when (text("mode")) {
                "ai" -> ScheduledJobMode.LLM
                "direct" -> ScheduledJobMode.DIRECT
                "reminder" -> ScheduledJobMode.REMINDER
                else -> error("mode must be ai, direct, or reminder")
            }
            val prompt = text("prompt")
            val actions = args["actions"] as? JsonArray
            when (mode) {
                ScheduledJobMode.LLM -> require(!prompt.isNullOrBlank()) { "prompt is required for ai mode" }
                ScheduledJobMode.DIRECT -> require(actions?.isNotEmpty() == true) { "actions are required for direct mode" }
                ScheduledJobMode.REMINDER -> require(notificationMessage.isNotBlank()) { "notification_message is required for reminder mode" }
            }

            val job = ScheduledJobEntity(
                id = Uuid.random().toString(),
                name = name.take(120),
                description = description,
                tags = null,
                mode = mode,
                prompt = if (mode == ScheduledJobMode.LLM) prompt else null,
                actionsJson = if (mode == ScheduledJobMode.DIRECT) actions?.toString() else null,
                assistantId = assistant.id.toString(),
                modelId = if (mode == ScheduledJobMode.LLM) model.id.toString() else null,
                scheduleType = scheduleType,
                atUnixMs = atUnixMs,
                cronExpression = cronExpression,
                timezone = zone.id,
                startAtUnixMs = null,
                endAtUnixMs = null,
                maxRuns = maxRuns,
                runsSoFar = 0,
                enabled = true,
                lastRunAtMs = null,
                nextRunAtMs = null,
                catchup = ScheduledJobCatchup.FIRE_ONCE,
                notificationEnabled = mode == ScheduledJobMode.REMINDER || enabled,
                notificationTitle = name.take(120),
                notificationBody = notificationMessage,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
            )
            saveJob(job)

            val scheduleSummary = if (scheduleType == ScheduledJobType.ONCE) {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(atUnixMs!!), zone).toString()
            } else {
                "${cronExpression} (${zone.id})"
            }
            val response = buildJsonObject {
                put("success", true)
                put("id", job.id)
                put("name", job.name)
                put("mode", mode)
                put("schedule", scheduleSummary)
                put("notification_message", notificationMessage)
            }
            listOf(UIMessagePart.Text(response.toString()))
        },
    )
}

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun enumProperty(values: List<String>, description: String) = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach(::add) })
    put("description", description)
}

private fun parseRunAt(value: String, zone: ZoneId): Long = runCatching {
    Instant.parse(value)
}.recoverCatching {
    OffsetDateTime.parse(value).toInstant()
}.recoverCatching {
    ZonedDateTime.parse(value).toInstant()
}.recoverCatching {
    LocalDateTime.parse(value).atZone(zone).toInstant()
}.getOrThrow().toEpochMilli()

private fun String.takeCodePoints(limit: Int): String {
    val count = codePointCount(0, length)
    return if (count <= limit) this else substring(0, offsetByCodePoints(0, limit))
}

private const val MAX_NOTIFICATION_MESSAGE_CODE_POINTS = 60
