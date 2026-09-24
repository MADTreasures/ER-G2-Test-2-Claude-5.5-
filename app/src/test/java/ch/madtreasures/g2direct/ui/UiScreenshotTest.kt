package ch.madtreasures.g2direct.ui

import android.bluetooth.BluetoothManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import ch.madtreasures.g2direct.G2App
import ch.madtreasures.g2direct.ble.ArmPhase
import ch.madtreasures.g2direct.ble.ArmUi
import ch.madtreasures.g2direct.ble.G2Arm
import ch.madtreasures.g2direct.ble.G2Pair
import ch.madtreasures.g2direct.ble.Notice
import ch.madtreasures.g2direct.ble.PageState
import ch.madtreasures.g2direct.ble.SessionPhase
import ch.madtreasures.g2direct.ble.SessionState
import ch.madtreasures.g2direct.ble.Severity
import ch.madtreasures.g2direct.ble.Side
import ch.madtreasures.g2direct.ble.Stats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the watch screens on a simulated round 454 px Wear OS display (large Pixel Watch)
 * into docs/screenshots. Opt-in: ./gradlew :app:testDebugUnitTest -Pscreenshots
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class UiScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()

    private val readyState = SessionState(
        phase = SessionPhase.READY,
        title = "G2 · SN …A4DE80",
        left = ArmUi(Side.LEFT, "Even G2_32_L_A4DE7F", phase = ArmPhase.READY, detail = "bereit (MTU 247)", authenticated = true, mtu = 247),
        right = ArmUi(Side.RIGHT, "Even G2_32_R_A4DE80", phase = ArmPhase.READY, detail = "bereit (MTU 247)", authenticated = true, mtu = 247),
        page = PageState.ACTIVE,
        imageStatus = "1/1 bestätigt",
        battery = 81,
        charging = false,
        firmware = "2.2.7.14",
        cursorX = 313,
        cursorY = 131,
        stats = Stats(textSent = 412, textAcked = 409, textTimeouts = 3, ackMsAvg = 38, updatesPerSecond = 19.5f, packetsSent = 903),
        lastGlassesInput = "Tippen (R)",
    )

    private fun shoot(name: String, content: @Composable () -> Unit) {
        // Watch battery for the touchpad's battery row (sticky system broadcast).
        RuntimeEnvironment.getApplication().sendStickyBroadcast(
            android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
                .putExtra(android.os.BatteryManager.EXTRA_LEVEL, 76)
                .putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                .putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_DISCHARGING)
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                AppScaffold(timeText = { TimeText() }) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(700)
        // Draw the window directly (Robolectric native graphics); captureToImage() waits for a
        // real frame callback that never comes under the paused Robolectric looper.
        val view = compose.activity.window.decorView
        val square = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(square))
        // Mask to the round display so the picture shows what is actually visible.
        val round = Bitmap.createBitmap(square.width, square.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(round)
        canvas.drawColor(android.graphics.Color.rgb(40, 40, 40))
        val path = Path().apply {
            addCircle(square.width / 2f, square.height / 2f, square.width / 2f, Path.Direction.CW)
        }
        canvas.clipPath(path)
        canvas.drawBitmap(square, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun arm(address: String, name: String, side: Side, rssi: Int?, bonded: Boolean): G2Arm {
        val context = RuntimeEnvironment.getApplication()
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        return G2Arm(device, address, name, side, "S110A4DE80XXXX", rssi, bonded, rssi != null, SystemClock.elapsedRealtime())
    }

    @Test
    fun devices() = shoot("01_brille_waehlen") {
        val pair = G2Pair(
            "S110A4DE80XXXX", "G2 · SN …80XXXX",
            left = arm("E8:9D:EF:A4:DE:7F", "Even G2_32_L_A4DE7F", Side.LEFT, -61, true),
            right = arm("E8:9D:EF:A4:DE:80", "Even G2_32_R_A4DE80", Side.RIGHT, -58, true),
        )
        DevicesScreen(
            pairs = listOf(pair), scanning = false, bluetoothOn = true, scanError = null,
            lastPair = G2App.LastPair("G2 · SN …80XXXX", "E8:9D:EF:A4:DE:80", "E8:9D:EF:A4:DE:7F"),
            onScan = {}, onEnableBluetooth = {}, onConnectPair = {}, onConnectLast = {}, onForgetLast = {}, onLog = {},
        )
    }

    @Test
    fun devicesEmpty() = shoot("02_keine_brille") {
        DevicesScreen(
            pairs = emptyList(), scanning = false, bluetoothOn = true, scanError = null, lastPair = null,
            onScan = {}, onEnableBluetooth = {}, onConnectPair = {}, onConnectLast = {}, onForgetLast = {}, onLog = {},
        )
    }

    @Test
    fun connecting() = shoot("03_verbindung") {
        StatusScreen(
            state = readyState.copy(
                phase = SessionPhase.INITIALIZING, page = PageState.NONE, imageStatus = "–",
                left = readyState.left.copy(phase = ArmPhase.SETUP, detail = "aktiviere Benachrichtigungen", authenticated = false),
                right = readyState.right.copy(authenticated = false),
            ),
            onCancel = {}, onRetry = {}, onOpenTouchpad = {}, onLog = {},
        )
    }

    @Test
    fun failed() = shoot("04_fehler") {
        StatusScreen(
            state = readyState.copy(
                phase = SessionPhase.FAILED, page = PageState.NONE, imageStatus = "–",
                left = readyState.left.copy(phase = ArmPhase.OFF, detail = "", authenticated = false),
                right = readyState.right.copy(
                    phase = ArmPhase.FAILED, authenticated = false,
                    detail = "Verbindung fehlgeschlagen: 133 (GATT_ERROR – Brille belegt oder nicht erreichbar)",
                ),
                notice = Notice(
                    "Rechter Bügel: Verbindung fehlgeschlagen: 133 (GATT_ERROR – Brille belegt oder nicht erreichbar)",
                    Severity.ERROR, now,
                ),
            ),
            onCancel = {}, onRetry = {}, onOpenTouchpad = {}, onLog = {},
        )
    }

    @Composable
    private fun Touchpad(state: SessionState) =
        TouchpadScreen(state = state, onTouchStart = {}, onMove = { _, _ -> }, onSpeed = {}, onDoubleTap = {}, onOpenMenu = {})

    private val lostPageState = readyState.copy(
        page = PageState.LOST,
        left = readyState.left.copy(phase = ArmPhase.FAILED),
        notice = Notice("Testbild geschlossen – Touchpad berühren zum Neuaufbau", Severity.WARN, now),
    )

    @Test
    fun touchpad() = shoot("05_touchpad") { Touchpad(readyState) }

    @Test
    fun touchpadWarning() = shoot("06_touchpad_warnung") { Touchpad(lostPageState) }


    @Test
    fun menu() = shoot("07_menue") {
        MenuScreen(
            state = readyState, onBack = {}, onRebuild = {}, onCenter = {}, onStyle = {}, onSpeed = {},
            onPipeline = {}, onLog = {}, onDisconnect = {},
        )
    }

    @Test
    fun log() = shoot("08_protokoll") {
        LogScreen(
            lines = listOf(
                "09:41:07.512  Bild wedge übertragen (1 Fragment, 2422 B)",
                "09:41:06.101  Bereit. Cursor über das Uhr-Display bewegen.",
                "09:41:06.098  Testseite per CREATE bestätigt",
                "09:41:05.874  Anmeldung bestätigt (rechts + links)",
                "09:41:04.310  links: bereit (MTU 247)",
                "09:41:03.702  rechts: bereit (MTU 247)",
                "09:41:02.955  Warnung: Keine Anmelde-Bestätigung vom rechten Bügel – versuche trotzdem weiter",
            ),
            onBack = {},
        )
    }

    @Test
    fun permission() = shoot("09_berechtigung") {
        PermissionScreen(onRequest = {}, onOpenSettings = {})
    }
}
