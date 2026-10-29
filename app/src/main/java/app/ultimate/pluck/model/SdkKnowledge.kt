package app.ultimate.pluck.model

/** What job an SDK does inside an app. */
enum class SdkRole(val label: String) {
    NetworkAndMediator("Ad network and mediator"),
    AdNetwork("Ad network"),
    Analytics("Usage analytics"),
    Attribution("Install attribution"),
    Crash("Crash reporting"),
}

/**
 * Plain-language facts about a known SDK. These describe the SDK in general, from its public documentation and the way such SDKs are
 * commonly configured. They do not say what a particular app does with it, and an app's developer can turn collection up or down.
 */
data class SdkInfo(
    val role: SdkRole,
    val company: String?,
    val what: String,
    val collects: String,
    val why: String,
)

object SdkKnowledge {
    private const val AD_DATA = "Typically: your advertising ID, IP address (so roughly where you are), device model and OS version, screen size, network type, and which ads were requested, shown and tapped."
    private const val NETWORK_WHY = "The app earns money each time an ad from this network is shown or tapped. Apps add several networks so they can sell each ad slot to whoever pays most."

    val all: Map<String, SdkInfo> = mapOf(
        "Google AdMob" to SdkInfo(
            SdkRole.NetworkAndMediator, "Google",
            "Google's mobile ad network. It also runs a mediation layer that can call other ad networks through adapter libraries.",
            "$AD_DATA With location permission it can use approximate location.",
            "It is the most common way for apps to show ads. Mediation lets Google's auction include other networks.",
        ),
        "AppLovin" to SdkInfo(
            SdkRole.NetworkAndMediator, "AppLovin",
            "An ad network and the MAX mediation platform. MAX asks many networks to bid for each ad slot and shows the winner.",
            AD_DATA,
            "Apps use MAX to compare bids from many networks at once. Each network it can call ships its own SDK, which is why apps using MAX list many ad SDKs.",
        ),
        "Meta Audience Network" to SdkInfo(
            SdkRole.AdNetwork, "Meta",
            "Meta's ad network, which lets apps show ads sold through Facebook and Instagram's advertisers.",
            "$AD_DATA Meta's network can use data Meta already has to choose ads, which is why it pays well.",
            NETWORK_WHY,
        ),
        "Unity Ads" to SdkInfo(
            SdkRole.NetworkAndMediator, "Unity",
            "Unity's ad network, common in games. Unity also operates the LevelPlay mediation platform, which absorbed ironSource.",
            AD_DATA,
            "Games use it for rewarded and interstitial video ads.",
        ),
        "ironSource" to SdkInfo(
            SdkRole.NetworkAndMediator, "Unity (ironSource merged into Unity in 2022)",
            "An ad network and the LevelPlay mediation platform, mostly used by games.",
            AD_DATA,
            "Mediation sells each ad slot to the highest bidder among many networks.",
        ),
        "Liftoff / Vungle" to SdkInfo(
            SdkRole.AdNetwork, "Liftoff (formerly Vungle)",
            "A video-ad network focused on mobile games and apps.",
            AD_DATA,
            NETWORK_WHY,
        ),
        "AdColony" to SdkInfo(
            SdkRole.AdNetwork, "Digital Turbine",
            "A video-ad network, now folded into Digital Turbine's products.",
            AD_DATA,
            NETWORK_WHY,
        ),
        "Mintegral" to SdkInfo(
            SdkRole.AdNetwork, null,
            "An ad network popular with mobile games, strong in Asia.",
            AD_DATA,
            NETWORK_WHY,
        ),
        "InMobi" to SdkInfo(
            SdkRole.AdNetwork, "InMobi",
            "A global mobile ad network and exchange.",
            AD_DATA,
            NETWORK_WHY,
        ),
        "Chartboost" to SdkInfo(
            SdkRole.AdNetwork, null,
            "A mobile-game ad network that also offers mediation.",
            AD_DATA,
            NETWORK_WHY,
        ),
        "Pangle" to SdkInfo(
            SdkRole.AdNetwork, "ByteDance (TikTok's parent)",
            "ByteDance's ad network, which brings advertisers from TikTok's ecosystem to other apps.",
            AD_DATA,
            NETWORK_WHY,
        ),
        "StartApp" to SdkInfo(
            SdkRole.AdNetwork, null,
            "A mobile ad network and monetization SDK, often used by free utility apps.",
            AD_DATA,
            NETWORK_WHY,
        ),
        "Digital Turbine / Fyber" to SdkInfo(
            SdkRole.AdNetwork, "Digital Turbine",
            "An ad exchange and mediation toolkit (Fyber) owned by Digital Turbine.",
            AD_DATA,
            NETWORK_WHY,
        ),
        "Tapjoy" to SdkInfo(
            SdkRole.AdNetwork, null,
            "An ad network built around rewarded offers: you get an in-app reward for watching or doing something.",
            AD_DATA,
            "Rewarded offers pay well, so games and utility apps add it as another revenue source.",
        ),
        "Smaato" to SdkInfo(
            SdkRole.AdNetwork, null,
            "An ad exchange that connects apps to many advertisers at once.",
            AD_DATA,
            NETWORK_WHY,
        ),
        "Firebase Analytics" to SdkInfo(
            SdkRole.Analytics, "Google",
            "Google's app analytics. It is built on Google's measurement library, so Firebase Analytics and Google Analytics usually appear together.",
            "Typically: what you do in the app (screens opened, buttons used), when and how long, a per-install app instance ID, device and OS details, approximate location from your IP, and your advertising ID if the app allows it.",
            "Developers use it to see which features people use and where they drop off. It also feeds Google's ad products.",
        ),
        "Google Analytics" to SdkInfo(
            SdkRole.Analytics, "Google",
            "Google's measurement library inside the app. It is the engine under Firebase Analytics.",
            "Same as Firebase Analytics: in-app events, session data, app instance ID, device and OS details, approximate location.",
            "It ships with Firebase and many Google services, so finding it alongside Firebase Analytics is normal rather than a second tracker.",
        ),
        "Meta App Events" to SdkInfo(
            SdkRole.Analytics, "Meta",
            "Meta's SDK for reporting what you do in an app back to Meta, mainly to measure Facebook and Instagram ads.",
            "Typically: app events such as installs, purchases and sign-ups, your advertising ID, and device details.",
            "Advertisers use it to learn which ads led to actions in the app, and Meta uses it to target and optimize ads.",
        ),
        "AppsFlyer" to SdkInfo(
            SdkRole.Attribution, "AppsFlyer",
            "A marketing attribution service that works out which ad or link led someone to install an app.",
            "Typically: advertising ID, IP address, device details, install source and campaign, and in-app events the developer chooses to report.",
            "Developers pay for ads and need to know which ones brought real users. Attribution also lets ad networks be paid per install.",
        ),
        "Adjust" to SdkInfo(
            SdkRole.Attribution, "AppLovin",
            "An attribution service that matches installs and in-app actions to the ad campaigns that caused them.",
            "Typically: advertising ID, IP address, device details, install source and campaign, and chosen in-app events.",
            "Same reason as other attribution tools: proving which ad spend worked.",
        ),
        "Branch" to SdkInfo(
            SdkRole.Attribution, "Branch",
            "A deep-linking and attribution service: it makes links open the right screen in an app and records where installs came from.",
            "Typically: device IDs, IP address, device details, and the link or campaign you came from.",
            "It lets a shared link survive an app install, and it tells developers which links and ads convert.",
        ),
        "Mixpanel" to SdkInfo(
            SdkRole.Analytics, "Mixpanel",
            "A product-analytics service that records user actions as events and builds funnels and cohorts from them.",
            "Typically: the events the developer defines, a user or device ID, device and OS details, and approximate location from IP.",
            "Teams use it to understand how people move through the app and what keeps them.",
        ),
        "Amplitude" to SdkInfo(
            SdkRole.Analytics, "Amplitude",
            "A product-analytics service, like Mixpanel, for tracking events and retention.",
            "Typically: developer-defined events, a user or device ID, device and OS details, and approximate location from IP.",
            "Used to measure engagement and test product changes.",
        ),
        "AppMetrica" to SdkInfo(
            SdkRole.Analytics, "Yandex",
            "Yandex's app analytics and attribution SDK, common in apps made for Russian-speaking markets.",
            "Typically: events, device and OS details, IP address, advertising ID, and install source.",
            "A free alternative to Western analytics services, with attribution built in.",
        ),
        "Flurry" to SdkInfo(
            SdkRole.Analytics, "Yahoo",
            "One of the oldest mobile analytics SDKs, now run by Yahoo.",
            "Typically: sessions, events, device and OS details, IP address, and advertising ID.",
            "Often left in older apps, or chosen because it is free.",
        ),
        "Segment" to SdkInfo(
            SdkRole.Analytics, "Twilio",
            "A pipe, not a destination: it collects events once and forwards them to many other analytics and marketing services.",
            "Whatever the developer sends it, which is then relayed onward; typically user IDs, events, and device details.",
            "It makes it easy for a company to feed the same data to several tools, so one Segment SDK can stand for many services behind it.",
        ),
        "Singular" to SdkInfo(
            SdkRole.Attribution, "Singular",
            "A marketing analytics and attribution service for measuring ad campaigns.",
            "Typically: advertising ID, IP address, device details, install source, and chosen in-app events.",
            "Same as other attribution services: connecting ad spend to installs and revenue.",
        ),
        "Firebase Crashlytics" to SdkInfo(
            SdkRole.Crash, "Google",
            "Google's crash reporter. When the app crashes it sends a report so the developer can fix the bug.",
            "Typically: the error and stack trace, app version, device model and OS version, free memory and storage, and an installation ID.",
            "Crash reports are how most developers find bugs. It is generally the least intrusive item in this list.",
        ),
        "Sentry" to SdkInfo(
            SdkRole.Crash, "Sentry",
            "An error-monitoring service that reports crashes and sometimes slow screens.",
            "Typically: error details and stack traces, app and device information, and breadcrumbs of recent actions leading up to the problem.",
            "Used for finding and fixing bugs; some teams self-host it so the reports never go to Sentry's servers.",
        ),
        "Bugsnag" to SdkInfo(
            SdkRole.Crash, "SmartBear",
            "An error-monitoring service that reports crashes and handled errors.",
            "Typically: error details, stack traces, app and device information, and recent in-app events.",
            "Used for finding and fixing bugs.",
        ),
    )

