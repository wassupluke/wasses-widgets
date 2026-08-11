package com.wassupluke.widgets

import android.content.Context
import com.wassupluke.widgets.data.AppDisplayMode
import com.wassupluke.widgets.data.AppSelection

/** Per-widget-instance preferences for the app-list widget, keyed by appWidgetId. */
object AppListStore {
    private const val PREFS = "app_list_widgets"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(id: Int, suffix: String) = "w_${id}_$suffix"

    fun selectedPackages(context: Context, id: Int): Set<String> =
        AppSelection.decodeSelection(prefs(context).getString(key(id, "packages"), "") ?: "")

    fun setSelectedPackages(context: Context, id: Int, packages: Set<String>) {
        prefs(context).edit()
            .putString(key(id, "packages"), AppSelection.encodeSelection(packages)).apply()
    }

    fun displayMode(context: Context, id: Int): AppDisplayMode =
        AppDisplayMode.fromNameOrDefault(prefs(context).getString(key(id, "display"), null))

    fun setDisplayMode(context: Context, id: Int, value: AppDisplayMode) {
        prefs(context).edit().putString(key(id, "display"), value.name).apply()
    }

    fun fontSize(context: Context, id: Int): Int =
        prefs(context).getInt(key(id, "font"), Settings.FONT_SIZE_DEFAULT)
            .coerceIn(Settings.FONT_SIZE_MIN, Settings.FONT_SIZE_MAX)

    fun setFontSize(context: Context, id: Int, sp: Int) {
        prefs(context).edit()
            .putInt(key(id, "font"), sp.coerceIn(Settings.FONT_SIZE_MIN, Settings.FONT_SIZE_MAX))
            .apply()
    }

    fun textAlign(context: Context, id: Int): Settings.TextAlign {
        val name = prefs(context).getString(key(id, "align"), null)
        return Settings.TextAlign.values().firstOrNull { it.name == name } ?: Settings.TextAlign.START
    }

    fun setTextAlign(context: Context, id: Int, value: Settings.TextAlign) {
        prefs(context).edit().putString(key(id, "align"), value.name).apply()
    }

    /** Remove all stored keys for a deleted widget. */
    fun clear(context: Context, id: Int) {
        prefs(context).edit()
            .remove(key(id, "packages"))
            .remove(key(id, "display"))
            .remove(key(id, "font"))
            .remove(key(id, "align"))
            .apply()
    }
}
