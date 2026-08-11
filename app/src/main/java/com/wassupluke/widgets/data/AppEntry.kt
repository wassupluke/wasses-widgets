package com.wassupluke.widgets.data

/** A launchable installed app. */
data class AppEntry(val packageName: String, val label: String)

/** What each app entry shows. */
enum class AppDisplayMode {
    ICON_ONLY, LABEL_ONLY, ICON_AND_LABEL;

    companion object {
        fun fromNameOrDefault(name: String?): AppDisplayMode =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: ICON_AND_LABEL
    }
}
