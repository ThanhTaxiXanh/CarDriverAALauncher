package com.thanhtaxixanh.cardriverlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnWidthsTest {
    @Test fun balancedDefaults() {
        assertEquals(listOf(100), ColumnWidths.even(1))
        assertEquals(listOf(50, 50), ColumnWidths.even(2))
        assertEquals(listOf(34, 33, 33), ColumnWidths.even(3))
    }

    @Test fun acceptsPresetWithoutChangingValues() {
        assertEquals(listOf(25, 50, 25), ColumnWidths.normalize(listOf(25, 50, 25), 3))
    }

    @Test fun previousSettingsReceiveBalancedWidths() {
        assertEquals(listOf(50, 50), ColumnWidths.normalize(emptyList(), 2))
    }

    @Test fun repairsBrokenSumAndKeepsMinimum() {
        val widths = ColumnWidths.normalize(listOf(20, 20, 20), 3)
        assertEquals(100, widths.sum())
        assertEquals(3, widths.size)
        assertEquals(true, widths.all { it >= ColumnWidths.MIN_PERCENT })
    }

    @Test fun updatingOneColumnRebalancesOtherColumns() {
        val changed = ColumnWidths.update(listOf(25, 50, 25), 1, 60)
        assertEquals(60, changed[1])
        assertEquals(100, changed.sum())
        assertEquals(true, changed.all { it >= ColumnWidths.MIN_PERCENT })
    }

    @Test fun respectsMaximumWidth() {
        val changed = ColumnWidths.update(listOf(25, 50, 25), 1, 100)
        assertEquals(listOf(5, 90, 5), changed)
    }

    @Test fun supportsSingleColumn() {
        assertEquals(listOf(100), ColumnWidths.update(listOf(100), 0, 40))
    }
}
