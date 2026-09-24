package ch.madtreasures.g2direct.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import ch.madtreasures.g2direct.G2App
import ch.madtreasures.g2direct.ble.ArmPhase
import ch.madtreasures.g2direct.ble.ArmUi
import ch.madtreasures.g2direct.ble.G2Pair
import ch.madtreasures.g2direct.ble.Notice
import ch.madtreasures.g2direct.ble.SessionPhase
import ch.madtreasures.g2direct.ble.SessionState
import ch.madtreasures.g2direct.ble.Severity
import ch.madtreasures.g2direct.protocol.CursorLayers
import java.util.Locale

val OkGreen = Color(0xFF7CFFA0)
val WarnOrange = Color(0xFFFFC266)
val ErrorRed = Color(0xFFFF7B7B)

@Composable
fun NoticeText(notice: Notice?, modifier: Modifier = Modifier) {
    if (notice == null) return
    val color = when (notice.severity) {
        Severity.ERROR -> ErrorRed
        Severity.WARN -> WarnOrange
        Severity.INFO -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        notice.text,
        modifier = modifier.fillMaxWidth(),
        color = color,
        textAlign = TextAlign.Center,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun CenterText(text: String, color: Color = MaterialTheme.colorScheme.onSurface, size: Int = 13) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = color,
        fontSize = size.sp,
    )
}

// ---------------------------------------------------------------------------------------------

@Composable
fun PermissionScreen(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Berechtigung") } }
            item {
                CenterText(
                    "Für die Verbindung zur Brille braucht die App „Geräte in der Nähe“ (Bluetooth).",
                    size = 14,
                )
            }
            item {
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Erlauben") }
            }
            item {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("App-Einstellungen")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun DevicesScreen(
    pairs: List<G2Pair>,
    scanning: Boolean,
    bluetoothOn: Boolean,
    scanError: String?,
    lastPair: G2App.LastPair?,
    onScan: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onConnectPair: (G2Pair) -> Unit,
    onConnectLast: (G2App.LastPair) -> Unit,
    onForgetLast: () -> Unit,
    onLog: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onScan, enabled = bluetoothOn && !scanning) {
                Text(if (scanning) "Suche…" else "Suchen")
            }
        },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Brille wählen") } }
            if (!bluetoothOn) {
                item { CenterText("Bluetooth ist ausgeschaltet.", color = ErrorRed, size = 14) }
                item {
                    Button(onClick = onEnableBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth einschalten") }
                }
            }
            if (scanError != null) item { CenterText(scanError, color = ErrorRed) }
            if (lastPair != null) {
                item {
                    Button(
                        onClick = { onConnectLast(lastPair) },
                        modifier = Modifier.fillMaxWidth(),
                        secondaryLabel = { Text("zuletzt verwendet", fontSize = 12.sp) },
                        label = { Text(lastPair.title, maxLines = 1) },
                    )
                }
            }
            if (scanning && pairs.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("Suche G2…", fontSize = 14.sp)
                    }
                }
            }
            items(pairs, key = { it.key }) { pair -> PairButton(pair, onConnectPair) }
            if (!scanning && pairs.isEmpty()) {
                item {
                    CenterText(
                        "Keine G2 gefunden. Brille aus dem Etui nehmen und am Smartphone Bluetooth " +
                            "ausschalten oder die Even-App beenden – sonst bleibt die Brille belegt.",
                        color = WarnOrange,
                    )
                }
            }
            if (lastPair != null) {
                item {
                    OutlinedButton(onClick = onForgetLast, modifier = Modifier.fillMaxWidth()) {
                        Text("„Zuletzt“ vergessen", fontSize = 13.sp)
                    }
                }
            }
            item {
                OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) { Text("Protokoll") }
            }
        }
    }
}

@Composable
private fun PairButton(pair: G2Pair, onConnect: (G2Pair) -> Unit) {
    val arms = (if (pair.left != null) "L ✓ " else "L – ") + (if (pair.right != null) "R ✓" else "R –")
    val right = pair.right
    val secondary = when {
        right == null -> "rechter Bügel fehlt – er trägt die Anzeige"
        right.advertising && right.rssi != null -> "$arms · ${right.rssi} dBm"
        right.bonded -> "$arms · gekoppelt"
        else -> arms
    }
    FilledTonalButton(
        onClick = { onConnect(pair) },
        enabled = pair.right != null,
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = { Text(secondary, fontSize = 11.sp, maxLines = 2) },
        label = { Text(pair.title, maxLines = 1) },
    )
}

// ---------------------------------------------------------------------------------------------

