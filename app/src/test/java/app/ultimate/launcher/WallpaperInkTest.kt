package app.ultimate.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The wallpaper accent, which is Compose state (so it needs the Android runtime Robolectric provides). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WallpaperInkTest {
    private fun contrast(a: Color, b: Color): Double { val l1 = maxOf(a.luminance(), b.luminance()).toDouble(); val l2 = minOf(a.luminance(), b.luminance()).toDouble(); return (l1 + 0.05) / (l2 + 0.05) }

    @Test fun withNoWallpaperColoursTheAccentIsTheDefault() {
        WallpaperInk.set(emptyList())
        assertEquals(Accent.Aurora.a, Accent.Wallpaper.a); assertEquals(Accent.Aurora.b, Accent.Wallpaper.b)
    }

    @Test fun theWallpaperAccentFollowsTheWallpaperAndTheBackground() {
        WallpaperInk.backdropLight(false); WallpaperInk.set(listOf(0xFFD81B60.toInt()))
        val onDark = Accent.Wallpaper.a
        assertTrue(contrast(onDark, Color(0xFF1B212B)) >= 4.0)
        WallpaperInk.backdropLight(true)
        val onPaper = Accent.Wallpaper.a
        assertTrue("on a light look the colour is darker", onPaper.luminance() < onDark.luminance())
        assertTrue(contrast(onPaper, Color(0xFFF1EADB)) >= 4.0)
        WallpaperInk.backdropLight(false); WallpaperInk.set(emptyList())
    }

}
