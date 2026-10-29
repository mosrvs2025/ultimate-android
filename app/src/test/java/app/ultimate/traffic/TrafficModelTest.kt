package app.ultimate.traffic

import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.traffic.model.Aggregator
import app.ultimate.traffic.model.DomainKind
import app.ultimate.traffic.model.FlowView
import app.ultimate.traffic.model.TrackerDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficModelTest {
    private fun flow(id: Int, pkg: String?, host: String?, up: Long = 10, down: Long = 100, open: Boolean = true, cleartext: String? = null, blocked: Boolean = false) =
        FlowView(id, pkg, pkg.orEmpty(), "TCP", "1.2.3.$id", 443, host, if (host != null) "TLS" else null, up, down, 1000L + id, 2000L + id, open, blocked, cleartext, null)

    @Test fun classifiesSubdomainsByTheLongestKnownSuffix() {
        assertEquals("AppLovin", TrackerDomains.classify("ms.applovin.com")?.sdk)
        assertEquals("AppLovin", TrackerDomains.classify("A.B.APPLVN.COM.")?.sdk)
        assertEquals(DomainKind.Analytics, TrackerDomains.classify("app-measurement.com")?.kind)
        assertEquals("Meta Audience Network", TrackerDomains.classify("an.facebook.com")?.sdk)
        assertEquals("Firebase Crashlytics", TrackerDomains.classify("firebasecrashlyticsreports-pa.googleapis.com")?.sdk)
    }

    @Test fun doesNotMistakeLookalikesOrGenericSitesForTrackers() {
        assertNull(TrackerDomains.classify("notapplovin.com"))
        assertNull(TrackerDomains.classify("www.facebook.com")) // only specific tracking endpoints, not the whole site
        assertNull(TrackerDomains.classify("example.org"))
        assertNull(TrackerDomains.classify(null))
        assertNull(TrackerDomains.classify("1.2.3.4"))
    }

    @Test fun everyKnownSdkNameInTheTableIsSpelledLikeTheCodeScanner() {
        // Traffic ties "seen on the network" to "found in code", so shared services must use the scanner's exact names.
        val catalog = TrackerScanner.catalog.map { it.name }.toSet()
        val fromTable = listOf(
            "ms.applovin.com", "googleads.g.doubleclick.net", "an.facebook.com", "unityads.unity3d.com", "supersonicads.com", "vungle.com", "adcolony.com",
            "mintegral.com", "inmobi.com", "chartboost.com", "pangle.io", "startappservice.com", "fyber.com", "tapjoy.com", "smaato.net", "app-measurement.com",
            "google-analytics.com", "graph.facebook.com", "appsflyer.com", "adjust.com", "branch.io", "mixpanel.com", "amplitude.com", "appmetrica.yandex.com",
            "flurry.com", "segment.io", "singular.net", "crashlytics.com", "sentry.io", "bugsnag.com",
        ).map { TrackerDomains.classify(it)!!.sdk }
        assertEquals("table names the scanner does not know: ${fromTable.filter { it !in catalog }}", emptyList<String>(), fromTable.filter { it !in catalog })
    }

    @Test fun groupsSubdomainsIntoFamilies() {
        assertEquals("applovin.com", TrackerDomains.family("ms.applovin.com"))
        assertEquals("bbc.co.uk", TrackerDomains.family("static.files.bbc.co.uk"))
        assertEquals("1.2.3.4", TrackerDomains.family("1.2.3.4"))
        assertEquals("localhost", TrackerDomains.family("localhost"))
    }

    @Test fun aggregatesHostsAndAppsAndKeepsUnnamedAddresses() {
        val flows = listOf(
            flow(1, "a.app", "ms.applovin.com", up = 100, down = 1000),
            flow(2, "a.app", "ms.applovin.com", up = 50, down = 500, open = false),
            flow(3, "a.app", null, up = 5, down = 5),
            flow(4, "b.app", "example.org", up = 1, down = 2, cleartext = "GET / HTTP/1.1"),
        )
        val snap = Aggregator.build(flows, emptyList())
        val applovin = snap.hosts.first { it.host == "ms.applovin.com" }
        assertEquals(150L, applovin.up); assertEquals(1500L, applovin.down); assertEquals(2, applovin.flows); assertEquals(1, applovin.openFlows)
        assertEquals("AppLovin", applovin.hit?.sdk)
        assertEquals("1.2.3.3", snap.hosts.last { it.host.startsWith("1.2.3") }.host)
        assertEquals(listOf("a.app", "b.app"), snap.apps.map { it.pkg })
        assertEquals(setOf("AppLovin"), snap.apps.first().seenSdks)
        assertTrue(snap.hosts.first { it.host == "example.org" }.cleartext)
        assertEquals(156L, snap.up); assertEquals(1507L, snap.down); assertEquals(3, snap.openFlows)
        assertEquals(listOf(1, 2, 3, 4), snap.flows.map { it.id }.sorted())
    }

    @Test fun csvQuotesAwkwardValuesAndHasAHeader() {
        val snap = Aggregator.build(listOf(flow(1, "a.app", "ms.applovin.com")).map { it.copy(label = "My \"App\", v2") }, emptyList())
        val lines = Aggregator.csv(snap.apps).trim().lines()
        assertTrue(lines.first().startsWith("app,package,host,service"))
        assertTrue(lines[1].startsWith("\"My \"\"App\"\", v2\",\"a.app\",\"ms.applovin.com\",\"AppLovin\""))
    }
}
