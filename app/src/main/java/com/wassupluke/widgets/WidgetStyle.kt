package com.wassupluke.widgets

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/** Per-render visual styling shared by the app's widgets. */
object WidgetStyle {

    /**
     * White, or a wallpaper-derived color when dynamic color is enabled. On Android 12+
     * this uses the Material You accent palette (contrast-picked from the wallpaper's
     * dark-text hint); pre-12 falls back to legible black/white from wallpaper luminance.
     */
    fun textColor(context: Context): Int {
        if (!Settings.dynamicColor(context)) return Color.WHITE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val tone = if (wallpaperPrefersDarkText(context)) {
                android.R.color.system_accent1_600
            } else {
                android.R.color.system_accent1_200
            }
            return ContextCompat.getColor(context, tone)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return Color.WHITE
        val primary = runCatching {
            WallpaperManager.getInstance(context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
        }.getOrNull() ?: return Color.WHITE
        return if (ColorUtils.calculateLuminance(primary) > 0.5) Color.BLACK else Color.WHITE
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun wallpaperPrefersDarkText(context: Context): Boolean {
        val hints = runCatching {
            WallpaperManager.getInstance(context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints ?: 0
        }.getOrDefault(0)
        return (hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
    }

    /** Vertical-centered gravity with the given horizontal alignment. */
    fun gravity(align: Settings.TextAlign): Int =
        Gravity.CENTER_VERTICAL or when (align) {
            Settings.TextAlign.START -> Gravity.START
            Settings.TextAlign.CENTER -> Gravity.CENTER_HORIZONTAL
            Settings.TextAlign.END -> Gravity.END
        }
}
