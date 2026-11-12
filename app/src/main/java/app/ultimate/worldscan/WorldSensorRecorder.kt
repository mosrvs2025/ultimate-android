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
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter

/**
 * Foreground-only, explicitly started location + IMU collection.
 *
 * The caller owns permission requests, a clearly visible Stop control and lifecycle.
 * No network traffic, hidden collection, background service or camera recording.
 * Session files are written under the app-private filesDir/worldscan directory.
 */
class WorldSensorRecorder(private val context: Context) : SensorEventListener, LocationListener, Closeable {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var writer: BufferedWriter? = null
    private var active = false
    private var lastLocation: Location? = null
    private val lock = Any()
    var sessionFile: File? = null
        private set

    /**
     * Requires coarse or fine location permission granted by the visible UI.
     * The caller must call stop() in onStop or when the user taps Stop.
     */
    fun start(sessionId: String): File {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid session ID" }
        check(!active) { "Recorder already running" }
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        check(fine || coarse) { "Location permission must be granted before recording" }

        val directory = File(context.filesDir, "worldscan")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording directory" }
        val file = File(directory, "$sessionId.jsonl")
        check(!file.exists()) { "Session already exists; refusing to overwrite it" }
        writer = BufferedWriter(FileWriter(file, false))
        sessionFile = file
        active = true
        try {
            append(JSONObject().put("type", "session").put("schema", 1)
                .put("sessionId", sessionId).put("startedEpochMillis", System.currentTimeMillis()))
            for (kind in intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT)) {
                sensorManager.getDefaultSensor(kind)?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            }
            val provider = when {
                fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
            }
            return file
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val values = JSONArray()
        for (i in 0 until event.values.size) {
            val v = event.values[i]
            values.put(if (v.isFinite()) v.toDouble() else JSONObject.NULL)
        }
        append(JSONObject().put("type", "sensor").put("sensorType", event.sensor.type)
            .put("tElapsedNanos", event.timestamp).put("accuracy", event.accuracy)
            .put("values", values))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (!active) return
        lastLocation = Location(location)
        append(JSONObject().put("type", "location")
            .put("tElapsedNanos", location.elapsedRealtimeNanos)
            .put("lat", location.latitude).put("lon", location.longitude)
            .put("horizontalAccuracyMeters",
                if (location.hasAccuracy()) location.accuracy.toDouble() else JSONObject.NULL)
            .put("altitudeMeters",
                if (location.hasAltitude()) location.altitude else JSONObject.NULL)
            .put("provider", location.provider ?: "unknown"))
    }

    /** Explicit ARCore integration point: caller feeds timestamp and its session-frame ID. */
    fun recordCameraFrame(timestampNanos: Long, localFrameId: String, tracking: TrackingQuality,
                          depthAvailable: Boolean) {
        if (!active || timestampNanos < 0L || localFrameId.isBlank()) return
        append(JSONObject().put("type", "camera-frame").put("tElapsedNanos", timestampNanos)
            .put("localFrameId", localFrameId).put("tracking", tracking.name)
            .put("depthAvailable", depthAvailable))
    }

    private fun append(entry: JSONObject) {
        synchronized(lock) {
            writer?.let {
                it.write(entry.toString())
                it.newLine()
            }
        }
    }

    fun stop() {
        if (!active && writer == null) return
        active = false
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        synchronized(lock) {
            writer?.flush()
            writer?.close()
            writer = null
        }
        lastLocation = null
    }

    override fun close() = stop()
}
