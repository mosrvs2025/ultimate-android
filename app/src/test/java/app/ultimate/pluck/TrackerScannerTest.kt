package app.ultimate.pluck

import app.ultimate.pluck.engine.SdkKind
import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.AppSource
import app.ultimate.pluck.model.arrange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerScannerTest {
    private fun dex(vararg descriptors: String) = descriptors.joinToString("\u0000") { "L$it;" }.toByteArray(Charsets.US_ASCII)

    @Test fun findsKnownSdksByClassPrefix() {
        val found = TrackerScanner.findIn(dex("com/applovin/sdk/AppLovinSdk", "com/google/firebase/crashlytics/FirebaseCrashlytics", "com/example/Main"))
        assertEquals(setOf("AppLovin", "Firebase Crashlytics"), found.map { it.name }.toSet())
    }

    @Test fun ignoresLookalikePrefixes() {
        assertTrue(TrackerScanner.findIn(dex("com/applovinfake/Thing", "org/example/applovin/Thing")).isEmpty())
    }

    @Test fun searchFindsNeedleAtEdges() {
        val needle = "abc".toByteArray()
        assertEquals(0, TrackerScanner.indexOf("abcxyz".toByteArray(), needle))
        assertEquals(3, TrackerScanner.indexOf("xyzabc".toByteArray(), needle))
        assertEquals(-1, TrackerScanner.indexOf("ababab".toByteArray(), needle))
        assertEquals(-1, TrackerScanner.indexOf("ab".toByteArray(), needle))
    }

    @Test fun classifiesInstallSources() {
        assertEquals(AppSource.PlayStore, AppSource.of("com.android.vending", false))
        assertEquals(AppSource.Sideloaded, AppSource.of(null, false))
        assertEquals(AppSource.Sideloaded, AppSource.of("com.google.android.packageinstaller", false))
        assertEquals(AppSource.System, AppSource.of(null, true))
        assertEquals(AppSource.OtherStore, AppSource.of("org.fdroid.fdroid", false))
    }
}

class ListOptionsTest {
    private fun app(name: String, bytes: Long = 10_000_000, system: Boolean = false, installer: String? = "com.android.vending", target: Int = 34, dangerous: Int = 0, splits: Int = 1) =
        app.ultimate.pluck.model.AppItem(
            packageName = "pkg.$name", label = name, versionName = "1", versionCode = 1, isSystem = system, enabled = true,
            apkPaths = List(splits) { "/x/$it.apk" }, apkBytes = bytes, installTime = 0, updateTime = 0, installer = installer,
            minSdk = 26, targetSdk = target, permissionCount = dangerous, dangerousCount = dangerous,
        )

    private val apps = listOf(
        app("Zed", bytes = 80_000_000, dangerous = 2),
        app("alpha", bytes = 5_000_000, installer = null, target = 28),
        app("Mid", bytes = 20_000_000, dangerous = 9, splits = 3),
        app("System", system = true),
    )
    private val trackers = mapOf(
        "pkg.Zed" to app.ultimate.pluck.model.TrackerFacts(listOf("AppLovin", "Firebase Analytics", "Sentry"), adSdks = 1),
        "pkg.Mid" to app.ultimate.pluck.model.TrackerFacts(listOf("Sentry"), adSdks = 0),
    )
    private fun names(o: app.ultimate.pluck.model.ListOptions, q: String = "") = apps.arrange(q, o, trackers).map { it.label }

    @Test fun defaultsToUserAppsByName() {
        assertEquals(listOf("alpha", "Mid", "Zed"), names(app.ultimate.pluck.model.ListOptions()))
    }

    @Test fun sortsByTrackersThenUnscannedLast() {
        assertEquals(listOf("Zed", "Mid", "alpha"), names(app.ultimate.pluck.model.ListOptions(sort = app.ultimate.pluck.model.Sort.Trackers)))
    }

    @Test fun reverseFlipsOrder() {
        assertEquals(listOf("Zed", "Mid", "alpha"), names(app.ultimate.pluck.model.ListOptions(reversed = true)))
    }

    @Test fun quickFiltersCombine() {
        val q = app.ultimate.pluck.model.QuickFilter.entries
        assertEquals(listOf("Zed"), names(app.ultimate.pluck.model.ListOptions(quick = setOf(q.first { it.name == "HasAds" }))))
        assertEquals(listOf("alpha", "Mid"), names(app.ultimate.pluck.model.ListOptions(quick = setOf(q.first { it.name == "Under30" }))))
        assertEquals(listOf("alpha"), names(app.ultimate.pluck.model.ListOptions(quick = setOf(q.first { it.name == "Under30" }, q.first { it.name == "Sideloaded" }))))
        assertEquals(listOf("Mid"), names(app.ultimate.pluck.model.ListOptions(quick = setOf(q.first { it.name == "Split" }))))
    }

