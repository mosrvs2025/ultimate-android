package app.ultimate

import app.ultimate.health.HealthDay
import app.ultimate.health.HealthFill
import app.ultimate.health.NotificationFacts
import app.ultimate.health.NotificationFacts.Kind
import app.ultimate.home.Facts
import app.ultimate.home.FactsCache
import app.ultimate.timeline.Finding
import app.ultimate.timeline.Moment
import app.ultimate.timeline.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackFixesTest {
    private val r = NotificationFacts::read
    private val pp = "com.paypal.android.p2pmobile"

    @Test fun aPurchaseThatDidNotGoThroughIsNeverSpending() {
        for (text in listOf(
            "A PayPal Debit Card purchase of \$412.50 didn't go through",
            "Your PayPal Debit Card purchase of \$412.50 did not go through.",
            "Your payment of \$88.00 wasn't approved",
            "Your payment of \$88.00 was not processed",
            "Purchase of \$99.99 at Target was declined",
            "We couldn't process your \$250.00 payment",
            "Payment of \$75.00 was cancelled",
            "A \$300.00 purchase was blocked",
            "Unsuccessful payment of \$50.00",
            "Action required: your \$120.00 payment hit a problem",
            "Your \$60.00 payment was reversed",
        )) assertEquals(text, Kind.Declined, r(pp, "PayPal", "PayPal", text, 0)?.kind)
    }

    @Test fun realPurchasesStillCount() {
        assertEquals(Kind.Spent, r(pp, "PayPal", "PayPal", "A PayPal Debit Card purchase of \$12.40 at Starbucks", 0)!!.kind)
        assertEquals(Kind.Spent, r("com.chase.sig.android", "Chase", "Card transaction", "You made a \$1,240.50 purchase at Best Buy", 0)!!.kind)
        assertEquals(Kind.Spent, r("com.venmo", "Venmo", "You paid Sam", "You paid Sam \$20.00", 0)!!.kind)
    }

    @Test fun securityOffersAndBalancesAreNotPayments() {
        assertNull(r("com.chase.sig.android", "Chase", "Security alert", "Verify a \$500.00 charge on your card", 0))
        assertNull(r("com.chase.sig.android", "Chase", "Sign-in", "Your code is 123456 for \$0.00", 0))
        assertNull(r(pp, "PayPal", "Offer", "Earn \$25.00 cash back on your next payment", 0))
        assertNull(r("com.chase.sig.android", "Chase", "Balance", "Your available balance is \$5,315.83 after payment", 0))
    }

    @Test fun theSameAnnouncementTwiceCountsOnce() {
        val a = NotificationFacts.Fact(Kind.Spent, 12.40, "x", "PayPal", 1_000_000)
        val b = a.copy(at = 1_000_000 + 5 * 60_000)
        val c = a.copy(at = 1_000_000 + 40 * 60_000)
        assertEquals(12.40 * 2, NotificationFacts.money(listOf(a, b, c)).spent, 0.001)
    }

    @Test fun theWeeklyTotalExcludesFailedPayments() {
        val facts = listOf(
            r(pp, "PayPal", "PayPal", "A PayPal Debit Card purchase of \$4,000.00 didn't go through", 10),
            r(pp, "PayPal", "PayPal", "A PayPal Debit Card purchase of \$1,315.83 was declined", 20_000_000),
            r(pp, "PayPal", "PayPal", "A PayPal Debit Card purchase of \$15.00 at Shell", 40_000_000),
        ).filterNotNull()
        val m = NotificationFacts.money(facts)
        assertEquals(15.0, m.spent, 0.001); assertEquals(2, m.declined.size)
    }

    private fun day(d: Long, hr: Long? = null, sleep: Long? = null, hrv: Double? = null) = HealthDay(d, restingHr = hr, sleepMin = sleep, hrvMs = hrv)

    @Test fun emptyMetricsFallBackToTheLatestRecentDay() {
        val days = listOf(day(95, hr = 58), day(97, hr = 56, hrv = 41.0), day(99, sleep = 410))
        val rest = HealthFill.latest(days, 100) { it.restingHr }!!
        assertEquals(56L, rest.value); assertEquals(3, rest.daysAgo)
        assertEquals(410L, HealthFill.latest(days, 100, 3) { it.sleepMin }!!.value)
        assertNull("too old to be last night", HealthFill.latest(days, 100, 0) { it.sleepMin })
        assertNull(HealthFill.latest(days, 100, 2) { it.hrvMs })
        assertEquals("today", HealthFill.ago(0)); assertEquals("yesterday", HealthFill.ago(1)); assertEquals("3 days ago", HealthFill.ago(3))
    }

    @Test fun anEmptyDashSaysWhy() {
        val m = HealthFill.Metric.Sleep
        assertTrue(HealthFill.whyEmpty(m, null).contains("isn't available"))
        assertTrue(HealthFill.whyEmpty(m, emptySet()).startsWith("Not allowed"))
        assertTrue(HealthFill.whyEmpty(m, setOf(m.permission)).contains("No sleep in Health Connect"))
        assertTrue(HealthFill.whyEmpty(HealthFill.Metric.Hrv, setOf(HealthFill.Metric.Hrv.permission)).contains("HRV"))
    }

    @Test fun homeCacheRoundTripsWithoutKeepingText() {
        val f = Facts(
            63, true, 40_000_000_000, 256_000_000_000, 3_600_000, 41, true, 82, 5_000, "1.91.0", 99,
            listOf(Moment(Source.Notification, 10, "secret title", "secret body", app = "Chase", end = 10), Moment(Source.Watched, 20, "", app = "YouTube", end = 600_020)),
            "32 things you copied", Finding("32 things you copied", Source.Clip, null, false),
        )
        val json = FactsCache.encode(f)
        assertTrue("no titles or details are kept", !json.contains("secret"))
        val back = FactsCache.decode(json, 123)!!
        assertEquals(63, back.battery); assertEquals(true, back.charging); assertEquals(41, back.notificationsToday)
        assertEquals(Source.Clip, back.findingTarget!!.source); assertEquals("32 things you copied", back.finding)
        assertEquals(listOf(Source.Notification, Source.Watched), back.today.map { it.source }); assertEquals("YouTube", back.today[1].app)
        assertEquals(123L, back.now)
        assertNull(FactsCache.decode("garbage"))
        assertNotNull(FactsCache.decode(FactsCache.encode(f.copy(battery = null, findingTarget = null, finding = null, today = emptyList()))))
    }
}
