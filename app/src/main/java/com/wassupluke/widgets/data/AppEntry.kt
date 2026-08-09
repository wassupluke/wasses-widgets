package com.wassupluke.widgets.data

/** A launchable installed app. */
data class AppEntry(val packageName: String, val label: String)

/** Orientation of the app grid. VERTICAL = single column; HORIZONTAL = reflowing columns. */
enum class AppListLayout {
    VERTICAL, HORIZONTAL;

    companion object {
        fun fromNameOrDefault(name: String?): AppListLayout =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: VERTICAL
    }
}

/** What each app entry shows. */
enum class AppDisplayMode {
    ICON_ONLY, LABEL_ONLY, ICON_AND_LABEL;

    companion object {
        fun fromNameOrDefault(name: String?): AppDisplayMode =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: ICON_AND_LABEL
    }
}
