package app.ultimate.checkup.engine

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager
import app.ultimate.checkup.model.Access
import app.ultimate.checkup.model.AppFacts
import app.ultimate.checkup.model.DeviceFacts
import java.io.File

/**
 * Reads what Android lets an ordinary app see about the phone and its apps. No root, no network. Anything the system will not tell
 * us is left null ("unknown") rather than guessed.
 */
class Collector(private val context: Context) {
    private val pm = context.packageManager

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun device(): DeviceFacts {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { b -> val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / s else null }
        val health = battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)?.let(::healthName)
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val cycles = battery?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val encryption = runCatching { dpm.storageEncryptionStatus }.getOrNull()
        val resolver = context.contentResolver
        return DeviceFacts(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
            kernel = System.getProperty("os.version"),
            chip = if (Build.VERSION.SDK_INT >= 31) listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ").ifBlank { null } else Build.HARDWARE,
            ramBytes = mem.totalMem,
            ramFreeBytes = mem.availMem,
            storageBytes = stat.totalBytes,
            storageFreeBytes = stat.availableBytes,
            batteryPercent = level,
            batteryHealth = health,
            batteryTempC = temp,
            batteryCycles = cycles,
            charging = status?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL },
            uptimeMs = SystemClock.elapsedRealtime(),
            screenLock = runCatching { context.getSystemService(KeyguardManager::class.java).isDeviceSecure }.getOrNull(),
            encrypted = encryption?.let { it == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE || it == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER },
            developerOptions = runCatching { Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrNull(),
            usbDebugging = runCatching { Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrNull(),
            locationOn = runCatching { context.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrNull(),
            rootSigns = rootSigns(),
            defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull(),
            defaultDialer = runCatching { context.getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull(),
            defaultBrowser = defaultFor(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
            defaultLauncher = defaultFor(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)),
        )
    }

    /** Every installed app with what it has been granted. [onProgress] reports done/total as apps are read. */
    fun apps(adSdks: Map<String, List<String>>?, usage: Boolean, onProgress: (Int, Int) -> Unit): List<AppFacts> {
        val packages = installed()
        val a11y = enabledAccessibility()
        val notif = notificationListeners()
        val notifCapable = servicesWith("android.service.notification.NotificationListenerService")
        val vpn = servicesWith("android.net.VpnService")
        val admins = runCatching { context.getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty().map { it.packageName }.toSet() }.getOrDefault(emptySet())
        val usageStats = if (usage) usage() else emptyMap()
        val storage = if (usage) context.getSystemService(StorageStatsManager::class.java) else null
        return packages.mapIndexed { i, p ->
            if (i % 10 == 0) onProgress(i, packages.size)
            val info = p.applicationInfo
            val granted = buildSet {
                val names = p.requestedPermissions.orEmpty()
                val flags = p.requestedPermissionsFlags ?: IntArray(0)
                names.forEachIndexed { k, name ->
                    val isGranted = k < flags.size && flags[k] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
                    if (isGranted) Access.byPermission[name]?.let(::add)
                }
            }
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(p.packageName).installingPackageName else @Suppress("DEPRECATION") pm.getInstallerPackageName(p.packageName)
            }.getOrNull()
            val stats = usageStats[p.packageName]
            AppFacts(
                packageName = p.packageName,
                label = info?.loadLabel(pm)?.toString() ?: p.packageName,
                system = info != null && info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                installer = installer,
                versionName = p.versionName.orEmpty(),
                targetSdk = info?.targetSdkVersion ?: 0,
                installedAt = p.firstInstallTime,
                updatedAt = p.lastUpdateTime,
                sizeBytes = info?.let { appSize(it) } ?: 0,
                granted = granted,
                accessibility = p.packageName in a11y,
                notificationAccess = notif?.contains(p.packageName),
                canReadNotifications = p.packageName in notifCapable,
                deviceAdmin = p.packageName in admins,
                providesVpn = p.packageName in vpn,
                canInstallApps = p.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"),
                adSdks = adSdks?.get(p.packageName),
                usedMs7d = if (usage) stats?.first ?: 0L else null,
                lastUsedAt = if (usage) stats?.second ?: 0L else null,
                storageBytes = storage?.let { s -> info?.let { runCatching { s.queryStatsForPackage(it.storageUuid ?: StorageManager.UUID_DEFAULT, p.packageName, Process.myUserHandle()).let { st -> st.appBytes + st.dataBytes + st.cacheBytes } }.getOrNull() } },
            )
        }.also { onProgress(packages.size, packages.size) }
    }

    @Suppress("DEPRECATION")
    private fun installed(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        else pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

    private fun appSize(info: ApplicationInfo): Long =
        (listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()).sumOf { runCatching { File(it).length() }.getOrDefault(0L) }

    private fun enabledAccessibility(): Set<String> = runCatching {
        context.getSystemService(AccessibilityManager::class.java).getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** Apps with notification access switched on, or null if this Android version does not let other apps read the setting. */
    private fun notificationListeners(): Set<String>? = runCatching {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.filter { it.isNotBlank() }?.map { it.substringBefore('/') }?.toSet()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun servicesWith(action: String): Set<String> = runCatching {
        val intent = Intent(action)
        val found = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0)) else pm.queryIntentServices(intent, 0)
        found.mapNotNull { it.serviceInfo?.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** Package → (foreground ms in the last 7 days, last time used). */
    private fun usage(): Map<String, Pair<Long, Long>> = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val week = usm.queryAndAggregateUsageStats(now - 7 * DAY, now)
        val month = usm.queryAndAggregateUsageStats(now - 60 * DAY, now)
        (week.keys + month.keys).associateWith { pkg -> (week[pkg]?.totalTimeInForeground ?: 0L) to (month[pkg]?.lastTimeUsed ?: 0L) }
    }.getOrDefault(emptyMap())

    @Suppress("DEPRECATION")
    private fun defaultFor(intent: Intent): String? = runCatching {
        val r = if (Build.VERSION.SDK_INT >= 33) pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        else pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        r?.activityInfo?.packageName?.takeIf { it != "android" }
    }.getOrNull()

    private fun rootSigns(): List<String> = buildList {
        if (Build.TAGS?.contains("test-keys") == true) add("test-keys build")
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk", "/data/adb/magisk").forEach { if (runCatching { File(it).exists() }.getOrDefault(false)) add(it) }
    }

    private fun healthName(code: Int): String? = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        else -> null
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