    fun info(name: String): SdkInfo? = all[name]
}

/** A mediation layer found in an app and the ad networks it can call. [usedByApp] is true when the app's own code calls the mediator. */
data class Mediation(val mediator: String, val networks: List<String>, val usedByApp: Boolean) {
    fun encode() = "$mediator|${if (usedByApp) 1 else 0}|${networks.joinToString(",")}"

    companion object {
        fun decode(s: String): Mediation? {
            val parts = s.split('|', limit = 3)
            if (parts.size < 3) return null
            return Mediation(parts[0], parts[2].split(',').filter { it.isNotBlank() }, parts[1] == "1")
        }
    }
}

/** What an installed permission lets code inside the app (including ad and tracking SDKs) reach. */
data class Exposure(val label: String, val why: String, val granted: Boolean, val sensitive: Boolean)

object Explainer {
    private val interesting = linkedMapOf(
        "com.google.android.gms.permission.AD_ID" to Triple("Advertising ID", "A resettable ID that ad and attribution SDKs use to recognize you across apps.", true),
        "android.permission.ACCESS_ADSERVICES_AD_ID" to Triple("Privacy Sandbox ad ID", "Android's newer route to the advertising ID.", true),
        "android.permission.ACCESS_ADSERVICES_ATTRIBUTION" to Triple("Privacy Sandbox attribution", "Lets the app measure which ads led to actions.", true),
        "android.permission.ACCESS_ADSERVICES_TOPICS" to Triple("Privacy Sandbox topics", "Lets the app read interest topics Android infers from your app use.", true),
        "android.permission.ACCESS_FINE_LOCATION" to Triple("Precise location", "SDKs inside the app run with its permissions, so they can use precise location when you grant it.", true),
        "android.permission.ACCESS_COARSE_LOCATION" to Triple("Approximate location", "SDKs inside the app can use approximate location when you grant it.", true),
        "android.permission.ACCESS_BACKGROUND_LOCATION" to Triple("Location in the background", "Allows location use even when the app is closed.", true),
        "android.permission.READ_PHONE_STATE" to Triple("Phone state", "Can expose carrier and device details to SDKs.", true),
        "android.permission.GET_ACCOUNTS" to Triple("Accounts on the phone", "Can reveal which accounts are signed in.", true),
        "android.permission.ACCESS_WIFI_STATE" to Triple("Wi-Fi details", "Network name and signal details help ad targeting.", false),
        "android.permission.ACCESS_NETWORK_STATE" to Triple("Network type", "Lets SDKs see whether you are on Wi-Fi or mobile data.", false),
    )

