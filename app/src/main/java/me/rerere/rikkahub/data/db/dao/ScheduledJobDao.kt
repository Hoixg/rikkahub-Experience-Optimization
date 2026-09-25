package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity

@Dao
interface ScheduledJobDao {
    @Query("SELECT * FROM scheduled_jobs ORDER BY enabled DESC, nextRunAtMs IS NULL, nextRunAtMs, name COLLATE NOCASE")
    fun observeJobs(): Flow<List<ScheduledJobEntity>>

    @Query("SELECT * FROM scheduled_jobs WHERE enabled = 1")
    suspend fun getEnabledJobs(): List<ScheduledJobEntity>

    @Query("SELECT * FROM scheduled_jobs WHERE id = :id")
    suspend fun getJob(id: String): ScheduledJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: ScheduledJobEntity)

    @Delete
    suspend fun deleteJob(job: ScheduledJobEntity)

    @Query("SELECT * FROM scheduled_job_runs WHERE jobId = :jobId ORDER BY startedAtMs DESC LIMIT 100")
    fun observeRuns(jobId: String): Flow<List<ScheduledJobRunEntity>>

    @Query("SELECT * FROM scheduled_job_runs WHERE jobId = :jobId ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getLatestRun(jobId: String): ScheduledJobRunEntity?

    @Query("SELECT * FROM scheduled_job_runs WHERE jobId = :jobId AND scheduledAtMs = :scheduledAtMs AND manual = 0 ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getRunForSchedule(jobId: String, scheduledAtMs: Long): ScheduledJobRunEntity?

    @Query("SELECT * FROM scheduled_job_runs WHERE jobId = :jobId AND finishedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getActiveRun(jobId: String): ScheduledJobRunEntity?

    @Query("SELECT * FROM scheduled_job_runs WHERE finishedAtMs IS NULL AND outcome != 'waiting_approval'")
    suspend fun getUnfinishedRuns(): List<ScheduledJobRunEntity>

    @Query("SELECT * FROM scheduled_job_runs WHERE outcome = 'waiting_approval'")
    suspend fun getWaitingApprovalRuns(): List<ScheduledJobRunEntity>

    @Query("SELECT * FROM scheduled_job_runs WHERE id = :id")
    suspend fun getRun(id: String): ScheduledJobRunEntity?

    @Query("SELECT * FROM scheduled_job_runs WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getRunByConversation(conversationId: String): ScheduledJobRunEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRun(run: ScheduledJobRunEntity): Long

    @Transaction
    suspend fun insertScheduledRunIfMissing(run: ScheduledJobRunEntity): Long {
        if (!run.manual && getRunForSchedule(run.jobId, run.scheduledAtMs) != null) return -1L
        return insertRun(run)
    }

    @Update
    suspend fun updateRun(run: ScheduledJobRunEntity)

    @Query("SELECT COUNT(*) FROM scheduled_job_runs WHERE jobId = :jobId AND manual = 0 AND outcome = 'success'")
    suspend fun countSuccessfulRuns(jobId: String): Int

    @Query("DELETE FROM scheduled_job_runs WHERE jobId = :jobId")
    suspend fun deleteRuns(jobId: String)

    @Query(
        "DELETE FROM scheduled_job_runs WHERE jobId = :jobId AND finishedAtMs IS NOT NULL " +
            "AND id NOT IN (SELECT id FROM scheduled_job_runs WHERE jobId = :jobId " +
            "AND finishedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT :keep)"
    )
    suspend fun trimRuns(jobId: String, keep: Int)
}
