package ch.madtreasures.g2direct.protocol

/**
 * Layout of the test page shown on the glasses (576 x 288, 9 of 12 container slots):
 *
 * - `evt`    1x1 empty text container with isEventCapture. MentraOS found that putting the
 *            capture flag on a visible container makes the firmware draw a stray caret-like
 *            line, so - like MentraOS - a dedicated invisible container takes it.
 * - `frame`  full-screen text container with a border; its single text line holds the
 *            title and the live info (cursor position, update counter).
 * - `box`    80x80 bordered box around (290, 143), the grid position closest to the display
 *            centre. The cursor starts exactly in its middle, so wrong glyph metrics on the
 *            real glasses show up as an off-centre cursor.
 * - `cur0..4` five transparent full-width text layers carrying the cursor ([CursorLayers]).
 * - `wedge`  192x24 image with 16 grey steps at the bottom: checks the image channel and the
 *            4-bit grey levels. The firmware paints images above all text, so the cursor is
 *            hidden while it passes over this strip.
 */
object TestPage {
    const val EVT_ID = 1
    const val FRAME_ID = 2
    const val BOX_ID = 3
    const val FIRST_LAYER_ID = 4
    const val WEDGE_ID = 10

    const val FRAME_NAME = "frame"

    val wedge = ImageContainer(WEDGE_ID, "wedge", x = 192, y = 250, width = 192, height = 24)

    val images: List<ImageContainer> = listOf(wedge)

    fun layerId(layer: Int) = FIRST_LAYER_ID + layer
    fun layerName(layer: Int) = "cur$layer"

    /**
     * Title plus live values, ASCII apart from the fixed middle dot, and always the same
     * length so the in-place update never depends on the contentLength semantics.
     */
    fun frameText(x: Int, y: Int, updates: Int): String =
        "G2 Direct · Testbild      X%03d Y%03d  #%05d".format(
            x.coerceIn(0, 999), y.coerceIn(0, 999), updates % 100000
        )

    fun textContainers(cursorX: Int, cursorY: Int): List<TextContainer> {
        val list = ArrayList<TextContainer>()
        list += TextContainer(
            id = EVT_ID, name = "evt", x = 0, y = 0, width = 1, height = 1,
            content = "", eventCapture = true,
        )
        list += TextContainer(
            id = FRAME_ID, name = FRAME_NAME, x = 0, y = 0, width = 576, height = 288,
            content = frameText(cursorX, cursorY, 0),
            borderWidth = 2, borderColor = 12, borderRadius = 8, padding = 6,
        )
        list += TextContainer(
            id = BOX_ID, name = "box", x = 250, y = 103, width = 80, height = 80,
            content = "", borderWidth = 1, borderColor = 7, borderRadius = 6,
        )
        for (layer in CursorLayers.LAYER_OFFSETS.indices) {
            list += TextContainer(
                id = layerId(layer), name = layerName(layer),
                x = 0, y = CursorLayers.LAYER_OFFSETS[layer],
                width = CursorLayers.SCREEN_W, height = CursorLayers.layerHeight(layer),
                content = "",
            )
        }
        return list
    }

    fun imageBitmap(image: ImageContainer): Gray4Bitmap = when (image.id) {
        WEDGE_ID -> Gray4Bitmap.grayWedge(image.width, image.height)
        else -> Gray4Bitmap(image.width, image.height)
    }
}
