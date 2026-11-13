package app.ultimate.worldscan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/** What the recorder wrote, for the walk's record and for honest reporting (a truncated log must say so). */
class RecorderSummary(val file: File, val bytes: Long, val truncated: Boolean, val counts: Map<String, Int>, val sensorCounts: Map<Int, Int>, val fixes: Int, val medianAccuracyM: Double?, val sensorsAvailable: List<Int>)

/** The latest GPS state for the screen: how good the last fix is and how many fixes have arrived. */
class GnssLive(val accuracyM: Float? = null, val fixes: Int = 0, val lastFixAgeMs: Long = Long.MAX_VALUE, val provider: String? = null, val enabled: Boolean = true)

/**
 * Foreground-only, explicitly started collection of location and motion-sensor streams alongside the camera tracking poses.
 *
 * The caller owns the permission requests, a clearly visible Stop control and the lifecycle. No network, no hidden collection, no
 * background service. The log is a private file written as JSON lines straight into a gzip stream (so a long walk costs about a fifth of
 * the plain size and a crash loses only the last couple of seconds), with a size cap: past it, the high-rate sensor lines stop and the log
 * says it was truncated, while the location fixes and camera poses (which a walk cannot be placed without) keep going.
 *
 * All callbacks run on a background thread, not the UI thread. Android 10 (API 29, this app's minimum) still requires the old
 * LocationListener status/provider callbacks to exist (they became optional only in API 30), so they are implemented here.
 */
class WorldSensorRecorder(private val context: Context, private val maxBytes: Long = 64L * 1024 * 1024) : SensorEventListener, LocationListener, Closeable {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var thread: HandlerThread? = null
    private var out: CountingStream? = null
    private var writer: java.io.Writer? = null
    @Volatile private var active = false
    @Volatile private var truncated = false
    private val lock = Any()
    private val counts = HashMap<String, Int>(); private val sensorCounts = HashMap<Int, Int>()
    private val accuracies = ArrayList<Float>()
    private val fixList = ArrayList<GeoFix>()
    private var lastFlush = 0L; private var lastPoseAt = 0L
    private var available: List<Int> = emptyList()
    var sessionFile: File? = null
        private set
    /** Live GPS state for the screen (accuracy of the latest fix, number of fixes). */
    val gnss: StateFlow<GnssLive> get() = live
    private val live = MutableStateFlow(GnssLive())
    private var lastFixRealtimeMs = 0L

