package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val assistantId: String,
    val scheduleType: String,
    val oneTimeDate: String?,
    val hour: Int,
    val minute: Int,
    val weekdays: Int,
    val enabled: Boolean,
    val notificationEnabled: Boolean,
    val notificationTitle: String,
    val notificationBody: String,
    val nextRunAt: Long?,
    val scheduledZoneId: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'AI'")
    val executionMode: String = ScheduledExecutionMode.AI,
    val modelId: String? = null,
)

object ScheduledExecutionMode {
    const val AI = "AI"
    const val REMINDER = "REMINDER"
}

@Entity(
    tableName = "scheduled_task_runs",
    indices = [Index("taskId"), Index(value = ["conversationId"], unique = true)],
)
data class ScheduledTaskRunEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val scheduledAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val status: String,
    val error: String?,
    val resultPreview: String?,
    val conversationId: String?,
)
