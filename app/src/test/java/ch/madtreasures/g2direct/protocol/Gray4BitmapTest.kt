package ch.madtreasures.g2direct.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class Gray4BitmapTest {

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    @Test
    fun bmpHeaderMatchesWhatTheFirmwareDecoderChecks() {
        val bmp = Gray4Bitmap(192, 24).apply { this[0, 0] = 15; this[1, 0] = 3; this[191, 23] = 9 }.toBmp()
        assertEquals('B'.code.toByte(), bmp[0])
        assertEquals('M'.code.toByte(), bmp[1])
        assertEquals(bmp.size, le32(bmp, 2))
        assertEquals(118, le32(bmp, 10))
        assertEquals(40, le32(bmp, 14))
        assertEquals(192, le32(bmp, 18))
        assertEquals(24, le32(bmp, 22))
        assertEquals(4, bmp[28].toInt()) // biBitCount
        // Palette entry 15 = white (i * 17).
        assertEquals(255, bmp[54 + 15 * 4].toInt() and 0xFF)
        val rowBytes = 96
        assertEquals(118 + rowBytes * 24, bmp.size)
        // Bottom-up: image row 0 is the last stored row; high nibble is the left pixel.
        val topRow = 118 + rowBytes * 23
        assertEquals(0xF3, bmp[topRow].toInt() and 0xFF)
        // Image row 23 (bottom) is stored first; pixel 191 is the low nibble of byte 95.
        assertEquals(0x09, bmp[118 + 95].toInt() and 0xFF)
    }

    @Test
    fun oddWidthRowsArePaddedToFourBytes() {
        val bmp = Gray4Bitmap(21, 20).toBmp()
        assertEquals(118 + 12 * 20, bmp.size)
    }

    @Test
    fun wedgeHasAllSixteenLevels() {
        val w = Gray4Bitmap.grayWedge(192, 24)
        val levels = (1 until 191).map { w[it, 12] }.toSet()
        assertEquals((0..15).toSet(), levels)
    }
}
