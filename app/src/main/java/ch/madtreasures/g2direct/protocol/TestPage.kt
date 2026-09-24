package ch.madtreasures.g2direct.protocol

/**
 * Pages shown on the glasses (576 x 288). The firmware allows 8 text and 4 image containers,
 * and exactly one of them must capture events.
 *
 * Main page ([Screen.MAIN], 8 text + 1 image container):
 * - `evt`    1x1 empty text container with isEventCapture. MentraOS found that putting the
 *            capture flag on a visible container makes the firmware draw a stray caret-like
 *            line, so - like MentraOS - a dedicated invisible container takes it.
 * - `frame`  full-screen text container with a border; its single text line holds the
 *            title and the live info (cursor position, update counter).
 * - `btnA`, `btnB`  two bordered fields. Pointing at one marks it with » «, a double tap on
 *            the watch "clicks" it and opens a window.
 * - `cur0..3` four transparent full-width text layers carrying the cursor ([CursorLayers]).
 * - `wedge`  192x24 image with 16 grey steps at the bottom: checks the image channel and the
 *            4-bit grey levels. The firmware paints images above all text, so the cursor is
 *            hidden while it passes over this strip.
 *
 * Window pages ([Screen.WINDOW_A], [Screen.WINDOW_B], 7 text containers): `evt`, a bordered
 * `window` with a short text, a `close` field that leads back, and the cursor layers.
 * Switching pages is a REBUILD; the cursor keeps its position.
 */
object TestPage {
    const val EVT_ID = 1
    const val FRAME_ID = 2
    const val BTN_A_ID = 3
    const val BTN_B_ID = 4
    const val FIRST_LAYER_ID = 5
    const val WINDOW_ID = 9
    const val WEDGE_ID = 10
    const val CLOSE_ID = 11

    const val FRAME_NAME = "frame"

    enum class Screen(val label: String) {
        MAIN("Hauptseite"),
        WINDOW_A("Fenster A"),
        WINDOW_B("Fenster B"),
    }

    /** A clickable field. [contains] is tested against the cursor hot spot. */
    data class Button(
        val id: Int, val name: String, val label: String,
        val x: Int, val y: Int, val width: Int, val height: Int,
        /** Page a click on this field opens. */
        val opens: Screen,
    ) {
        fun contains(px: Int, py: Int) = px in x until x + width && py in y until y + height
    }

    private const val BUTTON_BORDER = 2
    /** Puts the single text line in the vertical middle of a 60 px field (60 - 2*2 - 2*14 = 28 >= 27). */
    private const val BUTTON_PADDING = 14

    val buttonA = Button(BTN_A_ID, "btnA", "Feld A", x = 88, y = 104, width = 170, height = 60, opens = Screen.WINDOW_A)
    val buttonB = Button(BTN_B_ID, "btnB", "Feld B", x = 318, y = 104, width = 170, height = 60, opens = Screen.WINDOW_B)
    val closeButton = Button(CLOSE_ID, "close", "Schließen", x = 334, y = 192, width = 170, height = 60, opens = Screen.MAIN)

    private val window = TextContainer(
        id = WINDOW_ID, name = "window", x = 48, y = 20, width = 480, height = 248, content = "",
        borderWidth = 2, borderColor = 15, borderRadius = 10, padding = 12,
    )

    val wedge = ImageContainer(WEDGE_ID, "wedge", x = 192, y = 250, width = 192, height = 24)

    fun buttons(screen: Screen): List<Button> = when (screen) {
        Screen.MAIN -> listOf(buttonA, buttonB)
        Screen.WINDOW_A, Screen.WINDOW_B -> listOf(closeButton)
    }

    fun images(screen: Screen): List<ImageContainer> = if (screen == Screen.MAIN) listOf(wedge) else emptyList()

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

    /**
     * Field label, centred with NO-BREAK SPACEs. Pointed at, the two NBSPs around the label
     * become » and «: both are 2 bytes in UTF-8 like NBSP, so the byte length stays the same
     * (see [CursorLayers] on why that matters).
     */
    fun buttonText(button: Button, pointed: Boolean): String {
        val inner = button.width - 2 * (BUTTON_BORDER + BUTTON_PADDING)
        val core = "  ${button.label}  "
        val pad = " ".repeat(((inner - G2Font.lineWidth(core)) / 2 / CursorLayers.NBSP_WIDTH).coerceAtLeast(0))
        return pad + if (pointed) "» ${button.label} «" else core
    }

    fun windowText(screen: Screen): String = when (screen) {
        Screen.WINDOW_A -> "Fenster A\n\nGeöffnet per Doppeltipp auf der Uhr.\nZeiger auf „Schließen“ und doppeltippen,\num zurückzukehren."
        Screen.WINDOW_B -> "Fenster B\n\nAuch dieses Fenster kam per Klick.\nZeiger auf „Schließen“ und doppeltippen,\num zurückzukehren."
        Screen.MAIN -> ""
    }

    fun textContainers(screen: Screen, cursorX: Int, cursorY: Int): List<TextContainer> {
        val list = ArrayList<TextContainer>()
        list += TextContainer(
            id = EVT_ID, name = "evt", x = 0, y = 0, width = 1, height = 1,
            content = "", eventCapture = true,
        )
        if (screen == Screen.MAIN) {
            list += TextContainer(
                id = FRAME_ID, name = FRAME_NAME, x = 0, y = 0, width = 576, height = 288,
                content = frameText(cursorX, cursorY, 0),
                borderWidth = 2, borderColor = 12, borderRadius = 8, padding = 6,
            )
        } else {
            list += window.copy(content = windowText(screen))
        }
        for (b in buttons(screen)) {
            list += TextContainer(
                id = b.id, name = b.name, x = b.x, y = b.y, width = b.width, height = b.height,
                content = buttonText(b, pointed = false),
                borderWidth = BUTTON_BORDER, borderColor = 10, borderRadius = 8, padding = BUTTON_PADDING,
            )
        }
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
