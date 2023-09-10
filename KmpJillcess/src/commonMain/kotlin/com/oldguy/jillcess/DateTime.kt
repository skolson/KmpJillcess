package com.oldguy.jillcess

import korlibs.time.DateFormat
import korlibs.time.KlockLocale
import korlibs.time.PatternDateFormat

/**
 * Patterns and other stuff related to date to string handling, most likely to change when kotlin
 * publishes multi-platform datetime API
 */
object JillcessDateTime {
    val locale = KlockLocale.default
    val localeDateTimeFormatter = locale.formatDateTimeMedium
    val localeDateFormatter = locale.formatDateMedium
    val dateIso88601 = DateFormat.FORMAT_DATE
    val dateTimeIso88601 = DateFormat.FORMAT1

    val dateTimeFormatter = PatternDateFormat("yyyy-MM-dd HH:mm:ss")
}
