package me.rerere.rikkahub.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity

@Dao
interface ScheduledTaskDAO {
    @Query("SELECT * FROM scheduled_task ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_task WHERE id = :id")
    suspend fun getById(id: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_task")
    suspend fun getAll(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_task WHERE assistantId = :assistantId ORDER BY createdAt DESC")
    suspend fun getByAssistant(assistantId: String): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_task WHERE activeConversationId = :id LIMIT 1")
    suspend fun getByActiveConversation(id: String): ScheduledTaskEntity?

    @Query("UPDATE scheduled_task SET lastRunStatus = 'RUNNING' WHERE activeConversationId = :id AND lastRunStatus = 'WAITING_APPROVAL'")
    suspend fun resumeConversation(id: String)

    @Query("UPDATE scheduled_task SET lastConversationId = :conversationId WHERE id = :id AND activeRunId = :runId")
    suspend fun attachConversation(id: String, runId: String, conversationId: String)

    @Upsert
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_task WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE scheduled_task SET lastRunStatus = :status, lastError = :error, activeRunId = CASE WHEN :waiting THEN activeRunId ELSE NULL END, activeConversationId = CASE WHEN :waiting THEN activeConversationId ELSE NULL END, activeScheduledAt = CASE WHEN :waiting THEN activeScheduledAt ELSE NULL END WHERE id = :id AND activeRunId = :runId")
    suspend fun finish(id: String, runId: String, status: String, error: String, waiting: Boolean): Int
}
