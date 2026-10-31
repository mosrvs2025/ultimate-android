package app.ultimate.footprint

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import app.ultimate.checkup.engine.Collector
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One app and what it is allowed to do. */
class AppAccess(val pkg: String, val label: String, val system: Boolean, val access: Set<Access>)

/** One fact an app can read without being asked, and why that matters. */
class Fact(val name: String, val value: String, val why: String)

/** Reads the real thing from this phone. Everything stays on the phone; nothing here uses the network. */
object FootprintReaders {
    const val DAYS = 7

    fun hasUsageAccess(context: Context) = Collector(context).hasUsageAccess()

    private fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    /** Packages that are the home screen or system chrome: opening them is not "using an app". */
    private fun chrome(context: Context): Set<String> {
        val home = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }
        return (home + listOf("android", "com.android.systemui")).toSet()
    }

    /** The week's patterns from Android's usage log, or null without usage access. */
    fun usage(context: Context): UsageSummary? {
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val skip = chrome(context)
        val acc = UsageAccumulator(now, TimeZone.getDefault(), DAYS)
        val events = usm.queryEvents(startOfDay(DAYS - 1), now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (e.packageName !in skip) acc.add(UsageEvt(e.timeStamp, Evt.Resumed, e.packageName)) else acc.add(UsageEvt(e.timeStamp, Evt.Idle))
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName !in skip) acc.add(UsageEvt(e.timeStamp, Evt.Paused, e.packageName))
                UsageEvents.Event.SCREEN_INTERACTIVE -> acc.add(UsageEvt(e.timeStamp, Evt.ScreenOn))
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> acc.add(UsageEvt(e.timeStamp, Evt.ScreenOff))
                UsageEvents.Event.KEYGUARD_HIDDEN -> acc.add(UsageEvt(e.timeStamp, Evt.Unlocked))
            }
        }
        return acc.finish()
    }

    fun label(context: Context, pkg: String) = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** Every app with an icon on the home screen, and what it can do. */
    fun appAccess(context: Context): List<AppAccess> {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
        // One app at a time: asking for every app's permissions in a single call can exceed Android's size limit on phones with many apps.
        return launchable.filter { it != context.packageName }.mapNotNull { pkg ->
            runCatching {
                val p = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                AppAccess(pkg, label(context, pkg), (p.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0, PermissionMap.granted(p.requestedPermissions?.toList(), p.requestedPermissionsFlags))
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    /** Data each app sent and received over the last week (Wi-Fi and mobile), or null without usage access. */
    fun network(context: Context): List<NetUse>? {
        if (!hasUsageAccess(context)) return null
        val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis(); val from = startOfDay(DAYS - 1)
        val buckets = ArrayList<NetMath.Bucket>()
        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) runCatching {
            nsm.querySummary(type, null, from, now).use { stats ->
                val b = NetworkStats.Bucket()
                while (stats.hasNextBucket()) { stats.getNextBucket(b); buckets += NetMath.Bucket(b.uid, b.rxBytes, b.txBytes) }
            }
        }
        val pm = context.packageManager
        return NetMath.byApp(buckets) { uid -> pm.getPackagesForUid(uid)?.firstOrNull() }
    }

    /** What any app can read about this phone and its owner with no permission prompt at all. */
    fun facts(context: Context): List<Fact> = buildList {
        val pm = context.packageManager
        fun add(n: String, v: String?, why: String) { if (!v.isNullOrBlank()) add(Fact(n, v, why)) }
        add("Your phone", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", "Exactly which model you own, which hints at your budget and how old it is.")
        add("Android and security patch", "Android ${Build.VERSION.RELEASE}, patch ${Build.VERSION.SECURITY_PATCH}", "How up to date your phone is, and which known holes it still has.")
        add("Language and region", "${Locale.getDefault().displayName}, ${Locale.getDefault().displayCountry}", "Where you probably live and what you speak.")
        add("Time zone", TimeZone.getDefault().id, "A rough place in the world. It changes when you travel.")
        val dm = context.resources.displayMetrics
        add("Screen", "${dm.widthPixels} x ${dm.heightPixels}, ${dm.densityDpi} dpi, text size ${"%.2f".format(context.resources.configuration.fontScale)}x", "Part of a fingerprint that is almost unique. Text size can hint at your eyesight.")
        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        add("Memory and storage", "${"%.1f".format(mem.totalMem / 1e9)} GB memory, ${"%.0f".format(StatFs(Environment.getDataDirectory().path).totalBytes / 1e9)} GB storage", "More fingerprint, and how much you can afford.")
        add("Processor", "${Runtime.getRuntime().availableProcessors()} cores, ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}", "Another piece of the fingerprint.")
        add("Build fingerprint", Build.FINGERPRINT, "A string unique to your exact software version.")
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
            val pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            add("Battery", "$pct%, ${if (plugged) "charging" else "not charging"}, ${b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0} C", "Shows when you sleep (charging at night) and when you are at home or at work.")
        }
        add("Last restart", "${(SystemClock.elapsedRealtime() / 3_600_000L)} hours ago, restarted ${Settings.Global.getInt(context.contentResolver, "boot_count", -1).takeIf { it >= 0 } ?: "?"} times", "How you treat your phone, and whether it has been patched since.")
        add("Installed apps", "${pm.getInstalledApplications(0).size} apps, every name visible", "Your apps reveal your bank, health, dating, religion, politics and job.")
        add("Android ID", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID), "A number that stays the same for every app signed by the same developer, so they can recognise you across their apps.")
        context.getSystemService(TelephonyManager::class.java)?.networkOperatorName?.takeIf { it.isNotBlank() }?.let { add("Mobile carrier", it, "Who you pay, and a hint of your country.") }
        add("Sensors", "${context.getSystemService(SensorManager::class.java)?.getSensorList(Sensor.TYPE_ALL)?.size ?: 0} sensors, motion and light readable with no permission", "Motion data can reveal when you walk, drive, or even type.")
        val am = context.getSystemService(AudioManager::class.java)
        add("Listening now", if (am.isMusicActive) "Something is playing" else "Nothing is playing", "Apps can tell when you are listening to something.")
        add("Headphones", if (am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) "Connected" else "Not connected", "A sign you are out, commuting or exercising.")
        add("Ringer", when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "Silent"; AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"; else -> "Normal" }, "Whether you are in a meeting or asleep.")
        add("Do Not Disturb", context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let { if (it == NotificationManager.INTERRUPTION_FILTER_ALL) "Off" else "On" }, "Another way to tell when you are busy or sleeping.")
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.takeIf { it.isNotBlank() }?.let { add("Accessibility tools on", it.split(':').size.toString() + " running", "Can reveal a disability, and are powerful: they can read the screen.") }
        add("Default keyboard", Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/'), "The app that sees everything you type.")
        add("Developer options", if (Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0) "On" else "Off", "Says how technical you are.")
    }
}
