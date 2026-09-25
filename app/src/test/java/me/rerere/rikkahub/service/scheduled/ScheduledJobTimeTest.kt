package me.rerere.rikkahub.service.scheduled

import me.rerere.rikkahub.data.db.entity.ScheduledJobCatchup
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduledJobTimeTest {
    @Test
    fun cronSupportsUnixFieldsAndRejectsInvalidExpressions() {
        assertTrue(CronExpressionParser.parse("0 9 * * 1-5").isSuccess)
        assertTrue(CronExpressionParser.parse("@daily").isSuccess)
        assertFalse(CronExpressionParser.parse("61 9 * * *").isSuccess)
        assertFalse(CronExpressionParser.parse("@every 15s").isSuccess)
    }

    @Test
    fun springForwardSkipsNonexistentLocalTime() {
        val zone = ZoneId.of("America/New_York")
        val cron = CronExpressionParser.parse("30 2 * * *").getOrThrow()
        val basis = LocalDateTime.of(2026, 3, 8, 1, 59).atZone(zone)

        val next = CronExpressionParser.nextExecution(cron, basis)

        assertEquals(LocalDateTime.of(2026, 3, 9, 2, 30), next?.toLocalDateTime())
    }

    @Test
    fun nextCronRunHonorsTimezoneStartEndAndRunLimit() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-09-25T03:30:00Z").toEpochMilli()
        val job = job(
            timezone = zone.id,
            startAtUnixMs = Instant.parse("2026-09-25T05:15:00Z").toEpochMilli(),
        )

        assertEquals(Instant.parse("2026-09-25T06:00:00Z").toEpochMilli(), ScheduledJobTime.nextRunMs(job, now))
        assertNull(ScheduledJobTime.nextRunMs(job.copy(endAtUnixMs = Instant.parse("2026-09-25T05:30:00Z").toEpochMilli()), now))
        assertNull(ScheduledJobTime.nextRunMs(job.copy(maxRuns = 2, runsSoFar = 2), now))
    }

    @Test
    fun catchupPoliciesChooseTheExpectedMissedSlots() {
        val created = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-09-25T03:30:00Z").toEpochMilli()
        val base = job(timezone = "UTC", createdAtMs = created)

        val fireOnce = CatchupPlanner.plan(base.copy(catchup = ScheduledJobCatchup.FIRE_ONCE), null, now)
        val fireAll = CatchupPlanner.plan(base.copy(catchup = ScheduledJobCatchup.FIRE_ALL), null, now)
        val skip = CatchupPlanner.plan(base.copy(catchup = ScheduledJobCatchup.SKIP), null, now)

        val expected = listOf(
            Instant.parse("2026-09-25T01:00:00Z").toEpochMilli(),
            Instant.parse("2026-09-25T02:00:00Z").toEpochMilli(),
            Instant.parse("2026-09-25T03:00:00Z").toEpochMilli(),
        )
        assertEquals(listOf(expected.last()), fireOnce.fireSlotsMs)
        assertEquals(expected.dropLast(1), fireOnce.skippedSlotsMs)
        assertEquals(expected, fireAll.fireSlotsMs)
        assertTrue(fireAll.skippedSlotsMs.isEmpty())
        assertTrue(skip.fireSlotsMs.isEmpty())
        assertEquals(expected, skip.skippedSlotsMs)
        assertTrue(CatchupPlanner.plan(base.copy(maxRuns = 1, runsSoFar = 1), null, now).fireSlotsMs.isEmpty())
    }

    @Test
    fun fireOnceChoosesTheNewestSlotAfterTheCatchupScanLimit() {
        val created = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-09-25T03:30:30Z").toEpochMilli()
        val plan = CatchupPlanner.plan(
            job(timezone = "UTC", createdAtMs = created, catchup = ScheduledJobCatchup.FIRE_ONCE)
                .copy(cronExpression = "* * * * *"),
            null,
            now,
        )

        assertEquals(listOf(Instant.parse("2026-09-25T03:30:00Z").toEpochMilli()), plan.fireSlotsMs)
        assertEquals(100, plan.skippedSlotsMs.size)
        assertEquals(Instant.parse("2026-09-25T03:29:00Z").toEpochMilli(), plan.skippedSlotsMs.last())
    }

    private fun job(
        timezone: String? = null,
        startAtUnixMs: Long? = null,
        endAtUnixMs: Long? = null,
        maxRuns: Int? = null,
        runsSoFar: Int = 0,
        catchup: String = ScheduledJobCatchup.FIRE_ONCE,
        createdAtMs: Long = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli(),
    ) = ScheduledJobEntity(
        id = "job",
        name = "Job",
        description = null,
        tags = null,
        mode = "llm",
        prompt = "hello",
        actionsJson = null,
        assistantId = "assistant",
        modelId = null,
        scheduleType = ScheduledJobType.CRON,
        atUnixMs = null,
        cronExpression = "0 * * * *",
        timezone = timezone,
        startAtUnixMs = startAtUnixMs,
        endAtUnixMs = endAtUnixMs,
        maxRuns = maxRuns,
        runsSoFar = runsSoFar,
        enabled = true,
        lastRunAtMs = null,
        nextRunAtMs = null,
        catchup = catchup,
        notificationEnabled = true,
        notificationTitle = "Job",
        notificationBody = "",
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
    )
}
