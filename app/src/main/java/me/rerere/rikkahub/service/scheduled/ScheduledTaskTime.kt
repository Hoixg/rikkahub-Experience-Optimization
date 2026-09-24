package me.rerere.rikkahub.service.scheduled

import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal object ScheduleType {
    const val ONCE = "ONCE"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
}

internal fun nextOccurrence(task: ScheduledTaskEntity, after: Instant, zone: ZoneId): Instant? {
    val time = LocalTime.of(task.hour, task.minute)
    if (task.scheduleType == ScheduleType.ONCE) {
        val date = task.oneTimeDate?.let(LocalDate::parse) ?: return null
        return date.atTime(time).atZone(zone).toInstant().takeIf { it > after }
    }
    var date = after.atZone(zone).toLocalDate()
    repeat(8) {
        if (matchesDay(task, date)) {
            val candidate = date.atTime(time).atZone(zone).toInstant()
            if (candidate > after) return candidate
        }
        date = date.plusDays(1)
    }
    return null
}

internal fun latestOccurrence(task: ScheduledTaskEntity, at: Instant, zone: ZoneId): Instant? {
    val time = LocalTime.of(task.hour, task.minute)
    if (task.scheduleType == ScheduleType.ONCE) {
        val date = task.oneTimeDate?.let(LocalDate::parse) ?: return null
        return date.atTime(time).atZone(zone).toInstant().takeIf { it <= at }
    }
    var date = at.atZone(zone).toLocalDate()
    repeat(8) {
        if (matchesDay(task, date)) {
            val candidate = date.atTime(time).atZone(zone).toInstant()
            if (candidate <= at) return candidate
        }
        date = date.minusDays(1)
    }
    return null
}

private fun matchesDay(task: ScheduledTaskEntity, date: LocalDate): Boolean = when (task.scheduleType) {
    ScheduleType.DAILY -> true
    ScheduleType.WEEKLY -> task.weekdays and (1 shl (date.dayOfWeek.value - 1)) != 0
    else -> false
}
