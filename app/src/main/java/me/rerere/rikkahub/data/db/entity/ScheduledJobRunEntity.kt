package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_job_runs",
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["conversationId"], unique = true),
        Index(value = ["jobId", "scheduledAtMs"]),
    ],
)
data class ScheduledJobRunEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val mode: String,
    val scheduledAtMs: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val outcome: String,
    val conversationId: String?,
    val errorMessage: String?,
    val resultPreview: String?,
    val actionResultsJson: String?,
    val manual: Boolean,
)

object ScheduledJobOutcome {
    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val TIMED_OUT = "timed_out"
    const val WAITING_APPROVAL = "waiting_approval"
    const val INTERRUPTED = "interrupted"
    const val CONCURRENT_SKIP = "concurrent_skip"
    const val SKIPPED_CATCHUP = "skipped_catchup"
    const val PROCESS_KILLED_REPLAY = "process_killed_replay"
}
