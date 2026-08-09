# App List Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third µWidgets widget family — a per-widget-configurable grid of installed apps that launch on tap.

**Architecture:** A RemoteViews **collection** widget: a `GridView` in `widget_app_list.xml` is backed by a `RemoteViewsService`/`RemoteViewsFactory` that reads the widget's config, queries `PackageManager` for the selected launchable apps, sorts them alphabetically, and renders one item each. Each placed widget is configured by an `AppListConfigureActivity` (app selection with select-all/deselect-all, orientation, display mode, per-widget font size + alignment). A pure `data/` layer holds the testable sorting/encoding logic; the Android glue mirrors the existing weather/alarm providers.

**Tech Stack:** Kotlin, Android AppWidget + RemoteViews collections, SharedPreferences, Material3 components, JUnit4 (plain-JVM unit tests).

## Global Constraints

- Package `com.wassupluke.widgets`; minSdk 26; compile/target 36; JDK 17 source/target.
- **The `data/` layer must stay free of Android-only APIs** (no `android.*`) — its unit tests run on the plain JVM. Uri/PackageManager/RemoteViews usage lives only in the glue layer.
- Widget layouts use **RemoteViews-supported views only** — `LinearLayout`/`TextView`/`ImageView`/`GridView`. No `ConstraintLayout`. Hack font via `android:fontFamily="@font/hack"`; text color/size set per-render.
- Color + font family are **global** (`WidgetStyle.textColor`, `@font/hack`); **font size and text alignment are per-widget** (stored in `AppListStore`).
- Reuse existing enums/constants: `Settings.TextAlign`, `Settings.FONT_SIZE_MIN/MAX/DEFAULT`. Enum prefs stored by name, unknown → default.
- New display strings go in `res/values/strings.xml`. New persisted state goes through a SharedPreferences wrapper (`AppListStore`), never raw key access elsewhere.
- Package visibility already covered by the manifest `<queries>` MAIN/LAUNCHER element — do **not** add `QUERY_ALL_PACKAGES`.
- Lint has `abortOnError = true` — the final build must pass `./gradlew :app:lintDebug`.

---

### Task 1: Data layer — models, enums, pure selection helpers

**Files:**
- Create: `app/src/main/java/com/wassupluke/widgets/data/AppEntry.kt`
- Create: `app/src/main/java/com/wassupluke/widgets/data/AppSelection.kt`
- Test: `app/src/test/java/com/wassupluke/widgets/data/AppSelectionTest.kt`