    private class CountingStream(o: OutputStream) : FilterOutputStream(o) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    /**
     * Starts writing to [file] (which must not exist; a recording is never overwritten). Needs coarse or fine location permission,
     * granted through the visible UI. Throws if the file exists or no location permission is held.
     */
    fun start(file: File) {
        check(!active) { "Recorder already running" }
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        check(fine || coarse) { "Location permission must be granted before recording" }
        check(!file.exists()) { "Recording already exists; refusing to overwrite it" }
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Cannot create the recording folder" }
        val counting = CountingStream(BufferedOutputStream(FileOutputStream(file), 32 * 1024))
        out = counting
        writer = java.io.OutputStreamWriter(GZIPOutputStream(counting, 16 * 1024, true), Charsets.UTF_8)
        sessionFile = file; active = true; truncated = false; counts.clear(); sensorCounts.clear(); accuracies.clear(); fixList.clear()
        val ht = HandlerThread("world-sensors").also { it.start() }; thread = ht
        val handler = Handler(ht.looper)
        try {
            val kinds = intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT)
            val found: List<Sensor> = kinds.toList().mapNotNull { k -> sensorManager.getDefaultSensor(k) }
            available = found.map { it.type }
            val header = StringBuilder("{\"type\":\"session\",\"schema\":2,\"startedEpochMillis\":").append(System.currentTimeMillis())
                .append(",\"sdk\":").append(android.os.Build.VERSION.SDK_INT).append(",\"sensors\":[")
            found.forEachIndexed { i, s -> if (i > 0) header.append(','); header.append("{\"type\":").append(s.type).append(",\"name\":\"").append(s.name.replace("\"", "'")).append("\",\"maxRange\":").append(s.maximumRange).append(",\"resolution\":").append(s.resolution).append(",\"minDelayUs\":").append(s.minDelay).append('}') }
            header.append("]}")
            line("session", header.toString())
            for (s in found) {
                val period = when (s.type) { Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE -> SensorManager.SENSOR_DELAY_GAME; Sensor.TYPE_ROTATION_VECTOR -> 50_000; else -> SensorManager.SENSOR_DELAY_NORMAL }
                sensorManager.registerListener(this, s, period, handler)
            }
            val provider = when {
                fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            live.value = GnssLive(provider = provider, enabled = provider != null)
            if (provider != null) locationManager.requestLocationUpdates(provider, 1000L, 0f, this, ht.looper)
        } catch (e: Exception) { stop(); throw e }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active || truncated) return
        val sb = StringBuilder(96).append("{\"type\":\"sensor\",\"s\":").append(event.sensor.type).append(",\"t\":").append(event.timestamp).append(",\"a\":").append(event.accuracy).append(",\"v\":[")
        for (i in event.values.indices) { if (i > 0) sb.append(','); val v = event.values[i]; if (v.isFinite()) sb.append(v) else sb.append("null") }
        sb.append("]}")
        synchronized(lock) { sensorCounts[event.sensor.type] = (sensorCounts[event.sensor.type] ?: 0) + 1 }
        line("sensor", sb.toString())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (!active) return
        val acc = if (location.hasAccuracy()) location.accuracy else null
        lastFixRealtimeMs = SystemClock.elapsedRealtime()
        synchronized(lock) { if (acc != null) { accuracies += acc; fixList += GeoFix(location.latitude, location.longitude, if (location.hasAltitude()) location.altitude else null, acc, location.elapsedRealtimeNanos) } }
        live.value = GnssLive(acc, (live.value.fixes) + 1, 0L, location.provider, true)
        val sb = StringBuilder(160).append("{\"type\":\"location\",\"t\":").append(location.elapsedRealtimeNanos)
            .append(",\"lat\":").append(location.latitude).append(",\"lon\":").append(location.longitude)
            .append(",\"acc\":").append(acc ?: "null")
            .append(",\"alt\":").append(if (location.hasAltitude()) location.altitude else "null")
            .append(",\"speed\":").append(if (location.hasSpeed()) location.speed else "null")
            .append(",\"bearing\":").append(if (location.hasBearing()) location.bearing else "null")
            .append(",\"provider\":\"").append(location.provider ?: "unknown").append("\"}")
        line("location", sb.toString())
    }

    // API 29 calls these on GPS fixes and provider changes; without them the app crashes with AbstractMethodError on Android 10.
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) { live.value = GnssLive(live.value.accuracyM, live.value.fixes, live.value.lastFixAgeMs, provider, true) }
    override fun onProviderDisabled(provider: String) { live.value = GnssLive(live.value.accuracyM, live.value.fixes, live.value.lastFixAgeMs, provider, false) }

    /** Every GPS fix with an accuracy, in time order (the walk is placed on the map from these). */
    fun fixes(): List<GeoFix> = synchronized(lock) { fixList.toList() }

    /** Age of the last GPS fix for the screen, refreshed by the caller a few times a second. */
    fun refreshAge() { val v = live.value; if (lastFixRealtimeMs > 0) live.value = GnssLive(v.accuracyM, v.fixes, SystemClock.elapsedRealtime() - lastFixRealtimeMs, v.provider, v.enabled) }

    /**
     * One camera pose from motion tracking. [frameNanos] is ARCore's own frame time; [receivedNanos] is the elapsed-realtime clock when
     * the frame arrived. Both are written: ARCore does not define its frame clock, so [ClockSync] needs the pair to put poses on the
     * sensors' clock. At most ten poses a second are written.
     */
    fun recordPose(frameNanos: Long, receivedNanos: Long, x: Float, y: Float, z: Float, qx: Float, qy: Float, qz: Float, qw: Float, tracking: TrackingQuality) {
        if (!active || frameNanos < 0L || receivedNanos < 0L) return
        if (receivedNanos - lastPoseAt < 100_000_000L && tracking == TrackingQuality.TRACKING) return
        lastPoseAt = receivedNanos
        line("pose", "{\"type\":\"pose\",\"tf\":$frameNanos,\"tr\":$receivedNanos,\"p\":[$x,$y,$z],\"q\":[$qx,$qy,$qz,$qw],\"k\":\"${tracking.name}\"}")
    }

    /** A note that belongs in the log (a pause, a tracking loss, a chunk boundary), stamped with the elapsed-realtime clock. */
    fun note(kind: String, detail: String) { if (active) line("note", "{\"type\":\"note\",\"t\":${SystemClock.elapsedRealtimeNanos()},\"kind\":\"${kind.replace("\"", "'")}\",\"detail\":\"${detail.replace("\"", "'").replace("\n", " ")}\"}") }

    private fun line(kind: String, json: String) {
        synchronized(lock) {
            val w = writer ?: return
            val o = out ?: return
            // Past the cap the high-rate lines stop; location, pose and note lines (small) continue up to twice the cap.
            if (o.count > maxBytes && kind == "sensor") { truncated = true; return }
            if (o.count > maxBytes * 2) { truncated = true; return }
            w.write(json); w.write('\n'.code)
            counts[kind] = (counts[kind] ?: 0) + 1
            val now = SystemClock.elapsedRealtime()
            if (now - lastFlush > 2000) { lastFlush = now; w.flush() }
        }
    }

    /** Stops everything and closes the file. Safe to call twice. Returns what was written, or null when nothing was started. */
    fun stop(): RecorderSummary? {
        if (!active && writer == null) return null
        active = false
        runCatching { sensorManager.unregisterListener(this) }
        runCatching { locationManager.removeUpdates(this) }
        thread?.quitSafely(); thread = null
        val file = sessionFile
        var bytes = 0L
        synchronized(lock) {
            runCatching { writer?.flush(); writer?.close() }
            bytes = out?.count ?: 0L
            writer = null; out = null
        }
        val med = synchronized(lock) { accuracies.sorted().let { if (it.isEmpty()) null else it[it.size / 2].toDouble() } }
        file ?: return null
        return RecorderSummary(file, bytes, truncated, HashMap(counts), HashMap(sensorCounts), counts["location"] ?: 0, med, available)
    }

    override fun close() { stop() }
}
