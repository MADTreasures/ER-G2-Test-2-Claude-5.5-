package ch.madtreasures.g2direct.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.wear.compose.material3.MaterialTheme
import ch.madtreasures.g2direct.ble.SessionPhase
import ch.madtreasures.g2direct.ble.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real touchpad gesture code with synthetic touches: only finger movement may
 * move the cursor, never the position where the finger lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class TouchpadGestureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val moves = ArrayList<Offset>()
    private var touches = 0
    private var menuOpened = 0

    private fun total() = moves.fold(Offset.Zero) { sum, d -> sum + d }

    @Before
    fun showTouchpad() {
        compose.setContent {
            MaterialTheme {
                TouchpadScreen(
                    state = SessionState(phase = SessionPhase.READY),
                    onTouchStart = { touches++ },
                    onMove = { dx, dy -> moves += Offset(dx, dy) },
                    onSpeed = {},
                    onOpenMenu = { menuOpened++ },
                )
            }
        }
    }

    @Test
    fun liftingAndTouchingElsewhereDoesNotMoveTheCursor() {
        compose.onRoot().performTouchInput {
            down(Offset(80f, 120f))
            repeat(5) { moveBy(Offset(12f, 6f)) }
            up()
        }
        val first = total()
        assertTrue("erster Strich nach rechts unten: $first", first.x > 0f && first.y > 0f)

        // New touch at the opposite side of the display, then lifted without moving.
        moves.clear()
        compose.onRoot().performTouchInput {
            down(Offset(380f, 360f))
            up()
        }
        assertEquals(Offset.Zero, total())

        // A stroke to the left from there moves the cursor left, relative to where it was.
        compose.onRoot().performTouchInput {
            down(Offset(380f, 360f))
            repeat(5) { moveBy(Offset(-12f, 0f)) }
            up()
        }
        val second = total()
        assertTrue("zweiter Strich nach links: $second", second.x < 0f)
        assertEquals(0f, second.y, 0.001f)
        assertEquals(3, touches)
        assertEquals(0, menuOpened)
    }

    @Test
    fun sameStrokeMovesTheSameAnywhereOnTheDisplay() {
        compose.onRoot().performTouchInput {
            down(Offset(100f, 100f))
            repeat(4) { moveBy(Offset(0f, 10f)) }
            up()
        }
        val topLeft = total()
        moves.clear()
        compose.onRoot().performTouchInput {
            down(Offset(320f, 300f))
            repeat(4) { moveBy(Offset(0f, 10f)) }
            up()
        }
        val bottomRight = total()
        assertTrue(topLeft.y > 0f)
        assertEquals(topLeft.x, bottomRight.x, 0.001f)
        assertEquals(topLeft.y, bottomRight.y, 0.001f)
    }

    @Test
    fun holdingStillOpensTheMenuWithoutMovingTheCursor() {
        compose.onRoot().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onRoot().performTouchInput { up() }
        assertEquals(1, menuOpened)
        assertEquals(Offset.Zero, total())
    }

    @Test
    fun slowMovementNeverOpensTheMenu() {
        compose.onRoot().performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(6f, 0f), delayMillis = 100) }
            up()
        }
        assertEquals(0, menuOpened)
        assertTrue(total().x > 0f)
    }
}
