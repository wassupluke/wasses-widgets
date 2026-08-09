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
