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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ch.madtreasures.g2direct.ble.ArmPhase
import ch.madtreasures.g2direct.ble.PageState
import ch.madtreasures.g2direct.ble.SessionState
import ch.madtreasures.g2direct.ble.Severity
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.hypot

/**
 * The whole watch face is a relative touchpad: only finger *movement* moves the cursor,
 * never the touch position itself, so lifting and putting the finger down elsewhere does
 * not make the cursor jump. There are no zones or buttons on this screen: a double tap
 * anywhere is a mouse click at the cursor, holding the finger still for a moment opens the
 * menu, the crown changes the pointer speed.
 */
@Composable
fun TouchpadScreen(
    state: SessionState,
    onTouchStart: () -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onDoubleTap: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val speed by rememberUpdatedState(state.speed)
    val openMenu by rememberUpdatedState(onOpenMenu)
    // pointerInput(Unit) lives as long as the screen; always call the latest callbacks.
    val touchStart by rememberUpdatedState(onTouchStart)
    val move by rememberUpdatedState(onMove)
    val setSpeed by rememberUpdatedState(onSpeed)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    var lastTapAt by remember { mutableLongStateOf(-10_000L) }
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
                setSpeed(speed + event.verticalScrollPixels / 500f)
                speedShownAt = System.currentTimeMillis()
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                relativeTouchpad(
                    onDown = {
                        touching = true
                        touchStart()
                    },
                    onMove = { dx, dy, dtMs ->
                        // Watch pixels -> dp -> glasses pixels, with mild pointer acceleration:
                        // slow strokes position precisely, fast flicks cross the display.
                        val ddx = dx / density
                        val ddy = dy / density
                        val velocity = hypot(ddx, ddy) / dtMs
                        val accel = (0.55f + velocity * 1.2f).coerceIn(0.55f, 2.6f)
                        val k = BASE_GAIN * speed * accel
                        move(ddx * k, ddy * k)
                    },
                    onUp = { touching = false },
                    onTap = { at ->
                        if (at - lastTapAt <= DOUBLE_TAP_MS) {
                            lastTapAt = -10_000L
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            doubleTap()
                        } else {
                            lastTapAt = at
                        }
                    },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openMenu()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Everything here is read-only status; the whole surface stays a touchpad. The layout
        // is kept short so it fits inside the round display below the time.
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
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
            Text(
                "Anzeige: ${state.page.label}", fontSize = 13.sp, color = pageColor,
                textAlign = TextAlign.Center, maxLines = 1,
            )
            val s = state.stats
            Text(
                String.format(Locale.GERMANY, "%.0f/s · Ø %d ms", s.updatesPerSecond, s.ackMsAvg) +
                    if (s.fixedRateMode) " · Festtakt" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val notice = state.notice
            val showNotice = notice != null && notice.severity != Severity.INFO &&
                System.currentTimeMillis() - notice.atMs < 60_000
            if (showNotice && notice != null) {
                Text(
                    notice.text,
                    color = if (notice.severity == Severity.ERROR) ErrorRed else WarnOrange,
                    fontSize = 12.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            } else if (state.pointerInfo != null) {
                Text(state.pointerInfo, fontSize = 12.sp, color = OkGreen, maxLines = 1)
            } else {
                state.lastGlassesInput?.let {
                    Text("Brille-Eingabe: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (speedShownAt != 0L) {
                Text(String.format(Locale.GERMANY, "Tempo %.1f×", state.speed), fontSize = 16.sp, color = WarnOrange)
            } else if (!showNotice) {
                // Two short lines: the round display is too narrow this far down for one.
                Text(
                    "2× tippen = Klick\nHalten = Menü",
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
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

/** A touch shorter than this that stays within the touch slop is a tap. */
private const val TAP_MAX_MS = 300L

/** Two taps at most this far apart (end to end) make a double tap. */
private const val DOUBLE_TAP_MS = 400L

/**
 * Relative pointer tracking. Only deltas between successive events of the same finger are
 * reported; a new touch (or a second finger taking over) re-anchors without moving.
 * Movement within the touch slop is held back until the finger clearly moves, so a tap
 * never nudges the cursor; the held-back part is then sent along and nothing is lost.
 */
private suspend fun PointerInputScope.relativeTouchpad(
    onDown: () -> Unit,
    onMove: (dx: Float, dy: Float, dtMs: Float) -> Unit,
    onUp: () -> Unit,
    onTap: (upTimeMs: Long) -> Unit,
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
        var multiFinger = false
        var heldX = 0f
        var heldY = 0f
        var heldMs = 0f
        var upTime = down.uptimeMillis
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
                    val other = event.changes.firstOrNull { it.pressed }
                    if (other == null) {
                        upTime = change?.uptimeMillis ?: lastTime
                        break
                    }
                    // Another finger is still down: continue with it, re-anchored (no jump).
                    multiFinger = true
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
                if ((delta.x != 0f || delta.y != 0f) && !longPressed) {
                    travelled += delta.getDistance()
                    if (travelled <= slop) {
                        heldX += delta.x
                        heldY += delta.y
                        heldMs += dt
                    } else {
                        onMove(heldX + delta.x, heldY + delta.y, heldMs + dt)
                        heldX = 0f
                        heldY = 0f
                        heldMs = 0f
                    }
                }
                event.changes.forEach { it.consume() }
            }
        } finally {
            onUp()
        }
        if (!longPressed && !multiFinger && travelled <= slop && upTime - down.uptimeMillis <= TAP_MAX_MS) {
            onTap(upTime)
        }
    }
}
