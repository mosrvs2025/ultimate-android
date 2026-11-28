package app.ultimate.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the launcher's web search goes. */
class WebSearchTest {
    @Test fun theQueryIsEscapedSoNothingInItCanCutTheSearchShort() {
        assertEquals("https://www.google.com/search?q=cat+videos", SearchEngine.Google.url("  cat videos "))
        assertEquals("https://duckduckgo.com/?q=a%26b%3Dc%23d", SearchEngine.DuckDuckGo.url("a&b=c#d"))
        assertEquals("https://www.bing.com/search?q=caf%C3%A9", SearchEngine.Bing.url("caf\u00e9"))
        assertTrue(SearchEngine.Brave.url("x").startsWith("https://search.brave.com/search?q="))
    }

    @Test fun everyEngineHasAName() { assertTrue(SearchEngine.entries.all { it.label.isNotBlank() }) }

    @Test fun theEngineIsStoredAndTheDefaultIsGoogle() {
        assertEquals(SearchEngine.Google, LauncherSettings().searchEngine)
        val s = LauncherSettings(searchEngine = SearchEngine.DuckDuckGo)
        val stored = SettingsCodec.extras(s)
        assertEquals(SearchEngine.DuckDuckGo, SettingsCodec.withExtras(LauncherSettings()) { stored[it] }.searchEngine)
        assertEquals(SearchEngine.Google, SettingsCodec.withExtras(LauncherSettings()) { null }.searchEngine)
        assertEquals("a name from a later version keeps the default", SearchEngine.Google, SettingsCodec.withExtras(LauncherSettings()) { if (it == "searchEngine") "Quant" else null }.searchEngine)
    }
}
