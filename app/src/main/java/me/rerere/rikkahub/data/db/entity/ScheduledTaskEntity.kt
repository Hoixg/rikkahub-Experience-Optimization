package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Adapted from xiaoyuili/Yuihub (AGPL-3.0), with durable scheduling and execution ownership. */
@Entity(tableName = "scheduled_task", indices = [Index("assistantId"), Index("enabled"), Index("activeConversationId", unique = true)])
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val assistantId: String,
    val scheduleType: String = ScheduleType.DAILY.name,
    val triggerAt: Long = 0,
    val intervalMinutes: Int = 1440,
    val timeOfDayMinutes: Int = 540,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long = 0,
    val lastRunId: String = "",
    val lastManualRunId: String = "", // Retained for schema compatibility; new manual executions are disabled.
    val lastRunStatus: String = "",
    val lastConversationId: String = "",
    val lastError: String = "",
    val revision: String,
    val nextRunAt: Long? = null,
    val activeRunId: String? = null,
    val activeConversationId: String? = null,
    val activeManual: Boolean = false, // Retained to recognize approvals from older builds.
    val activeScheduledAt: Long? = null,
)

enum class ScheduleType { ONCE, DAILY, INTERVAL }
enum class ScheduledTaskRunStatus { RUNNING, WAITING_APPROVAL, SUCCESS, SKIPPED, FAILED, CANCELLED }
