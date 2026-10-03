package me.rerere.rikkahub.data.db.entity

import androidx.room.*

@Entity(tableName = "scheduled_task_run", indices = [Index("taskId"), Index("status")],
    foreignKeys = [ForeignKey(entity = ScheduledTaskEntity::class, parentColumns = ["id"],
        childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)])
data class ScheduledTaskRunEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val source: String,
    val dueAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val status: String,
    val conversationId: String? = null,
    val preview: String = "",
    val error: String = "",
    val generationMs: Long = 0,
)
