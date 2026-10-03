package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduledTaskScheduleTest {
    private val now = Instant.parse("2026-10-02T00:00:00Z").toEpochMilli()
    private fun task(type: ScheduleType = ScheduleType.DAILY) = ScheduledTaskEntity(
        id = "task", name = "Task", prompt = "Prompt", assistantId = "assistant",
        scheduleType = type.name, createdAt = now, updatedAt = now, revision = "revision",
        nextRunAt = now, triggerAt = now, intervalMinutes = 15,
    )
    private fun claim(task: ScheduledTaskEntity, revision: String = task.revision, at: Long = now) =
        ScheduledTaskSchedule.claim(task, revision, at, "run", "conversation", now)

    @Test fun dailyUsesDeviceLocalTime() {
        assertEquals(Instant.parse("2026-10-02T01:00:00Z").toEpochMilli(), ScheduledTaskSchedule.next(task(), now, ZoneId.of("Asia/Shanghai")))
    }
    @Test fun dailyAtExactTriggerMovesToTomorrow() {
        val at = Instant.parse("2026-10-02T01:00:00Z").toEpochMilli()
        assertEquals(at + 86400000L, ScheduledTaskSchedule.next(task(), at, ZoneId.of("Asia/Shanghai")))
    }
    @Test fun daylightSavingGapUsesNextValidLocalTime() {
        val day = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), ScheduledTaskSchedule.next(task().copy(timeOfDayMinutes = 150), day, ZoneId.of("America/New_York")))
    }
    @Test fun intervalSkipsMissedPeriodsWithoutLoopingOrBurst() {
        val intervalTask = task(ScheduleType.INTERVAL)
        assertEquals(now + 61 * 900000L, ScheduledTaskSchedule.next(intervalTask, now + 60 * 900000L + 1))
    }
    @Test fun startupRecalibratesFutureDailySlotsInTheCurrentTimeZone() {
        val stored = task().copy(nextRunAt = now + 25 * 3600000L)
        assertEquals(now + 3600000L, ScheduledTaskSchedule.nextOnStartup(stored, now, ZoneId.of("Asia/Shanghai")))
        assertEquals(now + 9 * 3600000L, ScheduledTaskSchedule.nextOnStartup(stored, now, ZoneId.of("UTC")))
    }
    @Test fun startupKeepsDeliveredSlotClaimableUntilAlarmReceiverRuns() {
        val overdue = task(ScheduleType.INTERVAL).copy(nextRunAt = now - 10 * 900000L)
        assertEquals(overdue.nextRunAt, ScheduledTaskSchedule.nextOnStartup(overdue, now))
        assertNull(ScheduledTaskSchedule.nextOnStartup(overdue.copy(enabled = false), now))
        assertEquals(now + 900000L, ScheduledTaskSchedule.nextOnStartup(overdue.copy(nextRunAt = null), now))
        assertEquals(now, ScheduledTaskSchedule.nextOnStartup(task(ScheduleType.ONCE), now))
    }
    @Test fun longRunsSkipElapsedPeriodsAndFutureSlotsRemainUnchanged() {
        val running = task(ScheduleType.INTERVAL).copy(nextRunAt = now + 900000L)
        val finishedAt = now + 50 * 60000L
        assertEquals(now + 60 * 60000L, ScheduledTaskSchedule.nextAfterExecution(running, finishedAt))
        assertEquals(running.nextRunAt, ScheduledTaskSchedule.nextAfterExecution(running, now))
    }
    @Test fun onceHasNoNextSlotAfterItsTrigger() {
        assertNull(ScheduledTaskSchedule.next(task(ScheduleType.ONCE), now))
        assertEquals(now + 1, ScheduledTaskSchedule.next(task(ScheduleType.ONCE).copy(triggerAt = now + 1), now))
    }
    @Test fun weeklyHonorsSelectedDaysAndInclusiveDateBounds() {
        val weekly = task(ScheduleType.WEEKLY).copy(weekdaysMask = 1 shl 0)
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals(Instant.parse("2026-10-05T01:00:00Z").toEpochMilli(), ScheduledTaskSchedule.next(weekly, now, shanghai))
        val bounded = weekly.copy(weekdaysMask = 1 shl 2, startDate = "2026-10-07", endDate = "2026-10-07")
        assertEquals(Instant.parse("2026-10-07T01:00:00Z").toEpochMilli(), ScheduledTaskSchedule.next(bounded, now, shanghai))
        assertNull(ScheduledTaskSchedule.next(bounded.copy(endDate = "2026-10-06"), now, shanghai))
    }
    @Test fun overlapUsesEarlierOffsetAndDailyRangeCanExpire() {
        val before = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
        val weekly = task(ScheduleType.WEEKLY).copy(timeOfDayMinutes = 90, weekdaysMask = 1 shl 6)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(),
            ScheduledTaskSchedule.next(weekly, before, ZoneId.of("America/New_York")))
        val daily = task().copy(endDate = "2026-10-01")
        assertNull(ScheduledTaskSchedule.next(daily, now, ZoneId.of("Asia/Shanghai")))
    }
    @Test fun scheduledOnceIsConsumedButItsResultIsRunning() {
        val run = claim(task(ScheduleType.ONCE)).run!!
        assertFalse(run.enabled)
        assertNull(run.nextRunAt)
        assertEquals("RUNNING", run.lastRunStatus)
        assertEquals("conversation", run.activeConversationId)
    }
    @Test fun disabledTasksCannotStartAnExecution() {
        val decision = claim(task().copy(enabled = false, nextRunAt = null))
        assertNull(decision.run)
        assertNull(decision.updated)
    }
    @Test fun staleRevisionAndFutureSlotsCannotStart() {
        assertNull(claim(task(), revision = "obsolete").run)
        assertNull(claim(task().copy(nextRunAt = now + 1), at = now + 1).run)
        assertNull(claim(task().copy(enabled = false)).run)
    }
    @Test fun consumedSlotCannotReplayAfterCompletion() {
        val run = claim(task(ScheduleType.INTERVAL)).run!!
        val completed = run.copy(activeRunId = null, activeConversationId = null, lastRunStatus = "SUCCESS")
        assertNull(claim(completed).run)
    }
    @Test fun waitingApprovalSkipsScheduledSlotButKeepsApprovalOwner() {
        val waiting = task(ScheduleType.INTERVAL).copy(activeRunId = "earlier", activeConversationId = "old-conversation", lastRunStatus = "WAITING_APPROVAL")
        val decision = claim(waiting)
        assertNull(decision.run)
        assertEquals("earlier", decision.updated!!.activeRunId)
        assertEquals("WAITING_APPROVAL", decision.updated!!.lastRunStatus)
        assertEquals(now + 900000L, decision.updated!!.nextRunAt)
    }
    @Test fun finalBoundedOccurrenceRunsAndThenDisablesTheSchedule() {
        val due = Instant.parse("2026-10-02T23:00:00Z").toEpochMilli()
        val finalDay = task(ScheduleType.WEEKLY).copy(weekdaysMask = 1 shl 4,
            endDate = "2026-10-02", nextRunAt = due)
        val decision = ScheduledTaskSchedule.claim(finalDay, finalDay.revision, due, "final", "conversation", due)
        assertNotNull(decision.run)
        assertFalse(decision.run!!.enabled)
        assertNull(decision.run!!.nextRunAt)
    }
    @Test fun nameAndPromptAndScheduleAreValidated() {
        for (invalid in listOf(task().copy(name = " "), task().copy(prompt = ""), task().copy(timeOfDayMinutes = 1440), task(ScheduleType.INTERVAL).copy(intervalMinutes = 14),
            task(ScheduleType.WEEKLY).copy(weekdaysMask = 0), task().copy(startDate = "2026-10-03", endDate = "2026-10-02"), task().copy(startDate = "2026-02-30"))) {
            assertThrows(IllegalArgumentException::class.java) { ScheduledTaskSchedule.validate(invalid, now, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { ScheduledTaskSchedule.validate(task(ScheduleType.ONCE), now, true) }
        ScheduledTaskSchedule.validate(task(ScheduleType.ONCE).copy(enabled = false), now, false)
    }
    @Test fun largeIntervalsDoNotOverflowIntMilliseconds() {
        assertEquals(now + Int.MAX_VALUE.toLong() * 60000L, ScheduledTaskSchedule.next(task(ScheduleType.INTERVAL).copy(intervalMinutes = Int.MAX_VALUE), now))
    }
}
