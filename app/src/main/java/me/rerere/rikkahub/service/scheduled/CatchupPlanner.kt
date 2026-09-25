package me.rerere.rikkahub.service.scheduled

import me.rerere.rikkahub.data.db.entity.ScheduledJobCatchup
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobType
import java.time.Instant
import java.time.ZoneId

/** Pure catch-up planning so restart behavior can be verified without WorkManager. */
object CatchupPlanner {
    const val FIRE_ALL_CAP = 20
    const val SKIPPED_ROWS_CAP = 100
    const val FIRE_ALL_STAGGER_MS = 2_000L
    private const val MATCHES_CAP = 10_000

    data class Plan(val fireSlotsMs: List<Long>, val skippedSlotsMs: List<Long>)

    fun plan(job: ScheduledJobEntity, lastRunMs: Long?, nowMs: Long): Plan {
        if (!job.enabled ||
            (job.maxRuns != null && job.runsSoFar >= job.maxRuns) ||
            (job.endAtUnixMs != null && nowMs > job.endAtUnixMs)
        ) return Plan(emptyList(), emptyList())
        val slots = when (job.scheduleType) {
            ScheduledJobType.ONCE -> {
                val at = job.atUnixMs ?: return Plan(emptyList(), emptyList())
                if (job.lastRunAtMs == null && at < nowMs) listOf(at) else emptyList()
            }
            ScheduledJobType.CRON -> missedCronSlots(job, lastRunMs ?: job.createdAtMs, nowMs)
            else -> emptyList()
        }
        return when (job.catchup) {
            ScheduledJobCatchup.SKIP -> Plan(emptyList(), slots.take(SKIPPED_ROWS_CAP))
            ScheduledJobCatchup.FIRE_ALL -> {
                val fire = slots.take(FIRE_ALL_CAP)
                Plan(fire, slots.drop(fire.size).take(SKIPPED_ROWS_CAP))
            }
            else -> if (slots.isEmpty()) Plan(emptyList(), emptyList())
                else Plan(listOf(slots.last()), slots.dropLast(1).takeLast(SKIPPED_ROWS_CAP))
        }
    }

    private fun missedCronSlots(job: ScheduledJobEntity, fromMs: Long, nowMs: Long): List<Long> {
        if (nowMs <= fromMs || (job.startAtUnixMs != null && nowMs < job.startAtUnixMs)) return emptyList()
        val expression = job.cronExpression ?: return emptyList()
        val cron = CronExpressionParser.parse(expression).getOrNull() ?: return emptyList()
        val zone = job.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val start = maxOf(fromMs, job.startAtUnixMs ?: fromMs)
        var cursor = Instant.ofEpochMilli(nowMs).plusMillis(1).atZone(zone)
        val matches = mutableListOf<Long>()
        repeat(MATCHES_CAP) {
            val previous = CronExpressionParser.previousExecution(cron, cursor) ?: return matches.asReversed()
            val previousMs = previous.toInstant().toEpochMilli()
            if (previousMs <= start || (job.endAtUnixMs != null && previousMs > job.endAtUnixMs)) {
                return matches.asReversed()
            }
            matches += previousMs
            cursor = previous
        }
        return matches.asReversed()
    }
}
