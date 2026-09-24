package ch.madtreasures.g2direct.ble

import android.bluetooth.BluetoothDevice

enum class Side(val label: String, val short: String) {
    LEFT("links", "L"),
    RIGHT("rechts", "R"),
}

/** One arm of a pair of G2 glasses as seen by the watch (scan result and/or bonded device). */
data class G2Arm(
    val device: BluetoothDevice,
    val address: String,
    val name: String,
    val side: Side,
    /** Serial number from the advertisement's manufacturer data, if seen. */
    val serial: String?,
    val rssi: Int?,
    val bonded: Boolean,
    val advertising: Boolean,
    val lastSeenMs: Long,
)

/** Both arms that belong together. [key] groups them (serial or name-derived id). */
data class G2Pair(
    val key: String,
    val title: String,
    val left: G2Arm?,
    val right: G2Arm?,
) {
    val complete: Boolean get() = left != null && right != null
}

object G2Names {
    /** Advertised names look like "Even G2_32_L_A4DE80" (left) / "Even G2_32_R_..." (right). */
    private val NAME = Regex("""G2_(\w+?)_([LR])_(\w+)""")

    fun isG2(name: String?): Boolean = name != null && name.contains("G2")

    fun side(name: String): Side? {
        NAME.find(name)?.let { return if (it.groupValues[2] == "L") Side.LEFT else Side.RIGHT }
        return when {
            name.contains("_L_") -> Side.LEFT
            name.contains("_R_") -> Side.RIGHT
            else -> null
        }
    }

    /** Grouping id derived from the name when no serial number is available (bonded devices). */
    fun groupId(name: String): String = NAME.find(name)?.groupValues?.get(1) ?: name

    /**
     * Manufacturer-specific data (company id already stripped by Android):
     * 14 bytes ASCII serial number, 6 bytes MAC (little-endian), flags.
     * Layout from MentraOS G2.kt extractSNFromScanRecord.
     */
    fun serialFromManufacturerData(data: ByteArray?): String? {
        if (data == null || data.size < 14) return null
        val sn = String(data, 0, 14, Charsets.US_ASCII).filter { it.code in 0x21..0x7E }
        return sn.ifEmpty { null }
    }
}

object G2Grouping {
    fun group(arms: Collection<G2Arm>): List<G2Pair> {
        // First key by serial where present; bonded devices without serial join a serial group
        // through a scanned arm with the same name-derived id.
        val idToSerial = HashMap<String, String>()
        for (a in arms) if (a.serial != null) idToSerial[G2Names.groupId(a.name)] = a.serial
        val groups = LinkedHashMap<String, MutableList<G2Arm>>()
        for (a in arms) {
            val key = a.serial ?: idToSerial[G2Names.groupId(a.name)] ?: "id:" + G2Names.groupId(a.name)
            groups.getOrPut(key) { ArrayList() }.add(a)
        }
        return groups.map { (key, list) ->
            fun best(side: Side) = list.filter { it.side == side }
                .maxWithOrNull(compareBy<G2Arm>({ it.advertising }, { it.lastSeenMs }))
            val left = best(Side.LEFT)
            val right = best(Side.RIGHT)
            val serial = list.firstNotNullOfOrNull { it.serial }
            val title = when {
                serial != null -> "G2 · SN …" + serial.takeLast(6)
                else -> "G2 · " + G2Names.groupId((right ?: left)!!.name)
            }
            G2Pair(key, title, left, right)
        }.sortedWith(compareByDescending<G2Pair> { it.complete }.thenByDescending { it.right?.rssi ?: -200 })
    }
}
