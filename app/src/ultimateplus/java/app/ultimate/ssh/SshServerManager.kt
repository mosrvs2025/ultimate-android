package app.ultimate.ssh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.AtomicFile
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** No boot receiver and no persisted enabled flag: every process starts with SSH off. */
class SshServerManager private constructor(context: Context) {
    val context: Context = context.applicationContext
    private val folder = File(this.context.filesDir, "ssh").apply { mkdirs() }
    private val keyFile = AtomicFile(File(folder, "approved_keys"))
    internal val hostKey = File(folder, "hostkey.ser").toPath()
    internal val approved = ApprovedKeys()
    data class State(val running: Boolean = false, val starting: Boolean = false,
        val clients: Int = 0, val fingerprint: String = "", val error: String? = null)
    internal val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val mutableKeys = MutableStateFlow("")
    val keys = mutableKeys.asStateFlow()
    @Volatile internal var shellFactory: ShellSession.Factory = ShellSession.Factory { command, _, _ ->
        ProcessShellSession("/system/bin/sh", Environment.getExternalStorageDirectory().toPath(), command)
    }
    init {
        org.apache.sshd.common.util.OsUtils.setAndroid(true)
        runCatching { if (keyFile.baseFile.exists()) String(keyFile.readFully(), Charsets.UTF_8) else "" }
            .onSuccess { text -> runCatching { approved.replace(text); mutableKeys.value = text }
                .onFailure { mutableState.value = State(error = "Stored keys could not be read. Approve them again.") } }
            .onFailure { mutableState.value = State(error = "Stored keys could not be read.") }
    }
    /** Call while stopped, before starting SSH. Existing channels never change implementations mid-session. */
    @Synchronized fun setShellSessionFactory(factory: ShellSession.Factory) {
        check(!state.value.running && !state.value.starting) { "Stop SSH before replacing the shell" }
        shellFactory = factory
    }
    @Synchronized fun approveKeys(text: String) {
        check(!state.value.running && !state.value.starting) { "Stop SSH before changing approved keys" }
        ApprovedKeys.parse(text) // validate the complete list before replacing anything
        var stream: java.io.FileOutputStream? = null
        try {
            stream = keyFile.startWrite(); stream.write(text.toByteArray(Charsets.UTF_8)); keyFile.finishWrite(stream)
        } catch (e: Exception) { keyFile.failWrite(stream); throw e }
        approved.replace(text); mutableKeys.value = text
    }
    fun storageAllowed(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    fun notificationsAllowed(): Boolean =
        context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(SshForegroundService.CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    internal fun validateStart() {
        check(!approved.isEmpty) { "Paste or scan a public key, then approve it first" }
        check(storageAllowed()) { "Allow all-files access first" }
        check(notificationsAllowed()) { "Allow notifications so the disconnect control stays visible" }
    }
    fun start() {
        validateStart()
        ContextCompat.startForegroundService(context, Intent(context, SshForegroundService::class.java))
    }
    fun stop() { context.stopService(Intent(context, SshForegroundService::class.java)) }
    fun disconnectClients() {
        if (state.value.running) context.startService(Intent(context, SshForegroundService::class.java).setAction(SshForegroundService.DISCONNECT))
    }
    internal fun loadFingerprint(): String = SshEngine.hostFingerprint(hostKey).also { fingerprint ->
        mutableState.value = mutableState.value.copy(fingerprint = fingerprint)
    }
    companion object {
        // The manager retains applicationContext only, never an Activity.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: SshServerManager? = null
        fun get(context: Context): SshServerManager = instance ?: synchronized(this) {
            instance ?: SshServerManager(context).also { instance = it }
        }
    }
}
