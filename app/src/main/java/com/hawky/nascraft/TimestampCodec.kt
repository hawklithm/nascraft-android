package com.hawky.nascraft

import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** RFC 3339 timestamps and epoch seconds without requiring Android API 26 java.time. */
object TimestampCodec {
    private val RFC3339 = Regex(
        """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$""",
    )

    fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L

    fun parseEpochSeconds(value: String): Long {
        value.toLongOrNull()?.let { return it }
        val match = RFC3339.matchEntire(value) ?: throw IllegalArgumentException("Invalid timestamp")
        val fields = match.groupValues
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            isLenient = false
            clear()
            set(fields[1].toInt(), fields[2].toInt() - 1, fields[3].toInt(), fields[4].toInt(), fields[5].toInt(), fields[6].toInt())
        }
        val fraction = fields[7].take(3).padEnd(3, '0').toIntOrNull() ?: 0
        val zone = fields[8]
        val offsetSeconds = if (zone == "Z") {
            0
        } else {
            val sign = if (zone[0] == '-') -1 else 1
            val hours = zone.substring(1, 3).toInt()
            val minutes = zone.substring(4, 6).toInt()
            require(hours <= 23 && minutes <= 59) { "Invalid timestamp offset" }
            sign * (hours * 3600 + minutes * 60)
        }
        return (calendar.timeInMillis + fraction) / 1000L - offsetSeconds
    }
}