    @Test fun oldestTargetFirst() {
        assertEquals("alpha", names(app.ultimate.pluck.model.ListOptions(sort = app.ultimate.pluck.model.Sort.TargetSdk)).first())
    }

    @Test fun compactEstimateMatchesMeasuredApks() {
        // Apk Extractor 6.0.2: 21.6 MB stored, 2.7 MB already compressed on disk; real zip -9 result was 11.4 MB.
        val est = app.ultimate.pluck.model.estimateCompact(21_617_546, 2_701_860)
        assertTrue("est $est", est in 10_500_000..12_500_000)
    }
}

class ReplacementsTest {
    private fun app(pkg: String, name: String = pkg) = app.ultimate.pluck.model.AppItem(
        packageName = pkg, label = name, versionName = "1", versionCode = 1, isSystem = false, enabled = true, apkPaths = listOf("/x.apk"),
        apkBytes = 10_000_000, installTime = 0, updateTime = 0, installer = null, minSdk = 26, targetSdk = 34, permissionCount = 0, dangerousCount = 0,
    )

    private val original = app("com.toralabs.apkextractor", "Apk Extractor")
    private val unrelated = app("com.example.other")

    @Test fun toolBuiltIntoThisAppIsAvailable() {
        val av = app.ultimate.pluck.model.Replacements.availability(setOf("squeeze", "audio", "pluck", "studio"), setOf("app.ultimate"))
        assertEquals(setOf("squeeze", "audio", "pluck", "studio"), av.keys)
        assertTrue(av.values.all { it.endsWith("in this app") })
        assertTrue(av.getValue("squeeze").endsWith("in this app"))
    }

    @Test fun standaloneEditionSeesOtherToolsOnlyWhenInstalled() {
        val none = app.ultimate.pluck.model.Replacements.availability(setOf("pluck"), setOf("app.pluck"))
        assertEquals(setOf("pluck"), none.keys)
        val withSqueeze = app.ultimate.pluck.model.Replacements.availability(setOf("pluck"), setOf("app.pluck", "app.squeeze"))
        assertEquals(setOf("pluck", "squeeze"), withSqueeze.keys)
        val viaSuite = app.ultimate.pluck.model.Replacements.availability(setOf("pluck"), setOf("app.pluck", "app.ultimate"))
        assertEquals(setOf("pluck", "squeeze", "audio", "studio"), viaSuite.keys)
    }

    @Test fun findsKnownAppsOnlyWhenTheirReplacementIsUsable() {
        val av = mapOf("pluck" to "Pluck in this app")
        val found = app.ultimate.pluck.model.Replacements.find(listOf(original, unrelated), av, emptyMap(), emptySet())
        assertEquals(listOf("com.toralabs.apkextractor"), found.map { it.app.packageName })
        assertTrue(app.ultimate.pluck.model.Replacements.find(listOf(original), emptyMap(), emptyMap(), emptySet()).isEmpty())
    }

    @Test fun partialReplacementsAreOnlySafeOnceAccepted() {
        val compressor = app("com.compress.video.compressor.size.reducer", "Compress Video Size")
        val av = mapOf("squeeze" to "Squeeze in this app", "studio" to "Studio in this app")
        val before = app.ultimate.pluck.model.Replacements.find(listOf(compressor), av, emptyMap(), emptySet()).single()
        assertTrue(!before.safe)
        assertTrue(before.replacement.missing.isNotEmpty())
        val after = app.ultimate.pluck.model.Replacements.find(listOf(compressor), av, emptyMap(), setOf(compressor.packageName)).single()
        assertTrue(after.safe)
    }

    @Test fun helperToolsCountOnlyWhereTheyAreInstalled() {
        val mp3 = app("mp3videoconverter.videotomp3.videotomp3converter", "Video to MP3 Converter")
        val r = app.ultimate.pluck.model.Replacements
        val suite = r.find(listOf(mp3), mapOf("audio" to "Video to audio in this app", "studio" to "Studio in this app"), emptyMap(), emptySet()).single()
        assertTrue(suite.safe)
        assertEquals("Video to audio + Studio in this app", suite.via)
        // The audio-only edition has no Studio: joining videos is then genuinely missing.
        val alone = r.find(listOf(mp3), mapOf("audio" to "Video to audio in this app"), emptyMap(), emptySet()).single()
        assertTrue(!alone.safe)
        assertEquals(listOf("Video merger"), alone.replacement.missing)
        assertEquals("Video to audio in this app", alone.via)
    }

