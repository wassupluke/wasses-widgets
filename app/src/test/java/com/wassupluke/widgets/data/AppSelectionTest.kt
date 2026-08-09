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
