package ch.madtreasures.g2direct.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.UUID

/**
 * One GATT connection to one arm of the glasses.
 *
 * All state lives on the [handler] thread; Android GATT callbacks arrive on binder threads
 * and are posted over. Lessons from the reference drivers that shaped this class:
 * - request MTU 247 before service discovery (Android starts at 23 bytes, packets are 244);
 * - write the CCCD descriptors explicitly, one GATT operation at a time;
 * - a write-without-response can be refused while the stack is busy: keep the packet and
 *   retry instead of dropping it, because a missing packet corrupts the whole message;
 * - never interleave packets of different messages (the firmware has one reassembly buffer);
 * - always close() the BluetoothGatt, leaked clients cause status 133 later.
 */
@SuppressLint("MissingPermission")
class G2Link(
    private val context: Context,
    override val side: Side,
    val device: BluetoothDevice,
    private val handler: Handler,
    private val listener: LinkListener,
) : ArmLink {

    companion object {
        val CHAR_WRITE: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5401")
        val CHAR_NOTIFY: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5402")
        /** Audio / second channel notify. Not used for data, subscribed like the Even app does. */
        val CHAR_NOTIFY_2: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e6402")
        /** File-service notify. Subscribed like MentraOS does, ignored otherwise. */
        val CHAR_NOTIFY_3: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e7402")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val WANTED_MTU = 247
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val SETUP_STEP_TIMEOUT_MS = 8_000L
        /** Minimum spacing between two packets (MentraOS 8 ms, ffs-os 6 ms). */
        private const val PACKET_GAP_MS = 7L
        private const val BUSY_RETRY_MS = 10L
        private const val MAX_BUSY_RETRIES = 150
        private const val WRITE_CALLBACK_TIMEOUT_MS = 80L
    }

    @Volatile
    override var state: LinkState = LinkState.CLOSED
        private set

    override var mtu: Int = 23
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val pendingSubscriptions = ArrayDeque<BluetoothGattCharacteristic>()
    private var everConnected = false
    private var timeout: Runnable? = null

    // ---- write queue ----
    private val queue = ArrayDeque<ByteArray>()
    private var draining = false
    private var waitingForWriteCallback = false
    private var busyRetries = 0
    private var lastWriteAt = 0L
    private var writeGeneration = 0
    override var packetsSent = 0L
        private set
    override var packetsDropped = 0L
        private set

    val queuedPackets: Int get() = queue.size

    /** Largest packet this link can carry in one write-without-response. */
    override val maxPacketSize: Int get() = (mtu - 3).coerceAtMost(244)

    override fun connect() {
        check(state == LinkState.CLOSED) { "already started" }
        setState(LinkState.CONNECTING, "Verbinde mit ${device.address}")
        // autoConnect=false: a direct connection attempt, the right choice right after the
        // device was seen or picked by the user. Callbacks are delivered on our handler.
        val g = device.connectGatt(
            context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler
        )
        if (g == null) {
            fail("connectGatt lieferte null (Bluetooth-Stack verweigert)")
            return
        }
        gatt = g
        armTimeout(CONNECT_TIMEOUT_MS) { "Keine Verbindung nach ${CONNECT_TIMEOUT_MS / 1000} s" }
    }

    /** Queue a complete message (all its packets) for sending. Packets of one message stay contiguous. */
    override fun send(packets: List<ByteArray>): Boolean {
        if (state != LinkState.READY) return false
        for (p in packets) queue.addLast(p)
        if (!draining) {
            draining = true
            handler.post { drain() }
        }
        return true
    }

    override fun requestHighPriority() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    override fun close(reason: String) {
        cancelTimeout()
        queue.clear()
        draining = false
        waitingForWriteCallback = false
        val g = gatt
        gatt = null
        writeChar = null
        if (g != null) {
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
        if (state != LinkState.CLOSED) setState(LinkState.CLOSED, reason)
    }

    // ------------------------------------------------------------------------------------------

    private fun setState(s: LinkState, detail: String) {
        state = s
        listener.onLinkState(this, s, detail)
    }

    private fun log(msg: String) = listener.onLinkLog(this, msg)

    private fun fail(reason: String) {
        close(reason)
        listener.onLinkFailed(this, reason)
    }

    private fun armTimeout(ms: Long, reason: () -> String) {
        cancelTimeout()
        val r = Runnable {
            timeout = null
            if (state != LinkState.CLOSED && state != LinkState.READY) fail(reason())
        }
        timeout = r
        handler.postDelayed(r, ms)
    }

    private fun cancelTimeout() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
    }

    private fun onConnectionState(g: BluetoothGatt, status: Int, newState: Int) {
        if (g !== gatt) return
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (state != LinkState.CONNECTING) return
            everConnected = true
            setState(LinkState.CONNECTED, "verbunden, MTU wird ausgehandelt")
            armTimeout(SETUP_STEP_TIMEOUT_MS) { "MTU-/Dienstsuche hängt" }
            if (!g.requestMtu(WANTED_MTU)) {
                log("requestMtu abgelehnt, Dienstsuche mit Standard-MTU")
                startDiscovery(g)
            } else {
                // Some stacks never report the MTU exchange; do not wait forever for it.
                handler.postDelayed({
                    if (g === gatt && state == LinkState.CONNECTED) {
                        log("Keine MTU-Antwort, Dienstsuche mit MTU $mtu")
                        startDiscovery(g)
                    }
                }, 3_000L)
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            val why = GattStatus.describe(status)
            if (state == LinkState.READY || everConnected) fail("Verbindung getrennt: $why")
            else fail("Verbindung fehlgeschlagen: $why")
        }
    }

    private fun startDiscovery(g: BluetoothGatt) {
        setState(LinkState.DISCOVERING, "MTU $mtu, suche Dienste")
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (!g.discoverServices()) fail("discoverServices abgelehnt")
    }

    private fun onServices(g: BluetoothGatt, status: Int) {
        if (g !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("Dienstsuche fehlgeschlagen: ${GattStatus.describe(status)}")
            return
        }
        var write: BluetoothGattCharacteristic? = null
        val notify = ArrayList<BluetoothGattCharacteristic>()
        for (service in g.services) {
            for (c in service.characteristics) {
                when (c.uuid) {
                    CHAR_WRITE -> write = c
                    CHAR_NOTIFY, CHAR_NOTIFY_2, CHAR_NOTIFY_3 -> notify += c
                }
            }
        }
        log("Dienste: " + g.services.joinToString { it.uuid.toString().substring(4, 8) + "…" + it.uuid.toString().takeLast(4) })
        if (write == null || notify.none { it.uuid == CHAR_NOTIFY }) {
            fail("G2-Kanal 5401/5402 nicht gefunden – ist das ein Even-G2-Bügel?")
            return
        }
        writeChar = write
        pendingSubscriptions.clear()
        // Protocol channel first, so it is enabled even if an optional one fails.
        notify.sortBy { if (it.uuid == CHAR_NOTIFY) 0 else 1 }
        pendingSubscriptions.addAll(notify)
        setState(LinkState.SUBSCRIBING, "aktiviere Benachrichtigungen")
        subscribeNext(g)
    }

    private fun subscribeNext(g: BluetoothGatt) {
        val c = pendingSubscriptions.pollFirst()
        if (c == null) {
            cancelTimeout()
            setState(LinkState.READY, "bereit (MTU $mtu)")
            return
        }
        if (!g.setCharacteristicNotification(c, true)) log("setCharacteristicNotification(${short(c.uuid)}) abgelehnt")
        val d = c.getDescriptor(CCCD)
        if (d == null) {
            subscribeNext(g)
            return
        }
        armTimeout(SETUP_STEP_TIMEOUT_MS) { "CCCD-Schreiben für ${short(c.uuid)} ohne Antwort" }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        if (!ok) {
            if (c.uuid == CHAR_NOTIFY) fail("Benachrichtigungen für 5402 konnten nicht aktiviert werden")
            else {
                log("CCCD ${short(c.uuid)} abgelehnt, übersprungen")
                subscribeNext(g)
            }
        }
    }

    private fun onDescriptorWritten(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
        if (g !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            val msg = "CCCD ${short(d.characteristic.uuid)}: ${GattStatus.describe(status)}"
            if (d.characteristic.uuid == CHAR_NOTIFY) {
                fail(msg)
                return
            }
            log("$msg (übersprungen)")
        }
        subscribeNext(g)
    }

    private fun drain() {
        val g = gatt
        val c = writeChar
        if (state != LinkState.READY || g == null || c == null) {
            draining = false
            queue.clear()
            return
        }
        if (waitingForWriteCallback) return
        val packet = queue.peekFirst()
        if (packet == null) {
            draining = false
            return
        }
        val now = SystemClock.uptimeMillis()
        val wait = lastWriteAt + PACKET_GAP_MS - now
        if (wait > 0) {
            handler.postDelayed({ drain() }, wait)
            return
        }
        val status = writeNoResponse(g, c, packet)
        if (status != 0) {
            busyRetries++
            if (busyRetries > MAX_BUSY_RETRIES) {
                // Dropping corrupts one message; stalling forever would kill heartbeats too.
                queue.pollFirst()
                packetsDropped++
                busyRetries = 0
                log("Paket nach $MAX_BUSY_RETRIES Versuchen verworfen (Status $status)")
            }
            handler.postDelayed({ drain() }, BUSY_RETRY_MS)
            return
        }
        busyRetries = 0
        queue.pollFirst()
        packetsSent++
        lastWriteAt = now
        // Android reports completion of write-without-response via onCharacteristicWrite.
        // Wait for it (or a short timeout, some stacks skip it) before the next packet.
        waitingForWriteCallback = true
        val gen = ++writeGeneration
        handler.postDelayed({
            if (waitingForWriteCallback && writeGeneration == gen) {
                waitingForWriteCallback = false
                drain()
            }
        }, WRITE_CALLBACK_TIMEOUT_MS)
    }

    private fun onWriteDone(g: BluetoothGatt) {
        if (g !== gatt) return
        if (waitingForWriteCallback) {
            waitingForWriteCallback = false
            drain()
        }
    }

    private fun writeNoResponse(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            if (g.writeCharacteristic(c)) 0 else -1
        }
    } catch (e: SecurityException) {
        log("Schreiben verweigert: ${e.message}")
        -2
    }

    private fun short(u: UUID) = u.toString().substring(32)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post { onConnectionState(gatt, status, newState) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (gatt !== this@G2Link.gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) this@G2Link.mtu = mtu
                log("MTU $mtu (Status ${GattStatus.describe(status)})")
                if (state == LinkState.CONNECTED) startDiscovery(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post { onServices(gatt, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post { onDescriptorWritten(gatt, descriptor, status) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post { onWriteDone(gatt) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            val uuid = characteristic.uuid
            handler.post { if (gatt === this@G2Link.gatt) listener.onLinkData(this@G2Link, uuid, copy) }
        }

        @Deprecated("Used below API 33 only")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val copy = characteristic.value?.copyOf() ?: return
            val uuid = characteristic.uuid
            handler.post { if (gatt === this@G2Link.gatt) listener.onLinkData(this@G2Link, uuid, copy) }
        }
    }
}

object GattStatus {
    fun describe(status: Int): String = when (status) {
        0 -> "OK"
        5 -> "5 (Authentifizierung nötig – Kopplung prüfen)"
        8 -> "8 (Verbindungs-Timeout, Brille außer Reichweite?)"
        15 -> "15 (Verschlüsselung nötig – Kopplung prüfen)"
        19 -> "19 (von der Brille getrennt)"
        22 -> "22 (von der Uhr getrennt)"
        34 -> "34 (Link-Layer-Timeout)"
        62 -> "62 (Verbindungsaufbau gescheitert)"
        133 -> "133 (GATT_ERROR – Brille belegt oder nicht erreichbar)"
        147 -> "147 (Verbindungs-Timeout)"
        257 -> "257 (GATT_FAILURE)"
        else -> "Status $status"
    }
}
