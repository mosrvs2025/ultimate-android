package app.ultimate.pluck.engine

import app.ultimate.pluck.model.Explainer
import app.ultimate.pluck.model.Mediation
import java.util.zip.ZipFile

enum class SdkKind(val label: String) { Ads("Advertising"), Analytics("Analytics & attribution"), Crash("Crash reporting") }

data class Sdk(val name: String, val kind: SdkKind, val prefixes: List<String>)

/**
 * Looks for well-known ad, analytics and crash-reporting SDKs by searching an APK's dex files for their class-name prefixes.
 * This is a heuristic: renamed (obfuscated) SDKs are missed, and a match shows the code is present, not that it is active.
 */
object TrackerScanner {
    val catalog: List<Sdk> = listOf(
        Sdk("Google AdMob", SdkKind.Ads, listOf("com/google/android/gms/ads/")),
        Sdk("AppLovin", SdkKind.Ads, listOf("com/applovin/")),
        Sdk("Meta Audience Network", SdkKind.Ads, listOf("com/facebook/ads/")),
        Sdk("Unity Ads", SdkKind.Ads, listOf("com/unity3d/ads/", "com/unity3d/services/")),
        Sdk("ironSource", SdkKind.Ads, listOf("com/ironsource/")),
        Sdk("Liftoff / Vungle", SdkKind.Ads, listOf("com/vungle/")),
        Sdk("AdColony", SdkKind.Ads, listOf("com/adcolony/")),
        Sdk("Mintegral", SdkKind.Ads, listOf("com/mbridge/")),
        Sdk("InMobi", SdkKind.Ads, listOf("com/inmobi/")),
        Sdk("Chartboost", SdkKind.Ads, listOf("com/chartboost/")),
        Sdk("Pangle", SdkKind.Ads, listOf("com/bytedance/sdk/openadsdk/")),
        Sdk("StartApp", SdkKind.Ads, listOf("com/startapp/")),
        Sdk("Digital Turbine / Fyber", SdkKind.Ads, listOf("com/fyber/")),
        Sdk("Tapjoy", SdkKind.Ads, listOf("com/tapjoy/")),
        Sdk("Smaato", SdkKind.Ads, listOf("com/smaato/")),
        Sdk("Firebase Analytics", SdkKind.Analytics, listOf("com/google/firebase/analytics/")),
        Sdk("Google Analytics", SdkKind.Analytics, listOf("com/google/android/gms/measurement/")),
        Sdk("Meta App Events", SdkKind.Analytics, listOf("com/facebook/appevents/")),
        Sdk("AppsFlyer", SdkKind.Analytics, listOf("com/appsflyer/")),
        Sdk("Adjust", SdkKind.Analytics, listOf("com/adjust/sdk/")),
        Sdk("Branch", SdkKind.Analytics, listOf("io/branch/")),
        Sdk("Mixpanel", SdkKind.Analytics, listOf("com/mixpanel/")),
        Sdk("Amplitude", SdkKind.Analytics, listOf("com/amplitude/")),
        Sdk("AppMetrica", SdkKind.Analytics, listOf("io/appmetrica/", "com/yandex/metrica/")),
        Sdk("Flurry", SdkKind.Analytics, listOf("com/flurry/")),
        Sdk("Segment", SdkKind.Analytics, listOf("com/segment/analytics/")),
        Sdk("Singular", SdkKind.Analytics, listOf("com/singular/sdk/")),
        Sdk("Firebase Crashlytics", SdkKind.Crash, listOf("com/google/firebase/crashlytics/")),
        Sdk("Sentry", SdkKind.Crash, listOf("io/sentry/")),
        Sdk("Bugsnag", SdkKind.Crash, listOf("com/bugsnag/")),
    )

    // Dex stores class descriptors as "Lcom/example/Foo;", so a prefix is searched with a leading 'L'.
    private val needles: List<Pair<Sdk, List<ByteArray>>> =
        catalog.map { sdk -> sdk to sdk.prefixes.map { ("L$it").toByteArray(Charsets.US_ASCII) } }

    /** Known SDKs plus any mediation layers, which explain why an app lists many ad SDKs. */
    data class Analysis(val sdks: List<Sdk>, val mediation: List<Mediation>)

    fun analyze(apkPaths: List<String>): Analysis {
        val found = LinkedHashSet<Sdk>()
        val raw = Raw()
        for (path in apkPaths) {
            runCatching {
                ZipFile(path).use { zip ->
                    for (entry in zip.entries()) {
                        if (!isDex(entry.name)) continue
                        val dex = zip.getInputStream(entry).use { it.readBytes() }
                        found += findIn(dex)
                        raw.add(dex)
                    }
                }
            }
        }
        return Analysis(catalog.filter { it in found }, raw.mediation())
    }

