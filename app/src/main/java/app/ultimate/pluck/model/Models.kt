package app.ultimate.pluck.model

/** One installed app as the package manager reports it. [apkPaths] lists the base APK first, then any split APKs. */
data class AppItem(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val enabled: Boolean,
    val apkPaths: List<String>,
    val apkBytes: Long,
    val installTime: Long,
    val updateTime: Long,
    val installer: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val permissionCount: Int,
    val dangerousCount: Int,
) {
    val isSplit: Boolean get() = apkPaths.size > 1
    val source: AppSource get() = AppSource.of(installer, isSystem)
}

enum class AppSource(val label: String) {
    System("System"), PlayStore("Play Store"), OtherStore("Other store"), Sideloaded("Sideloaded");

    companion object {
        private val stores = setOf(
            "com.sec.android.app.samsungapps", "com.amazon.venezia", "org.fdroid.fdroid", "com.huawei.appmarket",
            "com.xiaomi.mipicks", "com.heytap.market", "com.aurora.store", "com.apkpure.aegon",
        )
        private val installers = setOf("com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.apps.nbu.files")

        fun of(installer: String?, isSystem: Boolean): AppSource = when {
            installer == "com.android.vending" -> PlayStore
            installer in stores -> OtherStore
            installer == null && isSystem -> System
            installer == null || installer in installers -> Sideloaded
            isSystem -> System
            else -> OtherStore
        }
    }
}

enum class Filter(val label: String) { User("User"), System("System"), All("All") }

enum class Sort(val label: String) {
    Name("Name"), Size("Size"), Updated("Recently updated"), Installed("Recently installed"),
    Trackers("Most trackers"), Permissions("Most sensitive permissions"), TargetSdk("Oldest Android target"),
}

/** Extra filters that can be combined. Tracker filters only see apps that have been scanned. */
enum class QuickFilter(val label: String) {
    Replaced("Replaced"), HasAds("Has ads"), Sideloaded("Sideloaded"), PlayStore("Play Store"), Split("Split APK"), Under30("Under 30 MB"), Disabled("Disabled"),
}

/** Everything that decides which apps the list shows and in what order. */
data class ListOptions(
    val filter: Filter = Filter.User,
    val sort: Sort = Sort.Name,
    val reversed: Boolean = false,
    val quick: Set<QuickFilter> = emptySet(),
)

/** Tracker facts for one app, when it has been scanned. */
data class TrackerFacts(val sdks: List<String>, val adSdks: Int, val mediation: List<Mediation>? = null)

fun List<AppItem>.arrange(query: String, o: ListOptions, trackers: Map<String, TrackerFacts>, replaced: Set<String> = emptySet()): List<AppItem> {
    val shown = filter { a ->
        o.filter.accepts(a) && a.matches(query) && o.quick.all { q ->
            when (q) {
                QuickFilter.Replaced -> a.packageName in replaced
                QuickFilter.HasAds -> (trackers[a.packageName]?.adSdks ?: 0) > 0
                QuickFilter.Sideloaded -> a.source == AppSource.Sideloaded
                QuickFilter.PlayStore -> a.source == AppSource.PlayStore
                QuickFilter.Split -> a.isSplit
                QuickFilter.Under30 -> a.apkBytes < 30_000_000
                QuickFilter.Disabled -> !a.enabled
            }
        }
    }
    val sorted = when (o.sort) {
        Sort.Trackers -> shown.sortedWith(compareByDescending<AppItem> { trackers[it.packageName]?.sdks?.size ?: -1 }.thenBy { it.label.lowercase() })
        Sort.Permissions -> shown.sortedWith(compareByDescending<AppItem> { it.dangerousCount }.thenBy { it.label.lowercase() })
        Sort.TargetSdk -> shown.sortedWith(compareBy<AppItem> { it.targetSdk }.thenBy { it.label.lowercase() })
        else -> shown.sortedBy(o.sort)
    }
    return if (o.reversed) sorted.reversed() else sorted
}

/**
 * Rough size of an APK once packed into a maximum-compression zip. Entries an APK already deflates barely shrink;
 * entries it stores uncompressed (dex, native libraries, resources.arsc, which Android wants uncompressed for speed)
 * typically shrink to about 40%. Measured on several real APKs: 50 to 53% smaller overall.
 */
fun estimateCompact(storedBytes: Long, compressedBytes: Long): Long = (storedBytes * 0.42 + compressedBytes * 0.98).toLong()

data class Permission(val name: String, val granted: Boolean, val dangerous: Boolean) {
    val short: String get() = name.substringAfterLast('.')
}

data class ApkFile(val name: String, val bytes: Long)

/** The slower-to-compute facts shown on the detail screen. Null counts mean the package manager refused to list them. */
data class AppDetail(
    val item: AppItem,
    val signatures: List<String>,
    val permissions: List<Permission>,
    val activities: Int?,
    val services: Int?,
    val receivers: Int?,
    val providers: Int?,
    val abis: List<String>,
    val apks: List<ApkFile>,
    val uid: Int,
    val enabled: Boolean,
)

fun AppItem.matches(query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || label.contains(q, ignoreCase = true) || packageName.contains(q, ignoreCase = true)
}

fun List<AppItem>.sortedBy(sort: Sort): List<AppItem> = when (sort) {
    Sort.Name -> sortedBy { it.label.lowercase() }
    Sort.Size -> sortedByDescending { it.apkBytes }
    Sort.Updated -> sortedByDescending { it.updateTime }
    Sort.Installed -> sortedByDescending { it.installTime }
    // These need tracker data or tie-breaks, so arrange() handles them.
    Sort.Trackers, Sort.Permissions, Sort.TargetSdk -> sortedBy { it.label.lowercase() }
}

fun Filter.accepts(item: AppItem): Boolean = when (this) {
    Filter.User -> !item.isSystem
    Filter.System -> item.isSystem
    Filter.All -> true
}
