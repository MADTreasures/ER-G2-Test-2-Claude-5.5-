package ch.madtreasures.g2direct.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renders a freely positioned cursor using nothing but in-place text updates.
 *
 * Why text: on stock firmware a container can only be moved by rebuilding the whole
 * page (flickers, wipes images), while an image update costs ~100 ms each. Text
 * updates are flicker-free and small, so the cursor is a glyph inside full-width,
 * transparent text containers:
 *
 * - Horizontal: the firmware skips *leading ASCII spaces* on every line (documented by
 *   Even's own font-metrics library @evenrealities/pretext), so lines are indented with
 *   NO-BREAK SPACE (U+00A0, 5 px advance) instead -> 5 px steps.
 * - Vertical: the LVGL line height is 27 px. [LAYER_OFFSETS] stacks five layers shifted
 *   by ~5.4 px; the cursor lives in exactly one of them -> ~5.4 px steps.
 * - Every layer always carries the same number of UTF-8 bytes (glyphs are swapped for
 *   IDEOGRAPHIC SPACE U+3000, which has the same 3-byte size and 20 px advance, when a
 *   layer is blank). That makes the update independent of how the firmware interprets
 *   the TextContainerUpgrade contentLength field, which the reference implementations
 *   describe inconsistently.
 *
 * Glyph advances come from @evenrealities/pretext (MIT, Even Realities): space/NBSP
 * 5 px, box drawing and geometric shapes 20 px, line height 27 px.
 */
object CursorLayers {
    const val SCREEN_W = 576
    const val SCREEN_H = 288
    const val LINE_HEIGHT = 27
    const val NBSP_WIDTH = 5

    /** y offsets of the five cursor layers (27 / 5 = 5.4 px apart, rounded). */
    val LAYER_OFFSETS = intArrayOf(0, 5, 11, 16, 22)

    /** Rows a layer can hold without overflowing (an overflowing text container shows a scrollbar). */
    fun rows(layer: Int): Int = (SCREEN_H - LAYER_OFFSETS[layer]) / LINE_HEIGHT

    fun layerHeight(layer: Int): Int = SCREEN_H - LAYER_OFFSETS[layer]

    private const val NBSP = '\u00A0'
    private const val BLANK20 = '\u3000'

    /**
     * One line of a cursor block: [lead] NBSPs, then glyph cells, padded with NBSPs up
     * to the block width. Glyph cells are 20 px wide single chars (3 UTF-8 bytes each).
     */
    private class BlockLine(val lead: Int, val glyphs: String)

    enum class Style(
        val label: String,
        private val lines: List<BlockLine>,
        /** Hot spot (cursor point) relative to the block's top-left corner, in px. */
        val hotX: Int,
        val hotY: Int,
    ) {
        /** Default: one heavy cross glyph (20 x 27 px), reaches almost the whole display. */
        CROSSHAIR(
            "Fadenkreuz",
            listOf(BlockLine(0, "╋")),
            hotX = 10, hotY = LINE_HEIGHT / 2,
        ),
        /** 60 x 81 px; its centre cannot come closer than ~40 px to the top/bottom edge. */
        CROSSHAIR_LARGE(
            "Fadenkreuz groß",
            listOf(BlockLine(4, "┃"), BlockLine(0, "━╋━"), BlockLine(4, "┃")),
            hotX = 30, hotY = LINE_HEIGHT + LINE_HEIGHT / 2,
        ),
        RING(
            "Ring",
            listOf(BlockLine(0, "◎")),
            hotX = 10, hotY = LINE_HEIGHT / 2,
        );

        val lineCount: Int get() = lines.size

        /** Block width in px (all lines are padded to the same width). */
        val width: Int = lines.maxOf { it.lead * NBSP_WIDTH + it.glyphs.length * 20 }

        /** Maximum indentation (in NBSP units) that still fits on one 576 px line. */
        val maxIndent: Int = (SCREEN_W - width) / NBSP_WIDTH

        internal fun renderLines(indent: Int, visible: Boolean): List<String> = lines.map { line ->
            val sb = StringBuilder()
            repeat(indent + line.lead) { sb.append(NBSP) }
            for (g in line.glyphs) sb.append(if (visible) g else BLANK20)
            val used = line.lead * NBSP_WIDTH + line.glyphs.length * 20
            repeat((width - used) / NBSP_WIDTH + (maxIndent - indent)) { sb.append(NBSP) }
            sb.toString()
        }
    }

    /** Where the cursor ends up after snapping to the text grid. */
    data class Placement(val layer: Int, val row: Int, val indent: Int, val x: Int, val y: Int)

    /** Allowed range of the cursor hot spot for a style (so input can be clamped to it). */
    fun minX(style: Style) = style.hotX
    fun maxX(style: Style) = style.hotX + style.maxIndent * NBSP_WIDTH
    fun minY(style: Style) = style.hotY
    fun maxY(style: Style) = LAYER_OFFSETS.indices.maxOf { LAYER_OFFSETS[it] + (rows(it) - style.lineCount) * LINE_HEIGHT } + style.hotY

    /** Snap a hot-spot position to the nearest reachable grid position. */
    fun place(style: Style, x: Float, y: Float): Placement {
        val indent = ((x - style.hotX) / NBSP_WIDTH).roundToInt().coerceIn(0, style.maxIndent)
        val wantTop = y - style.hotY
        var best: Placement? = null
        var bestErr = Float.MAX_VALUE
        for (layer in LAYER_OFFSETS.indices) {
            val maxRow = rows(layer) - style.lineCount
            for (row in 0..maxRow) {
                val top = LAYER_OFFSETS[layer] + row * LINE_HEIGHT
                val err = abs(top - wantTop)
                if (err < bestErr) {
                    bestErr = err
                    best = Placement(layer, row, indent, style.hotX + indent * NBSP_WIDTH, top + style.hotY)
                }
            }
        }
        return best!!
    }

    /**
     * Content for [layer]: the cursor block at [placement] if the placement is in this
     * layer, otherwise the same block made of blank cells. The UTF-8 byte length only
     * depends on (style, layer) - see the class comment.
     */
    fun content(style: Style, layer: Int, placement: Placement?): String {
        val rowCount = rows(layer)
        val shown = placement?.takeIf { it.layer == layer }
        val visible = shown != null
        val row = shown?.row ?: 0
        val indent = shown?.indent ?: 0
        val block = style.renderLines(indent, visible)
        val lines = ArrayList<String>(rowCount)
        for (r in 0 until rowCount) {
            lines.add(if (r >= row && r < row + block.size) block[r - row] else "")
        }
        return lines.joinToString("\n")
    }

    fun byteLength(style: Style, layer: Int): Int = content(style, layer, null).toByteArray(Charsets.UTF_8).size
}
