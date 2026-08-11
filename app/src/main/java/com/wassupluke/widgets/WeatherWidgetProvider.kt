package com.wassupluke.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.wassupluke.widgets.data.WeatherCache
import com.wassupluke.widgets.data.WeatherCode
import com.wassupluke.widgets.data.formatTemperature
import java.util.Date
import java.util.concurrent.TimeUnit

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Render from cache + ensure the heartbeat; do NOT fetch here — the host (e.g. the
        // launcher's AppWidgetHost) can fire onUpdate repeatedly, and a fetch per update would
        // storm and, with some hosts, loop.
        Debug.log("weather onUpdate ids=${appWidgetIds.joinToString()}")
        renderWidgets(context)
        scheduleHeartbeat(context)
    }

    override fun onEnabled(context: Context) {
        // First widget placed: seed an initial fetch, start the refresh heartbeat, and start
        // receiving background location updates (so background refreshes have a location).
        Debug.log("weather onEnabled — first widget placed")
        RefreshWeatherWorker.enqueueOnce(context)
        scheduleHeartbeat(context)
        BackgroundLocationUpdates.register(context)
    }

    override fun onDisabled(context: Context) {
        Debug.log("weather onDisabled — last widget removed")
        cancelHeartbeat(context)
        BackgroundLocationUpdates.unregister(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Debug.log("weather onReceive action=${intent.action}")
        when (intent.action) {
            ACTION_REFRESH -> RefreshWeatherWorker.enqueueOnce(context)
            // The heartbeat alarm fired: refresh, then re-arm the next one.
            ACTION_HEARTBEAT -> if (hasWidgets(context)) {
                RefreshWeatherWorker.enqueueOnce(context)
                scheduleHeartbeat(context)
                // Self-heal: registration is skipped if location was off when we last tried, and
                // is lost outright on reboot. Re-registering is idempotent and cheap, so doing it
                // here means recovery doesn't depend on the user opening the app.
                BackgroundLocationUpdates.register(context)
            }
            // Alarms and location-update registrations are cleared by a reboot; restore both if a
            // widget is still placed.
            Intent.ACTION_BOOT_COMPLETED -> if (hasWidgets(context)) {
                scheduleHeartbeat(context)
                BackgroundLocationUpdates.register(context)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.wassupluke.widgets.ACTION_REFRESH"
        private const val ACTION_HEARTBEAT = "com.wassupluke.widgets.ACTION_HEARTBEAT"
        val REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30)

        /** Rebuilds RemoteViews for every widget instance from cached data + settings. */
        fun renderWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context)
            if (ids.isEmpty()) {
                Debug.log("weather render skipped — no widgets placed")
                return
            }

            val cached = WeatherCache.load(context)
            val unit = Settings.resolvedUnit(context)

            // All values below are identical across widget instances — compute once.
            val temperatureText = cached?.let { formatTemperature(it.temperature, unit) }
                ?: context.getString(R.string.placeholder_temperature)
            val conditionText = cached?.let { WeatherCode.describe(it.weatherCode) }
                ?: context.getString(R.string.weather_unavailable)
            val conditionVisibility =
                if (Settings.showCondition(context)) View.VISIBLE else View.GONE
            // Tap launches the chosen app, or refreshes if none is set / it's uninstalled.
            val onClick = Settings.launchPackage(context)?.let { launchIntent(context, it) }
                ?: broadcastIntent(context, ACTION_REFRESH, 0)
            val color = WidgetStyle.textColor(context)
            val temperatureSize = Settings.fontSize(context).toFloat()
            val conditionSize = temperatureSize * Settings.CONDITION_SIZE_RATIO
            val gravity = WidgetStyle.gravity(Settings.weatherTextAlign(context))

            Debug.log(
                "weather render ids=${ids.joinToString()} cached=${cached != null} " +
                    "text='$temperatureText' condition='$conditionText' " +
                    "tap=${Settings.launchPackage(context) ?: "refresh"}"
            )

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_weather)
                views.setTextViewText(R.id.temperature, temperatureText)
                views.setTextViewText(R.id.condition, conditionText)
                views.setViewVisibility(R.id.condition, conditionVisibility)
                views.setInt(R.id.widget_root, "setGravity", gravity)
                views.setTextColor(R.id.temperature, color)
                views.setTextColor(R.id.condition, color)
                views.setTextViewTextSize(R.id.temperature, TypedValue.COMPLEX_UNIT_SP, temperatureSize)
                views.setTextViewTextSize(R.id.condition, TypedValue.COMPLEX_UNIT_SP, conditionSize)
                // Click target is the text only, not the whole (mostly transparent) cell.
                views.setOnClickPendingIntent(R.id.temperature, onClick)
                views.setOnClickPendingIntent(R.id.condition, onClick)
                manager.updateAppWidget(id, views)
            }
        }

        private fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, WeatherWidgetProvider::class.java)
            )

        /** True if at least one weather widget is placed. */
        fun hasWidgets(context: Context): Boolean = widgetIds(context).isNotEmpty()

        /**
         * Push the next heartbeat out a full interval because something else just refreshed
         * (a delivered location update). Re-arming reuses the same PendingIntent, so this moves
         * the pending alarm rather than adding one. No-op with no widget placed — nothing to feed.
         */
        fun deferHeartbeat(context: Context) {
            if (hasWidgets(context)) scheduleHeartbeat(context)
        }

        /**
         * Arm a single ~30-min wake-up. `setAndAllowWhileIdle` fires through Doze without the
         * exact-alarm permission; it is one-shot, so each fire re-arms the next (see onReceive).
         * Re-arming reuses the same PendingIntent, so there is only ever one pending alarm.
         */
        private fun scheduleHeartbeat(context: Context) {
            val at = System.currentTimeMillis() + REFRESH_INTERVAL_MS
            Debug.log("heartbeat armed for ${Date(at)}")
            alarmManager(context).setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at, heartbeatIntent(context)
            )
        }

        private fun cancelHeartbeat(context: Context) {
            Debug.log("heartbeat cancelled")
            alarmManager(context).cancel(heartbeatIntent(context))
        }

        private fun alarmManager(context: Context): AlarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        private fun heartbeatIntent(context: Context): PendingIntent =
            broadcastIntent(context, ACTION_HEARTBEAT, 4)

        /** Explicit broadcast PendingIntent targeting this provider (refresh / heartbeat). */
        private fun broadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** PendingIntent that launches [packageName], or null if it can't be resolved. */
        private fun launchIntent(context: Context, packageName: String): PendingIntent? {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: run {
                    Debug.warn("tap target $packageName has no launch intent — falling back")
                    return null
                }
            return PendingIntent.getActivity(
                context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
