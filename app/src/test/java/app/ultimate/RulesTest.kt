package app.ultimate

import app.ultimate.rules.Action
import app.ultimate.rules.ActionType
import app.ultimate.rules.Describe
import app.ultimate.rules.Event
import app.ultimate.rules.RateLimiter
import app.ultimate.rules.Rule
import app.ultimate.rules.RuleJson
import app.ultimate.rules.RuleMatcher
import app.ultimate.rules.RuleParser
import app.ultimate.rules.Trigger
import app.ultimate.rules.TriggerType
import app.ultimate.rules.Vars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun rule(t: Trigger, undo: Boolean = false, enabled: Boolean = true) = Rule("r", "r", enabled, t, undo, listOf(Action(ActionType.Volume, value = 30)))

    @Test fun chargingRulesStartOnTheirSideAndUndoOnTheOther() {
        val r = rule(Trigger(TriggerType.Charging), undo = true)
        assertTrue(RuleMatcher.starts(r, Event.State(TriggerType.Charging, true)))
        assertFalse(RuleMatcher.starts(r, Event.State(TriggerType.Charging, false)))
        assertTrue(RuleMatcher.ends(r, Event.State(TriggerType.Charging, false)))
        assertFalse(RuleMatcher.ends(r, Event.State(TriggerType.Charging, true)))
        assertFalse(RuleMatcher.ends(r.copy(undoWhenEnds = false), Event.State(TriggerType.Charging, false)))
        assertFalse(RuleMatcher.starts(r.copy(enabled = false), Event.State(TriggerType.Charging, true)))
        assertFalse(RuleMatcher.starts(r, Event.State(TriggerType.Wifi, true)))
    }

    @Test fun unplugRulesStartOnTheOffSide() {
        val r = rule(Trigger(TriggerType.Charging, on = false), undo = true)
        assertTrue(RuleMatcher.starts(r, Event.State(TriggerType.Charging, false)))
        assertTrue(RuleMatcher.ends(r, Event.State(TriggerType.Charging, true)))
    }

    @Test fun batteryFiresOnlyWhenItCrossesTheLevel() {
        val r = rule(Trigger(TriggerType.BatteryBelow, percent = 20))
        assertTrue(RuleMatcher.starts(r, Event.Battery(21, 20 - 1)))
        assertFalse(RuleMatcher.starts(r, Event.Battery(19, 18)))
        assertFalse(RuleMatcher.starts(r, Event.Battery(30, 25)))
        assertTrue(RuleMatcher.starts(r, Event.Battery(20, 19)))
    }

    @Test fun timeRulesHonourTheirDays() {
        val weekdays = rule(Trigger(TriggerType.AtTime, minutes = 22 * 60, days = setOf(2, 3, 4, 5, 6)))
        assertTrue(RuleMatcher.starts(weekdays, Event.Time(22 * 60, 3)))
        assertFalse(RuleMatcher.starts(weekdays, Event.Time(22 * 60, 1)))
        assertFalse(RuleMatcher.starts(weekdays, Event.Time(22 * 60 + 1, 3)))
        assertTrue(RuleMatcher.starts(rule(Trigger(TriggerType.AtTime, minutes = 60)), Event.Time(60, 7)))
    }

    @Test fun notificationsMatchByAppAndWords() {
        val r = rule(Trigger(TriggerType.Notification, app = "com.whatsapp", contains = "meeting"))
        assertTrue(RuleMatcher.starts(r, Event.Notification("com.whatsapp", "WhatsApp", "Sam", "Meeting at 3")))
        assertFalse(RuleMatcher.starts(r, Event.Notification("com.slack", "Slack", "Sam", "Meeting at 3")))
        assertFalse(RuleMatcher.starts(r, Event.Notification("com.whatsapp", "WhatsApp", "Sam", "Lunch?")))
        assertTrue(RuleMatcher.starts(rule(Trigger(TriggerType.Notification)), Event.Notification("x", "X", "", "")))
    }

    @Test fun copiesMatchOnTheirWords() {
        val links = rule(Trigger(TriggerType.Copied, contains = "http"))
        assertTrue(RuleMatcher.starts(links, Event.Copied("see https://example.com")))
        assertFalse(RuleMatcher.starts(links, Event.Copied("just words")))
    }

    @Test fun variablesFillAndUnknownOnesStay() {
        assertEquals("Battery 15% at 22:00", Vars.fill("Battery %battery% at %time", mapOf("battery" to "15", "time" to "22:00")).replace("15%", "15%"))
        assertEquals("%nope stays", Vars.fill("%nope stays", mapOf("text" to "x")))
        assertEquals("a: hello", Vars.fill("%app: %text", mapOf("app" to "a", "text" to "hello")))
    }

    @Test fun rateLimiterStopsLoopsButNotRealUse() {
        val l = RateLimiter(perRuleMs = 10_000, perMinute = 3)
        assertTrue(l.allow("a", 0))
        assertFalse(l.allow("a", 5_000))
        assertTrue(l.allow("a", 10_000))
        assertTrue(l.allow("b", 11_000))
        assertFalse(l.allow("c", 12_000)) // third in the minute already used
        assertTrue(l.allow("c", 70_000))
    }

    @Test fun rulesSurviveSavingAndOneBadEntryDoesNot() {
        val r = Rule("id1", "Night", true, Trigger(TriggerType.AtTime, minutes = 1320, days = setOf(2, 6), contains = "x"), false,
            listOf(Action(ActionType.Dnd, value = 1), Action(ActionType.Notify, "Hi %time")))
        assertEquals(r, RuleJson.fromJson(RuleJson.toJson(r)))
        assertNull(RuleJson.fromJson(org.json.JSONObject("""{"id":"x","t":{"type":"FromTheFuture"},"a":[]}""")))
    }

    @Test fun cardsReadAsSentences() {
        val r = Rule("i", "n", true, Trigger(TriggerType.Charging), true, listOf(Action(ActionType.Dnd, value = 1), Action(ActionType.Volume, value = 30)))
        assertEquals("When I plug in the charger: turn on Do Not Disturb, set the volume to 30%. Put back when it ends", Describe.rule(r))
        assertEquals("it is 22:00 on weekdays", Describe.trigger(Trigger(TriggerType.AtTime, minutes = 1320, days = setOf(2, 3, 4, 5, 6))))
    }

    // ---- plain English ----

    private fun ok(s: String, apps: Map<String, String> = emptyMap()): Rule {
        val r = RuleParser.parse(s, { apps[it] }, { "id" }) as RuleParser.Result.Ok
        assertTrue("left something out: ${r.notes}", r.notes.isEmpty() || s.contains("juggle"))
        return r.rule
    }

    @Test fun chargerSentenceBecomesARule() {
        val r = ok("When I plug in the charger, set the volume to 30% and turn on do not disturb")
        assertEquals(Trigger(TriggerType.Charging), r.trigger)
        assertEquals(listOf(Action(ActionType.Volume, value = 30), Action(ActionType.Dnd, value = 1)), r.actions)
        assertFalse(r.undoWhenEnds)
    }

    @Test fun whileMeansUndo() {
        val r = ok("While I'm plugged in turn on do not disturb")
        assertEquals(TriggerType.Charging, r.trigger.type)
        assertTrue(r.undoWhenEnds)
        assertTrue(ok("when I connect my headphones set the volume to 60 and put it back when I disconnect").undoWhenEnds)
    }

    @Test fun headphonesAreNotTheCharger() {
        assertEquals(TriggerType.Headphones, ok("when I plug in headphones, set the volume to 40").trigger.type)
        assertEquals(false, ok("when I unplug my headphones, silence the phone").trigger.on)
        assertEquals(false, ok("when I unplug the charger, notify me").trigger.on)
    }

    @Test fun batteryAndTimes() {
        assertEquals(Trigger(TriggerType.BatteryBelow, percent = 20), ok("when the battery drops below 20%, show me a notification").trigger)
        val night = ok("every night at 10:30pm silence the phone").trigger
        assertEquals(22 * 60 + 30, night.minutes)
        assertEquals(setOf(2, 3, 4, 5, 6), ok("on weekdays at 7am, open spotify", mapOf("spotify" to "com.spotify.music")).trigger.days)
        assertEquals(7 * 60, ok("in the morning turn off do not disturb").trigger.minutes)
    }

    @Test fun notificationsAndLinks() {
        val r = ok("when a notification arrives from whatsapp containing meeting, say you have a meeting", mapOf("whatsapp" to "com.whatsapp"))
        assertEquals(Trigger(TriggerType.Notification, app = "com.whatsapp", contains = "meeting"), r.trigger)
        assertEquals(Action(ActionType.Speak, "you have a meeting"), r.actions.single())
        assertEquals("http", ok("when I copy a link, show a notification saying \"Link copied\"").trigger.contains)
        assertEquals(Action(ActionType.Notify, "Link copied"), ok("when I copy a link, show a notification saying \"Link copied\"").actions.single())
    }

    @Test fun actionsReadNaturally() {
        assertEquals(Action(ActionType.Ringer, value = 0), ok("at 10pm silence the phone").actions.single())
        assertEquals(Action(ActionType.Ringer, value = 1), ok("at 10pm put the phone on vibrate").actions.single())
        assertEquals(Action(ActionType.Torch, value = 0), ok("at 10pm turn off the flashlight").actions.single())
        assertEquals(Action(ActionType.OpenLink, "https://example.com"), ok("at 8am open example.com").actions.single())
        assertEquals(Action(ActionType.Notify, "Reminder: take pills"), ok("at 8am remind me to take pills").actions.single())
        assertEquals(Action(ActionType.Volume, value = 0), ok("at 8am mute the volume").actions.single())
    }

    @Test fun saysSoWhenNotUnderstood() {
        assertTrue(RuleParser.parse("make me a sandwich") is RuleParser.Result.Unclear)
        assertTrue(RuleParser.parse("") is RuleParser.Result.Unclear)
        assertTrue(RuleParser.parse("when I plug in the charger") is RuleParser.Result.Unclear)
        val partial = RuleParser.parse("when I plug in the charger, juggle and turn on do not disturb") as RuleParser.Result.Ok
        assertEquals(1, partial.rule.actions.size)
        assertEquals(1, partial.notes.size)
        assertNotNull(partial.rule.name)
    }
}

class ScheduleTest {
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = java.util.Calendar.getInstance(utc).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test fun laterTodayOtherwiseTomorrow() {
        val now = at(2026, 10, 30, 9, 0) // a Friday
        assertEquals(at(2026, 10, 30, 22, 0), app.ultimate.rules.Schedule.next(now, 22 * 60, emptySet(), utc))
        assertEquals(at(2026, 10, 31, 7, 0), app.ultimate.rules.Schedule.next(now, 7 * 60, emptySet(), utc))
        assertEquals(at(2026, 10, 31, 9, 0), app.ultimate.rules.Schedule.next(now, 9 * 60, emptySet(), utc)) // exactly now means next time
    }

    @Test fun skipsToTheRightWeekday() {
        val fridayNight = at(2026, 10, 30, 23, 0)
        val weekdays = setOf(2, 3, 4, 5, 6)
        assertEquals(at(2026, 11, 2, 7, 0), app.ultimate.rules.Schedule.next(fridayNight, 7 * 60, weekdays, utc)) // Monday
        assertEquals(at(2026, 10, 31, 7, 0), app.ultimate.rules.Schedule.next(fridayNight, 7 * 60, setOf(1, 7), utc)) // Saturday
    }
}
