package ch.madtreasures.g2direct.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ch.madtreasures.g2direct.ble.ArmPhase
import ch.madtreasures.g2direct.ble.G2Session
import ch.madtreasures.g2direct.ble.PageState
import ch.madtreasures.g2direct.ble.SessionState
import ch.madtreasures.g2direct.ble.Severity
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.hypot

/**
 * The whole watch face is a relative touchpad: only finger *movement* moves the cursor,
 * never the touch position itself, so lifting and putting the finger down elsewhere does
 * not make the cursor jump. There are no zones or buttons on this screen; holding the
 * finger still for a moment opens the menu, the crown changes the pointer speed.
 */
@Composable
fun TouchpadScreen(state: SessionState, session: G2Session, onOpenMenu: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val speed by rememberUpdatedState(state.speed)
    val openMenu by rememberUpdatedState(onOpenMenu)
    val focusRequester = remember { FocusRequester() }
    var speedShownAt by remember { mutableLongStateOf(0L) }
    var touching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(speedShownAt) {
        if (speedShownAt != 0L) {
            delay(1_500)
            speedShownAt = 0L
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { event ->
                session.setSpeed(speed + event.verticalScrollPixels / 500f)
                speedShownAt = System.currentTimeMillis()
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                relativeTouchpad(
                    onDown = {
                        touching = true
                        session.onTouchStart()
                    },
                    onMove = { dx, dy, dtMs ->
                        // Watch pixels -> dp -> glasses pixels, with mild pointer acceleration:
                        // slow strokes position precisely, fast flicks cross the display.
                        val ddx = dx / density
                        val ddy = dy / density
                        val velocity = hypot(ddx, ddy) / dtMs
                        val accel = (0.55f + velocity * 1.2f).coerceIn(0.55f, 2.6f)
                        val k = BASE_GAIN * speed * accel
                        session.moveCursorBy(ddx * k, ddy * k)
                    },
                    onUp = { touching = false },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openMenu()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ArmsLine(state)
            Text(
                "X ${state.cursorX}  ·  Y ${state.cursorY}",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (touching) OkGreen else MaterialTheme.colorScheme.onSurface,
            )
            val pageColor = when (state.page) {
                PageState.ACTIVE -> OkGreen
                PageState.UNCONFIRMED, PageState.CREATING, PageState.HIDDEN -> WarnOrange
                else -> ErrorRed
            }
            Text("Brille: ${state.page.label}", fontSize = 13.sp, color = pageColor, textAlign = TextAlign.Center)
            val s = state.stats
            Text(
                String.format(Locale.GERMANY, "%.0f/s · Ø %d ms", s.updatesPerSecond, s.ackMsAvg) +
                    if (s.fixedRateMode) " · Festtakt" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.lastGlassesInput?.let {
                Text("Eingabe Brille: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val notice = state.notice
            if (notice != null && notice.severity != Severity.INFO &&
                System.currentTimeMillis() - notice.atMs < 60_000
            ) {
                NoticeText(notice)
            }
            if (speedShownAt != 0L) {
                Text(String.format(Locale.GERMANY, "Tempo %.1f×", state.speed), fontSize = 16.sp, color = WarnOrange)
            } else {
                Text(
                    "Wischen = Cursor · Halten = Menü",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ArmsLine(state: SessionState) {
    fun mark(p: ArmPhase) = if (p == ArmPhase.READY) "●" else "○"
    val color = when {
        state.right.phase != ArmPhase.READY -> ErrorRed
        state.left.phase != ArmPhase.READY -> WarnOrange
        else -> OkGreen
    }
    val battery = state.battery?.let { "  ·  $it %" } ?: ""
    Text("L ${mark(state.left.phase)}   R ${mark(state.right.phase)}$battery", fontSize = 13.sp, color = color)
}

/** Glasses pixels per watch dp at speed 1.0 before acceleration. */
private const val BASE_GAIN = 2.6f

/** Finger must rest this long (without moving past the touch slop) to open the menu. */
private const val LONG_PRESS_MS = 900L

/**
 * Relative pointer tracking. Only deltas between successive events of the same finger are
 * reported; a new touch (or a second finger taking over) re-anchors without moving.
 */
private suspend fun PointerInputScope.relativeTouchpad(
    onDown: () -> Unit,
    onMove: (dx: Float, dy: Float, dtMs: Float) -> Unit,
    onUp: () -> Unit,
    onLongPress: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onDown()
        var tracked = down.id
        var last = down.position
        var lastTime = down.uptimeMillis
        var travelled = 0f
        var longPressed = false
        try {
            while (true) {
                val waitingForLongPress = !longPressed && travelled <= slop
                val event = if (waitingForLongPress) {
                    val remaining = LONG_PRESS_MS - (lastTime - down.uptimeMillis)
                    withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    longPressed = true
                    onLongPress()
                    continue
                }
                val change = event.changes.firstOrNull { it.id == tracked }
                if (change == null || !change.pressed) {
                    val other = event.changes.firstOrNull { it.pressed } ?: break
                    // Another finger is still down: continue with it, re-anchored (no jump).
                    tracked = other.id
                    last = other.position
                    lastTime = other.uptimeMillis
                    event.changes.forEach { it.consume() }
                    continue
                }
                val delta = change.position - last
                val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L).toFloat()
                last = change.position
                lastTime = change.uptimeMillis
                if (delta.x != 0f || delta.y != 0f) {
                    travelled += delta.getDistance()
                    if (!longPressed) onMove(delta.x, delta.y, dt)
                }
                event.changes.forEach { it.consume() }
            }
        } finally {
            onUp()
        }
    }
}
