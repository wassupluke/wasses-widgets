package com.wassupluke.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import com.wassupluke.widgets.data.AppDisplayMode

/** Home-screen grid of user-selected apps. Configured per instance; taps launch the app. */
class AppListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        Debug.log("applist onUpdate ids=${ids.joinToString()}")
        renderAppListWidgets(context, mgr, ids)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { AppListStore.clear(context, it) }
    }

    // Re-render (recompute reflow columns) when the widget is resized.
    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        renderAppListWidgets(context, mgr, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_LAUNCH_APP -> launchApp(context, intent.getStringExtra(EXTRA_PACKAGE))
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_CHANGED -> notifyData(context)
        }
    }

    private fun launchApp(context: Context, packageName: String?) {
        if (packageName.isNullOrEmpty()) return
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch == null) {
            Debug.log("applist launch: no launch intent for $packageName")
            return
        }
        context.startActivity(launch)
    }

    private fun notifyData(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, AppListWidgetProvider::class.java))
        if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.app_grid)
    }

    companion object {
        const val ACTION_LAUNCH_APP = "com.wassupluke.widgets.action.LAUNCH_APP"
        const val EXTRA_PACKAGE = "com.wassupluke.widgets.extra.PACKAGE"
        private const val EXTRA_GRID_GENERATION = "com.wassupluke.widgets.extra.GRID_GEN"

        // Icon size as a multiple of the font size. Bigger when icon-only (fills the grid),
        // smaller when inline beside a label. Shared with the item factory so the column
        // math and the rendered icon agree.
        const val ICON_RATIO_ICON_ONLY = 2.5f
        const val ICON_RATIO_INLINE = 1.4f

        fun iconRatio(mode: AppDisplayMode): Float =
            if (mode == AppDisplayMode.ICON_ONLY) ICON_RATIO_ICON_ONLY else ICON_RATIO_INLINE

        // Fallback width when the host reports no size (matches the declared minWidth in
        // app_list_widget_info.xml). Some AppWidgetHosts never populate the options bundle,
        // so without this the icon grid would collapse to a single column.
        private const val DEFAULT_WIDTH_DP = 110

        /**
         * Reflow column count for the icon-only grid from the widget's current width;
         * 1 for any labelled list (always a single vertical column).
         */
        private fun computeColumns(
            context: Context,
            mgr: AppWidgetManager,
            id: Int,
            mode: AppDisplayMode
        ): Int {
            if (mode != AppDisplayMode.ICON_ONLY) return 1
            // Hosts populate MIN_WIDTH (current-orientation width) and/or MAX_WIDTH
            // inconsistently — take the smallest positive one, and fall back to the
            // declared minWidth so a host that reports nothing still yields a real grid.
            val opts = mgr.getAppWidgetOptions(id)
            val widthDp = listOf(
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            ).filter { it > 0 }.minOrNull() ?: DEFAULT_WIDTH_DP
            // sp≈dp is close enough for a column estimate.
            val iconDp = AppListStore.fontSize(context, id) * iconRatio(mode)
            // Reserve the cell's real footprint (icon + 4dp root padding each side + 4dp
            // spacing) so a small-font icon grid isn't packed so tight the icons clip.
            val cellDp = iconDp + 12f
            return (widthDp / cellDp).toInt().coerceAtLeast(1)
        }

        /** Rebuild the RemoteViews for each widget and (re)load its data. */
        fun renderAppListWidgets(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_app_list)

                // Icon-only reflows into as many columns as the current width fits; a labelled
                // list stays one column. A concrete integer (not AUTO_FIT, which re-measures and
                // collapses to one column) keeps the grid stably filled.
                val mode = AppListStore.displayMode(context, id)
                val columns = computeColumns(context, mgr, id, mode)

                // Unique data URI per id so each widget gets its own factory instance. Fold the
                // column count into the URI: the host caches a collection's column layout and won't
                // re-apply setNumColumns on a plain update, so a new URI forces it to rebuild the
                // grid when the column count changes (reconfigure / resize).
                val serviceIntent = Intent(context, AppListRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(EXTRA_GRID_GENERATION, columns)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                views.setRemoteAdapter(R.id.app_grid, serviceIntent)
                views.setEmptyView(R.id.app_grid, R.id.app_list_empty)
                views.setTextColor(R.id.app_list_empty, WidgetStyle.textColor(context))
                views.setTextViewTextSize(
                    R.id.app_list_empty, TypedValue.COMPLEX_UNIT_SP,
                    AppListStore.fontSize(context, id).toFloat()
                )

                views.setInt(R.id.app_grid, "setNumColumns", columns)

                // Mutable template completed per-item by setOnClickFillInIntent (carries the package).
                val template = PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, AppListWidgetProvider::class.java).setAction(ACTION_LAUNCH_APP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                views.setPendingIntentTemplate(R.id.app_grid, template)

                mgr.updateAppWidget(id, views)
            }
            if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.app_grid)
        }
    }
}
