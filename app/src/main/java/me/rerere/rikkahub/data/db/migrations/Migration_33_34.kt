package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_33_34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scheduled_task ADD COLUMN mode TEXT NOT NULL DEFAULT 'NEW_CHAT'")
        listOf("targetConversationId", "targetUserMessageId", "modelOverrideId").forEach {
            db.execSQL("ALTER TABLE scheduled_task ADD COLUMN $it TEXT")
        }
        listOf("notify", "showPreview").forEach {
            db.execSQL("ALTER TABLE scheduled_task ADD COLUMN $it INTEGER NOT NULL DEFAULT 1")
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS scheduled_task_run (
            id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, source TEXT NOT NULL,
            dueAt INTEGER NOT NULL, startedAt INTEGER, endedAt INTEGER, status TEXT NOT NULL,
            conversationId TEXT, preview TEXT NOT NULL, error TEXT NOT NULL, generationMs INTEGER NOT NULL,
            FOREIGN KEY(taskId) REFERENCES scheduled_task(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_scheduled_task_run_taskId ON scheduled_task_run(taskId)")
        db.execSQL("CREATE INDEX index_scheduled_task_run_status ON scheduled_task_run(status)")
        db.execSQL("""INSERT INTO scheduled_task_run
            SELECT COALESCE(activeRunId, lastRunId), id, CASE WHEN activeManual THEN 'MANUAL' ELSE 'SCHEDULED' END,
            COALESCE(activeScheduledAt, lastRunAt), lastRunAt,
            CASE WHEN activeRunId IS NULL THEN lastRunAt ELSE NULL END,
            lastRunStatus, NULLIF(COALESCE(activeConversationId, lastConversationId), ''), '', lastError, 0
            FROM scheduled_task WHERE COALESCE(activeRunId, lastRunId) != '' AND lastRunStatus != ''""")
    }
}
