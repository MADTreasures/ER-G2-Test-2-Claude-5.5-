package ch.madtreasures.g2direct.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** Battery level of the watch itself. */
data class WatchBattery(val percent: Int?, val charging: Boolean)

private fun readBattery(intent: Intent?): WatchBattery {
    if (intent == null) return WatchBattery(null, false)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return WatchBattery(percent, charging)
}

/** Follows the watch battery through the system's sticky ACTION_BATTERY_CHANGED broadcast. */
@Composable
fun rememberWatchBattery(): WatchBattery {
    val context = LocalContext.current
    val filter = remember { IntentFilter(Intent.ACTION_BATTERY_CHANGED) }
    var battery by remember {
        mutableStateOf(readBattery(ContextCompat.registerReceiver(context, null, filter, ContextCompat.RECEIVER_NOT_EXPORTED)))
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                battery = readBattery(intent)
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return battery
}

/** "⌚ 76 %   👓 81 %" with drawn icons; the glasses icon takes the connection colour. */
@Composable
fun BatteryRow(watch: WatchBattery, glassesPercent: Int?, glassesCharging: Boolean?, glassesColor: Color) {
    val textColor = MaterialTheme.colorScheme.onSurface
    fun label(percent: Int?, charging: Boolean?) =
        (percent?.let { "$it %" } ?: "– %") + if (charging == true) " ⚡" else ""
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        WatchIcon(textColor, Modifier.size(width = 12.dp, height = 18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label(watch.percent, watch.charging), fontSize = 14.sp, color = textColor)
        Spacer(Modifier.width(14.dp))
        GlassesIcon(glassesColor, Modifier.size(width = 26.dp, height = 12.dp))
        Spacer(Modifier.width(5.dp))
        Text(label(glassesPercent, glassesCharging), fontSize = 14.sp, color = textColor)
    }
}

/** Watch: round-cornered case with strap stubs above and below. */
@Composable
private fun WatchIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.16f
        val strapW = w * 0.6f
        val caseTop = h * 0.2f
        val caseH = h * 0.6f
        drawRect(color, Offset((w - strapW) / 2, 0f), Size(strapW, caseTop + stroke))
        drawRect(color, Offset((w - strapW) / 2, caseTop + caseH - stroke), Size(strapW, h - caseTop - caseH + stroke))
        drawRoundRect(
            color, Offset(stroke / 2, caseTop), Size(w - stroke, caseH),
            cornerRadius = CornerRadius(w * 0.3f), style = Stroke(stroke),
        )
    }
}

/** Glasses: two round lenses, a bridge and short temples. */
@Composable
private fun GlassesIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = h * 0.16f
        val r = h * 0.38f
        val cy = h * 0.55f
        val left = Offset(w * 0.27f, cy)
        val right = Offset(w * 0.73f, cy)
        drawCircle(color, r, left, style = Stroke(stroke))
        drawCircle(color, r, right, style = Stroke(stroke))
        drawLine(color, Offset(left.x + r, cy - r * 0.3f), Offset(right.x - r, cy - r * 0.3f), stroke)
        drawLine(color, Offset(left.x - r, cy - r * 0.3f), Offset(0f, h * 0.12f), stroke)
        drawLine(color, Offset(right.x + r, cy - r * 0.3f), Offset(w, h * 0.12f), stroke)
    }
}
