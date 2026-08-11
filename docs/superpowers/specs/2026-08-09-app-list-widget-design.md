# App List Widget — Design

**Date:** 2026-08-09
**Status:** Approved design, pending implementation plan
**Package:** `com.wassupluke.widgets`

## Summary

Add a third widget family to µWidgets: an **App List widget** that shows a
configurable grid of installed apps on the home screen. This is the FOSS
alternative µLauncher's docs ask for
(<https://launcher.jrpie.de/docs/examples/apps-on-home-screen/>), which today
only points at proprietary options (Launchy, KWGT).

Pattern: **read installed apps + per-widget config → render a scrollable
collection**. No network, no location, no refresh heartbeat. Unlike the weather
and alarm widgets (static `LinearLayout` RemoteViews), this widget uses a
RemoteViews **collection** — a `GridView` backed by a `RemoteViewsService` /
`RemoteViewsFactory` — and is **configured per placed widget instance** through a
configuration `Activity`.

## Decisions (from brainstorming)

- **Item content:** configurable per widget — icon only, label only, or both.
  Default is **icon + label**.
- **Config scope:** **per-widget-instance**, via a configuration `Activity`
  launched on placement (and later, since the widget is `reconfigurable`).
- **Orientation ("horizontal vs vertical"):** a `GridView` whose `numColumns`
  reflows.
  - `AUTO_FIT` (columns reflow to the widget's width) when `layout == HORIZONTAL`
    **or** `displayMode == ICON_ONLY` — so an icon-only grid grows/reflows as the
    widget changes shape or size in either orientation.
  - `1` (single-column vertical list) otherwise.
- **Tap:** tapping an entry **launches that app**.
- **App order:** **alphabetical** (locale-aware).
- **Styling:** color and font family are **global** (shared `WidgetStyle` /
  Hack font, same as the other widgets); **font size and text alignment are
  per-widget**. Font size also drives icon size (larger font → larger icons).

## Architecture

### Data layer (`data/`) — pure, JVM-unit-tested

Stays free of Android-only APIs so the existing plain-JVM unit tests apply
(mirrors `LocationCache.encode/decode` and `AlarmDay`).

- `AppEntry(packageName: String, label: String)` — model for a launchable app.
- `AppListLayout` enum: `VERTICAL` / `HORIZONTAL`. Stored by name; unknown →
  default `VERTICAL`.
- `AppDisplayMode` enum: `ICON_ONLY` / `LABEL_ONLY` / `ICON_AND_LABEL`. Stored by
  name; unknown → default **`ICON_AND_LABEL`**.
- Pure helpers (unit-tested):
  - `sortAlphabetically(entries: List<AppEntry>, locale: Locale): List<AppEntry>`
    — locale-aware via `java.text.Collator` (JVM-safe, no `android.icu`).
  - `encodeSelection(packages: Set<String>): String` /
    `decodeSelection(raw: String): Set<String>` — round-trip storage of the
    selected package set (newline-delimited; blank → empty set).
  - `resolveSelected(stored: Set<String>, installed: Set<String>): Set<String>`
    — intersection, so packages no longer installed silently drop out.

### Per-widget storage

- `AppListStore(context)` — a small SharedPreferences wrapper (like `Settings` /
  `WeatherCache`) keyed by `appWidgetId`:
  - `selectedPackages(id)` / `setSelectedPackages(id, set)`
  - `layout(id)` / `setLayout(id, AppListLayout)`
  - `displayMode(id)` / `setDisplayMode(id, AppDisplayMode)`
  - `fontSize(id)` / `setFontSize(id, sp)` — per-widget
  - `textAlign(id)` / `setTextAlign(id, TextAlign)` — per-widget (reuses existing
    `TextAlign` enum)
  - `clear(id)` — remove all keys for a deleted widget
  - Enum prefs go through the existing `enumPref` / `setEnumPref` idiom.
- Global color / dynamic-color / font family remain in `Settings` / `WidgetStyle`
  and are **not** duplicated here.

### Android glue (package root `com/wassupluke/widgets/`)

- **`AppListWidgetProvider : AppWidgetProvider`**
  - `renderAppListWidgets(context, appWidgetManager, ids)` — the single place
    that builds the app-list `RemoteViews`. For each id:
    - Builds a service intent (embedding `appWidgetId` + a unique `data` URI so
      each widget gets its own factory) and calls
      `setRemoteAdapter(R.id.app_grid, serviceIntent)`.
    - Sets `numColumns` via
      `setInt(R.id.app_grid, "setNumColumns", cols)` where `cols =
      (layout == HORIZONTAL || displayMode == ICON_ONLY) ? GridView.AUTO_FIT : 1`.
    - Sets the empty view: `setEmptyView(R.id.app_grid, R.id.app_list_empty)`
      ("No apps selected").
    - Sets a **PendingIntent template** for taps (see "Tap → launch").
  - `onUpdate` → `renderAppListWidgets`.
  - `onReceive` also handles `ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REMOVED` /
    `ACTION_PACKAGE_CHANGED` (manifest receiver with `<data
    android:scheme="package"/>`, which is exempt from the implicit-broadcast
    restrictions) → `notifyAppWidgetViewDataChanged(ids, R.id.app_grid)` so
    installed/uninstalled apps appear/disappear.
  - `onDeleted(ids)` → `AppListStore.clear(id)` for each.
  - **Tap → launch:** the template is an *explicit broadcast* to
    `AppListWidgetProvider` (`ACTION_LAUNCH_APP`); each item's fill-in intent
    supplies `EXTRA_PACKAGE`. `onReceive` resolves the package's launch intent
    (`packageManager.getLaunchIntentForPackage`) and `startActivity`s it (with
    `FLAG_ACTIVITY_NEW_TASK`). A generic activity template cannot become
    different app launches from a fill-in alone, so this trampoline is the clean
    route. If the package no longer resolves, the tap is a no-op (and the next
    package broadcast prunes it).

- **`AppListRemoteViewsService : RemoteViewsService`** +
  **`AppListRemoteViewsFactory : RemoteViewsFactory`** — the collection adapter.
  - `onDataSetChanged()` reads the widget's config from `AppListStore`, queries
    `PackageManager` for launchable apps (`queryIntentActivities` MAIN/LAUNCHER —
    already covered by the existing `<queries>` element), filters to
    `resolveSelected`, maps to `AppEntry`, and `sortAlphabetically`.
  - `getViewAt(pos)` returns an item `RemoteViews` (`widget_app_list_item.xml`):
    - Icon `ImageView` and label `TextView` visibility toggled per
      `displayMode`.
    - Label: color from `WidgetStyle.textColor(context)`; size from per-widget
      `fontSize` (`setTextViewTextSize`); alignment from per-widget `textAlign`.
      Hack font is static in the item layout.
    - Icon: the app's launcher icon (`activityInfo.loadIcon`) drawn to a
      **bounded bitmap scaled to a multiple of `fontSize`** (so larger font →
      larger icons, mirroring the alarm widget sizing its icon to the font), set
      via `setImageViewBitmap`, at **native color (no tint)**. Bounding the
      bitmap size matters — RemoteViews collections have a hard cumulative
      bitmap-memory limit.
    - Fill-in intent carries `EXTRA_PACKAGE`.
  - Standard factory contract: stable `getItemId`, `hasStableIds = true`,
    `getCount`, `getViewTypeCount = 1`, `getLoadingView = null`.

- **`AppListConfigureActivity : Activity`** — the configuration screen
  (`android.appwidget.action.APPWIDGET_CONFIGURE`; the provider is
  `reconfigurable` so it can be reopened via long-press later).
  - Defaults `RESULT_CANCELED` up front (so backing out does not place a widget).
  - Reads the incoming `appWidgetId`; pre-loads any existing config (reconfigure
    case).
  - UI (reusing MainActivity's control idioms inside a `ScrollView`):
    - Scrollable **checkbox list of all launchable apps** (alphabetical),
      pre-checked from stored selection.
    - **Select all / Deselect all** buttons.
    - Layout toggle (vertical / horizontal) — `MaterialButtonToggleGroup`.
    - Display-mode toggle (icon / label / both).
    - Font-size `SeekBar`.
    - Text-align `MaterialButtonToggleGroup`.
  - Save: writes per-widget prefs via `AppListStore`, calls
    `renderAppListWidgets` + `notifyAppWidgetViewDataChanged`, sets `RESULT_OK`
    with the `appWidgetId`, finishes.

### Manifest / resources

- `<receiver android:name=".AppListWidgetProvider">` — filters
  `APPWIDGET_UPDATE` plus `PACKAGE_ADDED` / `PACKAGE_REMOVED` / `PACKAGE_CHANGED`
  with `<data android:scheme="package"/>`; `meta-data` →
  `@xml/app_list_widget_info`.
- `<service android:name=".AppListRemoteViewsService"
  android:permission="android.permission.BIND_REMOTEVIEWS"
  android:exported="false" />`.
- `<activity android:name=".AppListConfigureActivity" android:exported="true">`
  with an `APPWIDGET_CONFIGURE` intent filter.
- The existing `<queries>` MAIN/LAUNCHER element already permits listing and
  launching installed apps — no new permission (no `QUERY_ALL_PACKAGES`).
- `res/xml/app_list_widget_info.xml` — points `configure` at
  `AppListConfigureActivity`, `widgetFeatures="reconfigurable"`, resizable
  (`horizontal|vertical`), `previewLayout`, `updatePeriodMillis="0"`.
- `res/layout/widget_app_list.xml` — root `LinearLayout` containing a `GridView`
  (`@id/app_grid`, `columnWidth` sized to accommodate an icon at the default
  font size — verify a runtime `setColumnWidth` is needed if it must track font
  size) and an empty `TextView` (`@id/app_list_empty`).
- `res/layout/widget_app_list_item.xml` — `LinearLayout` with an `ImageView`
  (`@id/app_icon`) and a `TextView` (`@id/app_label`, `fontFamily="@font/hack"`,
  baked shadow like the other widgets).
- New strings in `res/values/strings.xml` (widget label, "No apps selected",
  config-screen labels, select-all / deselect-all).
- Widget-picker icon: reuse or add a simple grid glyph drawable for the
  `<receiver android:icon>`.

## Testing (TDD)

JVM unit tests (plain JVM, no Robolectric) for the pure data layer:

- `sortAlphabetically` — case-insensitivity, locale ordering, ties, mixed
  scripts.
- `encodeSelection` / `decodeSelection` — round-trip; empty set; blank input.
- `resolveSelected` — drops uninstalled packages; empty stored/installed.
- Enum prefs — `AppListLayout` and `AppDisplayMode` parse by name; unknown value
  → default (`VERTICAL`, `ICON_AND_LABEL`).

Android glue (PackageManager access, `RemoteViewsFactory`, `Activity`,
manifest wiring) is exercised manually / not JVM-unit-tested, consistent with
the existing convention (glue layer is not unit-tested).

## Out of scope (YAGNI)

- Drag-to-reorder / custom ordering (alphabetical only).
- Per-widget color / dynamic-color override (color stays global).
- App icon theming / monochrome tinting.
- Folders, search, or app grouping.
- True horizontally-scrolling collections (not supported by RemoteViews;
  reflowing `AUTO_FIT` grid covers the "horizontal" intent).