    fun exposures(permissions: List<Permission>): List<Exposure> =
        permissions.mapNotNull { p -> interesting[p.name]?.let { (label, why, sensitive) -> Exposure(label, why, p.granted, sensitive) } }

    /** Names a mediated network the way the rest of the app does. */
    fun networkName(raw: String): String = when (raw.lowercase().replace(" ", "")) {
        "google", "admob" -> "Google AdMob"
        "googleadmanager" -> "Google Ad Manager"
        "facebook", "meta" -> "Meta Audience Network"
        "applovin" -> "AppLovin"
        "unity", "unityads" -> "Unity Ads"
        "ironsource" -> "ironSource"
        "vungle", "liftoff" -> "Liftoff / Vungle"
        "adcolony" -> "AdColony"
        "mintegral" -> "Mintegral"
        "inmobi" -> "InMobi"
        "chartboost" -> "Chartboost"
        "pangle", "bytedance" -> "Pangle"
        "fyber", "digitalturbine" -> "Digital Turbine / Fyber"
        "tapjoy" -> "Tapjoy"
        "smaato" -> "Smaato"
        "yandex" -> "Yandex Ads"
        "mytarget" -> "myTarget"
        "bidmachine" -> "BidMachine"
        "verve" -> "Verve"
        else -> raw.replaceFirstChar { it.uppercase() }
    }

