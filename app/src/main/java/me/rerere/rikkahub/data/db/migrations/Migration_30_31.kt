package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Replaces the first scheduled-task storage with the Agent-style job and run tables.
 * Old task data is intentionally discarded, but ids are kept long enough for the
 * database-open cleanup to cancel old AlarmManager and WorkManager requests.
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS legacy_scheduled_task_cleanup (id TEXT NOT NULL PRIMARY KEY)"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO legacy_scheduled_task_cleanup(id) SELECT id FROM scheduled_tasks"
        )
        db.execSQL("DROP TABLE IF EXISTS scheduled_task_runs")
        db.execSQL("DROP TABLE IF EXISTS scheduled_tasks")

        db.execSQL(
            """
            CREATE TABLE scheduled_jobs (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                tags TEXT,
                mode TEXT NOT NULL,
                prompt TEXT,
                actionsJson TEXT,
                assistantId TEXT NOT NULL,
                modelId TEXT,
                scheduleType TEXT NOT NULL,
                atUnixMs INTEGER,
                cronExpression TEXT,
                timezone TEXT,
                startAtUnixMs INTEGER,
                endAtUnixMs INTEGER,
                maxRuns INTEGER,
                runsSoFar INTEGER NOT NULL,
                enabled INTEGER NOT NULL,
                lastRunAtMs INTEGER,
                nextRunAtMs INTEGER,
                catchup TEXT NOT NULL,
                notificationEnabled INTEGER NOT NULL,
                notificationTitle TEXT NOT NULL,
                notificationBody TEXT NOT NULL,
                createdAtMs INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX index_scheduled_jobs_enabled ON scheduled_jobs(enabled)")
        db.execSQL(
            """
            CREATE TABLE scheduled_job_runs (
                id TEXT NOT NULL PRIMARY KEY,
                jobId TEXT NOT NULL,
                mode TEXT NOT NULL,
                scheduledAtMs INTEGER NOT NULL,
                startedAtMs INTEGER NOT NULL,
                finishedAtMs INTEGER,
                outcome TEXT NOT NULL,
                conversationId TEXT,
                errorMessage TEXT,
                resultPreview TEXT,
                actionResultsJson TEXT,
                manual INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX index_scheduled_job_runs_jobId ON scheduled_job_runs(jobId)")
        db.execSQL("CREATE UNIQUE INDEX index_scheduled_job_runs_conversationId ON scheduled_job_runs(conversationId)")
        db.execSQL("CREATE INDEX index_scheduled_job_runs_jobId_scheduledAtMs ON scheduled_job_runs(jobId, scheduledAtMs)")
    }
}
