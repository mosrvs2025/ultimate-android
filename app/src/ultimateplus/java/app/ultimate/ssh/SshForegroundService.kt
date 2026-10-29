package app.ultimate.ssh

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.ultimate.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SshForegroundService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val destroyed = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: SshServerManager
    private var engine: SshEngine? = null
    private var started = false
    private val permissionCheck = object : Runnable {
        override fun run() {
            if (!manager.storageAllowed() || !manager.notificationsAllowed()) { stopSelf(); return }
            handler.postDelayed(this, 2000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        manager = SshServerManager.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "SSH and SFTP", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(clients: Int): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val disconnect = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(DISCONNECT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (clients > 0) "SSH: $clients connected" else "SSH and SFTP listening on 8022")
            .setContentText("Approved computers can run commands and access shared storage")
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Disconnect clients", disconnect).addAction(0, "Turn off SSH", stop).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == DISCONNECT) {
            if (!started) stopSelf() else worker.execute { engine?.disconnectAll() }; return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        try {
            manager.validateStart()
            ServiceCompat.startForeground(this, ID, notification(0),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (e: Exception) {
            manager.mutableState.value = manager.state.value.copy(error = e.message); stopSelf(); return START_NOT_STICKY
        }
        manager.mutableState.value = manager.state.value.copy(starting = true, error = null)
        handler.post(permissionCheck)
        worker.execute {
            var pending: SshEngine? = null
            try {
                manager.validateStart()
                val fingerprint = manager.loadFingerprint()
                pending = SshEngine(manager.hostKey, Environment.getExternalStorageDirectory().toPath(), manager.approved,
                    manager.shellFactory, { clients -> handler.post {
                        if (!destroyed.get()) {
                            manager.mutableState.value = manager.state.value.copy(clients = clients)
                            getSystemService(NotificationManager::class.java).notify(ID, notification(clients))
                        }
                    } }, "0.0.0.0", 8022)
                pending.start()
                if (destroyed.get()) pending.close() else {
                    engine = pending
                    manager.mutableState.value = SshServerManager.State(running = true, fingerprint = fingerprint)
                }
            } catch (e: Exception) {
                runCatching { pending?.close() }
                manager.mutableState.value = manager.state.value.copy(running = false, starting = false, error = e.message ?: "SSH failed to start")
                handler.post { stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        destroyed.set(true); handler.removeCallbacks(permissionCheck)
        worker.execute {
            val failure = runCatching { engine?.close() }.exceptionOrNull()
            engine = null
            manager.mutableState.value = manager.state.value.copy(running = false, starting = false, clients = 0,
                error = failure?.message ?: manager.state.value.error)
        }
        worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object {
        const val DISCONNECT = "app.ultimate.ssh.DISCONNECT"
        const val STOP = "app.ultimate.ssh.STOP"
        internal const val CHANNEL = "ultimate_ssh"
        private const val ID = 8022
    }
}
