package ch.madtreasures.g2direct.ble

import ch.madtreasures.g2direct.protocol.CursorLayers

enum class ArmPhase(val label: String) {
    OFF("–"),
    CONNECTING("verbinde…"),
    SETUP("richte ein…"),
    READY("verbunden"),
    FAILED("Fehler"),
}

data class ArmUi(
    val side: Side,
    val name: String? = null,
    val address: String? = null,
    val phase: ArmPhase = ArmPhase.OFF,
    val detail: String = "",
    val authenticated: Boolean = false,
    val bonded: Boolean = false,
    val mtu: Int = 0,
)

enum class SessionPhase(val label: String) {
    IDLE("nicht verbunden"),
    CONNECTING("Verbindungsaufbau"),
    INITIALIZING("Anmeldung an der Brille"),
    CREATING_PAGE("Testbild wird aufgebaut"),
    READY("bereit"),
    RECONNECTING("Verbindung verloren – neuer Versuch"),
    FAILED("fehlgeschlagen"),
}

/** State of our test page on the glasses (shown as "Anzeige: <label>"). */
enum class PageState(val label: String) {
    NONE("keine"),
    CREATING("wird aufgebaut"),
    ACTIVE("aktiv"),
    UNCONFIRMED("unbestätigt"),
    HIDDEN("im Hintergrund"),
    LOST("geschlossen"),
}

enum class Severity { INFO, WARN, ERROR }

data class Notice(val text: String, val severity: Severity, val atMs: Long)

data class Stats(
    val textSent: Int = 0,
    val textAcked: Int = 0,
    val textTimeouts: Int = 0,
    val textRejected: Int = 0,
    val ackMsAvg: Int = 0,
    val updatesPerSecond: Float = 0f,
    val fixedRateMode: Boolean = false,
    val packetsSent: Long = 0,
    val packetsDropped: Long = 0,
    val crcErrors: Int = 0,
    val rejectedFrames: Int = 0,
)

data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val title: String? = null,
    val left: ArmUi = ArmUi(Side.LEFT),
    val right: ArmUi = ArmUi(Side.RIGHT),
    val page: PageState = PageState.NONE,
    val imageStatus: String = "–",
    val battery: Int? = null,
    val charging: Boolean? = null,
    val firmware: String? = null,
    val cursorX: Int = 288,
    val cursorY: Int = 144,
    val style: CursorLayers.Style = CursorLayers.Style.CROSSHAIR,
    val speed: Float = 1.0f,
    val stats: Stats = Stats(),
    val notice: Notice? = null,
    val lastGlassesInput: String? = null,
    val reconnectAttempt: Int = 0,
    /** Which test page the glasses show (main page or one of the windows). */
    val screen: String = "Hauptseite",
    /** Field under the cursor or the result of the last click, for the watch display. */
    val pointerInfo: String? = null,
    /** Text updates allowed in flight at once (menu setting). */
    val pipeline: Int = DEFAULT_PIPELINE,
) {
    companion object {
        const val DEFAULT_PIPELINE = 4
        val PIPELINE_CHOICES = listOf(1, 2, 3, 4, 6, 8)
    }

    val connected: Boolean get() = phase == SessionPhase.READY
}
