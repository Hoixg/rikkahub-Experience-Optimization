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
    @Test fun weeklyDefaultsToWeekdaysAndCanClearOneDateWithoutChangingOthers() {
        val parsed = parseSchedule(args("""{"schedule_type":"WEEKLY","start_date":"2026-10-05","end_date":"2026-10-31"}"""))
        assertEquals(0x1f, parsed.weekdaysMask)
        val original = task().copy(scheduleType = "WEEKLY", weekdaysMask = 1 shl 2,
            startDate = parsed.startDate, endDate = parsed.endDate)
        val changed = parseSchedule(args("""{"start_date":null,"time_of_day":"10:45"}"""), original)
        assertNull(changed.startDate)
        assertEquals("2026-10-31", changed.endDate)
        assertEquals(original.weekdaysMask, changed.weekdaysMask)
    }
    @Test fun invalidWeekdaysAndDateRangesAreRejected() {
        for (json in listOf("""{"schedule_type":"WEEKLY","weekdays":[]}""", """{"schedule_type":"WEEKLY","weekdays":[0]}""",
            """{"schedule_type":"WEEKLY","weekdays":[8]}""", """{"schedule_type":"DAILY","weekdays":[1]}""",
            """{"schedule_type":"DAILY","start_date":"2026-02-30"}""")) {
            assertThrows(RuntimeException::class.java) { parseSchedule(args(json)) }
        }
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
    @Test fun changingModeAndTargetClearsObsoleteMessageAndPreservesNotificationSettings() {
        val firstId = "00000000-0000-0000-0000-000000000001"
        val secondId = "00000000-0000-0000-0000-000000000002"
        val original = task().copy(mode = "REGENERATE", targetConversationId = firstId, targetUserMessageId = firstId, notify = false)
        val changed = applyExecutionFields(original, args("""{"target_conversation_id":"$secondId"}"""))
        assertNull(changed.targetUserMessageId); assertFalse(changed.notify)
        val newChat = applyExecutionFields(original, args("""{"mode":"NEW_CHAT","model_override_id":null,"show_preview":false}"""))
        assertNull(newChat.targetConversationId); assertNull(newChat.targetUserMessageId); assertFalse(newChat.showPreview)
        assertThrows(IllegalArgumentException::class.java) { applyExecutionFields(original, args("""{"mode":"INVALID"}""")) }
    }

}
