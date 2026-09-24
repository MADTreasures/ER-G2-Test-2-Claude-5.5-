package ch.madtreasures.g2direct.protocol

/**
 * EvenBleTransport framing used on the G2 "5401/5402" characteristic pair.
 *
 * ```
 * [0] 0xAA  magic
 * [1] 0x21  phone -> glasses ((dest 2 << 4) | src 1); glasses answer with 0x12
 * [2] sync  message id, identical on every packet of one message (reassembly key)
 * [3] len   bytes after the 8-byte header in this packet (incl. CRC on the last one)
 * [4] total packets in this message
 * [5] index 1-based packet number
 * [6] sid   service id (0xE0 EvenHub, 0x80 device settings, ...)
 * [7] flags 0x20 = "reserve" flag set by the official app for most services;
 *           in answers bits 1..4 carry a result code (0 = ok)
 * [8..]     payload chunk, the last packet additionally carries the CRC-16/CCITT-FALSE
 *           of the complete payload, little-endian
 * ```
 *
 * Sources: MentraOS G2.kt (EvenBLETransport), g2-kit-unofficial envelope.ts and
 * i-soxi/even-g2-protocol packet-structure.md - three independent, hardware-tested
 * implementations agree on this layout.
 */
object G2Frame {
    const val MAGIC = 0xAA
    const val TYPE_PHONE_TO_GLASSES = 0x21
    const val TYPE_GLASSES_TO_PHONE = 0x12
    const val HEADER_SIZE = 8

    /** Largest body (bytes after the header) the reference implementations ever send. */
    const val MAX_BODY = 236

    /** Packet size limit for the ATT MTU of 247 the official app requests (247 - 3). */
    const val DEFAULT_MAX_PACKET = 244

    const val FLAG_RESERVE = 0x20

    /**
     * Split [payload] into ready-to-write packets.
     *
     * Unlike the MentraOS framer this never exceeds [maxPacketSize]: when the CRC does not
     * fit behind the last chunk it goes into an extra, CRC-only packet (MentraOS and g2-kit
     * both emit such packets as well, so the firmware accepts them).
     */
    fun encode(
        syncId: Int,
        serviceId: Int,
        payload: ByteArray,
        flags: Int = FLAG_RESERVE,
        maxPacketSize: Int = DEFAULT_MAX_PACKET,
    ): List<ByteArray> {
        val maxBody = minOf(MAX_BODY, maxPacketSize - HEADER_SIZE)
        require(maxBody >= 3) { "packet size $maxPacketSize too small" }

        val chunks = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxBody, payload.size)
            chunks.add(payload.copyOfRange(offset, end))
            offset = end
        }
        if (chunks.isEmpty()) chunks.add(ByteArray(0))
        if (chunks.last().size + 2 > maxBody) chunks.add(ByteArray(0))
        require(chunks.size <= 255) { "payload too large for one message: ${payload.size} bytes" }

        val crc = Crc16.ccittFalse(payload)
        val total = chunks.size
        return chunks.mapIndexed { index, chunk ->
            val last = index == total - 1
            val bodyLen = chunk.size + if (last) 2 else 0
            val packet = ByteArray(HEADER_SIZE + bodyLen)
            packet[0] = MAGIC.toByte()
            packet[1] = TYPE_PHONE_TO_GLASSES.toByte()
            packet[2] = syncId.toByte()
            packet[3] = bodyLen.toByte()
            packet[4] = total.toByte()
            packet[5] = (index + 1).toByte()
            packet[6] = serviceId.toByte()
            packet[7] = flags.toByte()
            chunk.copyInto(packet, HEADER_SIZE)
            if (last) {
                packet[HEADER_SIZE + chunk.size] = (crc and 0xFF).toByte()
                packet[HEADER_SIZE + chunk.size + 1] = ((crc ushr 8) and 0xFF).toByte()
            }
            packet
        }
    }
}

/** One complete message received from one arm. */
data class G2Inbound(
    val serviceId: Int,
    val syncId: Int,
    val payload: ByteArray,
    val crcOk: Boolean,
) {
    override fun equals(other: Any?) = other is G2Inbound && serviceId == other.serviceId &&
        syncId == other.syncId && payload.contentEquals(other.payload) && crcOk == other.crcOk

    override fun hashCode() = (serviceId * 31 + syncId) * 31 + payload.contentHashCode()
}

/** Result of feeding one BLE notification into the [G2Reassembler]. */
sealed interface G2RxResult {
    data class Complete(val message: G2Inbound) : G2RxResult
    data object Partial : G2RxResult
    /** The glasses flagged a rejected/aborted frame (non-zero result code in the flags byte). */
    data class Rejected(val serviceId: Int, val syncId: Int, val resultCode: Int, val raw: ByteArray) : G2RxResult
    data class Invalid(val reason: String, val raw: ByteArray) : G2RxResult
}

/** Reassembles multi-packet messages from one arm (keyed by service id + sync id). */
class G2Reassembler {
    private class Partial(val total: Int, var next: Int, val data: java.io.ByteArrayOutputStream)

    private val partials = HashMap<Int, Partial>()

    fun reset() = partials.clear()

    fun accept(raw: ByteArray): G2RxResult {
        if (raw.size < G2Frame.HEADER_SIZE) return G2RxResult.Invalid("zu kurz (${raw.size} B)", raw)
        if ((raw[0].toInt() and 0xFF) != G2Frame.MAGIC) return G2RxResult.Invalid("kein 0xAA-Header", raw)

        val sync = raw[2].toInt() and 0xFF
        val bodyLen = raw[3].toInt() and 0xFF
        val total = raw[4].toInt() and 0xFF
        val index = raw[5].toInt() and 0xFF
        val sid = raw[6].toInt() and 0xFF
        val status = raw[7].toInt() and 0xFF
        val resultCode = (status ushr 1) and 0x0F
        if (resultCode != 0) return G2RxResult.Rejected(sid, sync, resultCode, raw)
        if (raw.size < G2Frame.HEADER_SIZE + bodyLen) return G2RxResult.Invalid("Länge $bodyLen > Paket", raw)
        if (total == 0 || index == 0 || index > total) return G2RxResult.Invalid("Paketindex $index/$total", raw)

        val last = index == total
        val chunkEnd = G2Frame.HEADER_SIZE + bodyLen - if (last) 2 else 0
        if (chunkEnd < G2Frame.HEADER_SIZE) return G2RxResult.Invalid("CRC fehlt", raw)
        val chunk = raw.copyOfRange(G2Frame.HEADER_SIZE, chunkEnd)
        val key = (sid shl 8) or sync

        val payload: ByteArray
        if (total == 1) {
            payload = chunk
        } else {
            if (index == 1) {
                partials[key] = Partial(total, 2, java.io.ByteArrayOutputStream().apply { write(chunk) })
                return G2RxResult.Partial
            }
            val p = partials[key]
            if (p == null || p.total != total || p.next != index) {
                partials.remove(key)
                return G2RxResult.Invalid("Fragment $index/$total ohne Vorgänger", raw)
            }
            p.data.write(chunk)
            p.next++
            if (!last) return G2RxResult.Partial
            partials.remove(key)
            payload = p.data.toByteArray()
        }

        val crcRx = (raw[chunkEnd].toInt() and 0xFF) or ((raw[chunkEnd + 1].toInt() and 0xFF) shl 8)
        return G2RxResult.Complete(G2Inbound(sid, sync, payload, crcRx == Crc16.ccittFalse(payload)))
    }
}
