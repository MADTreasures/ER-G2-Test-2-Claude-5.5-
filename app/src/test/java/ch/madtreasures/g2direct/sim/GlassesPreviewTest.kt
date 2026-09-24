package ch.madtreasures.g2direct.sim

import ch.madtreasures.g2direct.ble.ArmTarget
import ch.madtreasures.g2direct.ble.ConnectRequest
import ch.madtreasures.g2direct.ble.SessionEngine
import ch.madtreasures.g2direct.protocol.G2Font
import ch.madtreasures.g2direct.protocol.TestPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Draws what the simulated glasses hold after a session (text containers, borders, the
 * grey-wedge image, the cursor) into docs/screenshots/brille_simulation_*.png.
 *
 * This is a LAYOUT PREVIEW from the protocol model, not the firmware's rendering: letters use
 * a crude 5x7 stand-in font placed with the real glyph advances, borders are drawn square.
 * Opt-in like the UI screenshots (-Pscreenshots). Android unit tests cannot use java.awt,
 * hence the tiny framebuffer and PNG writer below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassesPreviewTest {

    private class Frame(val w: Int, val h: Int) {
        val px = IntArray(w * h)
        fun set(x: Int, y: Int, rgb: Int) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = rgb
        }
        fun fill(x: Int, y: Int, fw: Int, fh: Int, rgb: Int) {
            for (yy in y until y + fh) for (xx in x until x + fw) set(xx, yy, rgb)
        }
    }

    private fun green(level: Int): Int {
        val l = level.coerceIn(0, 15)
        return ((l * 5) shl 16) or ((l * 17) shl 8) or (l * 7)
    }

    /** 5x7 stand-in glyphs for the characters of the frame line ('.' = off). */
    private val font: Map<Char, List<String>> = mapOf(
        'G' to listOf(".###.", "#....", "#....", "#.###", "#...#", "#...#", ".###."),
        'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'i' to listOf("..#..", ".....", ".##..", "..#..", "..#..", "..#..", ".###."),
        'r' to listOf(".....", ".....", "#.##.", "##..#", "#....", "#....", "#...."),
        'e' to listOf(".....", ".....", ".###.", "#...#", "#####", "#....", ".###."),
        'c' to listOf(".....", ".....", ".###.", "#....", "#....", "#...#", ".###."),
        't' to listOf(".#...", ".#...", "###..", ".#...", ".#...", ".#..#", "..##."),
        's' to listOf(".....", ".....", ".####", "#....", ".###.", "....#", "####."),
        'b' to listOf("#....", "#....", "#.##.", "##..#", "#...#", "#...#", "####."),
        'l' to listOf(".##..", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'd' to listOf("....#", "....#", ".##.#", "#..##", "#...#", "#...#", ".####"),
        '#' to listOf(".#.#.", ".#.#.", "#####", ".#.#.", "#####", ".#.#.", ".#.#."),
        '·' to listOf(".....", ".....", ".....", "..#..", ".....", ".....", "....."),
        '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'v' to listOf(".....", ".....", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'y' to listOf(".....", ".....", "#...#", "#...#", ".####", "....#", ".###."),
        'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        'a' to listOf(".....", ".....", ".###.", "....#", ".####", "#...#", ".####"),
        'f' to listOf("..##.", ".#...", ".#...", "###..", ".#...", ".#...", ".#..."),
        'g' to listOf(".....", ".####", "#...#", "#...#", ".####", "....#", ".###."),
        'h' to listOf("#....", "#....", "#.##.", "##..#", "#...#", "#...#", "#...#"),
        'k' to listOf("#....", "#....", "#..#.", "#.#..", "##...", "#.#..", "#..#."),
        'm' to listOf(".....", ".....", "##.#.", "#.#.#", "#.#.#", "#.#.#", "#.#.#"),
        'n' to listOf(".....", ".....", "#.##.", "##..#", "#...#", "#...#", "#...#"),
        'o' to listOf(".....", ".....", ".###.", "#...#", "#...#", "#...#", ".###."),
        'p' to listOf(".....", ".....", "####.", "#...#", "####.", "#....", "#...."),
        'u' to listOf(".....", ".....", "#...#", "#...#", "#...#", "#..##", ".##.#"),
        'w' to listOf(".....", ".....", "#...#", "#...#", "#.#.#", "#.#.#", ".#.#."),
        'z' to listOf(".....", ".....", "#####", "...#.", "..#..", ".#...", "#####"),
        'ä' to listOf(".#.#.", ".....", ".###.", "....#", ".####", "#...#", ".####"),
        'ö' to listOf(".#.#.", ".....", ".###.", "#...#", "#...#", "#...#", ".###."),
        'ü' to listOf(".#.#.", ".....", "#...#", "#...#", "#...#", "#..##", ".##.#"),
        'ß' to listOf(".##..", "#..#.", "#..#.", "#.#..", "#..#.", "#..#.", "#.##."),
        '»' to listOf(".....", "#.#..", ".#.#.", "..#.#", ".#.#.", "#.#..", "....."),
        '«' to listOf(".....", "..#.#", ".#.#.", "#.#..", ".#.#.", "..#.#", "....."),
        '.' to listOf(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
        ',' to listOf(".....", ".....", ".....", ".....", ".##..", "..#..", ".#..."),
        '„' to listOf(".....", ".....", ".....", ".....", ".#.#.", ".#.#.", "#.#.."),
        '“' to listOf(".#.#.", "#.#..", "#.#..", ".....", ".....", ".....", "....."),
    )

    private fun drawGlyph(f: Frame, ch: Char, x: Int, y: Int) {
        val c = green(15)
        val cx = x + 10
        val cy = y + 13
        when (ch) {
            '━' -> f.fill(x, cy - 2, 20, 4, c)
            '┃' -> f.fill(cx - 2, y, 4, 27, c)
            '╋' -> { f.fill(x, cy - 2, 20, 4, c); f.fill(cx - 2, y, 4, 27, c) }
            '◎' -> for (a in 0 until 360 step 4) {
                val r = Math.toRadians(a.toDouble())
                f.set(cx + (9 * Math.cos(r)).toInt(), cy + (9 * Math.sin(r)).toInt(), c)
                f.set(cx + (4 * Math.cos(r)).toInt(), cy + (4 * Math.sin(r)).toInt(), c)
            }
            else -> {
                val adv = ((G2Font.advance16(ch.code) ?: 160) + 8) shr 4
                val rows = font[ch] ?: listOf("#####", "#...#", "#...#", "#...#", "#...#", "#...#", "#####")
                val ox = x + (adv - 10) / 2
                for ((ry, row) in rows.withIndex()) for ((rx, bit) in row.withIndex()) {
                    if (bit == '#') f.fill(ox + rx * 2, y + 6 + ry * 2, 2, 2, c)
                }
            }
        }
    }

    private fun render(glasses: FakeGlasses): Frame {
        val f = Frame(576, 288)
        // Text containers first, images on top (the firmware paints images above text).
        for (box in glasses.texts.values) {
            val b = box.border
            if (b > 0) {
                val c = green(12)
                f.fill(box.x, box.y, box.w, b, c)
                f.fill(box.x, box.y + box.h - b, box.w, b, c)
                f.fill(box.x, box.y, b, box.h, c)
                f.fill(box.x + box.w - b, box.y, b, box.h, c)
            }
        }
        for (glyph in glasses.glyphs()) drawGlyph(f, glyph.ch, glyph.x, glyph.y)
        for (image in glasses.images.values) {
            val bmp = image.bmp ?: continue
            val rowBytes = ((image.w + 1) / 2 + 3) and 3.inv()
            for (y in 0 until image.h) for (x in 0 until image.w) {
                val v = bmp[118 + (image.h - 1 - y) * rowBytes + x / 2].toInt() and 0xFF
                f.set(image.x + x, image.y + y, green(if (x % 2 == 0) v shr 4 else v and 0xF))
            }
        }
        return f
    }

    private fun writePng(f: Frame, scale: Int, file: File) {
        val w = f.w * scale
        val h = f.h * scale
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) {
                val p = f.px[(y / scale) * f.w + x / scale]
                raw.write((p shr 16) and 0xFF); raw.write((p shr 8) and 0xFF); raw.write(p and 0xFF)
            }
        }
        val out = DataOutputStream(file.apply { parentFile.mkdirs() }.outputStream())
        fun chunk(type: String, data: ByteArray) {
            out.writeInt(data.size)
            val td = type.toByteArray() + data
            out.write(td)
            out.writeInt(CRC32().apply { update(td) }.value.toInt())
        }
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }
        chunk("IHDR", ihdr.toByteArray())
        val idat = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(raw.toByteArray()) } }
        chunk("IDAT", idat.toByteArray())
        chunk("IEND", ByteArray(0))
        out.close()
    }

    @Test
    fun renderSimulatedGlassesView() = runTest {
        val dir = System.getProperty("screenshotDir")
        assumeTrue("nur mit -Pscreenshots", dir != null)
        val glasses = FakeGlasses(backgroundScope)
        val engine = SessionEngine(backgroundScope, glasses.env(testScheduler))
        engine.connect(ConnectRequest("G2", ArmTarget("R", "Even G2_32_R_1", true), ArmTarget("L", "Even G2_32_L_1", true)))
        advanceTimeBy(10_000); runCurrent()
        writePng(render(glasses), 2, File(dir, "brille_simulation_start.png"))
        // Point at "Feld A": the field gets its » « marks.
        val a = TestPage.buttonA
        engine.moveCursorBy(a.x + a.width / 2 - engine.target().first, a.y + a.height / 2 - engine.target().second)
        advanceTimeBy(2_000); runCurrent()
        writePng(render(glasses), 2, File(dir, "brille_simulation_bewegt.png"))
        // Double tap on the watch = click: the window opens.
        engine.click()
        advanceTimeBy(3_000); runCurrent()
        val c = TestPage.closeButton
        engine.moveCursorBy(c.x + c.width / 2 - engine.target().first, c.y + c.height / 2 - engine.target().second)
        advanceTimeBy(2_000); runCurrent()
        writePng(render(glasses), 2, File(dir, "brille_simulation_fenster.png"))
        assertTrue(glasses.violations.toString(), glasses.violations.isEmpty())
    }
}