    /** Mediation evidence gathered across dex files. */
    internal class Raw {
        val maxAdapters = sortedSetOf<String>()
        val admobAdapters = sortedSetOf<String>()
        val levelPlayAdapters = sortedSetOf<String>()
        var maxCalled = false
        var levelPlayCalled = false

        fun add(dex: ByteArray) {
            names(dex, "Lcom/applovin/mediation/adapters/", "MediationAdapter").forEach { maxAdapters += it }
            names(dex, "Lcom/google/ads/mediation/").forEach { admobAdapters += it }
            names(dex, "Lcom/ironsource/adapters/").forEach { levelPlayAdapters += it }
            if (indexOf(dex, "Lcom/applovin/mediation/ads/Max".toByteArray()) >= 0) maxCalled = true
            if (indexOf(dex, "Lcom/ironsource/mediationsdk/IronSource;".toByteArray()) >= 0) levelPlayCalled = true
        }

        fun mediation(): List<Mediation> = buildList {
            if (maxCalled || maxAdapters.isNotEmpty()) add(Mediation("AppLovin MAX", maxAdapters.map(Explainer::networkName).distinct().sorted(), maxCalled))
            val admob = admobAdapters.filter { it != "admob" }
            if (admob.isNotEmpty()) add(Mediation("AdMob mediation", admob.map(Explainer::networkName).distinct().sorted(), usedByApp = false))
            if (levelPlayCalled || levelPlayAdapters.isNotEmpty()) add(Mediation("ironSource LevelPlay", levelPlayAdapters.map(Explainer::networkName).distinct().sorted(), levelPlayCalled))
        }
    }

    /**
     * Reads the package or class names that follow [prefix] in a dex's class descriptors.
     * With a [suffix], the name is a class such as "Lx/FooMediationAdapter;" and the result is "Foo".
     * Without one, the name is a package such as "Lx/foo/" and the result is "foo".
     * Names are letters and digits only, so a longer unrelated class is never misread as a match.
     */
    internal fun names(dex: ByteArray, prefix: String, suffix: String? = null): Set<String> {
        val needle = prefix.toByteArray(Charsets.US_ASCII)
        val stop = if (suffix == null) '/'.code.toByte() else ';'.code.toByte()
        val out = sortedSetOf<String>()
        var from = 0
        while (true) {
            val at = indexOf(dex, needle, from)
            if (at < 0) break
            var i = at + needle.size
            val start = i
            while (i < dex.size && isNameByte(dex[i])) i++
            if (i > start && i < dex.size && dex[i] == stop) {
                val word = String(dex, start, i - start, Charsets.US_ASCII)
                when {
                    suffix == null -> out += word
                    word.endsWith(suffix) && word.length > suffix.length -> out += word.removeSuffix(suffix)
                }
            }
            from = at + needle.size
        }
        return out
    }

    private fun isNameByte(b: Byte): Boolean = (b in 'a'.code.toByte()..'z'.code.toByte()) || (b in 'A'.code.toByte()..'Z'.code.toByte()) || (b in '0'.code.toByte()..'9'.code.toByte())

    fun scan(apkPaths: List<String>): List<Sdk> {
        val found = LinkedHashSet<Sdk>()
        for (path in apkPaths) {
            runCatching {
                ZipFile(path).use { zip ->
                    for (entry in zip.entries()) {
                        if (!isDex(entry.name)) continue
                        found += findIn(zip.getInputStream(entry).use { it.readBytes() })
                    }
                }
            }
        }
        return catalog.filter { it in found }
    }

    fun findIn(dex: ByteArray): Set<Sdk> =
        needles.filter { (_, patterns) -> patterns.any { indexOf(dex, it) >= 0 } }.map { it.first }.toSet()

    private fun isDex(name: String) = !name.contains('/') && name.startsWith("classes") && name.endsWith(".dex")

    /** Boyer–Moore–Horspool: fast enough to scan a multi-megabyte dex for each pattern. */
    internal fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        val n = needle.size
        if (n == 0 || haystack.size < n) return -1
        val skip = IntArray(256) { n }
        for (i in 0 until n - 1) skip[needle[i].toInt() and 0xFF] = n - 1 - i
        var i = from
        while (i <= haystack.size - n) {
            var j = n - 1
            while (j >= 0 && haystack[i + j] == needle[j]) j--
            if (j < 0) return i
            i += skip[haystack[i + n - 1].toInt() and 0xFF]
        }
        return -1
    }
}
