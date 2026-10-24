package com.thanhtaxixanh.cardriverlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class RowHeightTest {
    @Test fun existingSettingsDefaultToEqualRowHeights() {
        val config = LayoutConfig(rows = 2, columns = 1, rowPercents = emptyList()).normalized()
        assertEquals(listOf(50, 50), config.rowPercents)
        assertEquals(2, config.cells.size)
    }

    @Test fun independentHeightForTwoStackedWidgets() {
        val config = LayoutConfig(rows = 2, columns = 1, rowPercents = listOf(30, 70)).normalized()
        assertEquals(listOf(30, 70), config.rowPercents)
        assertEquals(listOf(100), config.columnPercents)
    }

    @Test fun updateBalancesTheOtherWidget() {
        val result = ColumnWidths.update(listOf(50, 50), 0, 25)
        assertEquals(listOf(25, 75), result)
    }

    @Test fun threeRowsPreservePreset() {
        val config = LayoutConfig(rows = 3, rowPercents = listOf(25, 50, 25)).normalized()
        assertEquals(listOf(25, 50, 25), config.rowPercents)
    }

    @Test fun oneRowAlwaysTakesEntireHeight() {
        val config = LayoutConfig(rows = 1, rowPercents = listOf(33, 67)).normalized()
        assertEquals(listOf(100), config.rowPercents)
    }

    @Test fun invalidRowPercentsNormalizeToHundred() {
        val heights = ColumnWidths.normalize(listOf(5, 10, 5), 3)
        assertEquals(100, heights.sum())
        assertEquals(true, heights.all { it >= ColumnWidths.MIN_PERCENT })
    }
}
