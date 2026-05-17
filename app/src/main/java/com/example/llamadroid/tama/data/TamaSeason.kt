package com.example.llamadroid.tama.data

import java.util.Calendar

enum class TamaSeason {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER;

    companion object {
        fun forMonth(month: Int): TamaSeason = when (month) {
            Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> SPRING
            Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> SUMMER
            Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER -> AUTUMN
            Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> WINTER
            else -> WINTER
        }

        fun current(calendar: Calendar = Calendar.getInstance()): TamaSeason =
            forMonth(calendar.get(Calendar.MONTH))
    }
}
