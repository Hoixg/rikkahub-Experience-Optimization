package me.rerere.rikkahub.service.scheduled

import com.cronutils.model.Cron
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.ZonedDateTime
import java.util.LinkedHashMap

/** Agent-compatible UNIX cron parser with nicknames and bounded @every aliases. */
object CronExpressionParser {
    private val parser: CronParser = run {
        val definition = CronDefinitionBuilder.defineCron()
            .withMinutes().withValidRange(0, 59).and()
            .withHours().withValidRange(0, 23).and()
            .withDayOfMonth().withValidRange(1, 31)
                .supportsL().supportsW().supportsLW().supportsQuestionMark().and()
            .withMonth().withValidRange(1, 12).and()
            .withDayOfWeek().withValidRange(0, 7).withMondayDoWValue(1)
                .supportsHash().supportsL().supportsQuestionMark().and()
            .withSupportedNicknameYearly()
            .withSupportedNicknameAnnually()
            .withSupportedNicknameMonthly()
            .withSupportedNicknameWeekly()
            .withSupportedNicknameDaily()
            .withSupportedNicknameMidnight()
            .withSupportedNicknameHourly()
            .instance()
        CronParser(definition)
    }

    private const val CACHE_CAP = 32
    private val cache = object : LinkedHashMap<String, Cron>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cron>?) = size > CACHE_CAP
    }

    fun parse(expression: String): Result<Cron> = synchronized(cache) {
        cache[expression]?.let { return@synchronized Result.success(it) }
        runCatching {
            val normalized = expandEvery(expression)
            val effective = normalized ?: if (expression.trim().startsWith("@every ")) {
                error("Unsupported @every value: $expression")
            } else expression
            parser.parse(effective).validate().also { cache[expression] = it }
        }
    }

    fun nextExecution(cron: Cron, basis: ZonedDateTime): ZonedDateTime? =
        ExecutionTime.forCron(cron).nextExecution(basis).orElse(null)

    fun previousExecution(cron: Cron, basis: ZonedDateTime): ZonedDateTime? =
        ExecutionTime.forCron(cron).lastExecution(basis).orElse(null)

    private fun expandEvery(expression: String): String? {
        val trimmed = expression.trim()
        if (!trimmed.startsWith("@every ")) return null
        val duration = trimmed.removePrefix("@every ").trim()
        val value = duration.dropLast(1).toLongOrNull() ?: return null
        if (value <= 0) return null
        return when (duration.lastOrNull()) {
            's' -> when {
                value < 60 -> null
                value < 3600 -> "*/${(value / 60).coerceIn(1, 59)} * * * *"
                value < 86400 -> "0 */${(value / 3600).coerceIn(1, 23)} * * *"
                value <= 86400L * 31 -> "0 0 */${(value / 86400).coerceIn(1, 31)} * *"
                else -> null
            }
            'm' -> if (value <= 59) "*/$value * * * *" else null
            'h' -> if (value <= 23) "0 */$value * * *" else null
            else -> null
        }
    }
}
