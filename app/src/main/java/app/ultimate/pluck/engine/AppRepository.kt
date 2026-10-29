package app.ultimate.pluck.engine

import app.ultimate.core.*
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import app.ultimate.pluck.model.ApkFile
import app.ultimate.pluck.model.AppDetail
import app.ultimate.pluck.model.AppItem
import app.ultimate.pluck.model.Permission
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    suspend fun loadAll(): List<AppItem> = withContext(Dispatchers.IO) {
        val dangerous = HashMap<String, Boolean>()
        installedPackages().mapNotNull { runCatching { toItem(it, dangerous) }.getOrNull() }
    }

    suspend fun detail(item: AppItem): AppDetail = withContext(Dispatchers.IO) {
        val info = pkg(item.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)
        val dangerous = HashMap<String, Boolean>()
        val perms = info?.requestedPermissions.orEmpty().mapIndexed { i, name ->
            val granted = (info?.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            Permission(name, granted, isDangerous(name, dangerous))
        }.sortedWith(compareByDescending<Permission> { it.dangerous }.thenBy { it.name })

        // Listing every component of a big app can exceed the binder limit, so each kind is requested on its own.
        fun count(flag: Int, pick: (PackageInfo) -> Array<*>?): Int? = pkg(item.packageName, flag)?.let { pick(it)?.size ?: 0 }
        val signers = info?.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }.orEmpty()

        AppDetail(
            item = item,
            signatures = signers.map { sha256Hex(it.toByteArray()) },
            permissions = perms,
            activities = count(PackageManager.GET_ACTIVITIES) { it.activities },
            services = count(PackageManager.GET_SERVICES) { it.services },
            receivers = count(PackageManager.GET_RECEIVERS) { it.receivers },
            providers = count(PackageManager.GET_PROVIDERS) { it.providers },
            abis = nativeAbis(item.apkPaths),
            apks = item.apkPaths.map { ApkFile(File(it).name, File(it).length()) },
            uid = runCatching { pm.getApplicationInfo(item.packageName, 0).uid }.getOrDefault(-1),
            enabled = item.enabled,
        )
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        else pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

    @Suppress("DEPRECATION")
    private fun pkg(name: String, flags: Int): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong())) else pm.getPackageInfo(name, flags)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun toItem(info: PackageInfo, dangerousCache: HashMap<String, Boolean>): AppItem? {
        val ai = info.applicationInfo ?: return null
        val paths = listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()
        val perms = info.requestedPermissions.orEmpty()
        return AppItem(
            packageName = info.packageName,
            label = pm.getApplicationLabel(ai).toString(),
            versionName = info.versionName ?: "?",
            versionCode = info.longVersionCode,
            isSystem = ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
            enabled = ai.enabled,
            apkPaths = paths,
            apkBytes = paths.sumOf { File(it).length() },
            installTime = info.firstInstallTime,
            updateTime = info.lastUpdateTime,
            installer = installerOf(info.packageName),
            minSdk = ai.minSdkVersion,
            targetSdk = ai.targetSdkVersion,
            permissionCount = perms.size,
            dangerousCount = perms.count { isDangerous(it, dangerousCache) },
        )
    }

    @Suppress("DEPRECATION")
    private fun installerOf(pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else pm.getInstallerPackageName(pkg)
    }.getOrNull()

    private fun isDangerous(name: String, cache: HashMap<String, Boolean>): Boolean = cache.getOrPut(name) {
        runCatching { pm.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false)
    }

    private fun nativeAbis(paths: List<String>): List<String> {
        val abis = sortedSetOf<String>()
        for (path in paths) {
            runCatching {
                ZipFile(path).use { zip ->
                    for (e in zip.entries()) {
                        val parts = e.name.split('/')
                        if (parts.size >= 3 && parts[0] == "lib") abis += parts[1]
                    }
                }
            }
        }
        return abis.toList()
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
    }
}
