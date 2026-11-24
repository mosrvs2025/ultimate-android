package app.ultimate.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherHubTest {
    @Test fun newSettingsKeepTheLookTheLauncherAlwaysHad() {
        val d = LauncherSettings()
        assertEquals(IconSize.Medium, d.dockIcons); assertEquals(IconSize.Medium, d.drawerIcons)
        assertTrue(d.dockLabels && d.drawerShowIcons && d.searchShowsAll)
        assertEquals(GestureAction.Search, d.swipeUp); assertEquals(GestureAction.EditPanel, d.longPress)
        assertEquals(GestureAction.None, d.swipeDown); assertEquals(GestureAction.None, d.doubleTap)
        assertEquals(AppSort.AZ, d.drawerSort); assertEquals(false, d.searchWeb)
        assertEquals(1f, IconSize.Medium.scale, 0f)
    }

    @Test fun extrasSurviveBeingStoredAndReadBack() {
        val s = LauncherSettings(accent = Accent.Ocean, dockIcons = IconSize.Large, dockLabels = false, drawerIcons = IconSize.Small, drawerShowIcons = false,
            drawerSort = AppSort.Newest, swipeUp = GestureAction.Notifications, swipeDown = GestureAction.QuickSettings, doubleTap = GestureAction.Ultimate,
            longPress = GestureAction.None, searchShowsAll = false, searchWeb = true)
        val stored = SettingsCodec.extras(s)
        assertEquals(s, SettingsCodec.withExtras(LauncherSettings(accent = Accent.Ocean), stored::get))
    }

    @Test fun missingOrUnknownStoredValuesFallBackToTheDefault() {
        val base = LauncherSettings()
        assertEquals(base, SettingsCodec.withExtras(base) { null })   // nothing stored yet, e.g. updating from an older version
        val odd = mapOf("dockIcons" to "Huge", "dockLabels" to "maybe", "swipeUp" to "Teleport", "drawerSort" to "", "searchWeb" to "1")
        assertEquals(base, SettingsCodec.withExtras(base, odd::get))
    }

    @Test fun wallpaperBackdropIsSeeThroughAndTheOthersAreNot() {
        assertTrue(Backdrop.Wallpaper.colors.all { it.alpha < 1f })
        assertTrue(Backdrop.values().filter { it != Backdrop.Wallpaper }.all { b -> b.colors.all { it.alpha == 1f } })
        // A setting saved by this version that an older one has never heard of still decodes to something sensible.
        assertEquals(Backdrop.Aurora, SettingsCodec.decode(null, "Hologram", null, true, true, 5, true, null).backdrop)
        assertEquals(Backdrop.Wallpaper, SettingsCodec.decode(null, "Wallpaper", null, true, true, 5, true, null).backdrop)
    }

    private val apps = listOf(
        HomeApp("b", "Banking", installedAt = 300), HomeApp("c", "Camera", installedAt = 100), HomeApp("c2", "Calendar", installedAt = 300),
        HomeApp("c3", "Clock", installedAt = 0),
    )

    @Test fun appsAreOrderedAsChosenAndTiesFallBackToTheName() {
        assertEquals(listOf("Calendar", "Camera", "Clock"), LauncherMath.appsFor(apps, 'C').map { it.label })
        assertEquals(listOf("Clock", "Camera", "Calendar"), LauncherMath.appsFor(apps, 'C', AppSort.ZA).map { it.label })
        assertEquals(listOf("Calendar", "Camera", "Clock"), LauncherMath.appsFor(apps, 'C', AppSort.Newest).map { it.label })
        assertEquals(listOf("Banking", "Calendar"), LauncherMath.sorted(apps, AppSort.Newest).take(2).map { it.label })
        assertTrue(LauncherMath.sorted(emptyList(), AppSort.Newest).isEmpty())
    }

    @Test fun hubListsEveryPageWhenNothingIsTyped() {
        assertEquals(SettingsPage.entries.toList(), SettingsPage.matching(""))
        assertEquals(SettingsPage.entries.toList(), SettingsPage.matching("  "))
    }

    @Test fun hubSearchFindsPagesByTheirNamesAndKeywords() {
        assertEquals(listOf(SettingsPage.Wallpaper), SettingsPage.matching("wallp"))
        assertEquals(listOf(SettingsPage.Colours), SettingsPage.matching("Colour"))
        assertTrue(SettingsPage.Gestures in SettingsPage.matching("double tap"))
        assertTrue(SettingsPage.Dock in SettingsPage.matching("icons size"))
        assertTrue(SettingsPage.Drawer in SettingsPage.matching("hidden"))
        assertEquals(listOf(SettingsPage.Search), SettingsPage.matching("web"))
        assertTrue(SettingsPage.matching("zzzz").isEmpty())
    }

    @Test fun everyWordTypedMustMatch() {
        assertTrue(SettingsPage.matching("swipe wallpaper").isEmpty())
        assertEquals(listOf(SettingsPage.Gestures), SettingsPage.matching("swipe hold"))
    }
}
