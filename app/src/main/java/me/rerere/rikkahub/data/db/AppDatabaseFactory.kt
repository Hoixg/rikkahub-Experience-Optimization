package me.rerere.rikkahub.data.db

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.app.AlarmManager
import android.app.PendingIntent
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_25_26
import me.rerere.rikkahub.data.db.migrations.Migration_26_27
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import androidx.work.WorkManager

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {
    fun create(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_25_26,
                Migration_26_27,
                Migration_27_28,
                Migration_30_31,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    if (name == SQLiteConfiguration.DATABASE_NAME) {
                        cleanupLegacyScheduledTasks(context, db)
                    }
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()

    private fun cleanupLegacyScheduledTasks(context: Context, db: SupportSQLiteDatabase) {
        val ids = runCatching {
            db.query("SELECT id FROM legacy_scheduled_task_cleanup").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) {
            runCatching { db.execSQL("DROP TABLE IF EXISTS legacy_scheduled_task_cleanup") }
            return
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull()
        ids.forEach { id ->
            val oldIntent = Intent().apply {
                component = ComponentName(
                    context.packageName,
                    "me.rerere.rikkahub.service.scheduled.ScheduledTaskReceiver",
                )
                action = "me.rerere.rikkahub.action.RUN_SCHEDULED_TASK"
                data = Uri.parse("rikkahub://scheduled-task/$id")
            }
            val alarmCancelled = runCatching {
                PendingIntent.getBroadcast(
                    context,
                    id.hashCode(),
                    oldIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )?.let { pendingIntent ->
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                }
            }.isSuccess
            val workCancelled = runCatching {
                checkNotNull(workManager) { "WorkManager is not initialized" }
                    .cancelUniqueWork("scheduled-task-$id")
            }.isSuccess
            if (alarmCancelled && workCancelled) runCatching {
                db.execSQL("DELETE FROM legacy_scheduled_task_cleanup WHERE id = ?", arrayOf(id))
            }
        }
        val remaining = runCatching {
            db.query("SELECT COUNT(*) FROM legacy_scheduled_task_cleanup").use { cursor ->
                cursor.moveToFirst() && cursor.getLong(0) > 0
            }
        }.getOrDefault(false)
        if (!remaining) runCatching { db.execSQL("DROP TABLE IF EXISTS legacy_scheduled_task_cleanup") }
    }
}
