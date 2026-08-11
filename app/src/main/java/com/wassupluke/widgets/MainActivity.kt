package com.wassupluke.widgets

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Debug.log("COARSE_LOCATION permission granted=$granted")
            updatePermissionStatus()
            if (granted) {
                // "Allow all the time" must be requested separately, after foreground is granted.
                runOnboardingPrompts()
                // The refresh heartbeat is armed by the widget's lifecycle; just fetch now.
                RefreshWeatherWorker.enqueueOnce(this)
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge is on by default at targetSdk 36; pad the content so it
        // clears the status and navigation bars.
        val base = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }

        val toggle = findViewById<MaterialSwitch>(R.id.switch_show_condition)
        toggle.isChecked = Settings.showCondition(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            Settings.setShowCondition(this, checked)
            WeatherWidgetProvider.renderWidgets(this)
        }

        val unitGroup = findViewById<RadioGroup>(R.id.unit_group)
        unitGroup.check(
            when (Settings.unitMode(this)) {
                Settings.UnitMode.AUTO -> R.id.unit_auto
                Settings.UnitMode.CELSIUS -> R.id.unit_celsius
                Settings.UnitMode.FAHRENHEIT -> R.id.unit_fahrenheit
            }
        )
        unitGroup.setOnCheckedChangeListener { _, checkedId ->
            Settings.setUnitMode(
                this,
                when (checkedId) {
                    R.id.unit_celsius -> Settings.UnitMode.CELSIUS
                    R.id.unit_fahrenheit -> Settings.UnitMode.FAHRENHEIT
                    else -> Settings.UnitMode.AUTO
                }
            )
            // Cached temperature is in Celsius, so a unit change re-renders instantly
            // with no refetch.
            WeatherWidgetProvider.renderWidgets(this)
        }

        val dynamicColor = findViewById<MaterialSwitch>(R.id.switch_dynamic_color)
        dynamicColor.isChecked = Settings.dynamicColor(this)
        dynamicColor.setOnCheckedChangeListener { _, checked ->
            Settings.setDynamicColor(this, checked)
            renderAllWidgets()
        }

        val fontSizeValue = findViewById<TextView>(R.id.font_size_value)
        val fontSizeBar = findViewById<SeekBar>(R.id.font_size_seekbar)
        fontSizeBar.min = Settings.FONT_SIZE_MIN
        fontSizeBar.max = Settings.FONT_SIZE_MAX
        fontSizeBar.progress = Settings.fontSize(this)
        fontSizeValue.text = getString(R.string.font_size_value, Settings.fontSize(this))
        fontSizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                fontSizeValue.text = getString(R.string.font_size_value, progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            // Persist + re-render once on release, not on every drag tick.
            override fun onStopTrackingTouch(bar: SeekBar) {
                Settings.setFontSize(this@MainActivity, bar.progress)
                renderAllWidgets()
            }
        })

        bindAlignGroup(
            groupId = R.id.align_group,
            startId = R.id.align_start,
            centerId = R.id.align_center,
            endId = R.id.align_end,
            current = { Settings.weatherTextAlign(this) },
            set = { Settings.setWeatherTextAlign(this, it) },
            render = { WeatherWidgetProvider.renderWidgets(this) },
        )
        bindAlignGroup(
            groupId = R.id.alarm_align_group,
            startId = R.id.alarm_align_start,
            centerId = R.id.alarm_align_center,
            endId = R.id.alarm_align_end,
            current = { Settings.alarmTextAlign(this) },
            set = { Settings.setAlarmTextAlign(this, it) },
            render = { AlarmWidgetProvider.renderAlarmWidgets(this) },
        )

        bindTapButton(
            buttonId = R.id.tap_action_button,
            defaultLabelRes = R.string.tap_action_refresh,
            titleRes = R.string.settings_tap_action,
            currentLabel = { Settings.launchLabel(this) },
            clear = { Settings.clearLaunchPackage(this) },
            set = { pkg, label -> Settings.setLaunchPackage(this, pkg, label) },
            render = { WeatherWidgetProvider.renderWidgets(this) },
        )
        bindTapButton(
            buttonId = R.id.alarm_tap_action_button,
            defaultLabelRes = R.string.alarm_tap_default,
            titleRes = R.string.settings_alarm_tap_action,
            currentLabel = { Settings.alarmLaunchLabel(this) },
            clear = { Settings.clearAlarmLaunchPackage(this) },
            set = { pkg, label -> Settings.setAlarmLaunchPackage(this, pkg, label) },
            render = { AlarmWidgetProvider.renderAlarmWidgets(this) },
        )

        // Read the installed manifest versionName (not BuildConfig) so it reflects the dev
        // variant's per-variant versionName override, e.g. "v2.0.5-dev-67e1446".
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version_info).text =
            getString(R.string.settings_version, versionName)

        if (!hasPermission()) {
            requestPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            runOnboardingPrompts()
        }
    }

    /**
     * First-run heads-up about the two settings that keep the widget updating in the background,
     * shown once after foreground location is granted. The background-location dialog (when
     * applicable) chains into the background-data dialog on dismiss so they never overlap; if the
     * location dialog is skipped, the data dialog is still considered.
     */
    private fun runOnboardingPrompts() {
        if (shouldShowBackgroundLocationDialog()) {
            showBackgroundLocationDialog(onDismiss = ::maybePromptBackgroundData)
        } else {
            maybePromptBackgroundData()
        }
    }

    /**
     * The "Allow all the time" prompt applies only on API 29+ (pre-29 it's covered by the
     * foreground grant), only after foreground is granted, only if background isn't already
     * granted, and at most once — then left to system Settings so we never nag.
     */
    private fun shouldShowBackgroundLocationDialog(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!hasPermission()) return false
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return !granted && !Settings.backgroundLocationAsked(this)
    }

    private fun showBackgroundLocationDialog(onDismiss: () -> Unit) {
        Settings.setBackgroundLocationAsked(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.background_location_title)
            .setMessage(R.string.background_location_message)
            .setPositiveButton(R.string.background_location_open_settings) { _, _ ->
                startActivity(
                    Intent(
                        SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            .setNegativeButton(R.string.background_location_dismiss, null)
            // Covers positive, negative, back press, and outside tap — so the data prompt always
            // follows once the location dialog is gone.
            .setOnDismissListener { onDismiss() }
            .show()
    }

    /**
     * Prompt once that "Background data" must be allowed or background refreshes are blocked on
     * metered networks. Informational — the OS setting is the fix; asked at most once.
     */
    private fun maybePromptBackgroundData() {
        if (Settings.backgroundDataAsked(this)) return
        Settings.setBackgroundDataAsked(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.background_data_title)
            .setMessage(R.string.background_data_message)
            .setPositiveButton(R.string.background_location_open_settings) { _, _ ->
                openBackgroundDataSettings()
            }
            .setNegativeButton(R.string.background_location_dismiss, null)
            .show()
    }

    /**
     * Open the app's unrestricted / background-data screen directly, falling back to the generic
     * app-details screen on OEMs where the dedicated action doesn't resolve.
     */
    private fun openBackgroundDataSettings() {
        val uri = Uri.fromParts("package", packageName, null)
        val ignore = Intent(
            SystemSettings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, uri
        )
        val target = if (ignore.resolveActivity(packageManager) != null) {
            ignore
        } else {
            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        }
        // resolveActivity should have vetted the target, but OEM quirks can still leave a
        // stale/broken entry; fall back to the app-details screen, then give up quietly.
        try {
            startActivity(target)
        } catch (e: ActivityNotFoundException) {
            Debug.warn("Background-data settings activity not found", e)
            val details = Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
            if (target.action != details.action) {
                try {
                    startActivity(details)
                } catch (e: ActivityNotFoundException) {
                    Debug.warn("App-details settings activity not found", e)
                }
            }
        }
    }

    /** Re-render both widgets — used by the appearance settings they share. */
    private fun renderAllWidgets() {
        WeatherWidgetProvider.renderWidgets(this)
        AlarmWidgetProvider.renderAlarmWidgets(this)
    }

    /** Binds an alignment toggle group to a per-widget-family alignment setting. */
    private fun bindAlignGroup(
        groupId: Int,
        startId: Int,
        centerId: Int,
        endId: Int,
        current: () -> Settings.TextAlign,
        set: (Settings.TextAlign) -> Unit,
        render: () -> Unit,
    ) {
        val group = findViewById<MaterialButtonToggleGroup>(groupId)
        group.check(
            when (current()) {
                Settings.TextAlign.START -> startId
                Settings.TextAlign.CENTER -> centerId
                Settings.TextAlign.END -> endId
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            set(
                when (checkedId) {
                    centerId -> Settings.TextAlign.CENTER
                    endId -> Settings.TextAlign.END
                    else -> Settings.TextAlign.START
                }
            )
            render()
        }
    }

    /** Binds a tap-action button: shows its current target and opens the app picker. */
    private fun bindTapButton(
        buttonId: Int,
        defaultLabelRes: Int,
        titleRes: Int,
        currentLabel: () -> String?,
        clear: () -> Unit,
        set: (packageName: String, label: String) -> Unit,
        render: () -> Unit,
    ) {
        val button = findViewById<Button>(buttonId)
        fun labelText() = currentLabel()?.let { getString(R.string.tap_action_launch, it) }
            ?: getString(defaultLabelRes)
        button.text = labelText()
        button.setOnClickListener {
            showAppPicker(titleRes, defaultLabelRes) { pkg, label ->
                if (pkg == null) clear() else set(pkg, label!!)
                render()
                button.text = labelText()
            }
        }
    }

    /**
     * Shows an app chooser whose first item is the "default" action (index 0 -> null
     * package), followed by all launchable apps. [onPick] receives the package + label,
     * or (null, null) for the default.
     */
    private fun showAppPicker(
        titleRes: Int,
        defaultLabelRes: Int,
        onPick: (packageName: String?, label: String?) -> Unit
    ) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }

        val items = (listOf(getString(defaultLabelRes)) + apps.map { it.second })
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(items) { _, which ->
                if (which == 0) onPick(null, null)
                else apps[which - 1].let { onPick(it.first, it.second) }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        val granted = hasPermission()
        Debug.log("MainActivity onResume, location permission=$granted")
        updatePermissionStatus()
        // Foreground fetch: refreshes now and seeds the last-known location that background
        // widget refreshes reuse. Also (re)register background location updates so the location
        // cache stays warm between app opens.
        if (granted) {
            RefreshWeatherWorker.enqueueOnce(this)
            // Only listen for pushed fixes while a widget is actually consuming them. onDisabled
            // fires only on the last-widget-removed transition, so a user who opens the app but
            // never places a widget would otherwise keep a 30-min location registration alive
            // forever, draining battery for nothing.
            if (WeatherWidgetProvider.hasWidgets(this)) {
                BackgroundLocationUpdates.register(this)
            } else {
                BackgroundLocationUpdates.unregister(this)
            }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True when "Allow all the time" is held (implied by the foreground grant pre-API 29). */
    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasPermission()
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** False only when Data Saver explicitly restricts this app's metered background data. */
    private fun isBackgroundDataAllowed(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.restrictBackgroundStatus !=
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    /** Refresh the required + optional permission rows with a check/cross per permission. */
    private fun updatePermissionStatus() {
        setPermissionRow(R.id.perm_location, R.string.permission_location, hasPermission())
        setPermissionRow(
            R.id.perm_background_location,
            R.string.permission_background_location,
            hasBackgroundLocation(),
        )
        setPermissionRow(
            R.id.perm_background_data,
            R.string.permission_background_data,
            isBackgroundDataAllowed(),
        )
    }

    private fun setPermissionRow(viewId: Int, nameRes: Int, granted: Boolean) {
        // ✔️ heavy check (granted) vs. ❌ cross mark (not granted).
        val status = if (granted) "✔️" else "❌"
        findViewById<TextView>(viewId).text =
            getString(R.string.permission_row, getString(nameRes), status)
    }
}
