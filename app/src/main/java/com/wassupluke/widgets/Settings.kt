package com.wassupluke.widgets

import android.content.Context
import com.wassupluke.widgets.data.TemperatureUnit
import java.util.Locale

/** App-wide shared preferences for the widgets. */
object Settings {
    private const val PREFS = "widget_settings"
    private const val KEY_SHOW_CONDITION = "show_condition"
    private const val KEY_UNIT_MODE = "unit_mode"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_LAUNCH_PACKAGE = "launch_package"
    private const val KEY_LAUNCH_LABEL = "launch_label"
    private const val KEY_ALARM_LAUNCH_PACKAGE = "alarm_launch_package"
    private const val KEY_ALARM_LAUNCH_LABEL = "alarm_launch_label"
    private const val KEY_TEXT_ALIGN = "text_align"
    private const val KEY_ALARM_TEXT_ALIGN = "alarm_text_align"
    private const val KEY_BG_LOCATION_ASKED = "bg_location_asked"
    private const val KEY_BG_DATA_ASKED = "bg_data_asked"

    /** Horizontal alignment of the widget text. */
    enum class TextAlign { START, CENTER, END }

    /** Temperature font size (sp). The condition line scales to 0.6x. */
    const val FONT_SIZE_DEFAULT = 30
    const val FONT_SIZE_MIN = 12
    const val FONT_SIZE_MAX = 64
    const val CONDITION_SIZE_RATIO = 0.6f

    /** Temperature-unit choice. AUTO follows the device's regional settings. */
    enum class UnitMode { AUTO, CELSIUS, FAHRENHEIT }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Enum prefs are stored by name and decoded leniently (unknown value -> default).
    private inline fun <reified T : Enum<T>> enumPref(context: Context, key: String, default: T): T =
        prefs(context).getString(key, null)
            ?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
            ?: default

    private fun setEnumPref(context: Context, key: String, value: Enum<*>) {
        prefs(context).edit().putString(key, value.name).apply()
    }

    /** When false, the widget shows only the numeric temperature. Default: true. */
    fun showCondition(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_CONDITION, true)

    fun setShowCondition(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_CONDITION, value).apply()
    }

    fun unitMode(context: Context): UnitMode =
        enumPref(context, KEY_UNIT_MODE, UnitMode.AUTO)

    fun setUnitMode(context: Context, mode: UnitMode) =
        setEnumPref(context, KEY_UNIT_MODE, mode)

    /** Temperature font size in sp, clamped to [FONT_SIZE_MIN, FONT_SIZE_MAX]. */
    fun fontSize(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE, FONT_SIZE_DEFAULT)
            .coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)

    fun setFontSize(context: Context, sp: Int) {
        prefs(context).edit()
            .putInt(KEY_FONT_SIZE, sp.coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)).apply()
    }

    /** When true, widget text is tinted with the wallpaper's dominant color. Default: false. */
    fun dynamicColor(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DYNAMIC_COLOR, false)

    fun setDynamicColor(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
    }

    /** Package the widget tap launches, or null to tap-to-refresh (the default). */
    fun launchPackage(context: Context): String? =
        prefs(context).getString(KEY_LAUNCH_PACKAGE, null)

    /** Display label of the launch app, for the settings UI. */
    fun launchLabel(context: Context): String? =
        prefs(context).getString(KEY_LAUNCH_LABEL, null)

    fun setLaunchPackage(context: Context, packageName: String, label: String) =
        setLaunchPref(context, KEY_LAUNCH_PACKAGE, KEY_LAUNCH_LABEL, packageName, label)

    fun clearLaunchPackage(context: Context) =
        clearLaunchPref(context, KEY_LAUNCH_PACKAGE, KEY_LAUNCH_LABEL)

    /** Package the alarm-widget tap launches, or null to open the clock app (default). */
    fun alarmLaunchPackage(context: Context): String? =
        prefs(context).getString(KEY_ALARM_LAUNCH_PACKAGE, null)

    fun alarmLaunchLabel(context: Context): String? =
        prefs(context).getString(KEY_ALARM_LAUNCH_LABEL, null)

    fun setAlarmLaunchPackage(context: Context, packageName: String, label: String) =
        setLaunchPref(context, KEY_ALARM_LAUNCH_PACKAGE, KEY_ALARM_LAUNCH_LABEL, packageName, label)

    fun clearAlarmLaunchPackage(context: Context) =
        clearLaunchPref(context, KEY_ALARM_LAUNCH_PACKAGE, KEY_ALARM_LAUNCH_LABEL)

    private fun setLaunchPref(
        context: Context, packageKey: String, labelKey: String, packageName: String, label: String
    ) {
        prefs(context).edit().putString(packageKey, packageName).putString(labelKey, label).apply()
    }

    private fun clearLaunchPref(context: Context, packageKey: String, labelKey: String) {
        prefs(context).edit().remove(packageKey).remove(labelKey).apply()
    }

    /** Whether we've already auto-prompted for background location (asked once, then never nag). */
    fun backgroundLocationAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BG_LOCATION_ASKED, false)

    fun setBackgroundLocationAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_BG_LOCATION_ASKED, true).apply()
    }

    fun backgroundDataAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BG_DATA_ASKED, false)

    fun setBackgroundDataAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_BG_DATA_ASKED, true).apply()
    }

    /** Horizontal alignment of the weather widget's text. */
    fun weatherTextAlign(context: Context): TextAlign =
        enumPref(context, KEY_TEXT_ALIGN, TextAlign.START)

    fun setWeatherTextAlign(context: Context, align: TextAlign) =
        setEnumPref(context, KEY_TEXT_ALIGN, align)

    /**
     * Horizontal alignment of the alarm widget's text. Defaults to the weather
     * alignment so an upgrade preserves the previously shared value until the
     * user explicitly diverges them.
     */
    fun alarmTextAlign(context: Context): TextAlign =
        enumPref(context, KEY_ALARM_TEXT_ALIGN, weatherTextAlign(context))

    fun setAlarmTextAlign(context: Context, align: TextAlign) =
        setEnumPref(context, KEY_ALARM_TEXT_ALIGN, align)

    /** The unit to display, honoring the manual override or, for AUTO, the device locale. */
    fun resolvedUnit(context: Context): TemperatureUnit = when (unitMode(context)) {
        UnitMode.CELSIUS -> TemperatureUnit.CELSIUS
        UnitMode.FAHRENHEIT -> TemperatureUnit.FAHRENHEIT
        UnitMode.AUTO -> TemperatureUnit.forLocale(deviceLocale(context))
    }

    // The resource configuration locale carries Android 14+ regional preferences,
    // which the process-wide default locale does not always reflect.
    private fun deviceLocale(context: Context): Locale =
        context.resources.configuration.locales.let { list ->
            if (list.isEmpty) Locale.getDefault(Locale.Category.FORMAT) else list.get(0)
        }
}
