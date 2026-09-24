package me.rerere.rikkahub.service.scheduled

import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduledTaskTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun task(type: String, oneTimeDate: String? = null, weekdays: Int = 0) =
        ScheduledTaskEntity(
            id = "task", name = "Task", prompt = "Prompt", assistantId = "assistant",
            scheduleType = type, oneTimeDate = oneTimeDate, hour = 13, minute = 0,
            weekdays = weekdays, enabled = true, notificationEnabled = true,
            notificationTitle = "", notificationBody = "", nextRunAt = null,
            scheduledZoneId = zone.id,
            createdAt = 0L, updatedAt = 0L,
        )

    @Test
    fun oneTimeOnlyFiresAfterSelectedInstant() {
        val once = task(ScheduleType.ONCE, "2026-09-24")
        assertEquals(
            Instant.parse("2026-09-24T05:00:00Z"),
            nextOccurrence(once, Instant.parse("2026-09-24T04:59:59Z"), zone),
        )
        assertNull(nextOccurrence(once, Instant.parse("2026-09-24T05:00:00Z"), zone))
    }

    @Test
    fun dailyMovesToTomorrowAfterCutoff() {
        val daily = task(ScheduleType.DAILY)
        assertEquals(
            Instant.parse("2026-09-25T05:00:00Z"),
            nextOccurrence(daily, Instant.parse("2026-09-24T05:00:00Z"), zone),
        )
        assertEquals(
            Instant.parse("2026-09-24T05:00:00Z"),
            latestOccurrence(daily, Instant.parse("2026-09-24T10:00:00Z"), zone),
        )
    }

    @Test
    fun weeklyUsesSelectedDaysAndFindsLatestMissedRun() {
        val mondayAndFriday = task(ScheduleType.WEEKLY, weekdays = (1 shl 0) or (1 shl 4))
        assertEquals(
            Instant.parse("2026-09-25T05:00:00Z"),
            nextOccurrence(mondayAndFriday, Instant.parse("2026-09-24T05:00:00Z"), zone),
        )
        assertEquals(
            Instant.parse("2026-09-21T05:00:00Z"),
            latestOccurrence(mondayAndFriday, Instant.parse("2026-09-24T05:00:00Z"), zone),
        )
    }

    @Test
    fun recurringTimeFollowsCurrentDeviceZone() {
        val daily = task(ScheduleType.DAILY)
        val before = Instant.parse("2026-09-24T03:00:00Z")
        assertEquals(
            Instant.parse("2026-09-24T05:00:00Z"),
            nextOccurrence(daily, before, ZoneId.of("Asia/Shanghai")),
        )
        assertEquals(
            Instant.parse("2026-09-24T04:00:00Z"),
            nextOccurrence(daily, before, ZoneId.of("Asia/Tokyo")),
        )
    }
}
