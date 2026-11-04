package app.ultimate

import app.ultimate.home.QuickActions
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickActionsTest {
    @Test fun offersOnlyToolsThisEditionHasInAFixedOrder() {
        assertEquals(listOf("echo", "ruler", "recorder", "cleanup"), QuickActions.available(setOf("cleanup", "recorder", "ruler", "echo", "files")).map { it.toolId })
        assertEquals(listOf("cleanup"), QuickActions.available(setOf("cleanup", "squeeze")).map { it.toolId })
        assertEquals(2, QuickActions.available(setOf("echo", "ruler", "recorder"), max = 2).size)
    }
}
