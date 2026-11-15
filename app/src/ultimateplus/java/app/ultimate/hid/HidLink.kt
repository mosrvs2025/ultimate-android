package app.ultimate.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.ultimate.air.PadSink
import app.ultimate.air.WatchPad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/**
 * The phone as a Bluetooth keyboard and mouse, through Android's own HID device profile. A computer, tablet, TV or
 * headset pairs with it like any keyboard, then this sends key presses, pointer movement, clicks, scrolling and media
 * keys. Nothing is installed on the other device; nothing is read from it. On while switched on in Keyboard & mouse.
 */
class HidLink private constructor(private val app: Context) {
    data class Host(val address: String, val name: String)
    data class State(
        val on: Boolean = false, val registered: Boolean = false,
        val connected: Host? = null, val connecting: Host? = null,
        val paired: List<Host> = emptyList(), val watchDrives: Boolean = false, val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val adapter: BluetoothAdapter? get() = app.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val mouse = HidReports.Mouse()

    fun allowed() = Build.VERSION.SDK_INT < 31 || app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            _state.update { it.copy(registered = registered) }
            // A computer this phone was last connected to reconnects by itself when the app registers again.
            if (registered && pluggedDevice != null) connect(host(pluggedDevice))
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val h = host(device)
            _state.update { s -> when (state) {
                BluetoothProfile.STATE_CONNECTED -> s.copy(connected = h, connecting = null, error = null)
                BluetoothProfile.STATE_CONNECTING -> s.copy(connecting = h)
                BluetoothProfile.STATE_DISCONNECTED -> s.copy(connected = if (s.connected?.address == h.address) null else s.connected, connecting = null)
                else -> s
            } }
            refreshPaired()
        }
    }

    @SuppressLint("MissingPermission")
    private fun host(d: BluetoothDevice) = Host(d.address, runCatching { d.name }.getOrNull() ?: d.address)

    @SuppressLint("MissingPermission")
    fun start() {
        if (!allowed()) { _state.update { it.copy(error = "Allow Nearby devices for Ultimate first.") }; return }
        val a = adapter ?: run { _state.update { it.copy(error = "This phone has no Bluetooth.") }; return }
        if (!a.isEnabled) { _state.update { it.copy(error = "Turn Bluetooth on first.") }; return }
        _state.update { it.copy(on = true, error = null) }
        refreshPaired()
        if (hid != null) { register(); return }
        val ok = a.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { hid = proxy as BluetoothHidDevice; register() }
            override fun onServiceDisconnected(profile: Int) { hid = null; _state.update { it.copy(registered = false, connected = null) } }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) _state.update { it.copy(on = false, error = "This phone does not offer the Bluetooth keyboard profile.") }
    }

    @SuppressLint("MissingPermission")
    private fun register() {
        val sdp = BluetoothHidDeviceAppSdpSettings("Ultimate", "Keyboard and mouse", "Ultimate", BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR)
        val qos = BluetoothHidDeviceAppQosSettings(BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX)
        val ok = runCatching { hid?.registerApp(sdp, null, qos, exec, callback) == true }.getOrDefault(false)
        if (!ok) _state.update { it.copy(error = "Bluetooth would not let this phone act as a keyboard right now. Try turning Bluetooth off and on.") }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { _state.value.connected?.let { c -> adapter?.getRemoteDevice(c.address)?.let { hid?.disconnect(it) } } }
        runCatching { hid?.unregisterApp() }
        runCatching { hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        hid = null
        setWatchDrives(false)
        _state.update { it.copy(on = false, registered = false, connected = null, connecting = null) }
    }

    @SuppressLint("MissingPermission")
    fun refreshPaired() {
        if (!allowed()) return
        val list = runCatching { adapter?.bondedDevices.orEmpty().map(::host).sortedBy { it.name.lowercase() } }.getOrDefault(emptyList())
        _state.update { it.copy(paired = list) }
    }

    @SuppressLint("MissingPermission")
    fun connect(h: Host) {
        val d = runCatching { adapter?.getRemoteDevice(h.address) }.getOrNull() ?: return
        _state.update { it.copy(connecting = h, error = null) }
        if (runCatching { hid?.connect(d) == true }.getOrDefault(false) != true) _state.update { it.copy(connecting = null, error = "${h.name} did not accept. Pair it from that device's Bluetooth settings first.") }
    }

    @SuppressLint("MissingPermission")
    private fun send(id: Byte, data: ByteArray): Boolean {
        val c = _state.value.connected ?: return false
        val d = adapter?.getRemoteDevice(c.address) ?: return false
        return runCatching { hid?.sendReport(d, id.toInt(), data) == true }.getOrDefault(false)
    }

    // ---- what the screens and the watch call ----

    fun move(dx: Float, dy: Float) = mouse.move(dx, dy).forEach { send(HidReports.MOUSE, it) }
    fun scroll(notches: Int) = mouse.move(0f, 0f, notches).forEach { send(HidReports.MOUSE, it) }
    fun click(button: Int = HidReports.LEFT) = mouse.click(button).forEach { send(HidReports.MOUSE, it) }
    fun press(button: Int = HidReports.LEFT) = send(HidReports.MOUSE, mouse.press(button))
    fun release(button: Int = HidReports.LEFT) = send(HidReports.MOUSE, mouse.release(button))
    fun key(k: HidReports.Key, modifiers: Int = 0) { send(HidReports.KEYBOARD, HidReports.keyDown(HidReports.Stroke(modifiers, k.code))); send(HidReports.KEYBOARD, HidReports.KEYS_UP) }
    /** Types [text]; returns how many characters a US keyboard could not type. */
    fun type(text: String): Int { val t = HidReports.type(text); t.reports.forEach { send(HidReports.KEYBOARD, it) }; return t.skipped }
    fun media(m: HidReports.Media) = HidReports.media(m).forEach { send(HidReports.MEDIA, it) }

    /** The watch's water trackpad drives this computer's pointer instead of the phone's own cursor. */
    fun setWatchDrives(on: Boolean) {
        _state.update { it.copy(watchDrives = on) }
        PadSink.external = if (!on) null else { m ->
            if (_state.value.connected == null) false else {
                when (m) {
                    is WatchPad.Msg.Move -> { val g = WatchPad.gain(kotlin.math.hypot(m.dx, m.dy) / m.ms.coerceAtLeast(1) * 1000f); move(m.dx * 900f * g, m.dy * 900f * g) }
                    WatchPad.Msg.Tap -> click()
                    WatchPad.Msg.Long -> click(HidReports.RIGHT)
                    is WatchPad.Msg.Scroll -> scroll(-m.amount.toInt().coerceIn(-10, 10))
                    WatchPad.Msg.Back -> key(HidReports.Key.Escape)
                    WatchPad.Msg.Home -> key(HidReports.Key.Home)
                    WatchPad.Msg.Recents -> key(HidReports.Key.Tab, HidReports.ALT)
                    else -> Unit
                }
                true
            }
        }
    }

    companion object {
        @Volatile private var instance: HidLink? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: HidLink(c.applicationContext).also { instance = it } }
    }
}
