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
class Migration_32_33_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun upgradeKeepsOldScheduleAndHistory() {
        val name = "scheduled-task-32-to-33"
        helper.createDatabase(name, 32).apply {
            execSQL("""INSERT INTO scheduled_task(id,name,prompt,assistantId,scheduleType,triggerAt,intervalMinutes,timeOfDayMinutes,enabled,
                createdAt,updatedAt,lastRunAt,lastRunId,lastManualRunId,lastRunStatus,lastConversationId,lastError,revision,activeManual)
                VALUES('task','Morning','Prompt','assistant','DAILY',0,1440,540,1,1,1,0,'','','SUCCESS','old-conversation','','rev',0)""")
            close()
        }
        helper.runMigrationsAndValidate(name, 33, true, Migration_32_33).apply {
            query("SELECT weekdaysMask,startDate,endDate,lastConversationId FROM scheduled_task WHERE id='task'").use {
                assertTrue(it.moveToFirst())
                assertEquals(127, it.getInt(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
                assertEquals("old-conversation", it.getString(3))
            }
            close()
        }
    }
}
