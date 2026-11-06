package app.ultimate

import app.ultimate.air.WatchMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchMessagesTest {
    @Test fun gesturesAndButtonsAreUnderstood() {
        assertEquals(WatchMessages.Message(false, "flick_left"), WatchMessages.parse("gesture:flick_left"))
        assertEquals(WatchMessages.Message(true, "playpause"), WatchMessages.parse("button:playpause"))
        assertEquals("Flick left", WatchMessages.label(WatchMessages.parse("gesture:flick_left")!!))
    }

    @Test fun unknownMessagesAreIgnored() {
        listOf("", "gesture", "gesture:wave_hello", "button:format_phone", "button:none", "other:next").forEach { assertNull(it, WatchMessages.parse(it)) }
    }

    @Test fun everyGestureHasADefaultThatIsARealAction() {
        WatchMessages.GESTURES.forEach { (k, _) ->
            val a = WatchMessages.DEFAULTS[k]
            assertEquals(k, true, app.ultimate.air.AirActions.CHOICES.any { it.first == a })
        }
    }
}
