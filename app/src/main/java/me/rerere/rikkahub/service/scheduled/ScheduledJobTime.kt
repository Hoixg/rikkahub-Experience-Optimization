package me.rerere.rikkahub.service.scheduled

import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobType
import java.time.Instant
import java.time.ZoneId

object ScheduledJobTime {
    fun nextRunMs(job: ScheduledJobEntity, nowMs: Long): Long? {
        if (!job.enabled) return null
        if (job.maxRuns != null && job.runsSoFar >= job.maxRuns) return null
        if (job.endAtUnixMs != null && nowMs > job.endAtUnixMs) return null
        return when (job.scheduleType) {
            ScheduledJobType.ONCE -> job.atUnixMs?.takeIf {
                job.lastRunAtMs == null && (job.endAtUnixMs == null || it <= job.endAtUnixMs)
            }
            ScheduledJobType.CRON -> {
                val cron = job.cronExpression?.let { CronExpressionParser.parse(it).getOrNull() } ?: return null
                val zone = job.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                    ?: ZoneId.systemDefault()
                val basisMs = maxOf(nowMs, job.startAtUnixMs ?: 0L) - 1L
                val next = CronExpressionParser.nextExecution(cron, Instant.ofEpochMilli(basisMs).atZone(zone))
                    ?.toInstant()?.toEpochMilli() ?: return null
                next.takeIf { job.endAtUnixMs == null || it <= job.endAtUnixMs }
            }
            else -> null
        }
    }
}
