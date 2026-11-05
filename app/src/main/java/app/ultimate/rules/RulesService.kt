package app.ultimate.rules

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.ultimate.BuildConfig
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.UltimateApp
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Watches the phone for the rules that need it (charger, headphones, battery, Wi-Fi) while any such rule is on, with a
 * notification that says so. It starts and stops with the rules and does nothing else.
 */
class RulesService : Service() {
    private val engine get() = (application as UltimateApp).rules
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var live: LiveTriggers? = null
    private var watching: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // Declared with foregroundServiceType="specialUse" in the manifests of the editions that include rules.
    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rules watching", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "rules").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun note(n: Int) = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Rules are watching")
            .setContentText("$n rule${if (n == 1) "" else "s"} on · tap to manage").setOngoing(true).setContentIntent(open).setSilent(true).build()
        val count = engine.rules.value.count { it.enabled && it.trigger.type in RuleKinds.LIVE }
        ServiceCompat.startForeground(this, 4, note(count), if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (live == null) live = LiveTriggers(this) { engine.fire(it) }.also { it.start() }
        if (watching == null) watching = engine.rules.onEach { rules -> nm.notify(4, note(rules.count { it.enabled && it.trigger.type in RuleKinds.LIVE })) }.launchIn(scope)
        return START_STICKY
    }

    override fun onDestroy() { live?.stop(); live = null; scope.cancel(); super.onDestroy() }

    private companion object { const val CHANNEL = "rules-watch" }
}

/**
 * The phone's own signals, turned into [Event]s: charger, headphones (wired and Bluetooth), battery level and Wi-Fi. Only a
 * real change is reported, never the state found when watching starts, so starting the service never fires a rule.
 */
class LiveTriggers(private val context: Context, private val emit: (Event) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var lastCharging: Boolean? = null
    private var lastHeadphones: Boolean? = null
    private var lastBattery: Int? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var deviceCallback: AudioDeviceCallback? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> charging(true)
                Intent.ACTION_POWER_DISCONNECTED -> charging(false)
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level < 0) return
                    val now = level * 100 / scale
                    lastBattery?.takeIf { it != now }?.let { emit(Event.Battery(it, now)) }
                    lastBattery = now
                }
            }
        }
    }

    private fun charging(on: Boolean) { if (lastCharging != on) { lastCharging = on; emit(Event.State(TriggerType.Charging, on)) } }

    private fun headphones(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun headphonesChanged() { val now = headphones(); if (lastHeadphones != now) { lastHeadphones = now; emit(Event.State(TriggerType.Headphones, now)) } }

    // ACCESS_NETWORK_STATE is declared only by the edition with network tools, and this block runs only there.
    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        // The sticky battery broadcast gives the starting state without reporting it as a change.
        val sticky = context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        sticky?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0) lastBattery = level * 100 / scale
            lastCharging = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        lastHeadphones = headphones()
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = headphonesChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = headphonesChanged()
        }.also { audio.registerAudioDeviceCallback(it, null) }
        // Wi-Fi needs the network-state permission, which only the edition with network tools declares.
        if (BuildConfig.HAS_NETWORK) runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            var wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (!wifi) { wifi = true; emit(Event.State(TriggerType.Wifi, true)) } }
                override fun onLost(network: Network) { if (wifi) { wifi = false; emit(Event.State(TriggerType.Wifi, false)) } }
            }.also { cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), it) }
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        deviceCallback?.let { audio.unregisterAudioDeviceCallback(it) }
        callback?.let { runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null; deviceCallback = null
    }
}

/** After a restart, brings the watching and the time rules back without anyone opening the app. */
class RulesBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) (context.applicationContext as? UltimateApp)?.rules?.sync()
    }
}
