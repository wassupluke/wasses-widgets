package com.wassupluke.widgets.data

/**
 * Pure layout math for the ICON_ONLY app grid. The grid reflows into [columns] equal
 * columns; every row fills those columns except a partial last row, whose few icons are
 * placed so the row aligns without breaking the column grid:
 *  - START/END snap the icons into the first/last column slots, so they stay vertically
 *    aligned with the columns above them (empty slots hold the remaining column space).
 *  - CENTER packs the leftover icons and centres them in the row (no column snapping).
 *
 * No Android APIs — unit-tested on the plain JVM.
 */
object AppGridLayout {

    enum class RowAlign { START, CENTER, END }

    /**
     * One row's placement.
     * @param cells app index per active column slot, or null for an empty (spacer) slot that
     *   still occupies its column. Size == active column count; a full row has no nulls.
     * @param centered true only for a partial last row under CENTER — [cells] then holds just
     *   the icons (no nulls), to be packed and centred rather than snapped to columns.
     */
    data class Row(val cells: List<Int?>, val centered: Boolean)

    /** Number of rows needed to show [totalApps] in [columns]-wide rows. */
    fun rowCount(totalApps: Int, columns: Int): Int {
        if (totalApps <= 0 || columns <= 0) return 0
        return (totalApps + columns - 1) / columns
    }

    fun row(rowIndex: Int, columns: Int, totalApps: Int, align: RowAlign): Row {
        val cols = columns.coerceAtLeast(1)
        val start = rowIndex * cols
        val count = (totalApps - start).coerceIn(0, cols)
        // Full row: every column holds an app.
        if (count >= cols) {
            return Row((0 until cols).map { start + it }, centered = false)
        }
        // Partial last row.
        if (align == RowAlign.CENTER) {
            return Row((0 until count).map { start + it }, centered = true)
        }
        val offset = if (align == RowAlign.END) cols - count else 0
        val cells = (0 until cols).map { c ->
            val appIdx = c - offset
            if (appIdx in 0 until count) start + appIdx else null
        }
        return Row(cells, centered = false)
    }
}
