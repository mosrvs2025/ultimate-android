package app.ultimate.mesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.ultimate.MainActivity
import app.ultimate.R

/**
 * Keeps the mesh alive while Ultimate is in the background, so another of your phones can still find this one, record
 * on it, or measure distance to it. It exists only while the mesh is on, shows a notification the whole time with Stop,
 * and the notification says when this phone is being used by another device.
 *
 * The microphone is declared only if the permission is already granted: Android refuses a microphone service without
 * it, and refuses to start one from the background, so a phone whose mesh was started from the app window can still
 * be measured while the screen is off, but one that was never given the microphone permission cannot.
 */
class MeshService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { MeshManager.get(this).stop(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Octopus Mesh running", NotificationManager.IMPORTANCE_LOW))
        val s = MeshManager.get(this).state.value
        val open = PendingIntent.getActivity(this, 41, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "mesh").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 42, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            s.recordingFor != null -> "Recording motion for ${s.recordingFor}"
            s.peers.isEmpty() -> "Looking for your other devices"
            else -> "${s.alive.size} of ${s.peers.size} device(s) connected"
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Octopus Mesh is on").setContentText(text)
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop", stop).build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        runCatching { ServiceCompat.startForeground(this, 8, n, type) }.onFailure { runCatching { ServiceCompat.startForeground(this, 8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) } }
        return START_NOT_STICKY
    }

    private companion object { const val CHANNEL = "mesh-running"; const val ACTION_STOP = "app.ultimate.mesh.STOP" }
}
