package ch.madtreasures.g2direct.protocol

/**
 * Advance widths of the glasses' built-in LVGL font for the characters this app sends,
 * in 1/16 px, copied from Even Realities' @evenrealities/pretext 0.1.4 (MIT). Kerning is
 * ignored (none of the cursor glyph pairs are kerned; ASCII kerning only changes the
 * width of the title line by a few pixels).
 */
object G2Font {
    const val LINE_HEIGHT = 27

    private val ASCII = intArrayOf(
        80, 64, 96, 240, 208, 224, 256, 64, 112, 112, 128, 160, 80, 160, 80, 128, // 32..47
        192, 128, 192, 192, 208, 192, 192, 208, 192, 192, 64, 80, 160, 160, 160, 192, // 48..63
        272, 224, 192, 192, 192, 176, 176, 192, 192, 96, 144, 192, 160, 256, 192, 192, // 64..79
        192, 192, 192, 192, 192, 192, 224, 256, 224, 224, 208, 112, 128, 112, 160, 144, // 80..95
        0, 192, 176, 176, 176, 176, 160, 176, 176, 64, 112, 160, 64, 256, 176, 176, // 96..111
        176, 176, 128, 176, 128, 192, 192, 256, 192, 192, 160, 144, 64, 144, 256, // 112..126
    )

    private val OTHER = mapOf(
        0x00A0 to 80, // NO-BREAK SPACE
        0x00AB to 192, // «
        0x00B7 to 80, // MIDDLE DOT
        0x00BB to 192, // »
        0x00C4 to 224, // Ä
        0x00D6 to 192, // Ö
        0x00DC to 192, // Ü
        0x00DF to 176, // ß
        0x00E4 to 192, // ä
        0x00F6 to 176, // ö
        0x00FC to 192, // ü
        0x2013 to 240, // –
        0x201C to 128, // “
        0x201E to 144, // „
        0x3000 to 320, // IDEOGRAPHIC SPACE
        0x2501 to 320, // ━
        0x2503 to 320, // ┃
        0x254B to 320, // ╋
        0x25CE to 320, // ◎
        0x2588 to 320, // █
        // Arrow cursor candidates: width assumed like the other symbols, not yet seen on hardware.
        0x2196 to 320, // ↖
        0x25B2 to 320, // ▲
        0x25E4 to 320, // ◤
        0x2B09 to 320, // ⬉
    )

    /** Advance in 1/16 px, or null if the glyph is not in this table. */
    fun advance16(cp: Int): Int? = if (cp in 32..126) ASCII[cp - 32] else OTHER[cp]

    /** Pixel width of one line, rounding per glyph like LVGL does. */
    fun lineWidth(text: String): Int {
        var w = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val adv = advance16(cp) ?: error("Glyph U+%04X nicht in der Tabelle".format(cp))
            w += (adv + 8) shr 4
            i += Character.charCount(cp)
        }
        return w
    }
}
