package com.babasama.edendale.domain

object ReleaseCalendar {

    fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0) && (year % 100 != 0 || year % 400 == 0)
    }

    fun daysInMonth(year: Int, month: Int): Int {
        return when (month) {
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 31
        }
    }

    /** Returns 0 for Sunday, 1 for Monday, ..., 6 for Saturday (Sakamoto's algorithm). */
    fun dayOfWeek(year: Int, month: Int, day: Int): Int {
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        var y = year
        if (month < 3) y -= 1
        return (y + y / 4 - y / 100 + y / 400 + t[month - 1] + day) % 7
    }

    private val monthNames = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    fun monthName(month: Int): String = monthNames[month - 1]

    private fun padZero(value: Int): String = if (value < 10) "0$value" else value.toString()

    private fun toAbsoluteDays(year: Int, month: Int, day: Int): Int {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        return 365 * y + y / 4 - y / 100 + y / 400 + (153 * m + 8) / 5 + day
    }

    private fun daysBetween(y1: Int, m1: Int, d1: Int, y2: Int, m2: Int, d2: Int): Int {
        return toAbsoluteDays(y2, m2, d2) - toAbsoluteDays(y1, m1, d1)
    }

    fun formatSummary(from: String, to: String): String {
        if (from.length != 10 || to.length != 10) return ""

        val y1 = from.substring(0, 4).toIntOrNull() ?: return ""
        val m1 = from.substring(5, 7).toIntOrNull() ?: return ""
        val d1 = from.substring(8, 10).toIntOrNull() ?: return ""

        val y2 = to.substring(0, 4).toIntOrNull() ?: return ""
        val m2 = to.substring(5, 7).toIntOrNull() ?: return ""
        val d2 = to.substring(8, 10).toIntOrNull() ?: return ""

        val days = daysBetween(y1, m1, d1, y2, m2, d2) + 1
        val daysStr = if (days == 1) "1 day" else "$days days"

        if (from == to) {
            return "$d1 ${monthName(m1)} $y1 · 1 day"
        }

        if (y1 == y2) {
            if (m1 == m2) {
                return "$d1 – $d2 ${monthName(m1)} $y1 · $daysStr"
            }
            return "$d1 ${monthName(m1)} – $d2 ${monthName(m2)} $y1 · $daysStr"
        }

        return "$d1 ${monthName(m1)} $y1 – $d2 ${monthName(m2)} $y2 · $daysStr"
    }

    fun createDateKey(year: Int, month: Int, day: Int): String {
        return "$year-${padZero(month)}-${padZero(day)}"
    }
}

data class DaySlot(
    val dateKey: String, // "yyyy-MM-dd"
    val year: Int,
    val month: Int,
    val day: Int,
    val isFirstOfMonth: Boolean,
)

data class WeekColumn(
    val slots: List<DaySlot?>, // size 7. 0 = Sun, 1 = Mon ...
    val monthLabel: String?
)

class ReleaseYearGrid(val year: Int) {
    val columns: List<WeekColumn>

    init {
        val cols = mutableListOf<WeekColumn>()
        var currentMonth = 1
        var currentDay = 1

        var currentSlots = arrayOfNulls<DaySlot>(7)
        var monthLabelForCol: String? = "Jan"

        while (currentMonth <= 12) {
            val dow = ReleaseCalendar.dayOfWeek(year, currentMonth, currentDay)
            val isFirstOfMonth = (currentDay == 1)

            if (isFirstOfMonth && currentMonth > 1) {
                monthLabelForCol = ReleaseCalendar.monthName(currentMonth)
            }

            val dateKey = ReleaseCalendar.createDateKey(year, currentMonth, currentDay)
            currentSlots[dow] = DaySlot(dateKey, year, currentMonth, currentDay, isFirstOfMonth)

            if (dow == 6) { // Saturday, end of column
                cols.add(WeekColumn(currentSlots.toList(), monthLabelForCol))
                currentSlots = arrayOfNulls<DaySlot>(7)
                monthLabelForCol = null
            }

            currentDay++
            if (currentDay > ReleaseCalendar.daysInMonth(year, currentMonth)) {
                currentMonth++
                currentDay = 1
            }
        }

        if (currentSlots.any { it != null }) {
            cols.add(WeekColumn(currentSlots.toList(), monthLabelForCol))
        }

        columns = cols
    }
}
