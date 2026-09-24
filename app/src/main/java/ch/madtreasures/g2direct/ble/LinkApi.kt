package ch.madtreasures.g2direct.ble

import java.util.UUID

/** Connection state of one arm. */
enum class LinkState { CONNECTING, CONNECTED, DISCOVERING, SUBSCRIBING, READY, CLOSED }

/** What the session needs from the BLE connection to one arm (real: [G2Link], tests: a fake). */
interface ArmLink {
    val side: Side
    val state: LinkState
    val mtu: Int
    /** Largest packet one write-without-response can carry on this link. */
    val maxPacketSize: Int
    val packetsSent: Long
    val packetsDropped: Long

    fun connect()

    /** Queues all packets of one message; they are written back to back, never interleaved. */
    fun send(packets: List<ByteArray>): Boolean

    fun requestHighPriority()

    fun close(reason: String)
}

/** Callbacks from an [ArmLink]; always delivered on the session thread. */
interface LinkListener {
    fun onLinkState(link: ArmLink, state: LinkState, detail: String)
    fun onLinkFailed(link: ArmLink, reason: String)
    fun onLinkData(link: ArmLink, characteristic: UUID, data: ByteArray)
    fun onLinkLog(link: ArmLink, message: String)
}

/** An arm to connect to. [device] carries the platform object (android BluetoothDevice). */
data class ArmTarget(
    val address: String,
    val name: String?,
    val bonded: Boolean,
    val device: Any? = null,
)

data class ConnectRequest(
    val title: String,
    val right: ArmTarget,
    val left: ArmTarget?,
)

/** Platform services the session engine uses; replaced by a virtual-time fake in tests. */
interface EngineEnv {
    fun elapsedMs(): Long
    fun wallClockMs(): Long
    fun utcOffsetMs(epochMs: Long): Int
    fun log(message: String)
    fun createLink(side: Side, target: ArmTarget, listener: LinkListener): ArmLink
}
