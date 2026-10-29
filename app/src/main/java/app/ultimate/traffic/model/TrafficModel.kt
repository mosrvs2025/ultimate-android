package app.ultimate.traffic.model

enum class DomainKind(val label: String) { Ads("Ad networks"), Analytics("Analytics & attribution"), Crash("Crash reporting"), Other("Everything else") }

/** A host that belongs to a known ad, analytics or crash-reporting service. [sdk] matches the names the code scanner uses where the service is in its catalog. */
data class TrackerHit(val sdk: String, val kind: DomainKind)

/**
 * Maps host names to the services they belong to. Best effort: it covers well-known endpoints, so a missing host does not mean
 * a host is harmless, and a hit shows the app talked to that service, not what it said.
 */
object TrackerDomains {
    private val table: Map<String, TrackerHit> = buildMap {
        fun add(kind: DomainKind, sdk: String, vararg suffixes: String) = suffixes.forEach { put(it, TrackerHit(sdk, kind)) }
        val ads = DomainKind.Ads
        val an = DomainKind.Analytics
        add(ads, "Google AdMob", "doubleclick.net", "googleadservices.com", "googlesyndication.com", "admob.com", "adservice.google.com")
        add(ads, "AppLovin", "applovin.com", "applvn.com")
        add(ads, "Meta Audience Network", "an.facebook.com")
        add(ads, "Unity Ads", "unityads.unity3d.com")
        add(ads, "ironSource", "supersonicads.com", "ironsrc.mobi", "ironsource.mobi", "ironsrc.com")
        add(ads, "Liftoff / Vungle", "vungle.com", "liftoff.io")
        add(ads, "AdColony", "adcolony.com")
        add(ads, "Mintegral", "mintegral.com", "mintegral.net", "rayjump.com", "mobvista.com")
        add(ads, "InMobi", "inmobi.com", "inmobicdn.net")
        add(ads, "Chartboost", "chartboost.com")
        add(ads, "Pangle", "pangle.io", "pangleglobal.com", "byteoversea.com")
        add(ads, "StartApp", "startappservice.com", "startapp.com")
        add(ads, "Digital Turbine / Fyber", "fyber.com", "inner-active.mobi", "digitalturbine.com")
        add(ads, "Tapjoy", "tapjoy.com", "tapjoyads.com")
        add(ads, "Smaato", "smaato.net", "smaato.com")
        add(ads, "Taboola", "taboola.com")
        add(ads, "Outbrain", "outbrain.com")
        add(ads, "Criteo", "criteo.com", "criteo.net")
        add(ads, "Moat", "moatads.com")
        add(ads, "Xandr", "adnxs.com")
        add(ads, "Magnite", "rubiconproject.com")
        add(ads, "PubMatic", "pubmatic.com")
        add(ads, "OpenX", "openx.net")
        add(ads, "Amazon Ads", "amazon-adsystem.com")
        add(ads, "The Trade Desk", "adsrvr.org")
        add(ads, "Index Exchange", "casalemedia.com")
        add(ads, "TripleLift", "3lift.com")
        add(ads, "Teads", "teads.tv")
        add(ads, "MoPub", "mopub.com")
        add(an, "Firebase Analytics", "app-measurement.com")
        add(an, "Google Analytics", "google-analytics.com")
        add(an, "Meta App Events", "graph.facebook.com", "graph.instagram.com")
        add(an, "AppsFlyer", "appsflyer.com", "appsflyersdk.com")
        add(an, "Adjust", "adjust.com", "adjust.io")
        add(an, "Branch", "branch.io", "app.link", "bnc.lt")
        add(an, "Mixpanel", "mixpanel.com")
        add(an, "Amplitude", "amplitude.com")
        add(an, "AppMetrica", "appmetrica.yandex.com", "appmetrica.yandex.ru", "mobile.yandex.net")
        add(an, "Flurry", "flurry.com")
        add(an, "Segment", "segment.io", "segment.com")
        add(an, "Singular", "singular.net", "sng.link")
        add(an, "Kochava", "kochava.com")
        add(an, "Tenjin", "tenjin.io", "tenjin.com")
        add(an, "OneSignal", "onesignal.com")
        add(an, "Braze", "braze.com", "appboy.com")
        add(DomainKind.Crash, "Firebase Crashlytics", "crashlytics.com", "firebasecrashlyticsreports-pa.googleapis.com")
        add(DomainKind.Crash, "Sentry", "sentry.io")
        add(DomainKind.Crash, "Bugsnag", "bugsnag.com")
    }

