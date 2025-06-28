package com.oldguy.jillcess.implementations

import com.oldguy.common.io.UByteBuffer
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toInstant
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class, FormatStringsInDatetimeFormats::class)
object AccessDateTime {
    val timeZone = TimeZone.currentSystemDefault()
    val baseDateTime = LocalDateTime(1899, 12, 30, 0, 0, 0).toInstant(timeZone)
    const val secondsInDay = 24 * 60 * 60
    const val isoFormatNoMillis = "yyyy-MM-dd'T'HH:mm:ss"
    const val isoFormat = "$isoFormatNoMillis.SSS"

    val dateTimeIso88601 = LocalDateTime.Format {
        byUnicodePattern(isoFormat)
    }

    val dateTimeFormatter = LocalDateTime.Format {
        byUnicodePattern("yyyy-MM-dd HH:mm:ss")
    }

    /**
     * Microsoft keeps a funky double value for a Instant (no time zone info).
     * Left of decimal is number of days since 1899-12-30, right of decimal is the fraction of
     * a day that must be converted into hours, minutes and seconds
     * Assumption evidently is the retrieve time time zone will be the same as the save time time zone

     * @param bytes must be positioned at the start of the date field, which since it is a Double must have 8
     * bytes available to read as a double.
     */
    fun dateFromBytes(bytes: UByteBuffer): Instant {
        val work: Double = bytes.double
        return dateFromDouble(work)
    }

    /**
     * Microsoft keeps a funky double value for an Instant (no time zone info).
     * Left of decimal is number of days since 1899-12-30, right of decimal is the fraction of
     * a day that must be converted into hours, minutes and seconds
     * Assumption evidently is the retrieve time time zone will be the same as the save time time zone

     * @param dateVal date encoded into a double
     */
    private fun dateFromDouble(dateVal: Double): Instant {
        val days = dateVal.toInt()
        val dayFraction = (dateVal - days).absoluteValue
        val seconds = secondsInDay.toDouble() * dayFraction
        val hours = (seconds / (3600).toDouble()).toInt()
        val minutes = ((seconds - (hours * 3600).toDouble()) / 60.toDouble()).toInt()
        val s = (seconds - (hours * 3600).toDouble() - (minutes * 60).toDouble()).toInt()
        return baseDateTime
            .plus(days.days)
            .plus(hours.hours)
            .plus(minutes.minutes)
            .plus(s.seconds)
    }

    fun doubleFromDate(_value: Instant, timeZone: TimeZone = AccessDateTime.timeZone): Double {
        val period = baseDateTime.periodUntil(_value, timeZone)
        val seconds = (period.hours * 60 * 60) +
                (period.minutes * 60) +
                period.seconds
        val fraction: Double = seconds.toDouble() / secondsInDay.toDouble()
        return period.days.toDouble() + fraction
    }
}