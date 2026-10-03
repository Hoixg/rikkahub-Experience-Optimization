package me.rerere.rikkahub.data.repository

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.utils.SystemPermissions
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledTaskPermissionTest {
    @Test fun deniedExactAlarmAccessAllowsDraftButRejectsActivation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 31)
        Assume.assumeFalse(SystemPermissions.canScheduleExactAlarms(context))
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = ScheduledTaskRepository(context, db, startRunner = {})
            val now = System.currentTimeMillis()
            val draft = ScheduledTaskEntity("draft", "Draft", "Prompt", "assistant", enabled = false,
                createdAt = now, updatedAt = now, revision = "first")
            repository.upsert(draft)
            assertFalse(repository.getById(draft.id)!!.enabled)
            try {
                repository.setEnabled(draft.id, true)
                fail("Expected activation to require exact-alarm access")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("精确闹钟"))
            }
            assertFalse(repository.getById(draft.id)!!.enabled)
            val manual = repository.runNow(draft.id)
            assertEquals("WAITING_IDLE", manual.lastRunStatus)
            assertFalse(manual.enabled)
            assertNull(manual.nextRunAt)
        } finally {
            db.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
