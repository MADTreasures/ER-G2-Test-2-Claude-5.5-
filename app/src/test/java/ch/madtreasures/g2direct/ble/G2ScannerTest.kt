package ch.madtreasures.g2direct.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothManager

/** Arms the watch already knows must be listed even when they do not advertise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class G2ScannerTest {

    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(BluetoothManager::class.java)

    private fun device(address: String, name: String, bonded: Boolean): BluetoothDevice =
        ShadowBluetoothDevice.newInstance(address).also {
            shadowOf(it).setName(name)
            shadowOf(it).setBondState(if (bonded) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE)
        }

    @Test
    fun bondedAndSystemConnectedArmsStayListedAcrossScans() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(manager.adapter).setEnabled(true)
        val left = device("E8:9D:EF:A4:DE:7F", "Even G2_32_L_A4DE7F", bonded = true)
        val other = device("11:22:33:44:55:66", "Pixel Buds Pro", bonded = true)
        shadowOf(manager.adapter).setBondedDevices(setOf(left, other))
        // The right arm is connected by the system (e.g. another app) but not bonded.
        val right = device("E8:9D:EF:A4:DE:80", "Even G2_32_R_A4DE80", bonded = false)
        Shadow.extract<ShadowBluetoothManager>(manager)
            .addDevice(BluetoothProfile.GATT, BluetoothProfile.STATE_CONNECTED, right)

        val scanner = G2Scanner(app)
        scanner.start()
        scanner.start() // a second scan must not drop the silent, unbonded arm

        val pair = scanner.pairs.value.single()
        assertTrue(pair.complete)
        assertEquals("E8:9D:EF:A4:DE:7F", pair.left?.address)
        assertEquals("E8:9D:EF:A4:DE:80", pair.right?.address)
        assertTrue(pair.left!!.bonded)
        assertFalse(pair.right!!.bonded)
        assertFalse(pair.right!!.advertising)
    }
}