@Composable
fun StatusScreen(
    state: SessionState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenTouchpad: () -> Unit,
    onLog: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val busy = state.phase in listOf(
        SessionPhase.CONNECTING, SessionPhase.INITIALIZING, SessionPhase.CREATING_PAGE, SessionPhase.RECONNECTING
    )
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (state.phase == SessionPhase.FAILED) {
                EdgeButton(onClick = onRetry) { Text("Erneut") }
            } else if (state.phase == SessionPhase.READY) {
                EdgeButton(onClick = onOpenTouchpad) { Text("Touchpad") }
            } else {
                EdgeButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) { Text("Abbrechen") }
            }
        },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(state.title ?: "G2") } }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(
                        state.phase.label + if (state.phase == SessionPhase.RECONNECTING) " ${state.reconnectAttempt}/3" else "",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.phase == SessionPhase.FAILED) ErrorRed else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (state.notice != null) item { NoticeText(state.notice) }
            armItems(state)
            item { CenterText("Anzeige: ${state.page.label} · Bild: ${state.imageStatus}", size = 12) }
            if (state.phase == SessionPhase.FAILED) {
                item {
                    CenterText(
                        "Tipps: Brille aus dem Etui nehmen, Handy-Bluetooth aus, " +
                            "Brille neu starten (5× auf beide Bügel tippen).",
                        color = WarnOrange, size = 12,
                    )
                }
                item {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Andere Brille") }
                }
            }
            item { OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) { Text("Protokoll") } }
        }
    }
}

private fun ScalingLazyListScope.armItems(state: SessionState) {
    item { ArmLine(state.right) }
    item { ArmLine(state.left) }
}

@Composable
private fun ArmLine(arm: ArmUi) {
    val color = when (arm.phase) {
        ArmPhase.READY -> OkGreen
        ArmPhase.FAILED -> ErrorRed
        ArmPhase.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> WarnOrange
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            "${if (arm.side == ch.madtreasures.g2direct.ble.Side.RIGHT) "Rechts" else "Links"}: ${arm.phase.label}" +
                (if (arm.authenticated) " ✓" else ""),
            color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        if (arm.detail.isNotEmpty() && arm.phase != ArmPhase.OFF) {
            Text(
                arm.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 3,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun MenuScreen(
    state: SessionState,
    onBack: () -> Unit,
    onRebuild: () -> Unit,
    onCenter: () -> Unit,
    onStyle: (CursorLayers.Style) -> Unit,
    onSpeed: (Float) -> Unit,
    onLog: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onBack) { Text("Touchpad") } },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Menü") } }
            if (state.notice != null) item { NoticeText(state.notice) }
            armItems(state)
            item {
                val s = state.stats
                val battery = state.battery?.let { "Akku $it %" + if (state.charging == true) " ⚡" else "" } ?: "Akku ?"
                CenterText(
                    "$battery · FW ${state.firmware ?: "?"}\n" +
                        "Anzeige: ${state.page.label} · Bild: ${state.imageStatus}\n" +
                        "Updates: ${s.textSent} gesendet, ${s.textAcked} bestätigt, ${s.textTimeouts} ohne Antwort\n" +
                        "Antwortzeit Ø ${s.ackMsAvg} ms · ${String.format(Locale.GERMANY, "%.1f", s.updatesPerSecond)}/s" +
                        (if (s.fixedRateMode) " (Festtakt)" else "") + "\n" +
                        "Pakete ${s.packetsSent} · verworfen ${s.packetsDropped} · CRC-Fehler ${s.crcErrors}",
                    size = 11,
                )
            }
            item {
                FilledTonalButton(onClick = onRebuild, modifier = Modifier.fillMaxWidth()) { Text("Testbild neu senden") }
            }
            item {
                FilledTonalButton(onClick = onCenter, modifier = Modifier.fillMaxWidth()) { Text("Cursor zentrieren") }
            }
            item {
                val styles = CursorLayers.Style.entries
                val next = styles[(styles.indexOf(state.style) + 1) % styles.size]
                FilledTonalButton(
                    onClick = { onStyle(next) },
                    modifier = Modifier.fillMaxWidth(),
                    secondaryLabel = { Text("tippen: ${next.label}", fontSize = 11.sp) },
                    label = { Text("Cursor: ${state.style.label}") },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { onSpeed(state.speed - 0.2f) }) { Text("−") }
                    Text(String.format(Locale.GERMANY, "Tempo %.1f×", state.speed), fontSize = 13.sp)
                    OutlinedButton(onClick = { onSpeed(state.speed + 0.2f) }) { Text("+") }
                }
            }
            item { OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) { Text("Protokoll") } }
            item {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) { Text("Trennen") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun LogScreen(lines: List<String>, onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onBack) { Text("Zurück") } },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Protokoll") } }
            if (lines.isEmpty()) item { CenterText("Noch keine Einträge.") }
            items(lines) { line ->
                val color = when {
                    line.contains("FEHLER") -> ErrorRed
                    line.contains("Warnung") -> WarnOrange
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    line, fontSize = 10.sp, color = color, lineHeight = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
        }
    }
}
