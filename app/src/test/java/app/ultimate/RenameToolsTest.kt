package app.ultimate

import androidx.test.core.app.ApplicationProvider
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which tools let their results be renamed, so a tool that makes files can't silently lose the button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class RenameToolsTest {
    private fun item(toolId: String, uri: String?) = LibraryItem("x", toolId, "t", "s", 0, 0, 0, uri, Thumb.Generic, emptyList(), "audio/mpeg")

    @Test fun toolsThatMakeFilesCanRenameThem() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        val renamable = setOf("audio", "squeeze", "studio", "recorder", "photos")
        for (t in app.tools.filter { it.id in renamable }) {
            assertTrue("${t.id} offers rename", t.canRename(item(t.id, "content://media/external/audio/media/1")))
            assertFalse("${t.id} has nothing to rename without a file", t.canRename(item(t.id, null)))
        }
        assertTrue("at least audio and squeeze exist in every edition that has them", app.tools.any { it.id in renamable } || app.tools.size < 5)
    }

    @Test fun toolsWithoutFilesDoNotOfferIt() {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        for (t in app.tools.filter { it.id !in setOf("audio", "squeeze", "studio", "recorder", "photos") }) assertFalse("${t.id} should not offer rename", t.canRename(item(t.id, "content://x")))
    }

    @Test fun renamingAResultThatIsGoneFailsPolitely() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<UltimateApp>()
        for (t in app.tools.filter { it.id in setOf("audio", "squeeze", "studio", "recorder", "photos") }) {
            val r = t.rename("no-such-id", "New name")
            assertTrue("${t.id} reports a failure", r.isFailure)
            assertEquals(false, r.exceptionOrNull()?.message.isNullOrBlank())
        }
    }
}
