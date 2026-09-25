package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_jobs", indices = [Index(value = ["enabled"])])
data class ScheduledJobEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val tags: String?,
    val mode: String,
    val prompt: String?,
    val actionsJson: String?,
    val assistantId: String,
    val modelId: String?,
    val scheduleType: String,
    val atUnixMs: Long?,
    val cronExpression: String?,
    /** Null means follow the device's current time zone. */
    val timezone: String?,
    val startAtUnixMs: Long?,
    val endAtUnixMs: Long?,
    val maxRuns: Int?,
    val runsSoFar: Int,
    val enabled: Boolean,
    val lastRunAtMs: Long?,
    val nextRunAtMs: Long?,
    val catchup: String,
    val notificationEnabled: Boolean,
    val notificationTitle: String,
    val notificationBody: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

object ScheduledJobMode {
    const val LLM = "llm"
    const val DIRECT = "direct"
    const val REMINDER = "reminder"
}

object ScheduledJobType {
    const val ONCE = "once"
    const val CRON = "cron"
}

object ScheduledJobCatchup {
    const val SKIP = "skip"
    const val FIRE_ONCE = "fire_once"
    const val FIRE_ALL = "fire_all"
}
