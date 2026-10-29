package app.ultimate.checkup.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Sensitive things an app can be allowed to reach, grouped the way Android's own settings group them. */
enum class Access(val label: String, val blurb: String) {
    Location("Location", "Where you are"),
    BackgroundLocation("Location all the time", "Where you are, even when the app is closed"),
    Camera("Camera", "Take photos and video"),
    Microphone("Microphone", "Record audio"),
    Contacts("Contacts", "Your contacts"),
    Sms("SMS", "Read or send text messages"),
    CallLog("Call log", "Who you called and when"),
    Phone("Phone", "Make calls and read phone state"),
    Calendar("Calendar", "Your events"),
    Media("Photos, videos and files", "Your photos, videos, music and files"),
    Body("Body sensors and activity", "Heart rate, steps and movement"),
    Nearby("Nearby devices", "Find and connect to nearby devices"),
    ;

    companion object {
        /** Android permission names to the access they grant. */
        val byPermission: Map<String, Access> = mapOf(
            "android.permission.ACCESS_FINE_LOCATION" to Location,
            "android.permission.ACCESS_COARSE_LOCATION" to Location,
            "android.permission.ACCESS_BACKGROUND_LOCATION" to BackgroundLocation,
            "android.permission.CAMERA" to Camera,
            "android.permission.RECORD_AUDIO" to Microphone,
            "android.permission.READ_CONTACTS" to Contacts,
            "android.permission.WRITE_CONTACTS" to Contacts,
            "android.permission.GET_ACCOUNTS" to Contacts,
            "android.permission.READ_SMS" to Sms,
            "android.permission.SEND_SMS" to Sms,
            "android.permission.RECEIVE_SMS" to Sms,
            "android.permission.RECEIVE_MMS" to Sms,
            "android.permission.READ_CALL_LOG" to CallLog,
            "android.permission.WRITE_CALL_LOG" to CallLog,
            "android.permission.CALL_PHONE" to Phone,
            "android.permission.READ_PHONE_STATE" to Phone,
            "android.permission.READ_PHONE_NUMBERS" to Phone,
            "android.permission.ANSWER_PHONE_CALLS" to Phone,
            "android.permission.READ_CALENDAR" to Calendar,
            "android.permission.WRITE_CALENDAR" to Calendar,
            "android.permission.READ_EXTERNAL_STORAGE" to Media,
            "android.permission.WRITE_EXTERNAL_STORAGE" to Media,
            "android.permission.READ_MEDIA_IMAGES" to Media,
            "android.permission.READ_MEDIA_VIDEO" to Media,
            "android.permission.READ_MEDIA_AUDIO" to Media,
            "android.permission.BODY_SENSORS" to Body,
            "android.permission.ACTIVITY_RECOGNITION" to Body,
            "android.permission.BLUETOOTH_SCAN" to Nearby,
            "android.permission.BLUETOOTH_CONNECT" to Nearby,
            "android.permission.NEARBY_WIFI_DEVICES" to Nearby,
            "android.permission.UWB_RANGING" to Nearby,
        )
    }
}

/** Facts about the phone itself. Null means the phone did not say. */
data class DeviceFacts(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    /** yyyy-MM-dd, as Android reports it. */
    val securityPatch: String?,
    val kernel: String?,
    val chip: String?,
    val ramBytes: Long,
    val ramFreeBytes: Long,
    val storageBytes: Long,
    val storageFreeBytes: Long,
    val batteryPercent: Int?,
    val batteryHealth: String?,
    val batteryTempC: Float?,
    val batteryCycles: Int?,
    val charging: Boolean?,
    val uptimeMs: Long,
    val screenLock: Boolean?,
    val encrypted: Boolean?,
    val developerOptions: Boolean?,
    val usbDebugging: Boolean?,
    val locationOn: Boolean?,
    val rootSigns: List<String>,
    val defaultSms: String?,
    val defaultDialer: String?,
    val defaultBrowser: String?,
    val defaultLauncher: String?,
)

