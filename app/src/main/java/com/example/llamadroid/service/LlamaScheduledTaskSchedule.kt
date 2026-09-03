package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskScheduleType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

object LlamaScheduledTaskSchedule {
    fun weekdayBit(dayOfWeek: DayOfWeek): Int = 1 shl (dayOfWeek.value - 1)

    fun hasWeekday(mask: Int, dayOfWeek: DayOfWeek): Boolean =
        mask and weekdayBit(dayOfWeek) != 0

    fun computeNextRun(
        task: LlamaScheduledTaskEntity,
        afterMillis: Long = System.currentTimeMillis()
    ): Long? {
        if (!task.enabled) return null
        val zone = zoneFor(task.timezoneId)
        return when (task.scheduleType) {
            LlamaScheduledTaskScheduleType.ONE_TIME -> {
                task.oneTimeAtMillis?.takeIf { it > afterMillis }
            }
            LlamaScheduledTaskScheduleType.DAILY -> {
                nextDaily(afterMillis, zone, timeOfDay(task.timeOfDayMinutes))
            }
            LlamaScheduledTaskScheduleType.WEEKLY -> {
                nextWeekly(afterMillis, zone, task.weekdaysMask, timeOfDay(task.timeOfDayMinutes))
            }
            LlamaScheduledTaskScheduleType.MONTHLY -> {
                nextMonthly(afterMillis, zone, task.dayOfMonth, timeOfDay(task.timeOfDayMinutes))
            }
            else -> null
        }
    }

    fun describeSchedule(task: LlamaScheduledTaskEntity): String =
        when (task.scheduleType) {
            LlamaScheduledTaskScheduleType.ONE_TIME -> "One time"
            LlamaScheduledTaskScheduleType.DAILY -> "Daily"
            LlamaScheduledTaskScheduleType.WEEKLY -> "Weekly"
            LlamaScheduledTaskScheduleType.MONTHLY -> "Monthly"
            else -> task.scheduleType
        }

    private fun nextDaily(afterMillis: Long, zone: ZoneId, time: LocalTime): Long {
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMillis), zone)
        val today = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()
        val candidate = LocalDateTime.of(today, time)
        val next = if (candidate.isAfter(after)) candidate else candidate.plusDays(1)
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    private fun nextWeekly(afterMillis: Long, zone: ZoneId, weekdaysMask: Int, time: LocalTime): Long? {
        if (weekdaysMask == 0) return null
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMillis), zone)
        val today = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (!hasWeekday(weekdaysMask, date.dayOfWeek)) continue
            val candidate = LocalDateTime.of(date, time)
            if (candidate.isAfter(after)) return candidate.atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    private fun nextMonthly(afterMillis: Long, zone: ZoneId, requestedDayOfMonth: Int, time: LocalTime): Long {
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMillis), zone)
        val currentMonth = YearMonth.from(after)
        val day = requestedDayOfMonth.coerceIn(1, 31)
        val currentCandidate = monthlyCandidate(currentMonth, day, time)
        val next = if (currentCandidate.isAfter(after)) {
            currentCandidate
        } else {
            monthlyCandidate(currentMonth.plusMonths(1), day, time)
        }
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    private fun monthlyCandidate(month: YearMonth, dayOfMonth: Int, time: LocalTime): LocalDateTime {
        val clampedDay = dayOfMonth.coerceAtMost(month.lengthOfMonth())
        return LocalDateTime.of(month.atDay(clampedDay), time)
    }

    private fun timeOfDay(minutes: Int): LocalTime {
        val safeMinutes = minutes.coerceIn(0, 23 * 60 + 59)
        return LocalTime.of(safeMinutes / 60, safeMinutes % 60)
    }

    private fun zoneFor(timezoneId: String): ZoneId =
        runCatching { ZoneId.of(timezoneId) }.getOrDefault(ZoneId.systemDefault())
}
