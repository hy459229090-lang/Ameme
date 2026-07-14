package com.ameme.android.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val fullDateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    .withLocale(Locale.SIMPLIFIED_CHINESE)

fun LocalDate.displayDate(): String = format(fullDateFormatter)