    /** The "why so many?" story, in short paragraphs, from what was found. */
    fun why(adSdks: List<String>, otherSdks: List<String>, mediation: List<Mediation>?, kindOf: (String) -> SdkRole?): List<String> {
        val out = ArrayList<String>()
        val ads = adSdks.size
        // Several mediation layers can be present: one the app calls, plus others pulled in by adapters. Lead with the one in use.
        val m = mediation?.firstOrNull { it.usedByApp } ?: mediation?.firstOrNull()
        val others = mediation.orEmpty().filter { it !== m }
        when {
            ads == 0 -> out += "No ad networks were found in this app."
            m != null -> {
                out += "${m.mediator} is an ad mediation layer. For every ad slot it holds an auction between ad networks and shows the winner, so the app earns more. " +
                    "Each network it can call needs its own SDK inside the app, which is the main reason $ads ad SDKs are present even though only one ad appears at a time."
                if (m.networks.isNotEmpty()) out += "Networks wired into it: ${m.networks.joinToString(", ")}."
                out += if (m.usedByApp) "The app's own code calls ${m.mediator} directly, so these networks are in use." else
                    "Only adapter code was found, with no direct calls from the app. Some of these may be bundled by a library rather than used."
                if (others.isNotEmpty()) {
                    out += "Also present: " + others.joinToString("; ") { o ->
                        o.mediator + if (o.usedByApp) " (called by the app)" else " (adapter code only, probably pulled in by another library)"
                    } + ". Stacked mediation like this is common and adds SDKs without adding ads."
                }
            }
            ads >= 3 -> out += "$ads ad SDKs are present but no mediation layer was found. The app may call each network itself, or the mediation code may be renamed so this scan cannot see it."
            else -> out += "A small ad setup: ${adSdks.joinToString(", ")}."
        }
        val analytics = otherSdks.count { kindOf(it) == SdkRole.Analytics || kindOf(it) == SdkRole.Attribution }
        if (analytics > 0) out += "$analytics analytics or attribution SDK${if (analytics == 1) "" else "s"} measure how the app is used and which ads brought users in."
        if (otherSdks.any { kindOf(it) == SdkRole.Crash }) out += "Crash reporting sends error details to the developer when something breaks."
        return out
    }
}
