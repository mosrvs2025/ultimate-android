package app.ultimate

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import app.ultimate.rules.Action
import app.ultimate.rules.ActionType
import app.ultimate.rules.Event
import app.ultimate.rules.LiveTriggers
import app.ultimate.rules.Rule
import app.ultimate.rules.RuleEngine
import app.ultimate.rules.Trigger
import app.ultimate.rules.TriggerType
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Runs the rules engine and the live watcher in a simulated Android, so a crash shows up here and not on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleEngineTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val audio get() = context.getSystemService(AudioManager::class.java)

    @Before fun startWorkManager() {
        // The real app starts WorkManager through its startup provider; the test app has no merged manifest, so use the test helper.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After fun restoreSecurityProviders() {
        java.security.Security.getProviders().filter { it.name.contains("Conscrypt", ignoreCase = true) }.forEach { java.security.Security.removeProvider(it.name) }
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!ok()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what" }; Thread.sleep(20) }
    }

    private fun rule(id: String, actions: List<Action>, undo: Boolean = true, enabled: Boolean = true, trigger: Trigger = Trigger(TriggerType.Charging)) =
        Rule(id, id, enabled, trigger, undo, actions)

    @Test fun volumeIsSetThenPutBack() {
        val engine = RuleEngine(context)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        engine.add(rule("car", listOf(Action(ActionType.Volume, value = 100))))
        engine.fire(Event.State(TriggerType.Charging, true))
        waitFor("volume to rise") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
        engine.fire(Event.State(TriggerType.Charging, false))
        waitFor("volume to come back") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 3 }
        waitFor("history") { engine.log.value.size == 2 }
        assertTrue(engine.log.value.first().result.startsWith("Put back"))
        assertTrue(engine.log.value.last().because.contains("plug"))
    }

    @Test fun aRuleWithoutUndoLeavesThingsAlone() {
        val engine = RuleEngine(context)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        engine.add(rule("loud", listOf(Action(ActionType.Volume, value = 100)), undo = false))
        engine.fire(Event.State(TriggerType.Charging, true))
        waitFor("volume to rise") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 2 }
        engine.fire(Event.State(TriggerType.Charging, false))
        Thread.sleep(200)
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 2)
    }

    @Test fun disabledRulesStayQuietAndRepeatsAreThrottled() {
        val engine = RuleEngine(context)
        engine.add(rule("off", listOf(Action(ActionType.Notify, "no")), enabled = false))
        engine.add(rule("on", listOf(Action(ActionType.Notify, "yes")), undo = false))
        repeat(5) { engine.fire(Event.State(TriggerType.Charging, true)) }
        waitFor("every record") { engine.log.value.size == 5 }
        assertEquals(listOf("on"), engine.log.value.map { it.ruleName }.distinct())
        assertEquals(1, engine.log.value.count { it.result.startsWith("Showed") })
        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }

    @Test fun missingAccessIsReportedNotCrashed() {
        val engine = RuleEngine(context)
        engine.add(rule("dnd", listOf(Action(ActionType.Dnd, value = 1)), undo = false))
        engine.fire(Event.State(TriggerType.Charging, true))
        waitFor("a run") { engine.log.value.isNotEmpty() }
        assertTrue(engine.log.value.first().result, engine.log.value.first().result.contains("Do Not Disturb access"))
    }

    @Test fun variablesReachTheText() {
        val engine = RuleEngine(context)
        engine.add(rule("say", listOf(Action(ActionType.Notify, "%app said %title")), undo = false, trigger = Trigger(TriggerType.Notification)))
        engine.fire(Event.Notification("com.x", "Chat", "Sam", "hi"))
        waitFor("a run") { engine.log.value.isNotEmpty() }
        assertEquals("Chat said Sam", shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single().extras.getCharSequence("android.text").toString())
    }

    @Test fun rulesSurviveARestartAndABrokenFileDoesNotCrash() {
        RuleEngine(context).add(rule("keep", listOf(Action(ActionType.Vibrate))))
        waitFor("save") { java.io.File(context.filesDir, "rules.json").let { it.exists() && it.length() > 10 } }
        assertEquals(listOf("keep"), RuleEngine(context).rules.value.map { it.id })
        java.io.File(context.filesDir, "rules.json").writeText("{ this is not json")
        assertEquals(emptyList<Rule>(), RuleEngine(context).rules.value)
    }

    @Test fun theWatcherReportsOnlyRealChanges() {
        val seen = ArrayList<Event>()
        val live = LiveTriggers(context) { synchronized(seen) { seen += it } }
        live.start()
        fun send(a: String, f: Intent.() -> Unit = {}) { context.sendBroadcast(Intent(a).apply(f)); shadowOf(android.os.Looper.getMainLooper()).idle() }
        send(Intent.ACTION_POWER_CONNECTED); send(Intent.ACTION_POWER_CONNECTED); send(Intent.ACTION_POWER_DISCONNECTED)
        send(Intent.ACTION_BATTERY_CHANGED) { putExtra("level", 30); putExtra("scale", 100) }
        send(Intent.ACTION_BATTERY_CHANGED) { putExtra("level", 14); putExtra("scale", 100) }
        live.stop()
        send(Intent.ACTION_POWER_CONNECTED)
        assertEquals(listOf<Event>(Event.State(TriggerType.Charging, true), Event.State(TriggerType.Charging, false), Event.Battery(30, 14)), synchronized(seen) { seen.toList() })
    }
}
