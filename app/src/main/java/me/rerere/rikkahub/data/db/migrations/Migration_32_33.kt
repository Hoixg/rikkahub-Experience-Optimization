package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scheduled_task ADD COLUMN weekdaysMask INTEGER NOT NULL DEFAULT 127")
        db.execSQL("ALTER TABLE scheduled_task ADD COLUMN startDate TEXT")
        db.execSQL("ALTER TABLE scheduled_task ADD COLUMN endDate TEXT")
    }
}
