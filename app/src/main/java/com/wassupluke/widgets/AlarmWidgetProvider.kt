package com.wassupluke.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.util.TypedValue
import android.widget.RemoteViews
import com.wassupluke.widgets.data.AlarmDay
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class AlarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Debug.log("alarm onUpdate ids=${appWidgetIds.joinToString()}")
        renderAlarmWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Debug.log("alarm onReceive action=${intent.action}")
        when (intent.action) {
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED -> renderAlarmWidgets(context)
        }
    }

    companion object {
        fun renderAlarmWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AlarmWidgetProvider::class.java)
            )
            if (ids.isEmpty()) {
                Debug.log("alarm render skipped — no widgets placed")
                return
            }

            val alarm = nextUserAlarm(context)
            val text = alarmText(context, alarm)
            Debug.log(
                "alarm render ids=${ids.joinToString()} text='$text' " +
                    "creator=${alarm?.showIntent?.creatorPackage ?: "none"}"
            )
            val color = WidgetStyle.textColor(context)
            val gravity = WidgetStyle.gravity(Settings.alarmTextAlign(context))
            val fontSize = Settings.fontSize(context).toFloat()
            val onClick = tapIntent(context, alarm)

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_alarm)
                views.setTextViewText(R.id.alarm_text, text)
                views.setTextColor(R.id.alarm_text, color)
                views.setInt(R.id.alarm_icon, "setColorFilter", color)
                views.setTextViewTextSize(R.id.alarm_text, TypedValue.COMPLEX_UNIT_SP, fontSize)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    views.setViewLayoutWidth(R.id.alarm_icon, fontSize, TypedValue.COMPLEX_UNIT_SP)
                    views.setViewLayoutHeight(R.id.alarm_icon, fontSize, TypedValue.COMPLEX_UNIT_SP)
                }
                views.setInt(R.id.alarm_root, "setGravity", gravity)
                // Tap target is the icon + text, not the transparent cell.
                views.setOnClickPendingIntent(R.id.alarm_icon, onClick)
                views.setOnClickPendingIntent(R.id.alarm_text, onClick)
                manager.updateAppWidget(id, views)
            }
        }

        /** The next alarm, but only if it was set by a user-facing app. */
        private fun nextUserAlarm(context: Context): AlarmManager.AlarmClockInfo? {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val info = am.nextAlarmClock ?: return null
            val creator = info.showIntent?.creatorPackage ?: run {
                Debug.log("alarm: next alarm has no showIntent creator — ignored")
                return null
            }
            val userFacing = context.packageManager.getLaunchIntentForPackage(creator) != null
            if (!userFacing) Debug.log("alarm: ignoring non-user alarm from $creator")
            return if (userFacing) info else null
        }

        private fun alarmText(context: Context, info: AlarmManager.AlarmClockInfo?): String {
            if (info == null) return context.getString(R.string.alarm_none)
            val locale = Locale.getDefault()
            val prefix = AlarmDay.dayPrefix(
                info.triggerTime,
                System.currentTimeMillis(),
                ZoneId.systemDefault(),
                locale,
                context.getString(R.string.alarm_tomorrow)
            )
            // getTimeFormat honors the device's 12/24-hour setting (and locale).
            val time = android.text.format.DateFormat.getTimeFormat(context)
                .format(Date(info.triggerTime))
            return if (prefix != null) "$prefix $time" else time
        }

        /**
         * Tap target: the configured app, else the alarm's own showIntent, else the
         * system "show alarms" screen.
         */
        private fun tapIntent(
            context: Context,
            info: AlarmManager.AlarmClockInfo?
        ): PendingIntent {
            Settings.alarmLaunchPackage(context)?.let { pkg ->
                context.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                    return PendingIntent.getActivity(
                        context, 2, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
            }
            info?.showIntent?.let { return it }
            val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, 3, showAlarms,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