    @Test fun fileNamePatternsFillInAndStaySafe() {
        val a = app("com.example.app", "My App: Pro").copy(versionName = "2.1/beta", versionCode = 42)
        val f = app.ultimate.pluck.model.FileNames
        assertEquals("My App_ Pro_2.1_beta", f.render(f.DEFAULT, a))
        assertEquals("com.example.app_42", f.render("{package}_{code}", a))
        assertEquals("com.example.app", f.render("///", a))
        assertEquals("v2", f.render("v2", a))
        assertTrue(f.render("{name}-{date}", a, java.util.Date(0)).endsWith("-1970-01-01"))
    }

    @Test fun handMarkedAppsCountAsSafeAndOurOwnAppsAreNeverOffered() {
        val mine = app("com.example.mine", "Mine")
        val custom = mapOf(
            mine.packageName to app.ultimate.pluck.model.Replacement(mine.packageName, "Mine", "squeeze", app.ultimate.pluck.model.Coverage.Full),
            "app.ultimate" to app.ultimate.pluck.model.Replacement("app.ultimate", "Ultimate", "squeeze", app.ultimate.pluck.model.Coverage.Full),
        )
        val av = mapOf("squeeze" to "Squeeze in this app")
        val found = app.ultimate.pluck.model.Replacements.find(listOf(mine, app("app.ultimate")), av, custom, emptySet())
        assertEquals(listOf("com.example.mine"), found.map { it.app.packageName })
        assertTrue(found.single().safe)
    }

    @Test fun replacedQuickFilterShowsOnlyThoseApps() {
        val shown = listOf(original, unrelated).arrange("", app.ultimate.pluck.model.ListOptions(quick = setOf(app.ultimate.pluck.model.QuickFilter.Replaced)), emptyMap(), setOf(original.packageName))
        assertEquals(listOf("Apk Extractor"), shown.map { it.label })
    }

    @Test fun everyKnownEntryPointsAtARealToolAndNeverAtOurOwnPackage() {
        app.ultimate.pluck.model.Replacements.known.forEach {
            assertTrue(it.toolId in setOf("squeeze", "audio", "pluck"))
            assertTrue(it.originalPackage !in app.ultimate.pluck.model.Replacements.ours)
        }
    }
}

class MediationTest {
    private fun dex(vararg descriptors: String) = descriptors.joinToString("\u0000") { "L$it;" }.toByteArray(Charsets.US_ASCII)

    private fun mediation(vararg d: String): List<app.ultimate.pluck.model.Mediation> {
        val raw = TrackerScanner.Raw()
        raw.add(dex(*d))
        return raw.mediation()
    }

    @Test fun readsNetworksFromAppLovinMaxAdapters() {
        val m = mediation(
            "com/applovin/mediation/adapters/GoogleMediationAdapter", "com/applovin/mediation/adapters/FacebookMediationAdapter",
            "com/applovin/mediation/adapters/GoogleMediationAdapter\$Listener", // an inner class must not count as a network
            "com/applovin/mediation/adapters/AppLovinAdapterInterstitialListener", // a listener, not an adapter
            "com/applovin/mediation/ads/MaxInterstitialAd",
        ).single()
        assertEquals("AppLovin MAX", m.mediator)
        assertEquals(listOf("Google AdMob", "Meta Audience Network"), m.networks)
        assertTrue(m.usedByApp)
    }

    @Test fun adapterCodeAloneIsNotReportedAsUsed() {
        val m = mediation("com/applovin/mediation/adapters/UnityAdsMediationAdapter").single()
        assertTrue(!m.usedByApp)
        assertEquals(listOf("Unity Ads"), m.networks)
    }

    @Test fun admobMediationIgnoresAdmobItself() {
        val m = mediation("com/google/ads/mediation/admob/AdMobAdapter", "com/google/ads/mediation/vungle/VungleMediationAdapter", "com/google/ads/mediation/pangle/PangleMediationAdapter").single()
        assertEquals("AdMob mediation", m.mediator)
        assertEquals(listOf("Liftoff / Vungle", "Pangle"), m.networks)
    }

    @Test fun plainAdmobWithNoAdaptersHasNoMediation() {
        assertTrue(mediation("com/google/ads/mediation/admob/AdMobAdapter", "com/google/android/gms/ads/AdView").isEmpty())
    }

    @Test fun levelPlayIsDetected() {
        val m = mediation("com/ironsource/mediationsdk/IronSource", "com/ironsource/adapters/admob/AdMobAdapter").single()
        assertEquals("ironSource LevelPlay", m.mediator)
        assertEquals(listOf("Google AdMob"), m.networks)
        assertTrue(m.usedByApp)
    }

