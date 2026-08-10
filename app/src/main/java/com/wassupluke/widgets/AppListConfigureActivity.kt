package com.wassupluke.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButtonToggleGroup
import com.wassupluke.widgets.data.AppDisplayMode
import com.wassupluke.widgets.data.AppEntry
import com.wassupluke.widgets.data.AppListLayout
import com.wassupluke.widgets.data.AppSelection
import java.util.Locale

/** Per-instance configuration for an app-list widget (also reconfigurable later). */
class AppListConfigureActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val checks = mutableListOf<Pair<String, CheckBox>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default to CANCELED so backing out doesn't place a broken widget.
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_app_list_configure)

        val configRoot = findViewById<View>(R.id.config_root)
        val basePadding = intArrayOf(
            configRoot.paddingLeft, configRoot.paddingTop,
            configRoot.paddingRight, configRoot.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(configRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                basePadding[0] + bars.left, basePadding[1] + bars.top,
                basePadding[2] + bars.right, basePadding[3] + bars.bottom
            )
            insets
        }

        val layoutGroup = findViewById<MaterialButtonToggleGroup>(R.id.layout_group)
        layoutGroup.check(
            if (AppListStore.layout(this, appWidgetId) == AppListLayout.HORIZONTAL) {
                R.id.layout_horizontal
            } else {
                R.id.layout_vertical
            }
        )

        val modeGroup = findViewById<MaterialButtonToggleGroup>(R.id.mode_group)
        modeGroup.check(
            when (AppListStore.displayMode(this, appWidgetId)) {
                AppDisplayMode.ICON_ONLY -> R.id.mode_icon
                AppDisplayMode.LABEL_ONLY -> R.id.mode_label
                AppDisplayMode.ICON_AND_LABEL -> R.id.mode_both
            }
        )

        val alignGroup = findViewById<MaterialButtonToggleGroup>(R.id.align_group)
        alignGroup.check(
            when (AppListStore.textAlign(this, appWidgetId)) {
                Settings.TextAlign.START -> R.id.align_start
                Settings.TextAlign.CENTER -> R.id.align_center
                Settings.TextAlign.END -> R.id.align_end
            }
        )

        val fontValue = findViewById<TextView>(R.id.font_size_value)
        val fontBar = findViewById<SeekBar>(R.id.font_size_seekbar)
        fontBar.min = Settings.FONT_SIZE_MIN
        fontBar.max = Settings.FONT_SIZE_MAX
        fontBar.progress = AppListStore.fontSize(this, appWidgetId)
        fontValue.text = getString(R.string.font_size_value, fontBar.progress)
        fontBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                fontValue.text = getString(R.string.font_size_value, progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        val container = findViewById<LinearLayout>(R.id.app_checklist)
        val selected = AppListStore.selectedPackages(this, appWidgetId)
        for (app in loadApps()) {
            val cb = CheckBox(this).apply {
                text = app.label
                isChecked = app.packageName in selected
            }
            container.addView(cb)
            checks.add(app.packageName to cb)
        }

        findViewById<Button>(R.id.select_all).setOnClickListener {
            checks.forEach { it.second.isChecked = true }
        }
        findViewById<Button>(R.id.deselect_all).setOnClickListener {
            checks.forEach { it.second.isChecked = false }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.app_list_configure, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_add_widget) {
            save()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .distinctBy { it.activityInfo.packageName }
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        return AppSelection.sortAlphabetically(apps, Locale.getDefault())
    }

    private fun save() {
        val layoutGroup = findViewById<MaterialButtonToggleGroup>(R.id.layout_group)
        val modeGroup = findViewById<MaterialButtonToggleGroup>(R.id.mode_group)
        val alignGroup = findViewById<MaterialButtonToggleGroup>(R.id.align_group)
        val fontBar = findViewById<SeekBar>(R.id.font_size_seekbar)

        val selected = checks.filter { it.second.isChecked }.map { it.first }.toSet()
        AppListStore.setSelectedPackages(this, appWidgetId, selected)
        AppListStore.setLayout(
            this, appWidgetId,
            if (layoutGroup.checkedButtonId == R.id.layout_horizontal) {
                AppListLayout.HORIZONTAL
            } else {
                AppListLayout.VERTICAL
            }
        )
        AppListStore.setDisplayMode(
            this, appWidgetId,
            when (modeGroup.checkedButtonId) {
                R.id.mode_icon -> AppDisplayMode.ICON_ONLY
                R.id.mode_label -> AppDisplayMode.LABEL_ONLY
                else -> AppDisplayMode.ICON_AND_LABEL
            }
        )
        AppListStore.setTextAlign(
            this, appWidgetId,
            when (alignGroup.checkedButtonId) {
                R.id.align_center -> Settings.TextAlign.CENTER
                R.id.align_end -> Settings.TextAlign.END
                else -> Settings.TextAlign.START
            }
        )
        AppListStore.setFontSize(this, appWidgetId, fontBar.progress)

        val mgr = AppWidgetManager.getInstance(this)
        AppListWidgetProvider.renderAppListWidgets(this, mgr, intArrayOf(appWidgetId))
        mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.app_grid)

        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )
        finish()
    }
}
