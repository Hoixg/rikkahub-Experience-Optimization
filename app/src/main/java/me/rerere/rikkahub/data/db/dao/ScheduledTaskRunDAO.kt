package me.rerere.rikkahub.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunEntity

@Dao
interface ScheduledTaskRunDAO {
    @Upsert suspend fun upsert(run: ScheduledTaskRunEntity)
    @Query("SELECT * FROM scheduled_task_run WHERE id = :id") suspend fun get(id: String): ScheduledTaskRunEntity?
    @Query("SELECT * FROM scheduled_task_run WHERE taskId = :id ORDER BY dueAt DESC, id DESC")
    fun historyFlow(id: String): Flow<List<ScheduledTaskRunEntity>>
    @Query("SELECT * FROM scheduled_task_run WHERE taskId = :id ORDER BY dueAt DESC, id DESC")
    suspend fun history(id: String): List<ScheduledTaskRunEntity>
    @Query("SELECT * FROM scheduled_task_run WHERE status = 'WAITING_IDLE' ORDER BY dueAt, id")
    suspend fun waiting(): List<ScheduledTaskRunEntity>
    @Query("SELECT * FROM scheduled_task_run WHERE status IN ('RUNNING','WAITING_IDLE','WAITING_APPROVAL')")
    fun pendingFlow(): Flow<List<ScheduledTaskRunEntity>>
    @Query("DELETE FROM scheduled_task_run WHERE taskId = :id AND status NOT IN ('RUNNING','WAITING_IDLE','WAITING_APPROVAL') AND id NOT IN (SELECT id FROM scheduled_task_run WHERE taskId = :id AND status NOT IN ('RUNNING','WAITING_IDLE','WAITING_APPROVAL') ORDER BY endedAt DESC, id DESC LIMIT 20)")
    suspend fun prune(id: String)
}
