package com.example.llamadroid.ui.agent.harness

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

internal enum class NativeHarnessScheduleUnit(val seconds: Long) {
    DAY(86_400L),
    HOUR(3_600L),
    MINUTE(60L),
    SECOND(1L),
}

internal data class NativeHarnessScheduleFrequency(
    val value: Long,
    val unit: NativeHarnessScheduleUnit,
)

internal data class NativeHarnessScheduleTiming(
    val localTime: String,
    val overdue: Boolean,
    val relativeValue: Long?,
    val relativeUnit: NativeHarnessScheduleUnit?,
)

private val scheduleUnits = NativeHarnessScheduleUnit.entries.toList()

/** Matches ScheduleCatalogAction's largest exact unit for recurring records. */
internal fun nativeHarnessScheduleFrequency(record: NativeHarnessScheduleRecord): NativeHarnessScheduleFrequency? {
    val seconds = (record as? NativeHarnessScheduleRecord.Every)?.everySeconds ?: return null
    val unit = scheduleUnits.firstOrNull { seconds % it.seconds == 0L } ?: NativeHarnessScheduleUnit.SECOND
    return NativeHarnessScheduleFrequency(seconds / unit.seconds, unit)
}

/** Produces the localized clock value and relative metadata used by the native card. */
internal fun nativeHarnessScheduleTiming(
    scheduledAt: String,
    nowMillis: Long,
    locale: Locale = Locale.getDefault(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): NativeHarnessScheduleTiming {
    val targetMillis = Instant.parse(scheduledAt).toEpochMilli()
    val differenceMillis = targetMillis - nowMillis
    val absoluteSeconds = kotlin.math.abs(differenceMillis).toDouble() / 1_000.0
    if (differenceMillis == 0L) {
        return NativeHarnessScheduleTiming(
            localTime = localScheduleTime(scheduledAt, locale, zoneId),
            overdue = true,
            relativeValue = null,
            relativeUnit = null,
        )
    }
    val unit = scheduleUnits.firstOrNull { absoluteSeconds >= it.seconds }
        ?: NativeHarnessScheduleUnit.SECOND
    val value = if (differenceMillis > 0L) {
        ceil(absoluteSeconds / unit.seconds).toLong()
    } else {
        floor(absoluteSeconds / unit.seconds).toLong()
    }.coerceAtLeast(1L)
    return NativeHarnessScheduleTiming(
        localTime = localScheduleTime(scheduledAt, locale, zoneId),
        overdue = differenceMillis < 0L,
        relativeValue = value,
        relativeUnit = unit,
    )
}

private fun localScheduleTime(scheduledAt: String, locale: Locale, zoneId: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zoneId)
        .format(Instant.parse(scheduledAt))
