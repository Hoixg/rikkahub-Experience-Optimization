package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import org.junit.Assert.*
import org.junit.Test

class ScheduledTaskToolsTest {
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun task() = ScheduledTaskEntity("my-task", "Task", "Prompt", "assistant-a", createdAt = 1, updatedAt = 1, revision = "rev")
    @Test fun createDefaultsToDailyNineOClock() {
        val parsed = parseSchedule(args("{}"))
        assertEquals("DAILY", parsed.type.name)
        assertEquals(540, parsed.timeOfDayMinutes)
        assertEquals(1440, parsed.intervalMinutes)
    }
    @Test fun updatingPromptPreservesOnceTimeWithoutTriggerArgument() {
        val original = task().copy(scheduleType = "ONCE", triggerAt = 123456789L)
        val parsed = parseSchedule(args("""{"schedule_type":"ONCE"}"""), original)
        assertEquals(original.triggerAt, parsed.triggerAt)
    }
    @Test fun updatingOneScheduleFieldPreservesOtherFields() {
        val original = task().copy(timeOfDayMinutes = 630, intervalMinutes = 90)
        val parsed = parseSchedule(args("""{"time_of_day":"10:45"}"""), original)
        assertEquals(645, parsed.timeOfDayMinutes)
        assertEquals(90, parsed.intervalMinutes)
    }
    @Test fun intervalsRejectShortAndNonIntegerValues() {
        for (value in listOf("14", "0", "1.5", "\"invalid\"")) {
            assertThrows(RuntimeException::class.java) {
                parseSchedule(args("""{"schedule_type":"INTERVAL","interval_minutes":$value}"""))
            }
        }
    }
    @Test fun invalidDatesAndTrailingTextAreRejected() {
        for (value in listOf("2026-02-30 09:00", "2026-10-02 25:00", "2026-10-02 09:00 trailing", "2026-10-02")) {
            assertThrows(IllegalArgumentException::class.java) { parseTriggerAt(value) }
        }
    }
    @Test fun timeOfDayIsValidated() {
        assertEquals(1439, parseTimeOfDay("23:59"))
        for (value in listOf("24:00", "12:60", "-1:00", "09:00 extra")) {
            assertThrows(IllegalArgumentException::class.java) { parseTimeOfDay(value) }
        }
    }
    @Test fun idLookupCannotEscapeAssistantScopedListOrFallBackToName() {
        val scoped = listOf(task())
        assertNull(findTask(args("""{"id":"other-assistant-task","name":"Task"}"""), scoped))
        assertEquals("my-task", findTask(args("""{"name":"Task"}"""), scoped)?.id)
    }
    @Test fun invalidScheduleTypeAndMissingOnceDateAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parseSchedule(args("""{"schedule_type":"CRON"}""")) }
        assertThrows(IllegalStateException::class.java) { parseSchedule(args("""{"schedule_type":"ONCE"}""")) }
    }
}
