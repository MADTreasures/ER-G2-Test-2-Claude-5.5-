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
    private var doubleTaps = 0

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
                    onDoubleTap = { doubleTaps++ },
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

    private val MID = Offset(227f, 227f)

    private fun tap(at: Offset, jitter: Offset = Offset.Zero) {
        compose.onRoot().performTouchInput {
            down(at)
            if (jitter != Offset.Zero) moveBy(jitter)
            up()
        }
    }

    @Test
    fun doubleTapClicksWithoutMovingTheCursor() {
        // Real taps wobble a little; that must neither move the cursor nor spoil the click.
        tap(MID, jitter = Offset(3f, -2f))
        tap(MID + Offset(20f, 10f), jitter = Offset(-2f, 2f))
        assertEquals(1, doubleTaps)
        assertEquals(Offset.Zero, total())
        assertEquals(0, menuOpened)
    }

    @Test
    fun singleTapIsNoClick() {
        tap(MID)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0, doubleTaps)
    }

    @Test
    fun twoSlowTapsAreNoDoubleTap() {
        tap(MID)
        compose.mainClock.advanceTimeBy(800)
        tap(MID)
        assertEquals(0, doubleTaps)
    }

    @Test
    fun smallStartOfAStrokeIsNotLost() {
        // The first few pixels are held back (could be a tap) and sent once the finger moves on.
        compose.onRoot().performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(4f, 0f)) }
            up()
        }
        assertEquals(0, doubleTaps)
        val single = total()
        moves.clear()
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }
        assertTrue("$single", single.x > 0f)
        // Same 40 px in ten small steps or one step: the cursor travels comparably far.
        assertTrue("${single.x} vs ${total().x}", single.x > total().x * 0.3f)
    }
}
