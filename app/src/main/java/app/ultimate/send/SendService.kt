package app.ultimate.send

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.UltimateApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps sharing on while Ultimate is in the background, with a notification that says so and stops it. Turns itself off after
 * two hours with no visits, so a forgotten session does not keep the phone listening.
 */
class SendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val server get() = (application as UltimateApp).sendServer
    override fun onBind(intent: Intent?): IBinder? = null

    // Declared with foregroundServiceType="specialUse" in the Ultimate+ manifest, the only edition with this tool.
    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { server.stop(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sharing with computers", NotificationManager.IMPORTANCE_LOW))
        val address = server.state.value.address?.removePrefix("http://") ?: "your Wi-Fi"
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "send").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 12, Intent(this, SendService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setColor(0xFF6750A4.toInt())
            .setContentTitle("Sharing with your computer").setContentText(address).setOngoing(true).setContentIntent(open)
            .addAction(0, "Turn off", stop).build()
        ServiceCompat.startForeground(this, 3, n, if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (!server.state.value.running) { stopSelf(); break }
                if (System.currentTimeMillis() - server.lastVisit > IDLE_MS) { server.stop(); break }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val CHANNEL = "send"
        const val STOP = "app.ultimate.send.STOP"
        const val IDLE_MS = 2 * 3_600_000L
    }
}
