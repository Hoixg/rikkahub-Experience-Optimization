package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_33_34_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun upgradesModesAndImportsTerminalAndResumableRuns() {
        val name = "scheduled-task-33-to-34"
        helper.createDatabase(name, 33).apply {
            listOf("SUCCESS", "WAITING_APPROVAL", "RUNNING").forEachIndexed { index, status ->
                val active = if (status == "SUCCESS") "NULL" else "'run-$index'"
                execSQL("""INSERT INTO scheduled_task(id,name,prompt,assistantId,scheduleType,triggerAt,intervalMinutes,timeOfDayMinutes,weekdaysMask,enabled,
                    createdAt,updatedAt,lastRunAt,lastRunId,lastManualRunId,lastRunStatus,lastConversationId,lastError,revision,activeManual,activeRunId,activeConversationId)
                    VALUES('task-$index','Task $index','Prompt','assistant','DAILY',0,1440,540,127,1,1,1,100,'run-$index','','$status','chat-$index','','rev',0,$active,${if(status == "SUCCESS") "NULL" else "'chat-$index'"})""")
            }
            close()
        }
        helper.runMigrationsAndValidate(name, 34, true, Migration_33_34).apply {
            query("SELECT mode,notify,showPreview,targetConversationId FROM scheduled_task").use {
                while (it.moveToNext()) { assertEquals("NEW_CHAT", it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals(1, it.getInt(2)); assertTrue(it.isNull(3)) }
            }
            query("SELECT status,endedAt FROM scheduled_task_run ORDER BY id").use {
                assertEquals(3, it.count)
                assertTrue(it.moveToNext()); assertEquals("SUCCESS", it.getString(0)); assertFalse(it.isNull(1))
                assertTrue(it.moveToNext()); assertEquals("WAITING_APPROVAL", it.getString(0)); assertTrue(it.isNull(1))
                assertTrue(it.moveToNext()); assertEquals("RUNNING", it.getString(0)); assertTrue(it.isNull(1))
            }
            execSQL("PRAGMA foreign_keys=ON")
            execSQL("DELETE FROM scheduled_task WHERE id='task-0'")
            query("SELECT COUNT(*) FROM scheduled_task_run").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            close()
        }
    }
}
