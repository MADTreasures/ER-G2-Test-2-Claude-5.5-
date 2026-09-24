package ch.madtreasures.g2direct.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FramingTest {

    /** Byte-wise formulation used by MentraOS G2.kt (g2Crc16) and the AtomS3 sketch. */
    private fun mentraCrc(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            val b = byte.toInt() and 0xFF
            crc = ((crc shr 8) or ((crc shl 8) and 0xFF00)) xor b
            crc = crc xor ((crc and 0xFF) shr 4)
            crc = crc xor ((crc shl 12) and 0xFFFF)
            crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
        }
        return crc and 0xFFFF
    }

    @Test
    fun crcMatchesStandardCheckValue() {
        assertEquals(0x29B1, Crc16.ccittFalse("123456789".toByteArray()))
    }

    @Test
    fun crcMatchesMentraImplementation() {
        val rnd = Random(42)
        repeat(200) {
            val data = ByteArray(rnd.nextInt(0, 600)).also { rnd.nextBytes(it) }
            assertEquals(mentraCrc(data), Crc16.ccittFalse(data))
        }
    }

    @Test
    fun heartbeatFrameMatchesReferenceLayout() {
        // EvenHub heartbeat as MentraOS builds it: {1:12, 2:0, 14:{}}
        val payload = G2Messages.evenHubHeartbeat(0)
        assertArrayEquals(byteArrayOf(0x08, 0x0C, 0x10, 0x00, 0x72, 0x00), payload)
        val packets = G2Frame.encode(syncId = 7, serviceId = ServiceId.EVEN_HUB, payload = payload)
        assertEquals(1, packets.size)
        val p = packets[0]
        val crc = Crc16.ccittFalse(payload)
        val expected = byteArrayOf(
            0xAA.toByte(), 0x21, 7, (payload.size + 2).toByte(), 1, 1, 0xE0.toByte(), 0x20,
        ) + payload + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
        assertArrayEquals(expected, p)
    }

    @Test
    fun devSettingsFrameHasNoReserveFlag() {
        val p = G2Frame.encode(1, ServiceId.DEVICE_SETTINGS, G2Messages.auth(3), flags = 0x00)[0]
        assertEquals(0x80.toByte(), p[6])
        assertEquals(0x00.toByte(), p[7])
    }

    @Test
    fun largePayloadIsSplitWithoutExceedingMtu() {
        val rnd = Random(1)
        for (size in listOf(0, 1, 233, 234, 235, 236, 237, 471, 472, 1000, 3900)) {
            val payload = ByteArray(size).also { rnd.nextBytes(it) }
            val packets = G2Frame.encode(9, ServiceId.EVEN_HUB, payload)
            assertTrue(packets.all { it.size <= G2Frame.DEFAULT_MAX_PACKET })
            packets.forEachIndexed { i, pkt ->
                assertEquals(9.toByte(), pkt[2])
                assertEquals(packets.size.toByte(), pkt[4])
                assertEquals((i + 1).toByte(), pkt[5])
                assertEquals((pkt.size - 8).toByte(), pkt[3])
            }
            // Round trip through the reassembler (answers use the same layout).
            val r = G2Reassembler()
            var result: G2RxResult? = null
            for (pkt in packets) result = r.accept(pkt)
            val complete = result as G2RxResult.Complete
            assertArrayEquals(payload, complete.message.payload)
            assertTrue(complete.message.crcOk)
        }
    }

    @Test
    fun smallerMtuIsRespected() {
        val payload = ByteArray(500) { it.toByte() }
        val packets = G2Frame.encode(1, ServiceId.EVEN_HUB, payload, maxPacketSize = 182)
        assertTrue(packets.all { it.size <= 182 })
        val r = G2Reassembler()
        var result: G2RxResult? = null
        for (pkt in packets) result = r.accept(pkt)
        assertArrayEquals(payload, (result as G2RxResult.Complete).message.payload)
    }

    @Test
    fun rejectedFrameIsReported() {
        val raw = byteArrayOf(0xAA.toByte(), 0x12, 5, 2, 1, 1, 0xE0.toByte(), 0x02, 0, 0)
        val res = G2Reassembler().accept(raw)
        assertTrue(res is G2RxResult.Rejected)
        assertEquals(1, (res as G2RxResult.Rejected).resultCode)
    }

    @Test
    fun decodesTextAckSeenByMentra() {
        // MentraOS filters "080652020808" as noise: cmd 6 (text response), TextResCmd{code 8}.
        val payload = byteArrayOf(0x08, 0x06, 0x52, 0x02, 0x08, 0x08)
        val ev = G2Responses.decode(G2Inbound(ServiceId.EVEN_HUB, 0, payload, true))
        assertEquals(G2Event.PageResult(EvenHubCmd.RESPONSE_TEXT_DATA, null, EvenHubResult.TEXT_SUCCESS), ev)
    }

    @Test
    fun decodesHeartbeatAckSeenByMentra() {
        val payload = byteArrayOf(0x08, 0x0C, 0x7A, 0x02, 0x10, 0x0C)
        val ev = G2Responses.decode(G2Inbound(ServiceId.EVEN_HUB, 0, payload, true))
        assertEquals(G2Event.HeartbeatAck(EvenHubResult.HEARTBEAT_SUCCESS), ev)
    }

    @Test
    fun decodesImageAck() {
        val img = ProtoWriter.build { int(1, 10); string(2, "wedge"); int(3, 5); int(6, 2); int(8, 4) }
        val payload = ProtoWriter.build { int(1, 4); message(6, img) }
        val ev = G2Responses.decode(G2Inbound(ServiceId.EVEN_HUB, 0, payload, true))
        assertEquals(G2Event.ImageAck(10, 5, 2, EvenHubResult.IMAGE_SUCCESS), ev)
    }

    @Test
    fun decodesSysEventWithOmittedClickType() {
        val payload = ProtoWriter.build { int(1, 2); message(13) { message(3) { int(2, 1) } } }
        val ev = G2Responses.decode(G2Inbound(ServiceId.EVEN_HUB, 0, payload, true))
        assertEquals(G2Event.Input("sys", OsEvent.CLICK, null, 1), ev)
    }

    @Test
    fun decodesAuthAnswer() {
        val payload = ProtoWriter.build { int(1, 4); int(2, 9); message(3) { bool(1, true) } }
        assertEquals(G2Event.Auth(true), G2Responses.decode(G2Inbound(ServiceId.DEVICE_SETTINGS, 0, payload, true)))
    }

    @Test
    fun decodesDeviceInfo() {
        val payload = ProtoWriter.build {
            int(1, 2)
            message(4) { string(5, "2.2.7.14"); string(6, "2.2.7.14"); int(12, 81); int(13, 0) }
        }
        val ev = G2Responses.decode(G2Inbound(ServiceId.G2_SETTING, 0, payload, true))
        assertEquals(G2Event.DeviceInfo(81, false, "2.2.7.14", "2.2.7.14"), ev)
    }
}
