package app.ultimate.spatial

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import app.ultimate.core.Senses
import app.ultimate.core.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Spatial sensing on the phone: the Wi-Fi you're connected to and every network you can hear, your watch's
 * Bluetooth beacon (when the watch's "Proximity beacon" is on), and your steps and heading for walking a map. It
 * runs only while you've started it (the Spatial screen, or "Keep sensing" with its notification and Stop). What it
 * finds goes to the shared spatial model and the senses bus, where Rules can use it. Nothing leaves the phone.
 */
class SpatialEngine(private val context: Context, private val senses: Senses) {
    data class Network(val bssid: String, val ssid: String, val dbm: Int, val mhz: Int)
    data class State(
        val running: Boolean = false,
        val wifiDbm: Int? = null, val wifiName: String? = null, val wifiSmoothed: Double? = null, val wifiConfidence: Double = 0.0,
        val networks: List<Network> = emptyList(), val scansAllowed: Boolean = false,
        val watchDbm: Double? = null, val watchZone: ProximityZones.Zone = ProximityZones.Zone.Gone, val watchConfidence: Double = 0.0, val watchHeardAt: Long = 0,
        val moving: Boolean = false, val heading: Double = 0.0,
        val place: Fingerprints.Match? = null,
        val mapping: Boolean = false, val path: List<Pair<Double, Double>> = emptyList(), val cells: List<Heatmap.Cell> = emptyList(),
        val senses: Map<String, Reading> = emptyMap(), val overall: Double = 0.0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    val caps by lazy { Capabilities.detect(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val wifiFilter = RssiFilter()
    private val watchFilter = RssiFilter(processNoise = 2.0, measureNoise = 25.0)
    val zones = ProximityZones()
    private val model = SpatialModel()
    private val path = StepPath(); private val heat = Heatmap()
    private var mapping = false
    private var heading = 0.0
    private var lastShake = 0L
    private var lastPlace: String? = null
    private val prefs = context.getSharedPreferences("spatial", Context.MODE_PRIVATE)

    init { prefs.getString("calib", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { zones.touch = it[0]; zones.arm = it[1] } }

    fun ok(perm: String) = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private val motion = object : SensorEventListener {
        private val rot = FloatArray(9); private val ori = FloatArray(3)
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> { SensorManager.getRotationMatrixFromVector(rot, e.values); SensorManager.getOrientation(rot, ori); heading = ori[0].toDouble() }
                Sensor.TYPE_STEP_DETECTOR -> {
                    lastShake = System.currentTimeMillis()
                    if (mapping) { path.step(heading); _state.value.wifiDbm?.let { heat.add(path.x, path.y, it.toDouble()) } }
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> if (hypot(hypot(e.values[0], e.values[1]).toDouble(), e.values[2].toDouble()) > 1.2) lastShake = System.currentTimeMillis()
            }
        }
    }

    private val ble = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val t = System.currentTimeMillis()
            watchFilter.feed(result.rssi.toDouble(), t)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (job?.isActive == true) return
        val sm = context.getSystemService(SensorManager::class.java)
        listOf(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_LINEAR_ACCELERATION).forEach { type ->
            sm?.getDefaultSensor(type)?.let { sm.registerListener(motion, it, SensorManager.SENSOR_DELAY_UI) }
        }
        // The watch's beacon: a Bluetooth advertisement carrying Ultimate's own id, so only your watch is heard.
        if (caps.ble && (Build.VERSION.SDK_INT < 31 || ok(Manifest.permission.BLUETOOTH_SCAN))) runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BEACON)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), ble)
        }
        job = scope.launch {
            var lastScan = 0L
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            while (isActive) {
                val t = System.currentTimeMillis()
                // Wi-Fi strength of the network you're on, once a second.
                @Suppress("DEPRECATION") val info = runCatching { wm?.connectionInfo }.getOrNull()
                val dbm = info?.rssi?.takeIf { it in -110..-1 }
                dbm?.let { wifiFilter.feed(it.toDouble(), t) }
                // Every network you can hear, as often as Android allows (it limits apps to a few scans a minute).
                val scansOk = ok(Manifest.permission.ACCESS_FINE_LOCATION)
                if (scansOk && t - lastScan > 30_000) { lastScan = t; @Suppress("DEPRECATION") runCatching { wm?.startScan() } }
                val nets = if (scansOk) runCatching { wm?.scanResults.orEmpty() }.getOrDefault(emptyList()).map {
                    @Suppress("DEPRECATION") Network(it.BSSID ?: "", it.SSID.orEmpty(), it.level, it.frequency)
                }.filter { it.bssid.isNotEmpty() }.sortedByDescending { it.dbm } else emptyList()
                // Places from the networks heard.
                val print = nets.associate { it.bssid to it.dbm }
                val place = if (print.isNotEmpty()) Fingerprints.recognise(print, places()) else null
                if (place?.place != lastPlace && (place == null || place.confidence > 0.35)) {
                    lastPlace?.let { senses.publish(Signal("spatial", "left_place", "You left $it")) }
                    place?.let { senses.publish(Signal("spatial", "place_" + slug(it.place), "You're at ${it.place}")) }
                    lastPlace = place?.place
                }
                // The watch: smoothed strength, and its zone once it has held.
                val heard = t - watchFilter.lastAt < 5_000 && !watchFilter.estimate.isNaN()
                zones.feed(if (heard) watchFilter.estimate else null, t)?.let { z ->
                    senses.publish(Signal("spatial", "watch_" + z.name.lowercase(), when (z) {
                        ProximityZones.Zone.Near -> "Your watch came close to the phone"; ProximityZones.Zone.Mid -> "Your watch is within reach"
                        ProximityZones.Zone.Far -> "Your watch moved away"; ProximityZones.Zone.Gone -> "Your watch's beacon went quiet" }))
                }
                val moving = t - lastShake < 2_000
                // The shared model: each sense with its confidence.
                dbm?.let { model.put("Wi-Fi strength", "$it dBm", wifiFilter.confidence(t), t) }
                if (heard) model.put("Watch distance", zones.zone.name, watchFilter.confidence(t), t)
                place?.let { model.put("Place", it.place, it.confidence, t) }
                model.put("Motion", if (moving) "moving" else "still", 0.9, t)
                if (nets.isNotEmpty()) model.put("Networks heard", "${nets.size}", 0.8, t)
                _state.value = State(true, dbm, info?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }, wifiFilter.estimate.takeIf { !it.isNaN() }, wifiFilter.confidence(t),
                    nets, scansOk, if (heard) watchFilter.estimate else null, zones.zone, if (heard) watchFilter.confidence(t) else 0.0, watchFilter.lastAt,
                    moving, heading, place, mapping, path.points.toList(), heat.cells(), model.now(t), model.overall(t))
                delay(1_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        job?.cancel(); job = null
        context.getSystemService(SensorManager::class.java)?.unregisterListener(motion)
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(ble) }
        _state.value = _state.value.copy(running = false)
    }

    fun startMap() { path.reset(); heat.clear(); mapping = true }
    fun stopMap() { mapping = false }

    /** Saves where you are as a place, from the networks heard right now (more saves of the same place make it surer). */
    fun savePlace(name: String): Boolean {
        val print = _state.value.networks.associate { it.bssid to it.dbm }
        if (print.isEmpty()) return false
        val all = JSONObject(prefs.getString("places", "{}")!!)
        val arr = all.optJSONArray(name) ?: org.json.JSONArray()
        arr.put(JSONObject(print as Map<*, *>)); all.put(name, arr)
        prefs.edit().putString("places", all.toString()).apply(); return true
    }
    fun forgetPlace(name: String) { val all = JSONObject(prefs.getString("places", "{}")!!); all.remove(name); prefs.edit().putString("places", all.toString()).apply() }
    fun places(): Map<String, List<Map<String, Int>>> = runCatching {
        val all = JSONObject(prefs.getString("places", "{}")!!)
        all.keys().asSequence().associateWith { k -> val a = all.getJSONArray(k); (0 until a.length()).map { i -> val o = a.getJSONObject(i); o.keys().asSequence().associateWith { o.getInt(it) } } }
    }.getOrDefault(emptyMap())

    /** Calibrates the watch zones from strengths heard at the phone and at arm's length. */
    fun calibrate(atPhone: List<Double>, atArm: List<Double>): Boolean {
        val c = ProximityZones.calibrate(atPhone, atArm) ?: return false
        zones.touch = c.first; zones.arm = c.second
        prefs.edit().putString("calib", "${c.first},${c.second}").apply(); return true
    }

    companion object {
        /** Ultimate's watch beacon id (the watch advertises it; nothing else uses it). */
        val BEACON: java.util.UUID = java.util.UUID.fromString("7c1d0b2a-5e8f-4c0a-9a51-0c0ffee5a7a1")
        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }
}

/** What this phone can really do for spatial sensing, checked on the device, not assumed. */
data class Capabilities(val ble: Boolean, val uwb: Boolean, val wifiRtt: Boolean, val channelSounding: Boolean, val stepDetector: Boolean, val rotation: Boolean) {
    companion object {
        fun detect(c: Context): Capabilities {
            val pm = c.packageManager; val sm = c.getSystemService(SensorManager::class.java)
            // Bluetooth channel sounding (real distance over Bluetooth) arrived in Android 16; ask the system if it has it.
            val cs = Build.VERSION.SDK_INT >= 36 && runCatching {
                val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return@runCatching false
                adapter.javaClass.methods.any { it.name == "getDistanceMeasurementManager" } &&
                    (adapter.javaClass.getMethod("isChannelSoundingSupported").invoke(adapter) as? Boolean ?: false)
            }.getOrDefault(false)
            return Capabilities(
                pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                Build.VERSION.SDK_INT >= 31 && pm.hasSystemFeature(PackageManager.FEATURE_UWB),
                pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT), cs,
                sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null, sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null,
            )
        }
    }
}
