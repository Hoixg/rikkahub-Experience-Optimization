package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.ScheduleType
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Pure scheduling policy shared by UI, tools, recovery and workers. */
object ScheduledTaskSchedule {
    /** Keep a delivered alarm claimable during a cold start; UI/system reconciliation skips missed slots. */
    fun nextOnStartup(task: ScheduledTaskEntity, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!task.enabled) return null
        if (task.nextRunAt != null && task.nextRunAt <= now) return task.nextRunAt
        return next(task, now, zone)
    }

    /** Long runs skip elapsed periods; retained legacy manual approvals keep their original schedule. */
    fun nextAfterExecution(task: ScheduledTaskEntity, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        if (!task.activeManual && task.enabled && task.scheduleType != ScheduleType.ONCE.name &&
            task.nextRunAt != null && task.nextRunAt <= now) next(task, now, zone) else task.nextRunAt

    /** A consumed slot can never be claimed twice, even after its worker is replayed. */
    fun claim(task: ScheduledTaskEntity, revision: String, scheduledAt: Long,
        runId: String, conversationId: String, now: Long): ScheduledTaskClaim {
        if (task.lastRunId == runId || task.revision != revision) return ScheduledTaskClaim(null, null)
        if (!task.enabled || task.nextRunAt != scheduledAt || scheduledAt > now) {
            return ScheduledTaskClaim(null, null)
        }
        val next = next(task, now)
        val enabled = task.scheduleType != ScheduleType.ONCE.name && next != null
        if (task.activeRunId != null) {
            return ScheduledTaskClaim(task.copy(nextRunAt = next, enabled = enabled), null)
        }
        val run = task.copy(activeRunId = runId, activeConversationId = conversationId, activeManual = false,
            activeScheduledAt = scheduledAt, lastRunAt = now, lastRunId = runId,
            lastRunStatus = "RUNNING", lastConversationId = "", lastError = "", nextRunAt = next, enabled = enabled)
        return ScheduledTaskClaim(run, run)
    }

    fun validate(task: ScheduledTaskEntity, now: Long, requireFutureOnce: Boolean) {
        require(task.name.isNotBlank()) { "任务名称不能为空" }
        val mode = me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.valueOf(task.mode)
        if (mode != me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.REGENERATE) require(task.prompt.isNotBlank()) { "任务提示词不能为空" }
        if (mode != me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.NEW_CHAT) require(!task.targetConversationId.isNullOrBlank()) { "请选择目标会话" }
        if (mode == me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.REGENERATE) require(!task.targetUserMessageId.isNullOrBlank()) { "请选择用户消息" }
        require(task.assistantId.isNotBlank()) { "请选择助手" }
        when (ScheduleType.valueOf(task.scheduleType)) {
            ScheduleType.ONCE -> if (requireFutureOnce) require(task.triggerAt > now) { "单次任务时间必须在未来" }
            ScheduleType.INTERVAL -> require(task.intervalMinutes >= 15) { "执行间隔至少为 15 分钟" }
            ScheduleType.DAILY, ScheduleType.WEEKLY -> {
                require(task.timeOfDayMinutes in 0..1439) { "执行时间无效" }
                val start = parseDate(task.startDate)
                val end = parseDate(task.endDate)
                require(start == null || end == null || !start.isAfter(end)) { "结束日期不能早于开始日期" }
                if (task.scheduleType == ScheduleType.WEEKLY.name) {
                    require(task.weekdaysMask in 1..0x7f) { "请至少选择一个星期日" }
                }
                if (task.enabled) require(next(task, now) != null) { "日期范围已结束" }
            }
        }
    }

    private fun parseDate(value: String?): LocalDate? = value?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("日期必须为 yyyy-MM-dd") }
    }

    fun next(task: ScheduledTaskEntity, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        when (ScheduleType.valueOf(task.scheduleType)) {
            ScheduleType.ONCE -> task.triggerAt.takeIf { it > now }
            ScheduleType.DAILY, ScheduleType.WEEKLY -> {
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                val first = maxOf(today, parseDate(task.startDate) ?: today)
                val end = parseDate(task.endDate)
                val time = LocalTime.of(task.timeOfDayMinutes / 60, task.timeOfDayMinutes % 60)
                (0L..7L).firstNotNullOfOrNull { offset ->
                    val day = first.plusDays(offset)
                    if (end != null && day.isAfter(end)) return@firstNotNullOfOrNull null
                    if (task.scheduleType == ScheduleType.WEEKLY.name && task.weekdaysMask and (1 shl (day.dayOfWeek.value - 1)) == 0) {
                        return@firstNotNullOfOrNull null
                    }
                    day.atTime(time).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
                        .takeIf { it > now }
                }
            }
            ScheduleType.INTERVAL -> {
                val interval = task.intervalMinutes.toLong() * 60_000L
                val base = task.createdAt
                if (base > now) base + interval else base + ((now - base) / interval + 1) * interval
            }
        }
}

data class ScheduledTaskClaim(val updated: ScheduledTaskEntity?, val run: ScheduledTaskEntity?)