/** Facts about one installed app. */
data class AppFacts(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val installer: String?,
    val versionName: String,
    val targetSdk: Int,
    val installedAt: Long,
    val updatedAt: Long,
    val sizeBytes: Long,
    val granted: Set<Access>,
    /** Has an accessibility service switched on: it can read and act on everything on screen. */
    val accessibility: Boolean = false,
    /** Has notification access switched on, or null when Android would not say. */
    val notificationAccess: Boolean? = null,
    val canReadNotifications: Boolean = false,
    val deviceAdmin: Boolean = false,
    val providesVpn: Boolean = false,
    val canInstallApps: Boolean = false,
    /** Ad SDKs found in its code by Pluck's scan, when that has run. */
    val adSdks: List<String>? = null,
    /** Foreground time in the last 7 days, when usage access is on. */
    val usedMs7d: Long? = null,
    /** Last time it was opened, when usage access is on (0 = never seen in the usage history). */
    val lastUsedAt: Long? = null,
    /** App + data + cache on disk, when usage access is on. */
    val storageBytes: Long? = null,
) {
    val sideloaded: Boolean get() = !system && (installer == null || installer !in Checkup.STORES)
}

enum class Severity(val weight: Int) { Critical(20), Warning(7), Info(0), Good(0) }

enum class Category(val label: String) { Security("Security"), Privacy("Privacy"), Apps("Apps"), Device("Device") }

/** Where a finding's button goes. The UI turns these into Android settings screens. */
sealed interface Fix {
    data object SecuritySettings : Fix
    data object UpdateSettings : Fix
    data object DeveloperSettings : Fix
    data object AccessibilitySettings : Fix
    data object NotificationAccessSettings : Fix
    data object DeviceAdminSettings : Fix
    data object UsageAccess : Fix
    data object StorageSettings : Fix
    data object BatterySettings : Fix
    data class AppSettings(val packageName: String) : Fix
}

data class Finding(
    val id: String,
    val severity: Severity,
    val category: Category,
    val title: String,
    val detail: String,
    val apps: List<String> = emptyList(),
    val fix: Fix? = null,
    val fixLabel: String? = null,
)

data class Scan(val at: Long, val device: DeviceFacts, val apps: List<AppFacts>, val usageAccess: Boolean, val findings: List<Finding>) {
    val score: Int get() = Checkup.score(findings)
}

/** Turns facts into plain-language findings. Pure, so every rule is unit-tested. */
object Checkup {
    /** Installers that review apps before publishing them. */
    val STORES = setOf(
        "com.android.vending", "com.google.android.packageinstaller.store", "com.sec.android.app.samsungapps", "com.amazon.venezia",
        "com.huawei.appmarket", "com.xiaomi.market", "com.heytap.market", "com.oppo.market", "org.fdroid.fdroid", "com.aurora.store",
    )
    private const val DAY = 24L * 60 * 60 * 1000

    fun score(findings: List<Finding>): Int = (100 - findings.sumOf { it.severity.weight }).coerceIn(0, 100)

    fun verdict(score: Int) = when {
        score >= 90 -> "Looking great"
        score >= 75 -> "Good, a few things to check"
        score >= 50 -> "Needs attention"
        else -> "At risk"
    }

