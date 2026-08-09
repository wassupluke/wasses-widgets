package com.wassupluke.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.GridView
import android.widget.RemoteViews
import com.wassupluke.widgets.data.AppDisplayMode
import com.wassupluke.widgets.data.AppListLayout

/** Home-screen grid of user-selected apps. Configured per instance; taps launch the app. */
class AppListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        Debug.log("applist onUpdate ids=${ids.joinToString()}")
        renderAppListWidgets(context, mgr, ids)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { AppListStore.clear(context, it) }
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

        /** Rebuild the RemoteViews for each widget and (re)load its data. */
        fun renderAppListWidgets(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_app_list)

                // Unique data URI per id so each widget gets its own factory instance.
                val serviceIntent = Intent(context, AppListRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                views.setRemoteAdapter(R.id.app_grid, serviceIntent)
                views.setEmptyView(R.id.app_grid, R.id.app_list_empty)

                // Icon-only grids reflow in either orientation; a labelled vertical list is one column.
                val layout = AppListStore.layout(context, id)
                val mode = AppListStore.displayMode(context, id)
                val columns =
                    if (layout == AppListLayout.HORIZONTAL || mode == AppDisplayMode.ICON_ONLY) {
                        GridView.AUTO_FIT
                    } else {
                        1
                    }
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
