package com.wassupluke.widgets.data

import com.wassupluke.widgets.data.AppGridLayout.RowAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGridLayoutTest {

    @Test
    fun rowCount_roundsUp() {
        assertEquals(0, AppGridLayout.rowCount(0, 4))
        assertEquals(1, AppGridLayout.rowCount(1, 4))
        assertEquals(1, AppGridLayout.rowCount(4, 4))
        assertEquals(2, AppGridLayout.rowCount(5, 4))
        assertEquals(3, AppGridLayout.rowCount(9, 4))
    }

    @Test
    fun rowCount_guardsZeroColumns() {
        assertEquals(0, AppGridLayout.rowCount(5, 0))
    }

    @Test
    fun fullRow_fillsEveryColumn() {
        val row = AppGridLayout.row(rowIndex = 0, columns = 4, totalApps = 5, align = RowAlign.START)
        assertEquals(listOf<Int?>(0, 1, 2, 3), row.cells)
        assertFalse(row.centered)
    }

    @Test
    fun partialRow_startAlign_snapsToLeadingColumns() {
        val row = AppGridLayout.row(rowIndex = 1, columns = 4, totalApps = 5, align = RowAlign.START)
        assertEquals(listOf<Int?>(4, null, null, null), row.cells)
        assertFalse(row.centered)
    }

    @Test
    fun partialRow_endAlign_snapsToTrailingColumns() {
        val row = AppGridLayout.row(rowIndex = 1, columns = 4, totalApps = 5, align = RowAlign.END)
        assertEquals(listOf<Int?>(null, null, null, 4), row.cells)
        assertFalse(row.centered)
    }

    @Test
    fun partialRow_endAlign_twoIcons_snapsToLastTwoColumns() {
        val row = AppGridLayout.row(rowIndex = 1, columns = 4, totalApps = 6, align = RowAlign.END)
        assertEquals(listOf<Int?>(null, null, 4, 5), row.cells)
    }

    @Test
    fun partialRow_centerAlign_packsIconsOnly() {
        val row = AppGridLayout.row(rowIndex = 1, columns = 4, totalApps = 5, align = RowAlign.CENTER)
        assertEquals(listOf<Int?>(4), row.cells)
        assertTrue(row.centered)
    }
}
