package app.ultimate.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth Low Energy presence: advertises this phone's mesh identifier and listens for the others'. It is how the
 * mesh can say "your Z Flip is right here" even when the two phones cannot reach each other over Wi-Fi (guest network,
 * different networks), and it gives a rough nearness band. It carries no data and opens no connection; see
 * [MeshBleCodec] for why the identifier means nothing to a phone without the pairing code.
 */
@SuppressLint("MissingPermission") // checked by [permitted] before every use
class MeshBle(context: Context, private val key: ByteArray, private val selfId: String, private val onChange: () -> Unit) {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val uuid = ParcelUuid(MeshBleCodec.serviceUuid(key))
    private val seen = ConcurrentHashMap<String, Seen>()
    private val smoothers = ConcurrentHashMap<String, MeshBleCodec.Smoother>()
    @Volatile var running = false; private set
    @Volatile var note: String? = null; private set

    data class Seen(val tag: String, val rssi: Double, val band: MeshBleCodec.Band, val at: Long)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { note = "Bluetooth could not advertise (code $errorCode)"; onChange() }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val data = r.scanRecord?.getManufacturerSpecificData(COMPANY) ?: return
            if (data.size < 4) return
            val tag = data.take(4).joinToString("") { "%02x".format(it) }
            if (tag == MeshBleCodec.tagHex(key, selfId)) return
            val rssi = smoothers.getOrPut(tag) { MeshBleCodec.Smoother() }.add(r.rssi)
            seen[tag] = Seen(tag, rssi, MeshBleCodec.band(rssi), System.currentTimeMillis())
            onChange()
        }
        override fun onScanFailed(errorCode: Int) { note = "Bluetooth scan failed (code $errorCode)"; onChange() }
    }

    fun permitted(): Boolean {
        fun ok(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 31) ok(android.Manifest.permission.BLUETOOTH_SCAN) && ok(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        else ok(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Starts advertising and scanning if this phone has Bluetooth LE, it is on, and the permissions are granted. */
    fun start(): Boolean {
        if (running) return true
        val a = adapter
        when {
            a == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> { note = "this phone has no Bluetooth LE"; return false }
            !a.isEnabled -> { note = "Bluetooth is off"; return false }
            !permitted() -> { note = "Bluetooth permission not given yet"; return false }
        }
        val advertiser = a!!.bluetoothLeAdvertiser; val scanner = a.bluetoothLeScanner
        if (advertiser == null || scanner == null) { note = "Bluetooth LE advertising is not available"; return false }
        advertiser.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(false).build(),
            AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(uuid).addManufacturerData(COMPANY, MeshBleCodec.tag(key, selfId)).build(),
            advertiseCallback)
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(uuid).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback)
        running = true; note = null
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        seen.clear(); smoothers.clear()
    }

    /** Devices heard within the last 30 s, keyed by tag. */
    fun nearby(now: Long = System.currentTimeMillis()): Map<String, Seen> = seen.filterValues { now - it.at < 30_000 }

    private companion object { const val COMPANY = 0xFFFF } // the "for testing" company id: this carries only the opaque tag
}
