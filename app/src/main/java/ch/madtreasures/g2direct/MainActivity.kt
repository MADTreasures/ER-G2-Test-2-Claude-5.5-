package ch.madtreasures.g2direct

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import ch.madtreasures.g2direct.ble.G2Pair
import ch.madtreasures.g2direct.ble.SessionPhase
import ch.madtreasures.g2direct.ui.DevicesScreen
import ch.madtreasures.g2direct.ui.LogScreen
import ch.madtreasures.g2direct.ui.MenuScreen
import ch.madtreasures.g2direct.ui.PermissionScreen
import ch.madtreasures.g2direct.ui.StatusScreen
import ch.madtreasures.g2direct.ui.TouchpadScreen

class MainActivity : ComponentActivity() {

    private val app get() = application as G2App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScaffold(timeText = { TimeText() }) {
                    Root()
                }
            }
        }
    }

    private enum class Screen { DEVICES, STATUS, TOUCHPAD, MENU, LOG }

    @Composable
    private fun Root() {
        val session = app.session
        val scanner = app.scanner
        val state by session.state.collectAsStateWithLifecycle()
        var permissionTick by remember { mutableIntStateOf(0) }
        val missing = remember(permissionTick) { missingPermissions() }
        var screen by rememberSaveable { mutableStateOf(Screen.DEVICES) }
        var logReturn by rememberSaveable { mutableStateOf(Screen.DEVICES) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissionTick++ }
        val enableBtLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { permissionTick++ }

        // Keep the watch awake while a session is running: the connection, heartbeats and the
        // touchpad all stop being useful once Wear OS dims into ambient mode.
        val keepAwake = state.phase != SessionPhase.IDLE && state.phase != SessionPhase.FAILED
        LaunchedEffect(keepAwake) {
            if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Follow the session: open the touchpad once ready, fall back to the status page on trouble.
        LaunchedEffect(state.phase) {
            when (state.phase) {
                // Also when the app is reopened while a session is still running.
                SessionPhase.READY -> if (screen == Screen.STATUS || screen == Screen.DEVICES) screen = Screen.TOUCHPAD
                SessionPhase.CONNECTING, SessionPhase.INITIALIZING, SessionPhase.CREATING_PAGE,
                SessionPhase.RECONNECTING, SessionPhase.FAILED ->
                    if (screen == Screen.TOUCHPAD || screen == Screen.MENU || screen == Screen.DEVICES) screen = Screen.STATUS
                SessionPhase.IDLE -> if (screen != Screen.LOG) screen = Screen.DEVICES
            }
        }

        if (missing.isNotEmpty()) {
            PermissionScreen(
                onRequest = { permissionLauncher.launch(missing.toTypedArray()) },
                onOpenSettings = {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                },
            )
            return
        }

        fun connect(req: Selection) {
            scanner.stop()
            app.saveLastPair(req.title, req.right.address, req.left?.address)
            session.connect(req.title, req.right, req.left)
            screen = Screen.STATUS
        }

        when (screen) {
            Screen.DEVICES -> {
                val pairs by scanner.pairs.collectAsStateWithLifecycle()
                val scanning by scanner.scanning.collectAsStateWithLifecycle()
                val scanError by scanner.error.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    if (scanner.bluetoothEnabled()) scanner.start() else scanner.refreshKnownDevices()
                }
                DevicesScreen(
                    pairs = pairs,
                    scanning = scanning,
                    bluetoothOn = scanner.bluetoothEnabled(),
                    scanError = scanError,
                    lastPair = app.lastPair(),
                    onScan = { scanner.start() },
                    onEnableBluetooth = {
                        try {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    },
                    onConnectPair = { pair -> requestFor(pair)?.let { connect(it) } },
                    onConnectLast = { last ->
                        val right = scanner.remoteDevice(last.right)
                        if (right != null) {
                            connect(Selection(last.title, right, last.left?.let { scanner.remoteDevice(it) }))
                        }
                    },
                    onForgetLast = { app.forgetLastPair(); permissionTick++ },
                    onLog = { logReturn = Screen.DEVICES; screen = Screen.LOG },
                )
            }

            Screen.STATUS -> StatusScreen(
                state = state,
                onCancel = { session.disconnect(); screen = Screen.DEVICES },
                onRetry = {
                    app.lastPair()?.let { last ->
                        val right = scanner.remoteDevice(last.right) ?: return@let
                        connect(Selection(last.title, right, last.left?.let { scanner.remoteDevice(it) }))
                    }
                },
                onOpenTouchpad = { screen = Screen.TOUCHPAD },
                onLog = { logReturn = Screen.STATUS; screen = Screen.LOG },
            )

            Screen.TOUCHPAD -> TouchpadScreen(
                state = state,
                onTouchStart = { session.onTouchStart() },
                onMove = { dx, dy -> session.moveCursorBy(dx, dy) },
                onSpeed = { session.setSpeed(it) },
                onDoubleTap = { session.click() },
                onOpenMenu = { screen = Screen.MENU },
            )

            Screen.MENU -> {
                BackHandler { screen = Screen.TOUCHPAD }
                MenuScreen(
                    state = state,
                    onBack = { screen = Screen.TOUCHPAD },
                    onRebuild = { session.rebuildTestPage(); screen = Screen.TOUCHPAD },
                    onCenter = { session.centerCursor(); screen = Screen.TOUCHPAD },
                    onStyle = { session.setStyle(it) },
                    onSpeed = { session.setSpeed(it) },
                    onPipeline = { session.setPipeline(it) },
                    onLog = { logReturn = Screen.MENU; screen = Screen.LOG },
                    onDisconnect = { session.disconnect(); screen = Screen.DEVICES },
                )
            }

            Screen.LOG -> {
                val lines by session.log.collectAsStateWithLifecycle()
                BackHandler { screen = logReturn }
                LogScreen(lines = lines, onBack = { screen = logReturn })
            }
        }
    }

    /** The arms the user picked. The right arm is required, the left one optional. */
    private class Selection(val title: String, val right: BluetoothDevice, val left: BluetoothDevice?)

    private fun requestFor(pair: G2Pair): Selection? {
        val right = pair.right ?: return null
        return Selection(pair.title, right.device, pair.left?.device)
    }

    private fun missingPermissions(): List<String> {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }
}
