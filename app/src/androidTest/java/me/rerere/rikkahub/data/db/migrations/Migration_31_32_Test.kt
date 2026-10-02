package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_31_32_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun upgradePreservesLegacyTasksAndHistoryWithoutRestoringThem() {
        val name = "scheduled-migration-test"
        helper.createDatabase(name, 31).apply {
            execSQL("""INSERT INTO scheduled_jobs(id,name,mode,assistantId,scheduleType,runsSoFar,enabled,catchup,notificationEnabled,notificationTitle,notificationBody,createdAtMs,updatedAtMs)
                VALUES('old-job','Old job','llm','assistant','once',0,1,'skip',1,'','',1,1)""")
            execSQL("""INSERT INTO scheduled_job_runs(id,jobId,mode,scheduledAtMs,startedAtMs,outcome,manual)
                VALUES('old-run','old-job','llm',1,1,'success',0)""")
            close()
        }
        helper.runMigrationsAndValidate(name, 32, true, Migration_31_32).apply {
            query("SELECT COUNT(*) FROM scheduled_jobs WHERE id='old-job'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            query("SELECT COUNT(*) FROM scheduled_job_runs WHERE id='old-run'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            query("SELECT COUNT(*) FROM scheduled_task").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            close()
        }
    }
}
