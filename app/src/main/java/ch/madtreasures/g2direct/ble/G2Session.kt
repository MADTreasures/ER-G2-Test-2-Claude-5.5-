package ch.madtreasures.g2direct.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import ch.madtreasures.g2direct.protocol.CursorLayers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import java.util.TimeZone

/**
 * Android side of the session: one "G2-Core" HandlerThread runs the [SessionEngine] and
 * receives all GATT callbacks of the [G2Link]s, so the engine never needs locks.
 */
@SuppressLint("MissingPermission")
class G2Session(context: Context) {
    private val app = context.applicationContext
    private val thread = HandlerThread("G2-Core").also { it.start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher("G2-Core"))

    private val env = object : EngineEnv {
        override fun elapsedMs() = SystemClock.elapsedRealtime()
        override fun wallClockMs() = System.currentTimeMillis()
        override fun utcOffsetMs(epochMs: Long) = TimeZone.getDefault().getOffset(epochMs)
        override fun log(message: String) {
            Log.i(TAG, message)
        }

        override fun createLink(side: Side, target: ArmTarget, listener: LinkListener): ArmLink =
            G2Link(app, side, target.device as BluetoothDevice, handler, listener)
    }

    private val engine = SessionEngine(scope, env)

    val state: StateFlow<SessionState> get() = engine.state
    val log: StateFlow<List<String>> get() = engine.log

    fun connect(title: String, right: BluetoothDevice, left: BluetoothDevice?) =
        engine.connect(ConnectRequest(title, target(right), left?.let { target(it) }))

    fun disconnect() = engine.disconnect()
    fun rebuildTestPage() = engine.rebuildTestPage()
    fun setStyle(style: CursorLayers.Style) = engine.setStyle(style)
    fun setSpeed(speed: Float) = engine.setSpeed(speed)
    fun moveCursorBy(dx: Float, dy: Float) = engine.moveCursorBy(dx, dy)
    fun centerCursor() = engine.centerCursor()
    fun onTouchStart() = engine.onTouchStart()

    private fun target(device: BluetoothDevice): ArmTarget {
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        val bonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }
        return ArmTarget(device.address, name, bonded, device)
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }) ?: return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDING ->
                    engine.onBondState(device.address, "Kopplung läuft – ggf. auf der Uhr bestätigen", bonded = false, inProgress = true)
                BluetoothDevice.BOND_BONDED ->
                    engine.onBondState(device.address, "gekoppelt", bonded = true, inProgress = false)
                BluetoothDevice.BOND_NONE ->
                    engine.onBondState(device.address, "nicht (mehr) gekoppelt", bonded = false, inProgress = false)
            }
        }
    }

    init {
        // Bond changes come from the Bluetooth process, so the receiver must be exported.
        ContextCompat.registerReceiver(
            app, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED
        )
    }

    companion object {
        private const val TAG = "G2Direct"
    }
}
