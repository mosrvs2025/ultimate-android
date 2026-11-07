package app.ultimate.wear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid

/**
 * The proximity beacon: the watch sends a small Bluetooth signal a few times a second carrying only Ultimate's own
 * id (no name, no data), so Ultimate+ on the phone can tell how close the watch is from how strongly it hears it.
 * Only while "Proximity beacon" is on; a notification with Stop shows while it runs.
 */
class BeaconService : Service() {
    private val callback = object : AdvertiseCallback() {}
    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { getSharedPreferences("watch", MODE_PRIVATE).edit().putBoolean("beacon", false).apply(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Proximity beacon", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 2, Intent(this, BeaconService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Proximity beacon on")
            .setContentText("Lets your phone feel how close the watch is.").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        runCatching {
            val adv = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser ?: return@runCatching
            adv.stopAdvertising(callback)
            adv.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW).setConnectable(false).build(),
                AdvertiseData.Builder().addServiceUuid(ParcelUuid(BEACON)).setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build(), callback)
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        super.onDestroy()
    }

    companion object {
        /** Must match the phone's SpatialEngine.BEACON. */
        val BEACON: java.util.UUID = java.util.UUID.fromString("7c1d0b2a-5e8f-4c0a-9a51-0c0ffee5a7a1")
        private const val CHANNEL = "beacon"; private const val ID = 8; private const val STOP = "app.ultimate.wear.BEACON_STOP"
        fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, BeaconService::class.java)) }
        fun stop(c: Context) = c.stopService(Intent(c, BeaconService::class.java))
    }
}
