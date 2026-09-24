package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunEntity

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
    fun observeTasks(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks")
    suspend fun getTasks(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getTask(id: String): ScheduledTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: ScheduledTaskEntity)

    @Delete
    suspend fun deleteTask(task: ScheduledTaskEntity)

    @Query("SELECT * FROM scheduled_task_runs WHERE taskId = :taskId ORDER BY scheduledAt DESC LIMIT 30")
    fun observeRuns(taskId: String): Flow<List<ScheduledTaskRunEntity>>

    @Query("SELECT * FROM scheduled_task_runs WHERE taskId = :taskId ORDER BY scheduledAt DESC LIMIT 1")
    suspend fun getLatestRun(taskId: String): ScheduledTaskRunEntity?

    @Query("SELECT * FROM scheduled_task_runs WHERE taskId = :taskId AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL') ORDER BY scheduledAt DESC LIMIT 1")
    suspend fun getActiveRun(taskId: String): ScheduledTaskRunEntity?

    @Query("SELECT * FROM scheduled_task_runs WHERE status IN ('RUNNING','WAITING_APPROVAL')")
    suspend fun getUnfinishedRuns(): List<ScheduledTaskRunEntity>

    @Query("SELECT * FROM scheduled_task_runs WHERE id = :id")
    suspend fun getRun(id: String): ScheduledTaskRunEntity?

    @Query("SELECT * FROM scheduled_task_runs WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getRunByConversation(conversationId: String): ScheduledTaskRunEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRun(run: ScheduledTaskRunEntity): Long

    @Update
    suspend fun updateRun(run: ScheduledTaskRunEntity)

    @Query("DELETE FROM scheduled_task_runs WHERE taskId = :taskId")
    suspend fun deleteRuns(taskId: String)
}
