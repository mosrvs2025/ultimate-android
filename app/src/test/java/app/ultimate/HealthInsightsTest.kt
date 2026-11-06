package app.ultimate

import app.ultimate.health.HealthDay
import app.ultimate.health.HealthInsights
import app.ultimate.health.PhoneDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HealthInsightsTest {
    private val rnd = Random(9)

    @Test fun correlationIsPearson() {
        assertEquals(1.0, HealthInsights.correlation(listOf(1.0 to 2.0, 2.0 to 4.0, 3.0 to 6.0))!!, 1e-9)
        assertEquals(-1.0, HealthInsights.correlation(listOf(1.0 to 3.0, 2.0 to 2.0, 3.0 to 1.0))!!, 1e-9)
        assertNull(HealthInsights.correlation(listOf(1.0 to 1.0, 1.0 to 2.0, 1.0 to 3.0)))
    }

    @Test fun findsLessSleepAfterLateNightPhoneUse() {
        // Each late-night minute on the phone costs about two minutes of sleep, with noise.
        val phone = (0 until 30L).map { d -> PhoneDay(d, 200, rnd.nextLong(0, 90)) }
        val health = phone.map { pd -> HealthDay(pd.day + 1, sleepMin = 480 - 2 * pd.lateMin + rnd.nextLong(-20, 20)) }
        val found = HealthInsights.find(health, phone)
        val sleep = found.first { it.title == "Late-night phone and sleep" }
        assertTrue(sleep.text, sleep.text.contains("less"))
        assertTrue(sleep.text.contains("not proof of cause"))
        assertEquals(30, sleep.days)
    }

    @Test fun showsNothingWithoutEnoughDaysOrAClearPattern() {
        val few = (0 until 5L).map { PhoneDay(it, 100, it * 10) }
        assertTrue(HealthInsights.find(few.map { HealthDay(it.day + 1, sleepMin = 400 - it.lateMin) }, few).isEmpty())
        val noise = (0 until 40L).map { PhoneDay(it, rnd.nextLong(50, 400), rnd.nextLong(0, 90)) }
        val noisy = noise.map { HealthDay(it.day + 1, sleepMin = rnd.nextLong(360, 540), steps = rnd.nextLong(2000, 12000)) }
        assertTrue(HealthInsights.find(noisy, noise).toString(), HealthInsights.find(noisy, noise).isEmpty())
    }

    @Test fun trendsCompareThisWeekWithTheLast() {
        val days = (0..13L).map { d -> HealthDay(d, steps = if (d >= 7) 9000 else 6000, sleepMin = 420) }
        val t = HealthInsights.trends(days, 13)
        assertEquals(1, t.size)
        assertTrue(t.first(), t.first().startsWith("Steps: 9,000 a day this week, up from 6,000"))
    }
}
