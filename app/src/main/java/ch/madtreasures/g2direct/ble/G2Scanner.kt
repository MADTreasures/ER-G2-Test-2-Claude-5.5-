package ch.madtreasures.g2direct.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.util.isNotEmpty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds G2 arms: live BLE advertisements plus devices the watch already knows (bonded in
 * the Bluetooth settings, or currently connected by the system). An arm that is connected
 * or bonded may not advertise at all, so the known-device list matters as much as the scan.
 */
@SuppressLint("MissingPermission")
class G2Scanner(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val found = LinkedHashMap<String, G2Arm>()
    private val _pairs = MutableStateFlow<List<G2Pair>>(emptyList())
    val pairs: StateFlow<List<G2Pair>> = _pairs.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var stopRunnable: Runnable? = null

    fun bluetoothEnabled(): Boolean = manager?.adapter?.isEnabled == true

    /** Adds bonded and system-connected G2 arms to the list (no radio activity). */
    fun refreshKnownDevices() {
        val adapter = manager?.adapter ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            val known = LinkedHashMap<String, BluetoothDevice>()
            for (d in adapter.bondedDevices.orEmpty()) known[d.address] = d
            for (profile in intArrayOf(BluetoothProfile.GATT, BluetoothProfile.GATT_SERVER)) {
                try {
                    for (d in manager.getConnectedDevices(profile)) known[d.address] = d
                } catch (_: IllegalArgumentException) {
                }
            }
            for (d in known.values) {
                val name = d.name ?: continue
                if (!G2Names.isG2(name)) continue
                val side = G2Names.side(name) ?: continue
                val old = found[d.address]
                found[d.address] = G2Arm(
                    device = d, address = d.address, name = name, side = side,
                    serial = old?.serial, rssi = old?.rssi,
                    bonded = d.bondState == BluetoothDevice.BOND_BONDED,
                    advertising = old?.advertising ?: false,
                    lastSeenMs = old?.lastSeenMs ?: now,
                )
            }
            publish()
        } catch (e: SecurityException) {
            _error.value = "Berechtigung „Geräte in der Nähe“ fehlt"
        }
    }

    fun start(durationMs: Long = 25_000L) {
        val adapter = manager?.adapter
        if (adapter == null) {
            _error.value = "Diese Uhr hat kein Bluetooth LE"
            return
        }
        if (!adapter.isEnabled) {
            _error.value = "Bluetooth ist ausgeschaltet"
            return
        }
        refreshKnownDevices()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _error.value = "BLE-Scanner nicht verfügbar"
            return
        }
        if (_scanning.value) stopInternal(scanner)
        // Drop stale advertisements, keep bonded entries.
        found.entries.removeAll { !it.value.bonded }
        publish()
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            _scanning.value = true
            _error.value = null
            stopRunnable?.let { main.removeCallbacks(it) }
            val r = Runnable { stop() }
            stopRunnable = r
            main.postDelayed(r, durationMs)
        } catch (e: SecurityException) {
            _error.value = "Berechtigung „Geräte in der Nähe“ fehlt"
        } catch (e: IllegalStateException) {
            _error.value = "Scan nicht möglich: ${e.message}"
        }
    }

    fun stop() {
        stopRunnable?.let { main.removeCallbacks(it) }
        stopRunnable = null
        val scanner = manager?.adapter?.bluetoothLeScanner ?: run { _scanning.value = false; return }
        stopInternal(scanner)
    }

    private fun stopInternal(scanner: android.bluetooth.le.BluetoothLeScanner) {
        try {
            scanner.stopScan(callback)
        } catch (_: Exception) {
        }
        _scanning.value = false
    }

    /**
     * Resolves a remembered address to a device object. Fresh scan results and bonded devices
     * carry the right address type; otherwise the G2's random static address type is assumed
     * (its addresses start with 0b11, and the AtomS3 driver saw address type 1 = random).
     */
    fun remoteDevice(address: String): BluetoothDevice? {
        found[address]?.let { return it.device }
        val adapter = manager?.adapter ?: return null
        return try {
            adapter.bondedDevices.orEmpty().firstOrNull { it.address == address }
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    adapter.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                } else {
                    adapter.getRemoteDevice(address)
                }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun publish() {
        _pairs.value = G2Grouping.group(found.values)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                _scanning.value = false
                _error.value = when (errorCode) {
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Scan fehlgeschlagen (Registrierung) – Bluetooth aus/ein schalten"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE-Scan wird nicht unterstützt"
                    SCAN_FAILED_INTERNAL_ERROR -> "Interner Bluetooth-Fehler beim Scan"
                    SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Zu viele Scans in kurzer Zeit – 30 s warten"
                    else -> "Scan fehlgeschlagen (Code $errorCode)"
                }
            }
        }
    }

    private fun handle(result: ScanResult) {
        val device = result.device ?: return
        val name = try {
            result.scanRecord?.deviceName ?: device.name
        } catch (_: SecurityException) {
            null
        } ?: return
        if (!G2Names.isG2(name)) return
        val side = G2Names.side(name) ?: return
        val mfg = result.scanRecord?.manufacturerSpecificData
        val serial = if (mfg != null && mfg.isNotEmpty()) G2Names.serialFromManufacturerData(mfg.valueAt(0)) else null
        val bonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }
        main.post {
            found[device.address] = G2Arm(
                device = device, address = device.address, name = name, side = side,
                serial = serial ?: found[device.address]?.serial, rssi = result.rssi,
                bonded = bonded, advertising = true, lastSeenMs = SystemClock.elapsedRealtime(),
            )
            publish()
        }
    }
}
