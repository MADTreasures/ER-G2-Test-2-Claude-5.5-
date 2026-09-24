package ch.madtreasures.g2direct.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CursorLayersTest {

    private val styles = CursorLayers.Style.entries

    @Test
    fun byteLengthOfALayerNeverChanges() {
        for (style in styles) for (layer in CursorLayers.LAYER_OFFSETS.indices) {
            val expected = CursorLayers.byteLength(style, layer)
            var x = 0f
            while (x <= 576f) {
                var y = 0f
                while (y <= 288f) {
                    val p = CursorLayers.place(style, x, y)
                    val content = CursorLayers.content(style, layer, p)
                    assertEquals("$style layer $layer at $x/$y", expected, content.toByteArray(Charsets.UTF_8).size)
                    y += 3.7f
                }
                x += 11.3f
            }
        }
    }

    @Test
    fun everyLineFitsTheDisplayWidthAndNoLineStartsWithASpace() {
        for (style in styles) for (layer in CursorLayers.LAYER_OFFSETS.indices) {
            for (x in listOf(0f, 100f, 288f, 575f)) for (y in listOf(0f, 144f, 287f)) {
                val content = CursorLayers.content(style, layer, CursorLayers.place(style, x, y))
                val lines = content.split('\n')
                assertEquals(CursorLayers.rows(layer), lines.size)
                assertTrue(lines.size * G2Font.LINE_HEIGHT <= CursorLayers.layerHeight(layer))
                for (line in lines) {
                    assertTrue("$style: line too wide", G2Font.lineWidth(line) <= CursorLayers.SCREEN_W)
                    // The firmware skips leading U+0020; indentation must use U+00A0.
                    assertFalse(line.startsWith(" "))
                }
                assertFalse(content.endsWith("\n\n\n\n\n\n\n\n\n\n\n"))
            }
        }
    }

    @Test
    fun placementIsWithinHalfAGridStep() {
        for (style in styles) {
            var x = CursorLayers.minX(style).toFloat()
            while (x <= CursorLayers.maxX(style)) {
                var y = CursorLayers.minY(style).toFloat()
                while (y <= CursorLayers.maxY(style)) {
                    val p = CursorLayers.place(style, x, y)
                    assertTrue("x $x -> ${p.x}", abs(p.x - x) <= 2.5f + 1e-3f)
                    assertTrue("y $y -> ${p.y}", abs(p.y - y) <= 3.5f)
                    y += 1f
                }
                x += 1.7f
            }
        }
    }

    @Test
    fun visibleGlyphIsExactlyWherePlacementSaysItIs() {
        val style = CursorLayers.Style.CROSSHAIR
        val p = CursorLayers.place(style, 300f, 150f)
        val lines = CursorLayers.content(style, p.layer, p).split('\n')
        val line = lines[p.row]
        val prefix = line.substringBefore('╋')
        // Hot spot = glyph left edge + 10 px (half of the 20 px glyph).
        assertEquals(p.x, G2Font.lineWidth(prefix) + 10)
        assertEquals(p.y, CursorLayers.LAYER_OFFSETS[p.layer] + p.row * 27 + style.hotY)
        // Only this layer shows a glyph; every other layer is blank.
        for (layer in CursorLayers.LAYER_OFFSETS.indices) {
            val c = CursorLayers.content(style, layer, p)
            assertEquals(layer == p.layer, c.contains('╋'))
        }
    }

    @Test
    fun largeCrosshairArmsAreAligned() {
        val style = CursorLayers.Style.CROSSHAIR_LARGE
        val p = CursorLayers.place(style, 200f, 120f)
        val lines = CursorLayers.content(style, p.layer, p).split('\n')
        val top = lines[p.row]
        val mid = lines[p.row + 1]
        val bottom = lines[p.row + 2]
        val topCentre = G2Font.lineWidth(top.substringBefore('┃')) + 10
        val midCentre = G2Font.lineWidth(mid.substringBefore('╋')) + 10
        val bottomCentre = G2Font.lineWidth(bottom.substringBefore('┃')) + 10
        assertEquals(p.x, midCentre)
        assertEquals(midCentre, topCentre)
        assertEquals(midCentre, bottomCentre)
    }

    @Test
    fun cursorRangeCoversNearlyTheWholeDisplay() {
        val style = CursorLayers.Style.CROSSHAIR
        assertTrue(CursorLayers.minX(style) <= 10)
        assertTrue(CursorLayers.maxX(style) >= 565)
        assertTrue(CursorLayers.minY(style) <= 14)
        assertTrue(CursorLayers.maxY(style) >= 270)
    }

    @Test
    fun testPageFrameTextFitsOnOneLine() {
        val text = TestPage.frameText(575, 287, 99999)
        assertTrue(G2Font.lineWidth(text) <= 576 - 2 * (6 + 2))
        assertEquals(TestPage.frameText(0, 0, 0).toByteArray().size, text.toByteArray().size)
    }

    @Test
    fun testPageRespectsFirmwareLimits() {
        val texts = TestPage.textContainers(288, 144)
        assertTrue(texts.size <= 8)
        assertEquals(1, texts.count { it.eventCapture })
        assertTrue(texts.size + TestPage.images.size <= 12)
        val msg = G2Messages.createPage(1, texts, TestPage.images)
        // Well below the ~4 KB reassembly limit reported for single messages.
        assertTrue(msg.size < 1500)
    }
}
