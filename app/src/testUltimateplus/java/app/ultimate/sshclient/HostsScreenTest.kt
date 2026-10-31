package app.ultimate.sshclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.ultimate.UltimateApp
import app.ultimate.core.ToolLaunch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Hosts screen, drawn in a simulated Android with real saved data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class HostsScreenTest {
    @get:Rule val compose = createComposeRule()

    @After fun restoreSecurityProviders() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", ignoreCase = true) }.forEach { java.security.Security.removeProvider(it.name) }
    }

    private fun tool(): HostsTool = ApplicationProvider.getApplicationContext<UltimateApp>().tools.filterIsInstance<HostsTool>().single()

    @Test fun savedHostsAreListedUnderTheirGroups() {
        val t = tool()
        t.vault.saveHost(Host("a", "acme-api-dev", "10.0.0.1", user = "stan", group = "AWS", tags = listOf("api")))
        t.vault.saveHost(Host("b", "naspi", "192.168.2.178", user = "pi"))
        compose.setContent { MaterialTheme { t.Screen({}, ToolLaunch()) } }
        compose.onNodeWithText("AWS").assertIsDisplayed()
        compose.onNodeWithText("acme-api-dev").assertIsDisplayed()
        compose.onNodeWithText("naspi").assertIsDisplayed()
        compose.onNodeWithText("pi@192.168.2.178", substring = true).assertIsDisplayed()
    }

    @Test fun typingAnAddressOffersToConnectWithoutSaving() {
        val t = tool()
        compose.setContent { MaterialTheme { t.Screen({}, ToolLaunch()) } }
        compose.onNodeWithText("user@host to connect right now").performTextInput("me@box.local:2222")
        compose.onNodeWithText("Connect to me@box.local:2222").assertIsDisplayed()
    }

    @Test fun theOtherTabsDraw() {
        val t = tool()
        compose.setContent { MaterialTheme { t.Screen({}, ToolLaunch()) } }
        compose.onNodeWithText("Snippets").performClick()
        compose.onNodeWithText("Biggest files here").assertIsDisplayed()
        compose.onNodeWithText("Keys").performClick()
        compose.onNodeWithText("No keys yet").assertIsDisplayed()
    }
}