    /** Days since the security patch, or null when unknown. */
    fun patchAgeDays(patch: String?, today: LocalDate): Long? =
        patch?.let { runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it), today) }.getOrNull() }

    fun findings(d: DeviceFacts, apps: List<AppFacts>, now: Long, usageAccess: Boolean, ownPackages: Set<String> = emptySet()): List<Finding> = buildList {
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val user = apps.filter { !it.system && it.packageName !in ownPackages }
        fun names(list: List<AppFacts>) = list.sortedBy { it.label.lowercase() }.map { it.packageName }

        // ── security ──
        when (d.screenLock) {
            false -> add(Finding("lock", Severity.Critical, Category.Security, "No screen lock",
                "Anyone who picks up your phone can open everything on it. Set a PIN, pattern or password.", fix = Fix.SecuritySettings, fixLabel = "Set screen lock"))
            true -> add(Finding("lock", Severity.Good, Category.Security, "Screen lock is on", "Your phone is protected when it is locked."))
            null -> Unit
        }
        val patchAge = patchAgeDays(d.securityPatch, today)
        when {
            patchAge == null -> Unit
            patchAge > 180 -> add(Finding("patch", Severity.Critical, Category.Security, "Security updates are ${patchAge / 30} months old",
                "The last security patch is from ${d.securityPatch}. Known security holes fixed since then are still open on this phone. Check for a system update; if none comes, the phone may no longer be supported.",
                fix = Fix.UpdateSettings, fixLabel = "Check for updates"))
            patchAge > 90 -> add(Finding("patch", Severity.Warning, Category.Security, "Security update is ${patchAge / 30} months old",
                "The last security patch is from ${d.securityPatch}. Phones usually get one every month or two.", fix = Fix.UpdateSettings, fixLabel = "Check for updates"))
            else -> add(Finding("patch", Severity.Good, Category.Security, "Security patch is recent", "Installed patch: ${d.securityPatch}."))
        }
        if (d.encrypted == false) add(Finding("encryption", Severity.Critical, Category.Security, "Storage is not encrypted",
            "Someone with the phone could read its storage directly. Encryption normally switches on with a screen lock.", fix = Fix.SecuritySettings, fixLabel = "Security settings"))
        if (d.usbDebugging == true) add(Finding("adb", Severity.Warning, Category.Security, "USB debugging is on",
            "A computer you plug into can install apps and read data once you allow it. Turn it off unless you are developing.", fix = Fix.DeveloperSettings, fixLabel = "Developer options"))
        else if (d.developerOptions == true) add(Finding("dev", Severity.Info, Category.Security, "Developer options are on",
            "Harmless by itself, but it exposes settings that can weaken security.", fix = Fix.DeveloperSettings, fixLabel = "Developer options"))
        if (d.rootSigns.isNotEmpty()) add(Finding("root", Severity.Warning, Category.Security, "This phone may be rooted",
            "Found: ${d.rootSigns.joinToString()}. Root access lets apps bypass Android's protections. Ignore this if you rooted it on purpose."))

        // ── privacy: special access ──
        val a11y = user.filter { it.accessibility }
        if (a11y.isNotEmpty()) add(Finding("a11y", Severity.Warning, Category.Privacy, "${a11y.size} app${s(a11y.size)} can see and control your screen",
            "Accessibility access lets an app read everything shown, including messages and passwords as you type, and tap for you. Keep it only for apps you trust that need it.",
            names(a11y), Fix.AccessibilitySettings, "Review accessibility"))
        val notif = user.filter { it.notificationAccess == true }
        if (notif.isNotEmpty()) add(Finding("notif", Severity.Warning, Category.Privacy, "${notif.size} app${s(notif.size)} read all your notifications",
            "They see every message preview, code and alert that arrives, from every app.", names(notif), Fix.NotificationAccessSettings, "Review notification access"))
        val admins = user.filter { it.deviceAdmin }
        if (admins.isNotEmpty()) add(Finding("admin", Severity.Warning, Category.Privacy, "${admins.size} app${s(admins.size)} ${if (admins.size == 1) "is a" else "are"} device admin",
            "Device admins can lock or wipe the phone and are harder to uninstall. Expected for work profiles and Find My Device; suspicious otherwise.",
            names(admins), Fix.DeviceAdminSettings, "Review device admins"))
        val bgLocation = user.filter { Access.BackgroundLocation in it.granted }
        if (bgLocation.isNotEmpty()) add(Finding("bgloc", Severity.Warning, Category.Privacy, "${bgLocation.size} app${s(bgLocation.size)} track your location all the time",
            "They can see where you are even when you are not using them. \"Only while using the app\" is enough for most.", names(bgLocation)))
        val mic = user.filter { Access.Microphone in it.granted }
        if (mic.size >= 5) add(Finding("mic", Severity.Info, Category.Privacy, "${mic.size} apps may use the microphone", "Worth a look: remove it from apps that do not need to hear you.", names(mic)))
        val sms = user.filter { Access.Sms in it.granted || Access.CallLog in it.granted }
        if (sms.isNotEmpty()) add(Finding("sms", Severity.Info, Category.Privacy, "${sms.size} app${s(sms.size)} can read texts or call history",
            "Texts often carry login codes. Only your messaging and phone apps should need this.", names(sms)))

        // ── apps ──
        val sideloaded = user.filter { it.sideloaded }
        if (sideloaded.isNotEmpty()) add(Finding("sideload", Severity.Info, Category.Apps, "${sideloaded.size} app${s(sideloaded.size)} came from outside an app store",
            "Installed from a file or an unknown source, so nobody reviewed them. Fine if you know where they came from.", names(sideloaded)))
        val installers = user.filter { it.canInstallApps }
        if (installers.isNotEmpty()) add(Finding("installers", Severity.Info, Category.Apps, "${installers.size} app${s(installers.size)} can ask to install other apps",
            "Browsers and file managers commonly can. Android still asks you before each install.", names(installers)))
        val old = user.filter { it.targetSdk in 1..27 }
        if (old.isNotEmpty()) add(Finding("oldtarget", Severity.Warning, Category.Apps, "${old.size} app${s(old.size)} built for Android 8 or older",
            "Apps made for old Android skip newer privacy rules, such as limits on background location and storage. They have probably not been updated in years.", names(old)))
        val stale = user.filter { it.updatedAt > 0 && now - it.updatedAt > 730 * DAY }
        if (stale.isNotEmpty()) add(Finding("stale", Severity.Info, Category.Apps, "${stale.size} app${s(stale.size)} not updated in over 2 years",
            "Abandoned apps don't get security fixes.", names(stale)))
        val ads = user.filter { !it.adSdks.isNullOrEmpty() }
        if (ads.isNotEmpty()) add(Finding("ads", Severity.Info, Category.Apps, "${ads.size} app${s(ads.size)} contain advertising SDKs",
            "Found in their code by Pluck's scan. Ad SDKs often collect device identifiers and usage.", names(ads)))
        if (usageAccess) {
            val unused = user.filter { it.lastUsedAt != null && now - it.installedAt > 30 * DAY && (it.lastUsedAt == 0L || now - it.lastUsedAt > 30 * DAY) }
            if (unused.isNotEmpty()) add(Finding("unused", Severity.Info, Category.Apps, "${unused.size} app${s(unused.size)} not opened in 30 days",
                "Uninstalling them frees ${formatSize(unused.sumOf { it.storageBytes ?: it.sizeBytes })} and removes their access.", names(unused)))
        } else {
            add(Finding("usage", Severity.Info, Category.Apps, "Turn on usage access for more",
                "Lets this checkup see screen time, apps you never open, and how much space each app really uses. Nothing leaves your phone.",
                fix = Fix.UsageAccess, fixLabel = "Allow usage access"))
        }

        // ── device ──
        if (d.storageBytes > 0) {
            val freePct = d.storageFreeBytes * 100.0 / d.storageBytes
            if (freePct < 10) add(Finding("storage", Severity.Warning, Category.Device, "Storage is almost full (${freePct.roundToInt()}% free)",
                "A full phone slows down and can fail to install updates.", fix = Fix.StorageSettings, fixLabel = "Free up space"))
        }
        d.batteryHealth?.takeIf { it != "Good" }?.let { h ->
            add(Finding("battery", Severity.Warning, Category.Device, "Battery health: $h", "The battery reports a problem. It may need replacing.", fix = Fix.BatterySettings, fixLabel = "Battery settings"))
        }
        d.batteryCycles?.takeIf { it >= 800 }?.let { c ->
            add(Finding("cycles", Severity.Info, Category.Device, "Battery has been through $c charge cycles", "Most phone batteries hold about 80% of their capacity after 500–800 cycles."))
        }
        d.batteryTempC?.takeIf { it >= 45f }?.let { t ->
            add(Finding("hot", Severity.Warning, Category.Device, "Battery is hot (${t.roundToInt()} °C)", "Heat wears batteries out. Let it cool, and avoid charging while it is hot."))
        }
    }

    /** Which apps hold each sensitive access, most-held first. */
    fun privacyMap(apps: List<AppFacts>, ownPackages: Set<String> = emptySet()): List<Pair<Access, List<AppFacts>>> =
        Access.entries.map { a -> a to apps.filter { !it.system && it.packageName !in ownPackages && a in it.granted }.sortedBy { it.label.lowercase() } }
            .filter { it.second.isNotEmpty() }.sortedByDescending { it.second.size }

    private fun s(n: Int) = if (n == 1) "" else "s"

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes shr 20} MB"
        else -> "${bytes shr 10} KB"
    }
}