**Interfaces:**
- Produces:
  - `data class AppEntry(val packageName: String, val label: String)`
  - `enum class AppListLayout { VERTICAL, HORIZONTAL }` with `companion fun fromNameOrDefault(name: String?): AppListLayout` (default `VERTICAL`)
  - `enum class AppDisplayMode { ICON_ONLY, LABEL_ONLY, ICON_AND_LABEL }` with `companion fun fromNameOrDefault(name: String?): AppDisplayMode` (default `ICON_AND_LABEL`)
  - `object AppSelection` with `sortAlphabetically(entries: List<AppEntry>, locale: Locale): List<AppEntry>`, `encodeSelection(packages: Set<String>): String`, `decodeSelection(raw: String): Set<String>`, `resolveSelected(stored: Set<String>, installed: Set<String>): Set<String>`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/wassupluke/widgets/data/AppSelectionTest.kt`:

```kotlin
package com.wassupluke.widgets.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppSelectionTest {
    private val locale = Locale.US

    @Test fun sortsCaseInsensitivelyByLabel() {
        val input = listOf(
            AppEntry("com.z", "Zulu"),
            AppEntry("com.a", "alpha"),
            AppEntry("com.b", "Bravo"),
        )
        val out = AppSelection.sortAlphabetically(input, locale).map { it.label }
        assertEquals(listOf("alpha", "Bravo", "Zulu"), out)
    }

    @Test fun encodeDecodeRoundTrip() {
        val set = setOf("com.b", "com.a")
        assertEquals(set, AppSelection.decodeSelection(AppSelection.encodeSelection(set)))
    }

    @Test fun decodeBlankIsEmpty() {
        assertEquals(emptySet<String>(), AppSelection.decodeSelection(""))
    }

    @Test fun encodeSkipsBlankEntries() {
        assertEquals(setOf("com.a"), AppSelection.decodeSelection(AppSelection.encodeSelection(setOf("com.a", " "))))
    }

    @Test fun resolveDropsUninstalled() {
        assertEquals(
            setOf("com.a"),
            AppSelection.resolveSelected(setOf("com.a", "com.b"), setOf("com.a", "com.x")),
        )
    }

    @Test fun layoutUnknownDefaultsToVertical() {
        assertEquals(AppListLayout.VERTICAL, AppListLayout.fromNameOrDefault("bogus"))
        assertEquals(AppListLayout.VERTICAL, AppListLayout.fromNameOrDefault(null))
        assertEquals(AppListLayout.HORIZONTAL, AppListLayout.fromNameOrDefault("HORIZONTAL"))
    }

    @Test fun displayModeUnknownDefaultsToBoth() {
        assertEquals(AppDisplayMode.ICON_AND_LABEL, AppDisplayMode.fromNameOrDefault(null))
        assertEquals(AppDisplayMode.ICON_AND_LABEL, AppDisplayMode.fromNameOrDefault("bogus"))
        assertEquals(AppDisplayMode.ICON_ONLY, AppDisplayMode.fromNameOrDefault("ICON_ONLY"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wassupluke.widgets.data.AppSelectionTest"`
Expected: FAIL — `AppEntry` / `AppSelection` / enums unresolved (compile error).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/wassupluke/widgets/data/AppEntry.kt`:

```kotlin
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
```

Create `app/src/main/java/com/wassupluke/widgets/data/AppSelection.kt`:

```kotlin
package com.wassupluke.widgets.data

import java.text.Collator
import java.util.Locale

/** Pure, JVM-testable helpers for the app-list widget. No Android APIs. */
object AppSelection {

    /** Locale-aware, case-insensitive-primary alphabetical sort by label. */
    fun sortAlphabetically(entries: List<AppEntry>, locale: Locale): List<AppEntry> {
        val collator = Collator.getInstance(locale)
        return entries.sortedWith(Comparator { a, b -> collator.compare(a.label, b.label) })
    }

    /** Newline-delimited storage of a package set; blanks dropped, order stable. */
    fun encodeSelection(packages: Set<String>): String =
        packages.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString("\n")

    fun decodeSelection(raw: String): Set<String> =
        raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Keep only still-installed packages, so uninstalled selections silently disappear. */
    fun resolveSelected(stored: Set<String>, installed: Set<String>): Set<String> =
        stored.intersect(installed)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wassupluke.widgets.data.AppSelectionTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wassupluke/widgets/data/AppEntry.kt \
        app/src/main/java/com/wassupluke/widgets/data/AppSelection.kt \
        app/src/test/java/com/wassupluke/widgets/data/AppSelectionTest.kt
git commit -m "feat: app-list data layer (models, enums, selection helpers)"
```

---

### Task 2: `AppListStore` — per-widget SharedPreferences wrapper

Pure Android glue (Context-bound), not JVM-unit-tested, matching the repo convention that the glue layer isn't unit-tested. Gate is a clean Kotlin compile.

**Files:**
- Create: `app/src/main/java/com/wassupluke/widgets/AppListStore.kt`

**Interfaces:**
- Consumes: `AppListLayout`, `AppDisplayMode`, `AppSelection` (Task 1); `Settings.TextAlign`, `Settings.FONT_SIZE_*` (existing).
- Produces: `object AppListStore` with, for a given `context` and `id: Int`:
  - `selectedPackages(context, id): Set<String>` / `setSelectedPackages(context, id, Set<String>)`
  - `layout(context, id): AppListLayout` / `setLayout(context, id, AppListLayout)`
  - `displayMode(context, id): AppDisplayMode` / `setDisplayMode(context, id, AppDisplayMode)`
  - `fontSize(context, id): Int` / `setFontSize(context, id, Int)`
  - `textAlign(context, id): Settings.TextAlign` / `setTextAlign(context, id, Settings.TextAlign)`
  - `clear(context, id)`

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/wassupluke/widgets/AppListStore.kt`:

```kotlin
package com.wassupluke.widgets

import android.content.Context
import com.wassupluke.widgets.data.AppDisplayMode
import com.wassupluke.widgets.data.AppListLayout
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

    fun layout(context: Context, id: Int): AppListLayout =
        AppListLayout.fromNameOrDefault(prefs(context).getString(key(id, "layout"), null))

    fun setLayout(context: Context, id: Int, value: AppListLayout) {
        prefs(context).edit().putString(key(id, "layout"), value.name).apply()
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
            .remove(key(id, "layout"))
            .remove(key(id, "display"))
            .remove(key(id, "font"))
            .remove(key(id, "align"))
            .apply()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/wassupluke/widgets/AppListStore.kt
git commit -m "feat: per-widget AppListStore preferences"
```

---

### Task 3: Resources — strings, picker icon, layouts, widget-info

Adds every resource the glue references. Gate is a full resource+code compile (`assembleDebug`); the `configure` attribute in the widget-info is a plain component-name string (not validated at build time), so it can name the not-yet-created activity.

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/drawable/ic_app_grid.xml`
- Create: `app/src/main/res/layout/widget_app_list.xml`
- Create: `app/src/main/res/layout/widget_app_list_item.xml`
- Create: `app/src/main/res/layout/activity_app_list_configure.xml`
- Create: `app/src/main/res/xml/app_list_widget_info.xml`

- [ ] **Step 1: Add strings**

In `app/src/main/res/values/strings.xml`, add before `</resources>`:

```xml
    <string name="app_list_widget_label">Apps</string>
    <string name="app_list_empty">No apps selected</string>
    <string name="app_list_icon_desc">App icon</string>
    <string name="app_list_layout_title">Layout</string>
    <string name="app_list_layout_vertical">Vertical</string>
    <string name="app_list_layout_horizontal">Horizontal</string>
    <string name="app_list_display_title">Show</string>
    <string name="app_list_display_icon">Icon</string>
    <string name="app_list_display_label">Label</string>
    <string name="app_list_display_both">Both</string>
    <string name="app_list_select_apps">Apps to show</string>
    <string name="app_list_select_all">Select all</string>
    <string name="app_list_deselect_all">Deselect all</string>
    <string name="app_list_save">Add widget</string>
```

- [ ] **Step 2: Add the widget-picker icon**

Create `app/src/main/res/drawable/ic_app_grid.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M4,4h6v6H4z M14,4h6v6h-6z M4,14h6v6H4z M14,14h6v6h-6z" />
</vector>
```

- [ ] **Step 3: Add the widget layout**

Create `app/src/main/res/layout/widget_app_list.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/app_list_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/transparent"
    android:orientation="vertical"
    android:padding="4dp">

    <GridView
        android:id="@+id/app_grid"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:numColumns="1"
        android:columnWidth="72dp"
        android:stretchMode="columnWidth"
        android:gravity="fill"
        android:horizontalSpacing="4dp"
        android:verticalSpacing="4dp" />

    <TextView
        android:id="@+id/app_list_empty"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:fontFamily="@font/hack"
        android:textColor="@color/widget_text"
        android:textSize="16sp"
        android:text="@string/app_list_empty"
        android:shadowColor="#80000000"
        android:shadowDx="0"
        android:shadowDy="1"
        android:shadowRadius="3"
        android:visibility="gone" />
</LinearLayout>
```

- [ ] **Step 4: Add the item layout**

Create `app/src/main/res/layout/widget_app_list_item.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/app_list_item_root"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="4dp">

    <ImageView
        android:id="@+id/app_icon"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_gravity="center"
        android:contentDescription="@string/app_list_icon_desc"
        tools:src="@drawable/ic_app_grid" />

    <TextView
        android:id="@+id/app_label"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:maxLines="1"
        android:ellipsize="end"
        android:fontFamily="@font/hack"
        android:textColor="@color/widget_text"
        android:textSize="14sp"
        android:shadowColor="#80000000"
        android:shadowDx="0"
        android:shadowDy="1"
        android:shadowRadius="3"
        tools:text="Camera" />
</LinearLayout>
```

- [ ] **Step 5: Add the configuration-screen layout**

Create `app/src/main/res/layout/activity_app_list_configure.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/app_list_layout_title"
            android:textSize="16sp"
            android:paddingBottom="8dp" />

        <com.google.android.material.button.MaterialButtonToggleGroup
            android:id="@+id/layout_group"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:singleSelection="true"
            app:selectionRequired="true">

            <Button android:id="@+id/layout_vertical"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/app_list_layout_vertical" />

            <Button android:id="@+id/layout_horizontal"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/app_list_layout_horizontal" />
        </com.google.android.material.button.MaterialButtonToggleGroup>

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/app_list_display_title"
            android:textSize="16sp"
            android:layout_marginTop="24dp"
            android:paddingBottom="8dp" />

        <com.google.android.material.button.MaterialButtonToggleGroup
            android:id="@+id/mode_group"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:singleSelection="true"
            app:selectionRequired="true">

            <Button android:id="@+id/mode_icon"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/app_list_display_icon" />

            <Button android:id="@+id/mode_label"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/app_list_display_label" />

            <Button android:id="@+id/mode_both"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/app_list_display_both" />
        </com.google.android.material.button.MaterialButtonToggleGroup>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="24dp">

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/settings_font_size"
                android:textSize="16sp" />

            <TextView
                android:id="@+id/font_size_value"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="16sp" />
        </LinearLayout>

        <SeekBar
            android:id="@+id/font_size_seekbar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:min="12"
            android:max="64" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/settings_text_align"
            android:textSize="16sp"
            android:layout_marginTop="24dp"
            android:paddingBottom="8dp" />

        <com.google.android.material.button.MaterialButtonToggleGroup
            android:id="@+id/align_group"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:singleSelection="true"
            app:selectionRequired="true">

            <Button android:id="@+id/align_start"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/align_left" />

            <Button android:id="@+id/align_center"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/align_center" />

            <Button android:id="@+id/align_end"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/align_right" />
        </com.google.android.material.button.MaterialButtonToggleGroup>

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/app_list_select_apps"
            android:textSize="16sp"
            android:layout_marginTop="24dp"
            android:paddingBottom="8dp" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <Button android:id="@+id/select_all"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/app_list_select_all" />

            <Button android:id="@+id/deselect_all"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="@string/app_list_deselect_all" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/app_checklist"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:layout_marginTop="8dp" />

        <Button
            android:id="@+id/save"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:text="@string/app_list_save" />
    </LinearLayout>
</ScrollView>
```

- [ ] **Step 6: Add the widget-info**

Create `app/src/main/res/xml/app_list_widget_info.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:updatePeriodMillis="0"
    android:initialLayout="@layout/widget_app_list"
    android:previewLayout="@layout/widget_app_list"
    android:configure="com.wassupluke.widgets.AppListConfigureActivity"
    android:widgetFeatures="reconfigurable"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen" />
```

- [ ] **Step 7: Verify resources compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (new resources compile; not yet wired into the manifest).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/drawable/ic_app_grid.xml \
        app/src/main/res/layout/widget_app_list.xml \
        app/src/main/res/layout/widget_app_list_item.xml \
        app/src/main/res/layout/activity_app_list_configure.xml \
        app/src/main/res/xml/app_list_widget_info.xml
git commit -m "feat: app-list widget resources (layouts, strings, widget-info)"
```

---

### Task 4: Provider + collection adapter (RemoteViewsService/Factory)

The provider and the factory are mutually referential (the render sets the grid's adapter to the service; the factory's item click uses the provider's fill-in extra), so they land together.

**Files:**
- Create: `app/src/main/java/com/wassupluke/widgets/AppListWidgetProvider.kt`
- Create: `app/src/main/java/com/wassupluke/widgets/AppListRemoteViewsService.kt`

**Interfaces:**
- Consumes: `AppListStore` (Task 2); `AppListLayout`, `AppDisplayMode`, `AppEntry`, `AppSelection` (Task 1); `WidgetStyle.textColor` (existing); resources `R.layout.widget_app_list`, `R.layout.widget_app_list_item`, `R.id.app_grid`, `R.id.app_list_empty`, `R.id.app_list_item_root`, `R.id.app_icon`, `R.id.app_label` (Task 3).
- Produces:
  - `class AppListWidgetProvider : AppWidgetProvider` with `companion object { const val ACTION_LAUNCH_APP; const val EXTRA_PACKAGE; fun renderAppListWidgets(context, mgr: AppWidgetManager, ids: IntArray) }`
  - `class AppListRemoteViewsService : RemoteViewsService`

- [ ] **Step 1: Write the provider**

Create `app/src/main/java/com/wassupluke/widgets/AppListWidgetProvider.kt`:

```kotlin
package com.wassupluke.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.GridView
import android.widget.RemoteViews
import com.wassupluke.widgets.data.AppDisplayMode
import com.wassupluke.widgets.data.AppListLayout

/** Home-screen grid of user-selected apps. Configured per instance; taps launch the app. */
class AppListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        Debug.log("applist onUpdate ids=${ids.joinToString()}")
        renderAppListWidgets(context, mgr, ids)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { AppListStore.clear(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_LAUNCH_APP -> launchApp(context, intent.getStringExtra(EXTRA_PACKAGE))
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_CHANGED -> notifyData(context)
        }
    }

    private fun launchApp(context: Context, packageName: String?) {
        if (packageName.isNullOrEmpty()) return
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch == null) {
            Debug.log("applist launch: no launch intent for $packageName")
            return
        }
        context.startActivity(launch)
    }

    private fun notifyData(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, AppListWidgetProvider::class.java))
        if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.app_grid)
    }

    companion object {
        const val ACTION_LAUNCH_APP = "com.wassupluke.widgets.action.LAUNCH_APP"
        const val EXTRA_PACKAGE = "com.wassupluke.widgets.extra.PACKAGE"

        /** Rebuild the RemoteViews for each widget and (re)load its data. */
        fun renderAppListWidgets(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_app_list)

                // Unique data URI per id so each widget gets its own factory instance.
                val serviceIntent = Intent(context, AppListRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                views.setRemoteAdapter(R.id.app_grid, serviceIntent)
                views.setEmptyView(R.id.app_grid, R.id.app_list_empty)

                // Icon-only grids reflow in either orientation; a labelled vertical list is one column.
                val layout = AppListStore.layout(context, id)
                val mode = AppListStore.displayMode(context, id)
                val columns =
                    if (layout == AppListLayout.HORIZONTAL || mode == AppDisplayMode.ICON_ONLY) {
                        GridView.AUTO_FIT
                    } else {
                        1
                    }
                views.setInt(R.id.app_grid, "setNumColumns", columns)

                // Mutable template completed per-item by setOnClickFillInIntent (carries the package).
                val template = PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, AppListWidgetProvider::class.java).setAction(ACTION_LAUNCH_APP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                views.setPendingIntentTemplate(R.id.app_grid, template)

                mgr.updateAppWidget(id, views)
            }
            if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.app_grid)
        }
    }
}
```

- [ ] **Step 2: Write the RemoteViewsService + factory**

Create `app/src/main/java/com/wassupluke/widgets/AppListRemoteViewsService.kt`:

```kotlin
package com.wassupluke.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.graphics.drawable.toBitmap
import com.wassupluke.widgets.data.AppDisplayMode
import com.wassupluke.widgets.data.AppEntry
import com.wassupluke.widgets.data.AppSelection
import java.util.Locale

/** Supplies the app-list grid's item views for one widget instance. */
class AppListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return AppListFactory(applicationContext, id)
    }
}

private class AppListFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    /** Icon size as a multiple of the label's font size, so bigger font → bigger icons. */
    private val iconSizeRatio = 2.0f

    private var entries: List<AppEntry> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() { entries = emptyList() }
    override fun getCount(): Int = entries.size
    override fun getViewTypeCount(): Int = 1
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getItemId(position: Int): Long =
        entries.getOrNull(position)?.packageName?.hashCode()?.toLong() ?: position.toLong()

    override fun onDataSetChanged() {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = pm.queryIntentActivities(query, 0)
            .distinctBy { it.activityInfo.packageName }
        val installedPackages = installed.map { it.activityInfo.packageName }.toSet()
        val selected = AppSelection.resolveSelected(
            AppListStore.selectedPackages(context, appWidgetId), installedPackages
        )
        val chosen = installed
            .filter { it.activityInfo.packageName in selected }
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        entries = AppSelection.sortAlphabetically(chosen, Locale.getDefault())
    }

    override fun getViewAt(position: Int): RemoteViews {
        val entry = entries[position]
        val item = RemoteViews(context.packageName, R.layout.widget_app_list_item)

        val mode = AppListStore.displayMode(context, appWidgetId)
        val fontSp = AppListStore.fontSize(context, appWidgetId).toFloat()
        val color = WidgetStyle.textColor(context)

        val showLabel = mode != AppDisplayMode.ICON_ONLY
        item.setViewVisibility(R.id.app_label, if (showLabel) View.VISIBLE else View.GONE)
        if (showLabel) {
            item.setTextViewText(R.id.app_label, entry.label)
            item.setTextColor(R.id.app_label, color)
            item.setTextViewTextSize(R.id.app_label, TypedValue.COMPLEX_UNIT_SP, fontSp)
        }

        val showIcon = mode != AppDisplayMode.LABEL_ONLY
        item.setViewVisibility(R.id.app_icon, if (showIcon) View.VISIBLE else View.GONE)
        if (showIcon) {
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, fontSp * iconSizeRatio, context.resources.displayMetrics
            ).toInt().coerceAtLeast(1)
            val bitmap = runCatching {
                pmIcon(entry.packageName, px)
            }.getOrNull()
            item.setImageViewBitmap(R.id.app_icon, bitmap)
        }

        item.setOnClickFillInIntent(
            R.id.app_list_item_root,
            Intent().putExtra(AppListWidgetProvider.EXTRA_PACKAGE, entry.packageName)
        )
        return item
    }

    private fun pmIcon(packageName: String, sizePx: Int) =
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/wassupluke/widgets/AppListWidgetProvider.kt \
        app/src/main/java/com/wassupluke/widgets/AppListRemoteViewsService.kt
git commit -m "feat: app-list widget provider and collection adapter"
```

---

### Task 5: Configure activity + manifest wiring + build/lint/manual verification

Adds the configuration screen and registers all three components. This is the task that produces a placeable, working widget.

**Files:**
- Create: `app/src/main/java/com/wassupluke/widgets/AppListConfigureActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AppListStore` (Task 2); `AppListLayout`, `AppDisplayMode`, `AppEntry`, `AppSelection` (Task 1); `AppListWidgetProvider.renderAppListWidgets` (Task 4); `Settings.TextAlign`, `Settings.FONT_SIZE_*` (existing); the configure layout ids (Task 3).

- [ ] **Step 1: Write the configure activity**

Create `app/src/main/java/com/wassupluke/widgets/AppListConfigureActivity.kt`:

```kotlin
package com.wassupluke.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
        findViewById<Button>(R.id.save).setOnClickListener {
            save(layoutGroup, modeGroup, alignGroup, fontBar)
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

    private fun save(
        layoutGroup: MaterialButtonToggleGroup,
        modeGroup: MaterialButtonToggleGroup,
        alignGroup: MaterialButtonToggleGroup,
        fontBar: SeekBar,
    ) {
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
```

- [ ] **Step 2: Register the components in the manifest**

In `app/src/main/AndroidManifest.xml`, add inside `<application>` (after the existing `AlarmWidgetProvider` receiver, before the `LocationUpdateReceiver`):

```xml
        <receiver
            android:name=".AppListWidgetProvider"
            android:exported="true"
            android:icon="@drawable/ic_app_grid"
            android:label="@string/app_list_widget_label">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.PACKAGE_ADDED" />
                <action android:name="android.intent.action.PACKAGE_REMOVED" />
                <action android:name="android.intent.action.PACKAGE_CHANGED" />
                <data android:scheme="package" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/app_list_widget_info" />
        </receiver>

        <service
            android:name=".AppListRemoteViewsService"
            android:exported="false"
            android:permission="android.permission.BIND_REMOTEVIEWS" />

        <activity
            android:name=".AppListConfigureActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>
```

- [ ] **Step 3: Build, run the full unit suite, and lint**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL; all unit tests pass (existing 29 + 6 new); lint reports no errors.

- [ ] **Step 4: Manual on-device verification**

With a device/emulator connected:

```bash
./gradlew :app:installDebug
```

Verify:
1. Long-press home screen → widget picker shows **µWidgets · Apps** with the grid icon.
2. Placing it opens the configuration screen. Toggle vertical/horizontal, icon/label/both, adjust font size + alignment, tick a few apps, tap **Add widget**.
3. The widget shows the chosen apps, sorted A–Z, styled with the shared color/Hack font at the chosen size/alignment. Tapping an app launches it.
4. Switch display mode to **Icon** and layout to **Horizontal** (reconfigure via long-press → the configure screen reopens); confirm the icon grid reflows to columns and grows with a larger font size. Confirm **Label** mode hides icons and shows a text column.
5. **Select all** / **Deselect all** update every checkbox; deselecting all shows the "No apps selected" empty view.
6. Remove the widget; confirm no crash. (`AppListStore.clear` runs on delete.)

Note: `columnWidth` for the reflowing grid is a static `72dp` in `widget_app_list.xml`. If very large font sizes make icon columns feel cramped, that is the value to revisit (out of scope for this task).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wassupluke/widgets/AppListConfigureActivity.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: app-list configure activity and manifest wiring"
```

---

## Self-Review

**Spec coverage:**
- FOSS app-list widget on home screen → Tasks 3–5 (widget family, picker entry). ✓
- Configurable horizontal/vertical (reflowing grid, `AUTO_FIT` vs single column; icon-only reflows either way) → Task 4 render logic. ✓
- User selects which apps show → Task 5 configure activity checklist. ✓
- Select all / deselect all + per-app checkboxes → Task 5. ✓
- Per-widget config → `AppListStore` (Task 2) keyed by id; configure activity (Task 5); `reconfigurable` widget-info (Task 3). ✓
- Item content icon/label/both, default both → `AppDisplayMode` (Task 1), factory visibility (Task 4). ✓
- Font size drives icon size → factory `iconSizeRatio * fontSp` (Task 4). ✓
- Alphabetical order → `AppSelection.sortAlphabetically` (Task 1), used in factory + configure. ✓
- Tap launches app → template + fill-in + `ACTION_LAUNCH_APP` trampoline (Task 4). ✓
- Global color/font, per-widget size/align → `WidgetStyle.textColor` + `AppListStore` (Tasks 2, 4). ✓
- Uninstalled apps drop out → `resolveSelected` + package broadcasts (Tasks 1, 4). ✓
- `onDeleted` cleans prefs → Task 4 provider. ✓
- Pure data layer JVM-tested; glue not unit-tested → Tasks 1 (tests) vs 2/4/5 (build gates). ✓

**Placeholder scan:** No TBD/TODO; every code step contains full content. ✓

**Type consistency:** `renderAppListWidgets(context, mgr, ids)`, `AppListStore` method signatures, `AppListWidgetProvider.EXTRA_PACKAGE`, enum `fromNameOrDefault`, and resource ids (`app_grid`, `app_list_empty`, `app_list_item_root`, `app_icon`, `app_label`, configure-screen ids) are used identically across the tasks that define and consume them. ✓
