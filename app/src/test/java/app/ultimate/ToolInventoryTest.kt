package app.ultimate

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Records which tools this edition really registers, so documentation states a measured number instead of a remembered
 * one. Writes build/tool-inventory-<flavor>.txt (one "id<TAB>title" per line) and fails if a tool id appears twice.
 * The full edition (ultimateplus) must keep at least [FULL_EDITION_MIN] tools: dropping below it means a tool went missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class ToolInventoryTest {
    @Test fun registeredToolsAreUniqueAndListed() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        val tools = app.tools
        val flavor = BuildConfig.FLAVOR
        File("build/tool-inventory-$flavor.txt").apply { parentFile?.mkdirs() }.writeText(tools.joinToString("\n") { "${it.id}\t${it.title}" } + "\n")
        println("TOOL-INVENTORY $flavor ${tools.size}")
        assertTrue("duplicate tool ids: " + tools.groupBy { it.id }.filter { it.value.size > 1 }.keys, tools.map { it.id }.toSet().size == tools.size)
        if (flavor == "ultimateplus") assertTrue("Ultimate+ registers only ${tools.size} tools", tools.size >= FULL_EDITION_MIN)
    }

    companion object { const val FULL_EDITION_MIN = 30 }
}
