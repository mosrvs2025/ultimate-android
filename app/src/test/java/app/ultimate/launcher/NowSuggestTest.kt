package app.ultimate.launcher

import app.ultimate.context.Prediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowSuggestTest {
    private val labels = mapOf("spotify" to "Spotify", "maps" to "Maps", "gmail" to "Gmail", "strava" to "Strava")
    private fun ctx(moving: String? = null, recording: Boolean = false, headphones: Boolean = false, habits: List<Prediction> = emptyList(), canRecord: Boolean = true) =
        NowContext(moving, recording, headphones, "spotify", habits, labels.keys, canRecord)

    @Test fun aWalkTheWatchNoticedOffersToRecordIt() {
        val s = NowSuggest.suggest(ctx(moving = "walking"), labels::get)
        assertEquals(Now.Action("movement.record", "Record this walk", "your watch says you're walking"), s.first())
        assertEquals("Record this run", (NowSuggest.suggest(ctx(moving = "running"), labels::get).first() as Now.Action).label)
    }

    @Test fun aRecordingInProgressIsOfferedInsteadOfANewOne() {
        val s = NowSuggest.suggest(ctx(moving = "walking", recording = true), labels::get)
        assertEquals("movement.open", (s.first() as Now.Action).id)
        assertTrue(s.none { it is Now.Action && it.id == "movement.record" })
    }

    @Test fun headphonesOfferTheMusicAppAndHabitsFillTheRestWithoutRepeats() {
        val s = NowSuggest.suggest(ctx(headphones = true, habits = listOf(Prediction("spotify", 0.6, listOf("evening")), Prediction("maps", 0.3, listOf("morning")), Prediction("gmail", 0.2, emptyList()))), labels::get)
        assertEquals(listOf("Spotify", "Maps", "Gmail"), s.map { it.label })
        assertEquals("your headphones are in", s[0].because)
        assertEquals("you often open it morning", s[1].because)
    }

    @Test fun nothingWorthSayingMeansNothingIsShown() {
        assertTrue(NowSuggest.suggest(ctx(), labels::get).isEmpty())
        // An edition that cannot record (or no location permission) does not offer to.
        assertTrue(NowSuggest.suggest(ctx(moving = "walking", canRecord = false), labels::get).isEmpty())
        // Uninstalled apps are never offered.
        assertTrue(NowSuggest.suggest(ctx(habits = listOf(Prediction("gone", 0.9, emptyList()))), labels::get).isEmpty())
    }

    @Test fun neverMoreThanThree() {
        val s = NowSuggest.suggest(ctx(moving = "walking", headphones = true, habits = listOf(Prediction("maps", 0.5, emptyList()), Prediction("gmail", 0.4, emptyList()), Prediction("strava", 0.3, emptyList()))), labels::get)
        assertEquals(3, s.size)
    }
}
