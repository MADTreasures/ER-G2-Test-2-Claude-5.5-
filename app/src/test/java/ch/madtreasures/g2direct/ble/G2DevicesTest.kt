package ch.madtreasures.g2direct.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Name parsing and left/right grouping behind the device list. */
class G2DevicesTest {

    /**
     * BluetoothDevice has no public constructor and the grouping only carries it along, so
     * every arm gets the same uninitialised instance (plain JVM test, no Robolectric).
     */
    private val device: BluetoothDevice by lazy {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, BluetoothDevice::class.java) as BluetoothDevice
    }

    private fun arm(
        name: String,
        address: String,
        serial: String? = null,
        rssi: Int? = null,
        bonded: Boolean = false,
        seenMs: Long = 0,
    ) = G2Arm(device, address, name, G2Names.side(name)!!, serial, rssi, bonded, rssi != null, seenMs)

    @Test
    fun parsesSideAndPairIdFromName() {
        assertEquals(Side.LEFT, G2Names.side("Even G2_32_L_A4DE7F"))
        assertEquals(Side.RIGHT, G2Names.side("Even G2_32_R_A4DE80"))
        assertNull(G2Names.side("Even G2 Case"))
        assertEquals("32", G2Names.groupId("Even G2_32_L_A4DE7F"))
        assertEquals("32", G2Names.groupId("Even G2_32_R_A4DE80"))
        assertTrue(G2Names.isG2("Even G2_32_R_A4DE80"))
        assertFalse(G2Names.isG2("Pixel Buds Pro"))
        assertFalse(G2Names.isG2(null))
    }

    @Test
    fun readsSerialFromManufacturerData() {
        val serial = "S110A4DE80ABCD".toByteArray(Charsets.US_ASCII)
        val mac = byteArrayOf(0x80.toByte(), 0xDE.toByte(), 0xA4.toByte(), 0xEF.toByte(), 0x9D.toByte(), 0xE8.toByte())
        assertEquals("S110A4DE80ABCD", G2Names.serialFromManufacturerData(serial + mac + byteArrayOf(1)))
        assertNull(G2Names.serialFromManufacturerData(serial.copyOf(10)))
        assertNull(G2Names.serialFromManufacturerData(null))
    }

    @Test
    fun scannedArmsWithSameSerialFormOnePair() {
        val pairs = G2Grouping.group(listOf(
            arm("Even G2_32_L_A4DE7F", "E8:9D:EF:A4:DE:7F", serial = "S110A4DE80ABCD", rssi = -61),
            arm("Even G2_32_R_A4DE80", "E8:9D:EF:A4:DE:80", serial = "S110A4DE80ABCD", rssi = -58),
        ))
        assertEquals(1, pairs.size)
        val pair = pairs.single()
        assertTrue(pair.complete)
        assertEquals("E8:9D:EF:A4:DE:7F", pair.left?.address)
        assertEquals("E8:9D:EF:A4:DE:80", pair.right?.address)
        assertEquals("G2 · SN …80ABCD", pair.title)
    }

    @Test
    fun bondedArmThatDoesNotAdvertiseJoinsItsScannedPartner() {
        // A bonded (or system-connected) arm often stays silent: no advertisement, no serial.
        val pairs = G2Grouping.group(listOf(
            arm("Even G2_32_R_A4DE80", "E8:9D:EF:A4:DE:80", bonded = true),
            arm("Even G2_32_L_A4DE7F", "E8:9D:EF:A4:DE:7F", serial = "S110A4DE80ABCD", rssi = -61),
        ))
        assertEquals(1, pairs.size)
        assertTrue(pairs.single().complete)
        assertTrue(pairs.single().right!!.bonded)
    }

    @Test
    fun onlyBondedArmsAreGroupedByTheNameId() {
        val pairs = G2Grouping.group(listOf(
            arm("Even G2_32_L_A4DE7F", "E8:9D:EF:A4:DE:7F", bonded = true),
            arm("Even G2_32_R_A4DE80", "E8:9D:EF:A4:DE:80", bonded = true),
        ))
        assertEquals(1, pairs.size)
        assertTrue(pairs.single().complete)
        assertEquals("G2 · 32", pairs.single().title)
    }

    @Test
    fun differentGlassesStayApartAndCompletePairsComeFirst() {
        val pairs = G2Grouping.group(listOf(
            arm("Even G2_47_R_11AA22", "E8:9D:EF:11:AA:22", serial = "S110XXXXXXXXXX", rssi = -40),
            arm("Even G2_32_L_A4DE7F", "E8:9D:EF:A4:DE:7F", serial = "S110A4DE80ABCD", rssi = -70),
            arm("Even G2_32_R_A4DE80", "E8:9D:EF:A4:DE:80", serial = "S110A4DE80ABCD", rssi = -72),
        ))
        assertEquals(2, pairs.size)
        assertTrue(pairs[0].complete)
        assertEquals("E8:9D:EF:A4:DE:80", pairs[0].right?.address)
        assertFalse(pairs[1].complete)
        assertNull(pairs[1].left)
    }

    @Test
    fun freshAdvertisementWinsOverStaleBondedEntryOfTheSameSide() {
        // Same arm under an old bonded address and a new advertised one (e.g. after a reset).
        val pairs = G2Grouping.group(listOf(
            arm("Even G2_32_R_A4DE80", "E8:9D:EF:00:00:01", bonded = true, seenMs = 5_000),
            arm("Even G2_32_R_A4DE80", "E8:9D:EF:A4:DE:80", serial = "S110A4DE80ABCD", rssi = -58, seenMs = 1_000),
        ))
        assertEquals(1, pairs.size)
        assertEquals("E8:9D:EF:A4:DE:80", pairs.single().right?.address)
    }
}
