package ch.madtreasures.g2direct.protocol

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A 4-bit (16 level) greyscale image and its encoding as the uncompressed 4 bpp BMP
 * file the G2 image containers accept (same layout as MentraOS `build4BitBmp` and the
 * Python gateway `build_4bit_bmp`, both hardware-tested). Level 0 is "off", which the
 * firmware treats as transparent; 15 is full brightness.
 */
class Gray4Bitmap(val width: Int, val height: Int) {
    private val px = ByteArray(width * height)

    operator fun get(x: Int, y: Int): Int = px[y * width + x].toInt()

    operator fun set(x: Int, y: Int, level: Int) {
        if (x in 0 until width && y in 0 until height) px[y * width + x] = level.coerceIn(0, 15).toByte()
    }

    fun fillRect(x0: Int, y0: Int, w: Int, h: Int, level: Int) {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) this[x, y] = level
    }

    fun hLine(x0: Int, x1: Int, y: Int, level: Int) {
        for (x in minOf(x0, x1)..maxOf(x0, x1)) this[x, y] = level
    }

    fun vLine(x: Int, y0: Int, y1: Int, level: Int) {
        for (y in minOf(y0, y1)..maxOf(y0, y1)) this[x, y] = level
    }

    fun rect(x0: Int, y0: Int, w: Int, h: Int, level: Int) {
        hLine(x0, x0 + w - 1, y0, level)
        hLine(x0, x0 + w - 1, y0 + h - 1, level)
        vLine(x0, y0, y0 + h - 1, level)
        vLine(x0 + w - 1, y0, y0 + h - 1, level)
    }

    /** Anti-aliasing-free circle outline of the given stroke width. */
    fun circle(cx: Double, cy: Double, r: Double, stroke: Double, level: Int) {
        for (y in 0 until height) for (x in 0 until width) {
            if (abs(hypot(x + 0.5 - cx, y + 0.5 - cy) - r) <= stroke / 2) this[x, y] = level
        }
    }

    /** Uncompressed 4 bpp BMP, bottom-up rows padded to 4 bytes, 16-entry grey palette. */
    fun toBmp(): ByteArray {
        val rowBytes = ((width + 1) / 2 + 3) and 3.inv()
        val pixelBytes = rowBytes * height
        val headerSize = 14 + 40 + 16 * 4
        val out = ByteArrayOutputStream(headerSize + pixelBytes)

        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }

        out.write('B'.code); out.write('M'.code)
        le32(headerSize + pixelBytes)
        le16(0); le16(0)
        le32(headerSize)
        le32(40)
        le32(width)
        le32(height) // positive = bottom-up
        le16(1)
        le16(4)
        le32(0) // BI_RGB
        le32(pixelBytes)
        le32(2835); le32(2835)
        le32(16); le32(0)
        for (i in 0 until 16) {
            val v = i * 17
            out.write(v); out.write(v); out.write(v); out.write(0)
        }
        val row = ByteArray(rowBytes)
        for (r in 0 until height) {
            row.fill(0)
            val y = height - 1 - r
            for (x in 0 until width) {
                val level = this[x, y]
                val idx = x / 2
                row[idx] = if (x % 2 == 0) (level shl 4).toByte() else (row[idx].toInt() or level).toByte()
            }
            out.write(row)
        }
        return out.toByteArray()
    }

    companion object {
        /** 16-step grey wedge with a bright outline: verifies that all 4 bits arrive. */
        fun grayWedge(width: Int, height: Int): Gray4Bitmap {
            val bmp = Gray4Bitmap(width, height)
            val inner = width - 2
            for (x in 1 until width - 1) {
                val level = ((x - 1) * 16 / inner).coerceIn(0, 15)
                for (y in 1 until height - 1) bmp[x, y] = level
            }
            bmp.rect(0, 0, width, height, 15)
            return bmp
        }

        /** Concentric rings with a centre cross: marks the display centre the cursor starts at. */
        fun target(size: Int): Gray4Bitmap {
            val bmp = Gray4Bitmap(size, size)
            val c = size / 2.0
            bmp.circle(c, c, size / 2.0 - 2, 2.0, 15)
            bmp.circle(c, c, size * 0.30, 1.5, 9)
            bmp.circle(c, c, size * 0.12, 1.5, 6)
            val mid = size / 2
            for (i in 0 until size) {
                if (abs(i - mid) > size / 12) {
                    bmp[i, mid] = 5
                    bmp[mid, i] = 5
                }
            }
            bmp[mid, mid] = 15
            return bmp
        }

        fun levelFromLuminance(lum: Double): Int = (lum.coerceIn(0.0, 1.0) * 15).roundToInt()
    }
}
