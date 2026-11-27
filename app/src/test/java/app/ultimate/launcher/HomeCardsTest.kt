package app.ultimate.launcher

import app.ultimate.health.HeartBrief
import app.ultimate.health.HeartWords
import app.ultimate.mesh.CamNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZoneId

/** The home cards: what they say, where a tap goes, and how the choice of cards is stored. */
class HomeCardsTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val min = 60_000L
    private val now = 1_800_000_000_000L // an arbitrary "now"

    @Test fun agesAreInPlainWords() {
        assertEquals("just now", HeartWords.ago(now - 30_000, now, utc))
        assertEquals("12 min ago", HeartWords.ago(now - 12 * min, now, utc))
        assertEquals("3 h ago", HeartWords.ago(now - 190 * min, now, utc))
        assertEquals("yesterday", HeartWords.ago(now - 26 * 60 * min, now, utc))
        assertEquals("3 days ago", HeartWords.ago(now - 3 * 24 * 60 * min - 5 * min, now, utc))
        assertEquals("a reading from the future is just now, not a negative number", "just now", HeartWords.ago(now + 5 * min, now, utc))
    }

    @Test fun aFreshReadingLeadsWithTheRestingRateAndRangeBeside() {
        val b = HeartBrief(bpm = 72, at = now - 12 * min, restingBpm = 58, restingAt = now - 5 * 60 * min, lowBpm = 51, highBpm = 133)
        assertEquals("72 bpm", HeartWords.headline(b, now))
        assertEquals("12 min ago · resting 58 · 51–133 today", HeartWords.detail(b, now))
    }

    @Test fun anOldReadingFallsBackToTheRestingRateAndSaysHowOldItIs() {
        val b = HeartBrief(bpm = 90, at = now - 30 * 60 * min, restingBpm = 57, restingAt = now - 26 * 60 * min)
        assertEquals("57 resting", HeartWords.headline(b, now))
        assertEquals("resting rate from yesterday", HeartWords.detail(b, now))
    }

    @Test fun withOnlyAnOldReadingItIsLabelledAsOld() {
        val b = HeartBrief(bpm = 88, at = now - 8 * 60 * min, restingBpm = null, restingAt = 0)
        assertEquals("88 bpm", HeartWords.headline(b, now))
        assertEquals("last reading 8 h ago", HeartWords.detail(b, now))
    }

    @Test fun noReadingsGivesNoHeadlineAndAFlatRangeIsNotShown() {
        assertNull(HeartWords.headline(HeartBrief(null, 0, null, 0), now))
        val flat = HeartBrief(70, now - min, null, 0, lowBpm = 70, highBpm = 70)
        assertEquals("a range of one number says nothing", "just now · ".dropLast(3), HeartWords.detail(flat, now))
    }

    private fun node(name: String, viewing: Boolean = false) = CamNodes.Node(name.lowercase(), name, viewing)

    @Test fun theCamerasLineNamesWhoIsSharing() {
        assertNull(HomeCards.cameraLine(emptyList()))
        assertEquals("Kitchen is sharing its camera", HomeCards.cameraLine(listOf(node("Kitchen"))))
        assertEquals("Kitchen and Garden are sharing cameras", HomeCards.cameraLine(listOf(node("Kitchen"), node("Garden"))))
        assertEquals("Kitchen, Garden and Hall are sharing cameras", HomeCards.cameraLine(listOf(node("Kitchen"), node("Garden"), node("Hall"))))
        assertEquals("Kitchen, Garden and 2 more are sharing cameras", HomeCards.cameraLine(listOf("Kitchen", "Garden", "Hall", "Shed").map { node(it) }))
        assertEquals("A device is sharing its camera", HomeCards.cameraLine(listOf(node(""))))
        assertEquals(1, HomeCards.watching(listOf(node("Kitchen", viewing = true), node("Garden"))))
    }

    @Test fun eachCardOpensItsOwnPage() {
        assertEquals("timeline" to "activity", HomeCards.target(HomeCard.Activity))
        assertEquals("health" to null, HomeCards.target(HomeCard.Heart))
        assertEquals("mesh" to null, HomeCards.target(HomeCard.Cameras))
        assertTrue(HomeCard.entries.all { it.label.isNotBlank() && it.sub.isNotBlank() })
    }

    @Test fun theChoiceOfCardsIsStoredAndNobodyHasAnyUntilTheySwitchThemOn() {
        assertEquals(emptySet<HomeCard>(), LauncherSettings().cards)
        val s = LauncherSettings(cards = setOf(HomeCard.Cameras, HomeCard.Activity))
        assertEquals("activity before cameras, always the same order", "Activity,Cameras", HomeCards.encode(s.cards))
        val stored = SettingsCodec.extras(s)
        assertEquals(s.cards, SettingsCodec.withExtras(LauncherSettings()) { stored[it] }.cards)
        assertEquals(emptySet<HomeCard>(), SettingsCodec.withExtras(LauncherSettings()) { null }.cards)
        assertEquals("unknown names from a later version are dropped, the rest kept", setOf(HomeCard.Heart), HomeCards.decode("Heart,Teleporter, "))
    }

    @Test fun anOldInstallsSettingsStillReadAndTheDefaultLookIsStillAurora() {
        assertEquals(Looks.aurora, Looks.matching(LauncherSettings()))
        assertEquals(emptySet<HomeCard>(), SettingsCodec.withExtras(LauncherSettings()) { null }.cards)
    }
}
