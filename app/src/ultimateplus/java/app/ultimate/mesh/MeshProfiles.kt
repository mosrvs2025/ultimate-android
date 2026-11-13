package app.ultimate.mesh

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import app.ultimate.BuildConfig

/** Reads what this phone's hardware reports, so it can tell the rest of the mesh what it is able to do. */
object MeshProfiles {
    fun canRange(c: Context): Boolean {
        val pm = c.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) || !pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) return false
        val am = c.getSystemService(AudioManager::class.java)
        // Many phones say nothing about near-ultrasound and still work; only an explicit "false" rules the phone out.
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND) != "false" && am.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND) != "false"
    }

    @Volatile private var hardware: DeviceProfile? = null

    /** The hardware part is read once; only the owner's lend choice changes afterwards. */
    fun local(c: Context, lends: Boolean): DeviceProfile = (hardware ?: read(c).also { hardware = it }).copy(lends = lends)

    private fun read(c: Context): DeviceProfile {
        val lends = false
        val pm = c.packageManager
        val sm = c.getSystemService(SensorManager::class.java)
        val caps = buildSet {
            add(Cap.Compute)
            if (sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) add(Cap.Motion)
            if (pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) add(Cap.Mic)
            if (pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) add(Cap.Speaker)
            if (canRange(c)) add(Cap.NearUltrasound)
            if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) add(Cap.Camera)
            if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) add(Cap.Bluetooth)
        }
        val foldable = pm.hasSystemFeature("android.hardware.sensor.hinge_angle") || Build.MODEL.startsWith("SM-F")
        val tablet = !foldable && c.resources.configuration.smallestScreenWidthDp >= 600
        return DeviceProfile(Build.MODEL, if (foldable) Kind.Foldable else if (tablet) Kind.Tablet else Kind.Phone, caps, lends, BuildConfig.VERSION_NAME)
    }
}
