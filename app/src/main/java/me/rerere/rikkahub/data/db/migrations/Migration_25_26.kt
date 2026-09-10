package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Reconciles the independently released v25 schemas before applying the merged v26 schema. */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("ConversationEntity", "plan_mode_enabled", "INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("workspaces", "shell_compatibility_mode", "INTEGER NOT NULL DEFAULT 0")
    }
}

private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
    val exists = query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return@use true
        }
        false
    }
    if (!exists) execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
}