    @Test fun mediationRoundTripsThroughStorage() {
        val m = app.ultimate.pluck.model.Mediation("AppLovin MAX", listOf("Google AdMob", "Meta Audience Network"), true)
        assertEquals(m, app.ultimate.pluck.model.Mediation.decode(m.encode()))
        assertEquals(app.ultimate.pluck.model.Mediation("X", emptyList(), false), app.ultimate.pluck.model.Mediation.decode(app.ultimate.pluck.model.Mediation("X", emptyList(), false).encode()))
    }

    @Test fun everyScannedSdkHasAnExplanation() {
        val missing = TrackerScanner.catalog.filter { app.ultimate.pluck.model.SdkKnowledge.info(it.name) == null }.map { it.name }
        assertTrue("no explanation for $missing", missing.isEmpty())
        TrackerScanner.catalog.forEach {
            val info = app.ultimate.pluck.model.SdkKnowledge.info(it.name)!!
            assertTrue(it.name, info.what.isNotBlank() && info.collects.isNotBlank() && info.why.isNotBlank())
        }
    }

    @Test fun sdkRoleAgreesWithCatalogKind() {
        TrackerScanner.catalog.forEach {
            val role = app.ultimate.pluck.model.SdkKnowledge.info(it.name)!!.role
            val expected = when (it.kind) {
                SdkKind.Ads -> setOf(app.ultimate.pluck.model.SdkRole.AdNetwork, app.ultimate.pluck.model.SdkRole.NetworkAndMediator)
                SdkKind.Analytics -> setOf(app.ultimate.pluck.model.SdkRole.Analytics, app.ultimate.pluck.model.SdkRole.Attribution)
                SdkKind.Crash -> setOf(app.ultimate.pluck.model.SdkRole.Crash)
            }
            assertTrue("${it.name} is ${it.kind} but explained as $role", role in expected)
        }
    }

    @Test fun whyStoryNamesTheMediatorAndAllowsForUnusedAdapters() {
        val kind = { n: String -> app.ultimate.pluck.model.SdkKnowledge.info(n)?.role }
        val used = app.ultimate.pluck.model.Explainer.why(
            List(15) { "Ad$it" }, listOf("Firebase Analytics", "Firebase Crashlytics"),
            listOf(app.ultimate.pluck.model.Mediation("AppLovin MAX", listOf("Google AdMob"), true)), kind,
        ).joinToString(" ")
        assertTrue(used.contains("AppLovin MAX") && used.contains("15 ad SDKs") && used.contains("calls AppLovin MAX directly"))
        val bundled = app.ultimate.pluck.model.Explainer.why(
            List(4) { "Ad$it" }, emptyList(), listOf(app.ultimate.pluck.model.Mediation("AdMob mediation", listOf("Pangle"), false)), kind,
        ).joinToString(" ")
        assertTrue(bundled.contains("bundled by a library"))
        val none = app.ultimate.pluck.model.Explainer.why(emptyList(), emptyList(), null, kind).single()
        assertTrue(none.contains("No ad networks"))
        val unknown = app.ultimate.pluck.model.Explainer.why(List(5) { "Ad$it" }, emptyList(), emptyList(), kind).joinToString(" ")
        assertTrue(unknown.contains("no mediation layer was found"))
    }

    @Test fun whyLeadsWithTheMediatorTheAppActuallyCalls() {
        val kind = { n: String -> app.ultimate.pluck.model.SdkKnowledge.info(n)?.role }
        val story = app.ultimate.pluck.model.Explainer.why(
            List(6) { "Ad$it" }, emptyList(),
            listOf(
                app.ultimate.pluck.model.Mediation("AdMob mediation", listOf("Liftoff / Vungle"), false),
                app.ultimate.pluck.model.Mediation("AppLovin MAX", listOf("Google AdMob"), true),
                app.ultimate.pluck.model.Mediation("ironSource LevelPlay", emptyList(), true),
            ),
            kind,
        )
        assertTrue(story.first().startsWith("AppLovin MAX"))
        val also = story.first { it.startsWith("Also present") }
        assertTrue(also.contains("AdMob mediation (adapter code only") && also.contains("ironSource LevelPlay (called by the app)"))
        assertTrue(story.none { it.startsWith("Networks wired into it") && it.contains("Vungle") })
    }

    @Test fun exposureListsOnlyRequestedPermissionsAndKeepsGrantState() {
        val perms = listOf(
            app.ultimate.pluck.model.Permission("com.google.android.gms.permission.AD_ID", true, false),
            app.ultimate.pluck.model.Permission("android.permission.ACCESS_COARSE_LOCATION", false, true),
            app.ultimate.pluck.model.Permission("android.permission.CAMERA", true, true),
        )
        val e = app.ultimate.pluck.model.Explainer.exposures(perms)
        assertEquals(listOf("Advertising ID", "Approximate location"), e.map { it.label })
        assertEquals(listOf(true, false), e.map { it.granted })
    }
}
