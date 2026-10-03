package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

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
    /** Monday is bit 0, Sunday is bit 6. Used only by WEEKLY tasks. */
    @ColumnInfo(defaultValue = "127") val weekdaysMask: Int = 0x7f,
    /** Inclusive local calendar dates, used only by DAILY and WEEKLY tasks. */
    val startDate: String? = null,
    val endDate: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long = 0,
    val lastRunId: String = "",
    val lastManualRunId: String = "", // Retained for compatibility with older run records.
    val lastRunStatus: String = "",
    val lastConversationId: String = "",
    val lastError: String = "",
    val revision: String,
    val nextRunAt: Long? = null,
    val activeRunId: String? = null,
    val activeConversationId: String? = null,
    val activeManual: Boolean = false, // Distinguishes manual runs without changing their schedule.
    val activeScheduledAt: Long? = null,
    @ColumnInfo(defaultValue = "'NEW_CHAT'") val mode: String = ScheduledTaskMode.NEW_CHAT.name,
    val targetConversationId: String? = null,
    val targetUserMessageId: String? = null,
    val modelOverrideId: String? = null,
    @ColumnInfo(defaultValue = "1") val notify: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showPreview: Boolean = true,
)

enum class ScheduleType { ONCE, DAILY, INTERVAL, WEEKLY }
enum class ScheduledTaskMode { NEW_CHAT, FOLLOW_UP, REGENERATE }
enum class ScheduledTaskRunStatus { WAITING_IDLE, RUNNING, WAITING_APPROVAL, SUCCESS, SKIPPED, FAILED, CANCELLED }
