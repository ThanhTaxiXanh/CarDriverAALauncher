package com.thanhtaxixanh.cardriverlauncher

import org.junit.Assert.*
import org.junit.Test

class LunarCalendarTest {
    @Test fun tet2024() {
        val date = LunarCalendar.solarToLunar(10, 2, 2024)
        assertEquals(LunarDate(1, 1, 2024, false), date)
    }
    @Test fun tet2025() {
        val date = LunarCalendar.solarToLunar(29, 1, 2025)
        assertEquals(LunarDate(1, 1, 2025, false), date)
    }
    @Test fun canChiYear2024() { assertEquals("Giáp Thìn", LunarCalendar.canChi(2024)) }
    @Test fun inverseTet2024() {
        assertArrayEquals(intArrayOf(10, 2, 2024), LunarCalendar.lunarToSolar(1, 1, 2024, false))
    }
}
