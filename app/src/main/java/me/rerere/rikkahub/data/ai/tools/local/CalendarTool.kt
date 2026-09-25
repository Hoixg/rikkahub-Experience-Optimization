package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal fun buildCalendarQueryTool(context: Context): Tool = Tool(
    name = "calendar_query",
    description = """
        Query calendar events on the user's device within a time range.
        Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week/month).
        Returns a list of events with title, description, location, start/end times, and calendar info.
        Each event includes an 'id' and title; pass both to calendar_delete when the user asks to delete it.
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires the 'Calendar' permission; if it is not granted, an error is returned and the
        permission request is triggered automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                            "When provided, 'range' is ignored."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "End time (exclusive), same formats as 'begin'. Defaults to now."
                    )
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("today")
                            add("week")
                            add("month")
                        }
                    )
                    put(
                        "description",
                        "Convenience preset, used only when 'begin' is omitted: today, week, or month. Default today."
                    )
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional keyword to filter events by title (case-insensitive substring match).")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of events to return. Default 20.")
                })
            }
        )
    },
    execute = { args ->
        if (!hasCalendarReadPermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar read permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val query = params["query"]?.jsonPrimitive?.contentOrNull

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseCalendarTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else when (rangePreset) {
                "week" -> startTime.plusDays(7)
                "month" -> startTime.plusMonths(1)
                else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format for begin/end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        val selection = if (query != null) {
            "${CalendarContract.Instances.TITLE} LIKE ?"
        } else null
        val selectionArgs = if (query != null) {
            arrayOf("%$query%")
        } else null

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val events = buildJsonArray {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("title", cursor.getString(1) ?: "")
                        put("description", cursor.getString(2) ?: "")
                        put("location", cursor.getString(3) ?: "")
                        val dtStart = cursor.getLong(4)
                        val dtEnd = cursor.getLong(5)
                        val allDay = cursor.getInt(6) == 1
                        if (allDay) {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate().toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().toString()
                                } else {
                                    ""
                                }
                            )
                        } else {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(zone).withNano(0).toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(zone).withNano(0).toString()
                                } else {
                                    ""
                                }
                            )
                        }
                        put("all_day", allDay)
                        put("calendar", cursor.getString(7) ?: "")
                    })
                    count++
                }
            }
        }

        val payload = buildJsonObject {
            put("range_start", startTime.withNano(0).toString())
            put("range_end", endTime.withNano(0).toString())
            put("count", events.size)
            put("events", events)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

internal fun buildCalendarCreateTool(context: Context): Tool = Tool(
    name = "calendar_create",
    description = """
        Create a new calendar event on the user's device.
        Requires title and start time at minimum. End time defaults to 1 hour after start.
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires the 'Calendar' permission; if it is not granted, an error is returned and the
        permission request is triggered automatically.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Event title.")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Event description or notes.")
                })
                put("location", buildJsonObject {
                    put("type", "string")
                    put("description", "Event location.")
                })
                put("start", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time. Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "End time, same formats as 'start'. Defaults to 1 hour after start."
                    )
                })
                put("all_day", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether this is an all-day event. Default false.")
                })
            },
            required = listOf("title", "start")
        )
    },
    execute = { args ->
        if (!hasCalendarWritePermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar write permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
        val startRaw = params["start"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val allDay = params["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        if (title.isNullOrBlank() || startRaw.isNullOrBlank()) {
            val payload = buildJsonObject {
                put("error", "MISSING_REQUIRED")
                put("message", "Both 'title' and 'start' are required.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val zone = ZoneId.systemDefault()
        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = parseCalendarTime(startRaw, zone)
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else if (allDay) {
                startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
            } else {
                startTime.plusHours(1)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "end must be later than start.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val description = params["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val location = params["location"]?.jsonPrimitive?.contentOrNull ?: ""

        val eventStartMillis: Long
        val eventEndMillis: Long
        val eventTimeZone: String
        if (allDay) {
            val startDate = startTime.toLocalDate()
            val endDate = endTime.toLocalDate()
            if (!startDate.isBefore(endDate)) {
                val payload = buildJsonObject {
                    put("error", "INVALID_RANGE")
                    put("message", "all-day event end date must be later than start date.")
                }
                return@Tool listOf(UIMessagePart.Text(payload.toString()))
            }
            eventStartMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventEndMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventTimeZone = "UTC"
        } else {
            eventStartMillis = startTime.toInstant().toEpochMilli()
            eventEndMillis = endTime.toInstant().toEpochMilli()
            eventTimeZone = zone.id
        }

        val calendarId = getDefaultCalendarId(context)
        if (calendarId == null) {
            val payload = buildJsonObject {
                put("error", "NO_CALENDAR")
                put("message", "No calendar account found on this device. Please add a calendar account first.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, eventStartMillis)
            put(CalendarContract.Events.DTEND, eventEndMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, eventTimeZone)
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        if (uri == null) {
            val payload = buildJsonObject {
                put("error", "INSERT_FAILED")
                put("message", "Failed to insert calendar event.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val eventId = ContentUris.parseId(uri)
        val payload = buildJsonObject {
            put("success", true)
            put("event_id", eventId)
            put("title", title)
            put("start", startTime.withNano(0).toString())
            put("end", endTime.withNano(0).toString())
            put("all_day", allDay)
            put("location", location)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

internal fun buildCalendarDeleteTool(context: Context): Tool = Tool(
    name = "calendar_delete",
    description = """
        Delete a calendar event on the user's device. Only delete an event using its 'id' and exact
        'title' returned by calendar_query. Every deletion requires user approval. For a recurring
        event, deletion affects the entire series; set 'delete_series' to true only when the user
        explicitly asked to delete the whole series. Deleting a single occurrence is not supported.
        Requires Calendar read and write permissions.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("event_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "The event 'id' returned by calendar_query.")
                })
                put("expected_title", buildJsonObject {
                    put("type", "string")
                    put("description", "The exact event title returned by calendar_query; used to verify the target before deletion.")
                })
                put("delete_series", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Set true only when the user explicitly asked to delete the entire recurring series. Default false.")
                })
            },
            required = listOf("event_id", "expected_title")
        )
    },
    execute = { args ->
        if (!hasCalendarWritePermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar read and write permissions are required. Please ask the user to enable " +
                        "calendar permissions in the assistant's local tools settings."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val eventId = params["event_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val expectedTitle = params["expected_title"]?.jsonPrimitive?.contentOrNull
        val deleteSeries = params["delete_series"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (eventId == null || eventId <= 0 || expectedTitle == null) {
            val payload = buildJsonObject {
                put("error", "MISSING_REQUIRED")
                put("message", "Both a valid 'event_id' and 'expected_title' from calendar_query are required.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
        )
        var currentTitle: String? = null
        var calendarId: Long? = null
        var recurring = false
        context.contentResolver.query(eventUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                currentTitle = cursor.getString(0) ?: ""
                calendarId = cursor.getLong(1)
                recurring = !cursor.isNull(2) && cursor.getString(2).isNotBlank() ||
                    !cursor.isNull(3) && cursor.getString(3).isNotBlank()
            }
        }

        if (currentTitle == null || calendarId == null) {
            val payload = buildJsonObject {
                put("error", "EVENT_NOT_FOUND")
                put("message", "No calendar event was found for event_id=$eventId.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }
        if (currentTitle != expectedTitle) {
            val payload = buildJsonObject {
                put("error", "EVENT_CHANGED")
                put("message", "The event title changed since it was queried. Query the calendar again before deleting.")
                put("current_title", currentTitle)
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }
        if (recurring && !deleteSeries) {
            val payload = buildJsonObject {
                put("error", "RECURRING_EVENT_REQUIRES_SCOPE")
                put("message", "This is a recurring event. Deleting one occurrence is not supported. Set delete_series=true only if the user explicitly asked to remove the whole series.")
                put("title", currentTitle)
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val accessLevel = getCalendarAccessLevel(context, calendarId)
        if (accessLevel == null || accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
            val payload = buildJsonObject {
                put("error", "CALENDAR_NOT_WRITABLE")
                put("message", "The event belongs to a calendar that cannot be modified by this app.")
                put("title", currentTitle)
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val deleted = context.contentResolver.delete(
            eventUri,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
        )
        val payload = buildJsonObject {
            if (deleted == 1) {
                put("success", true)
                put("event_id", eventId)
                put("title", currentTitle)
                put("deleted_series", recurring)
            } else {
                put("error", "DELETE_FAILED")
                put("message", "The calendar provider did not delete the requested event.")
                put("event_id", eventId)
                put("title", currentTitle)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun hasCalendarReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun hasCalendarWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun getDefaultCalendarId(context: Context): Long? {
    val projection = arrayOf(CalendarContract.Calendars._ID)
    val writableSelection =
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
    val writableArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        "$writableSelection AND ${CalendarContract.Calendars.IS_PRIMARY} = 1",
        writableArgs,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        writableSelection,
        writableArgs,
        "${CalendarContract.Calendars.VISIBLE} DESC"
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    return null
}

private fun getCalendarAccessLevel(context: Context, calendarId: Long): Int? {
    val projection = arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        "${CalendarContract.Calendars._ID} = ?",
        arrayOf(calendarId.toString()),
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getInt(0)
    }
    return null
}

private fun parseCalendarTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'. Use ISO-8601 date/date-time or epoch milliseconds.")
}
