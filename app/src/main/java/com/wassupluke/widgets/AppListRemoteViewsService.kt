package com.wassupluke.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.graphics.drawable.toBitmap
import com.wassupluke.widgets.data.AppDisplayMode
import com.wassupluke.widgets.data.AppEntry
import com.wassupluke.widgets.data.AppSelection
import java.util.Locale

/** Supplies the app-list grid's item views for one widget instance. */
class AppListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return AppListFactory(applicationContext, id)
    }
}

private class AppListFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    /** Icon size as a multiple of the label's font size, so bigger font → bigger icons. */
    private val iconSizeRatio = 2.0f

    private var entries: List<AppEntry> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() { entries = emptyList() }
    override fun getCount(): Int = entries.size
    override fun getViewTypeCount(): Int = 1
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getItemId(position: Int): Long =
        entries.getOrNull(position)?.packageName?.hashCode()?.toLong() ?: position.toLong()

    override fun onDataSetChanged() {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = pm.queryIntentActivities(query, 0)
            .distinctBy { it.activityInfo.packageName }
        val installedPackages = installed.map { it.activityInfo.packageName }.toSet()
        val selected = AppSelection.resolveSelected(
            AppListStore.selectedPackages(context, appWidgetId), installedPackages
        )
        val chosen = installed
            .filter { it.activityInfo.packageName in selected }
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        entries = AppSelection.sortAlphabetically(chosen, Locale.getDefault())
    }

    override fun getViewAt(position: Int): RemoteViews {
        val entry = entries.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_app_list_item)
        val item = RemoteViews(context.packageName, R.layout.widget_app_list_item)

        val mode = AppListStore.displayMode(context, appWidgetId)
        val fontSp = AppListStore.fontSize(context, appWidgetId).toFloat()
        val color = WidgetStyle.textColor(context)

        val gravity = Gravity.CENTER_VERTICAL or when (AppListStore.textAlign(context, appWidgetId)) {
            Settings.TextAlign.START -> Gravity.START
            Settings.TextAlign.CENTER -> Gravity.CENTER_HORIZONTAL
            Settings.TextAlign.END -> Gravity.END
        }
        item.setInt(R.id.app_list_item_root, "setGravity", gravity)
        item.setInt(R.id.app_label, "setGravity", gravity)

        val showLabel = mode != AppDisplayMode.ICON_ONLY
        item.setViewVisibility(R.id.app_label, if (showLabel) View.VISIBLE else View.GONE)
        if (showLabel) {
            item.setTextViewText(R.id.app_label, entry.label)
            item.setTextColor(R.id.app_label, color)
            item.setTextViewTextSize(R.id.app_label, TypedValue.COMPLEX_UNIT_SP, fontSp)
        }

        val showIcon = mode != AppDisplayMode.LABEL_ONLY
        item.setViewVisibility(R.id.app_icon, if (showIcon) View.VISIBLE else View.GONE)
        if (showIcon) {
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, fontSp * iconSizeRatio, context.resources.displayMetrics
            ).toInt().coerceAtLeast(1)
            val bitmap = runCatching {
                pmIcon(entry.packageName, px)
            }.getOrNull()
            if (bitmap != null) {
                item.setImageViewBitmap(R.id.app_icon, bitmap)
            } else {
                item.setImageViewResource(R.id.app_icon, R.drawable.ic_app_grid)
            }
        }

        item.setOnClickFillInIntent(
            R.id.app_list_item_root,
            Intent().putExtra(AppListWidgetProvider.EXTRA_PACKAGE, entry.packageName)
        )
        return item
    }

    private fun pmIcon(packageName: String, sizePx: Int) =
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
}
