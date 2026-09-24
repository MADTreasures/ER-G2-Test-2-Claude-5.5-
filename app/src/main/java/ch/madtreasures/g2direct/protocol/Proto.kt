package ch.madtreasures.g2direct.protocol

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf (proto3 wire format) writer.
 *
 * The G2 firmware speaks protobuf inside its BLE frames. Only the wire types the
 * protocol actually uses are implemented: varint (0) and length-delimited (2).
 * Every field is written explicitly, including zero values, exactly like the
 * MentraOS G2 driver does - the firmware accepts both forms, and explicit zeros
 * keep the byte layout predictable for tests.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(value: Long): ProtoWriter {
        var v = value
        // Unsigned comparison so negative int32 values become 10-byte varints (protobuf rule).
        while (v.toULong() > 0x7FuL) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
        return this
    }

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    fun int(field: Int, value: Int): ProtoWriter {
        tag(field, 0)
        return varint(value.toLong())
    }

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: ByteArray): ProtoWriter = bytes(field, value)

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun build(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).toByteArray()
    }
}

/**
 * Minimal protobuf reader producing a multimap of field number to raw values.
 * Varints become [Long], length-delimited fields [ByteArray], fixed32 [Int] bits
 * and fixed64 [Long] bits. Parsing stops silently at the first malformed byte,
 * returning whatever was decoded so far - notifications are best-effort data.
 */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    val fieldNumbers: Set<Int> get() = fields.keys

    fun has(field: Int): Boolean = fields.containsKey(field)

    fun long(field: Int): Long? = fields[field]?.firstOrNull { it is Long } as Long?

    fun int(field: Int): Int? = long(field)?.toInt()

    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull { it is ByteArray } as ByteArray?

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    fun messages(field: Int): List<ProtoMessage> =
        fields[field].orEmpty().filterIsInstance<ByteArray>().map { parse(it) }

    fun fixed32(field: Int): Int? = fields[field]?.firstOrNull { it is Int } as Int?

    override fun toString(): String = fields.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
        "$k=" + v.joinToString("|") { value ->
            when (value) {
                is ByteArray -> "[" + value.toHex() + "]"
                else -> value.toString()
            }
        }
    }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val map = LinkedHashMap<Int, MutableList<Any>>()
            var i = 0

            fun readVarint(): Long? {
                var result = 0L
                var shift = 0
                while (i < data.size) {
                    val b = data[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) return null
                }
                return null
            }

            while (i < data.size) {
                val key = readVarint() ?: break
                val field = (key ushr 3).toInt()
                if (field == 0) break
                val value: Any = when ((key and 7).toInt()) {
                    0 -> readVarint() ?: break
                    1 -> {
                        if (i + 8 > data.size) break
                        var v = 0L
                        for (k in 0 until 8) v = v or ((data[i + k].toLong() and 0xFF) shl (8 * k))
                        i += 8
                        v
                    }
                    2 -> {
                        val len = readVarint() ?: break
                        if (len < 0 || i + len > data.size) break
                        val v = data.copyOfRange(i, i + len.toInt())
                        i += len.toInt()
                        v
                    }
                    5 -> {
                        if (i + 4 > data.size) break
                        var v = 0
                        for (k in 0 until 4) v = v or ((data[i + k].toInt() and 0xFF) shl (8 * k))
                        i += 4
                        v
                    }
                    else -> break
                }
                map.getOrPut(field) { ArrayList(1) }.add(value)
            }
            return ProtoMessage(map)
        }
    }
}

fun ByteArray.toHex(max: Int = Int.MAX_VALUE): String {
    val n = minOf(size, max)
    val sb = StringBuilder(n * 2 + 3)
    for (k in 0 until n) {
        val v = this[k].toInt() and 0xFF
        sb.append("0123456789ABCDEF"[v ushr 4]).append("0123456789ABCDEF"[v and 0xF])
    }
    if (n < size) sb.append("…")
    return sb.toString()
}
