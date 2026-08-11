package com.wassupluke.widgets

import android.content.Intent
import android.content.pm.PackageManager
import com.wassupluke.widgets.data.AppEntry

/**
 * All launchable apps as [AppEntry]s (one per package, unsorted). Shared by the
 * configure screen and the widget factory so the MAIN/LAUNCHER query and label
 * resolution stay identical between them. Android-only (touches PackageManager),
 * so it lives outside the pure data layer.
 */
fun PackageManager.launcherApps(): List<AppEntry> {
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return queryIntentActivities(query, 0)
        .distinctBy { it.activityInfo.packageName }
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(this).toString()) }
}
