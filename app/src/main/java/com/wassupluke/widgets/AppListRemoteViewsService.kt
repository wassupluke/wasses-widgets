package com.wassupluke.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.graphics.drawable.toBitmap
import com.wassupluke.widgets.data.AppDisplayMode
import com.wassupluke.widgets.data.AppEntry
import com.wassupluke.widgets.data.AppGridLayout
import com.wassupluke.widgets.data.AppSelection
import java.util.Locale

/** Supplies the app-list grid's item views for one widget instance. */
class AppListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val columns = intent.getIntExtra(AppListWidgetProvider.EXTRA_COLUMNS, 1)
        return AppListFactory(applicationContext, id, columns)
    }
}

private class AppListFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val columns: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var entries: List<AppEntry> = emptyList()
    private var mode: AppDisplayMode = AppDisplayMode.ICON_AND_LABEL

    override fun onCreate() {}
    override fun onDestroy() { entries = emptyList() }

    // Icon-only is a grid of rows (one item per row); labelled modes are one item per app.
    override fun getCount(): Int =
        if (mode == AppDisplayMode.ICON_ONLY) AppGridLayout.rowCount(entries.size, columns)
        else entries.size

    // Three distinct item layouts across a factory's life: labelled item, snapped icon row,
    // centred icon row.
    override fun getViewTypeCount(): Int = 3
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getItemId(position: Int): Long =
        if (mode == AppDisplayMode.ICON_ONLY) position.toLong()
        else entries.getOrNull(position)?.packageName?.hashCode()?.toLong() ?: position.toLong()

    override fun onDataSetChanged() {
        mode = AppListStore.displayMode(context, appWidgetId)
        val installed = context.packageManager.launcherApps()
        val selected = AppSelection.resolveSelected(
            AppListStore.selectedPackages(context, appWidgetId),
            installed.map { it.packageName }.toSet()
        )
        val chosen = installed.filter { it.packageName in selected }
        entries = AppSelection.sortAlphabetically(chosen, Locale.getDefault())
    }

    override fun getViewAt(position: Int): RemoteViews =
        if (mode == AppDisplayMode.ICON_ONLY) iconRow(position) else labelledItem(position)

    /** A grid row of icons, placed by [AppGridLayout] so a partial last row aligns. */
    private fun iconRow(rowIndex: Int): RemoteViews {
        val align = when (AppListStore.textAlign(context, appWidgetId)) {
            Settings.TextAlign.START -> AppGridLayout.RowAlign.START
            Settings.TextAlign.CENTER -> AppGridLayout.RowAlign.CENTER
            Settings.TextAlign.END -> AppGridLayout.RowAlign.END
        }
        val plan = AppGridLayout.row(rowIndex, columns, entries.size, align)
        val layoutId =
            if (plan.centered) R.layout.widget_app_list_row_center
            else R.layout.widget_app_list_row
        val row = RemoteViews(context.packageName, layoutId)

        val fontSp = AppListStore.fontSize(context, appWidgetId).toFloat()
        val iconSp = fontSp * AppListWidgetProvider.iconRatio(AppDisplayMode.ICON_ONLY)
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, iconSp, context.resources.displayMetrics
        ).toInt().coerceAtLeast(1)

        // Every declared cell is set explicitly each render (the host recycles row views).
        // Snapped rows wrap each icon in a weighted cell container (app_cell_N) so the tap
        // target is the icon, not the whole column; the centred layout has no containers —
        // its icons are direct children, so only the icon ids are touched there.
        for (cell in ICON_CELL_IDS.indices) {
            val cellId = ICON_CELL_IDS[cell]
            val cellRootId = ICON_CELL_ROOT_IDS[cell]
            val appIdx = plan.cells.getOrNull(cell)
            when {
                appIdx != null -> {
                    val entry = entries[appIdx]
                    val bitmap = runCatching { pmIcon(entry.packageName, px) }.getOrNull()
                    if (bitmap != null) row.setImageViewBitmap(cellId, bitmap)
                    else row.setImageViewResource(cellId, R.drawable.ic_app_grid)
                    if (!plan.centered) row.setViewVisibility(cellRootId, View.VISIBLE)
                    row.setViewVisibility(cellId, View.VISIBLE)
                    row.setOnClickFillInIntent(cellId, fillIn(entry.packageName))
                }
                // Empty active column in a snapped row: keep the cell (it holds the column
                // weight) but hide its icon, so columns stay aligned with no stray tap target.
                cell < plan.cells.size && !plan.centered -> {
                    row.setViewVisibility(cellRootId, View.VISIBLE)
                    row.setViewVisibility(cellId, View.GONE)
                }
                // Unused slot: drop it so the row packs to the active columns.
                else -> {
                    if (!plan.centered) row.setViewVisibility(cellRootId, View.GONE)
                    row.setViewVisibility(cellId, View.GONE)
                }
            }
        }
        return row
    }

    /** A single app row with a label (LABEL_ONLY / ICON_AND_LABEL). */
    private fun labelledItem(position: Int): RemoteViews {
        val entry = entries.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_app_list_item)
        val item = RemoteViews(context.packageName, R.layout.widget_app_list_item)

        val fontSp = AppListStore.fontSize(context, appWidgetId).toFloat()
        val color = WidgetStyle.textColor(context)

        // Position the icon+label group left/center/right within the row.
        val gravity = Gravity.CENTER_VERTICAL or when (AppListStore.textAlign(context, appWidgetId)) {
            Settings.TextAlign.START -> Gravity.START
            Settings.TextAlign.CENTER -> Gravity.CENTER_HORIZONTAL
            Settings.TextAlign.END -> Gravity.END
        }
        item.setInt(R.id.app_list_item_root, "setGravity", gravity)

        item.setViewVisibility(R.id.app_label, View.VISIBLE)
        item.setTextViewText(R.id.app_label, entry.label)
        item.setTextColor(R.id.app_label, color)
        item.setTextViewTextSize(R.id.app_label, TypedValue.COMPLEX_UNIT_SP, fontSp)

        val showIcon = mode != AppDisplayMode.LABEL_ONLY
        item.setViewVisibility(R.id.app_icon, if (showIcon) View.VISIBLE else View.GONE)
        if (showIcon) {
            val iconSp = fontSp * AppListWidgetProvider.iconRatio(mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item.setViewLayoutWidth(R.id.app_icon, iconSp, TypedValue.COMPLEX_UNIT_SP)
                item.setViewLayoutHeight(R.id.app_icon, iconSp, TypedValue.COMPLEX_UNIT_SP)
                item.setViewLayoutMargin(
                    R.id.app_icon, RemoteViews.MARGIN_END, 6f, TypedValue.COMPLEX_UNIT_DIP
                )
            }
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, iconSp, context.resources.displayMetrics
            ).toInt().coerceAtLeast(1)
            val bitmap = runCatching { pmIcon(entry.packageName, px) }.getOrNull()
            if (bitmap != null) item.setImageViewBitmap(R.id.app_icon, bitmap)
            else item.setImageViewResource(R.id.app_icon, R.drawable.ic_app_grid)
        }

        // Tap target is the icon + label only, not the surrounding cell.
        val fillIn = fillIn(entry.packageName)
        item.setOnClickFillInIntent(R.id.app_icon, fillIn)
        item.setOnClickFillInIntent(R.id.app_label, fillIn)
        return item
    }

    private fun fillIn(packageName: String): Intent =
        Intent().putExtra(AppListWidgetProvider.EXTRA_PACKAGE, packageName)

    private fun pmIcon(packageName: String, sizePx: Int) =
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)

    private companion object {
        val ICON_CELL_IDS = intArrayOf(
            R.id.app_icon_0, R.id.app_icon_1, R.id.app_icon_2, R.id.app_icon_3, R.id.app_icon_4,
            R.id.app_icon_5, R.id.app_icon_6, R.id.app_icon_7, R.id.app_icon_8, R.id.app_icon_9,
        )

        // Weighted cell containers in the snapped row layout (parallel to ICON_CELL_IDS);
        // absent from the centred layout, so only touched when !plan.centered.
        val ICON_CELL_ROOT_IDS = intArrayOf(
            R.id.app_cell_0, R.id.app_cell_1, R.id.app_cell_2, R.id.app_cell_3, R.id.app_cell_4,
            R.id.app_cell_5, R.id.app_cell_6, R.id.app_cell_7, R.id.app_cell_8, R.id.app_cell_9,
        )
    }
}
