package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS scheduled_task (
            id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, prompt TEXT NOT NULL,
            assistantId TEXT NOT NULL, scheduleType TEXT NOT NULL, triggerAt INTEGER NOT NULL,
            intervalMinutes INTEGER NOT NULL, timeOfDayMinutes INTEGER NOT NULL, enabled INTEGER NOT NULL,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastRunAt INTEGER NOT NULL, lastRunId TEXT NOT NULL, lastManualRunId TEXT NOT NULL,
            lastRunStatus TEXT NOT NULL, lastConversationId TEXT NOT NULL, lastError TEXT NOT NULL,
            revision TEXT NOT NULL, nextRunAt INTEGER, activeRunId TEXT, activeConversationId TEXT,
            activeManual INTEGER NOT NULL, activeScheduledAt INTEGER
        )""".trimIndent())
        db.execSQL("CREATE INDEX index_scheduled_task_assistantId ON scheduled_task(assistantId)")
        db.execSQL("CREATE INDEX index_scheduled_task_enabled ON scheduled_task(enabled)")
        db.execSQL("CREATE UNIQUE INDEX index_scheduled_task_activeConversationId ON scheduled_task(activeConversationId)")
    }
}