    /** The service a host belongs to, matching the longest known suffix. */
    fun classify(host: String?): TrackerHit? {
        var h = host?.lowercase()?.trimEnd('.') ?: return null
        while (true) {
            table[h]?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0) return null
            h = h.substring(dot + 1)
        }
    }

    /** The known suffix a host matched, e.g. "applovin.com" for "ms.applovin.com"; what to block to stop the whole service. */
    fun suffixFor(host: String?): String? {
        var h = host?.lowercase()?.trimEnd('.') ?: return null
        while (true) {
            if (table.containsKey(h)) return h
            val dot = h.indexOf('.')
            if (dot < 0) return null
            h = h.substring(dot + 1)
        }
    }

    /** Every known suffix of a kind, e.g. all ad-network domains, for blocking them as a group. */
    fun suffixes(kind: DomainKind): Set<String> = table.filterValues { it.kind == kind }.keys

    /** The registrable-looking part of a host, used to group subdomains: "ms.applovin.com" becomes "applovin.com". */
    fun family(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2 || host.all { it.isDigit() || it == '.' }) return host
        val twoLevel = setOf("co.uk", "com.au", "co.jp", "co.in", "com.br", "com.cn", "co.kr", "com.tr", "co.za", "com.mx")
        val last2 = parts.takeLast(2).joinToString(".")
        return if (last2 in twoLevel && parts.size >= 3) parts.takeLast(3).joinToString(".") else last2
    }
}

/** One finished or running connection, reduced to what the screens need. */
data class FlowView(
    val id: Int, val pkg: String?, val label: String, val protocol: String, val remote: String, val port: Int,
    val host: String?, val hostSource: String?, val up: Long, val down: Long, val startedAt: Long, val lastAt: Long,
    val open: Boolean, val blocked: Boolean, val cleartext: String?, val note: String?,
) {
    val name: String get() = host ?: remote
}

/** Everything one app has said to one host (or address) so far. */
data class HostView(
    val host: String, val pkgs: List<String>, val up: Long, val down: Long, val flows: Int, val openFlows: Int,
    val firstAt: Long, val lastAt: Long, val hit: TrackerHit?, val blocked: Boolean, val cleartext: Boolean, val port: Int,
) {
    val kind: DomainKind get() = hit?.kind ?: DomainKind.Other
    val total: Long get() = up + down
}

/** One app's traffic in a session. */
data class AppTraffic(val pkg: String, val label: String, val up: Long, val down: Long, val flows: Int, val hosts: List<HostView>) {
    val total: Long get() = up + down
    val seenSdks: Set<String> get() = hosts.mapNotNull { it.hit?.sdk }.toSet()
}

data class DnsView(val at: Long, val pkg: String?, val label: String, val name: String, val type: String, val blocked: Boolean, val hit: TrackerHit?)

/** A point-in-time picture of a capture, rebuilt a few times a second while it runs. */
data class TrafficSnapshot(
    val up: Long = 0, val down: Long = 0, val openFlows: Int = 0, val flows: List<FlowView> = emptyList(),
    val hosts: List<HostView> = emptyList(), val apps: List<AppTraffic> = emptyList(), val dns: List<DnsView> = emptyList(),
) {
    val trackerHosts: List<HostView> get() = hosts.filter { it.hit != null }
}

object Aggregator {
    /** Groups flows into hosts and apps. Hosts without a name fall back to the address, so nothing is hidden. */
    fun build(flows: List<FlowView>, dns: List<DnsView>, classify: (String?) -> TrackerHit? = TrackerDomains::classify): TrafficSnapshot {
        val byHost = flows.groupBy { it.name }
        val hosts = byHost.map { (name, list) ->
            HostView(
                host = name, pkgs = list.mapNotNull { it.pkg }.distinct(), up = list.sumOf { it.up }, down = list.sumOf { it.down },
                flows = list.size, openFlows = list.count { it.open }, firstAt = list.minOf { it.startedAt }, lastAt = list.maxOf { it.lastAt },
                hit = classify(list.firstNotNullOfOrNull { it.host }), blocked = list.any { it.blocked },
                cleartext = list.any { it.cleartext != null }, port = list.first().port,
            )
        }.sortedByDescending { it.total }
        val apps = flows.filter { it.pkg != null }.groupBy { it.pkg!! }.map { (pkg, list) ->
            val appHosts = list.groupBy { it.name }.map { (name, fs) ->
                HostView(
                    name, listOf(pkg), fs.sumOf { it.up }, fs.sumOf { it.down }, fs.size, fs.count { it.open }, fs.minOf { it.startedAt }, fs.maxOf { it.lastAt },
                    classify(fs.firstNotNullOfOrNull { it.host }), fs.any { it.blocked }, fs.any { it.cleartext != null }, fs.first().port,
                )
            }.sortedByDescending { it.total }
            AppTraffic(pkg, list.first().label, list.sumOf { it.up }, list.sumOf { it.down }, list.size, appHosts)
        }.sortedByDescending { it.total }
        return TrafficSnapshot(flows.sumOf { it.up }, flows.sumOf { it.down }, flows.count { it.open }, flows.sortedByDescending { it.lastAt }, hosts, apps, dns)
    }

    /** CSV of every host each app contacted, for sharing or opening in a spreadsheet. */
    fun csv(apps: List<AppTraffic>): String = buildString {
        append("app,package,host,service,kind,uploaded_bytes,downloaded_bytes,connections,cleartext_http,blocked\n")
        apps.forEach { a ->
            a.hosts.forEach { h ->
                append(listOf(a.label, a.pkg, h.host, h.hit?.sdk.orEmpty(), h.kind.label, h.up, h.down, h.flows, h.cleartext, h.blocked).joinToString(",") { v -> "\"" + v.toString().replace("\"", "\"\"") + "\"" })
                append('\n')
            }
        }
    }
}
