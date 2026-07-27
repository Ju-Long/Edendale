package com.babasama.edendale.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseCalendarTest {

    @Test
    fun testLeapYear() {
        assertTrue(ReleaseCalendar.isLeapYear(2020))
        assertTrue(ReleaseCalendar.isLeapYear(2024))
        assertTrue(ReleaseCalendar.isLeapYear(2000))
        assertFalse(ReleaseCalendar.isLeapYear(2023))
        assertFalse(ReleaseCalendar.isLeapYear(2100))
        assertFalse(ReleaseCalendar.isLeapYear(1900))
    }

    @Test
    fun testDaysInMonth() {
        assertEquals(31, ReleaseCalendar.daysInMonth(2024, 1))
        assertEquals(29, ReleaseCalendar.daysInMonth(2024, 2))
        assertEquals(28, ReleaseCalendar.daysInMonth(2023, 2))
        assertEquals(30, ReleaseCalendar.daysInMonth(2024, 4))
    }

    @Test
    fun testDayOfWeek() {
        // 2026-03-12 is a Thursday (4)
        assertEquals(4, ReleaseCalendar.dayOfWeek(2026, 3, 12))
        // 2024-01-01 is a Monday (1)
        assertEquals(1, ReleaseCalendar.dayOfWeek(2024, 1, 1))
        // 2023-12-31 is a Sunday (0)
        assertEquals(0, ReleaseCalendar.dayOfWeek(2023, 12, 31))
    }

    @Test
    fun testFormatSummary() {
        // 1-day
        assertEquals("12 Mar 2026 · 1 day", ReleaseCalendar.formatSummary("2026-03-12", "2026-03-12"))

        // same-month
        assertEquals("12 – 18 Mar 2026 · 7 days", ReleaseCalendar.formatSummary("2026-03-12", "2026-03-18"))

        // same-year
        assertEquals("28 Feb – 2 Mar 2026 · 3 days", ReleaseCalendar.formatSummary("2026-02-28", "2026-03-02"))

        // cross-year
        assertEquals("20 Dec 2025 – 4 Jan 2026 · 16 days", ReleaseCalendar.formatSummary("2025-12-20", "2026-01-04"))
    }

    @Test
    fun testGridShape() {
        // 2024 starts on Monday
        val grid2024 = ReleaseYearGrid(2024)
        val firstCol2024 = grid2024.columns.first()
        assertNull(firstCol2024.slots[0]) // Sunday is null
        assertEquals(1, firstCol2024.slots[1]?.day) // Monday is 1st
        assertEquals("Jan", firstCol2024.monthLabel)

        // 366 days in 2024. 1st is Mon (1). 1st col has 6 days. Last day 2024-12-31 is Tue (2).
        // 360 days remaining -> 51 full weeks -> 3 days in last col. 1+51+1 = 53 cols.
        // Wait, 53 cols is typical. Let's check 2026 (starts Thursday, 365 days).
        val grid2026 = ReleaseYearGrid(2026)
        val firstCol2026 = grid2026.columns.first()
        assertNull(firstCol2026.slots[3]) // Wed is null
        assertEquals(1, firstCol2026.slots[4]?.day) // Thu is 1st
    }

}
