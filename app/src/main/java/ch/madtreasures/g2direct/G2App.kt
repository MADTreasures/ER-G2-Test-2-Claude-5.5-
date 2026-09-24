package ch.madtreasures.g2direct

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import ch.madtreasures.g2direct.ble.G2Scanner
import ch.madtreasures.g2direct.ble.G2Session

/** Holds the BLE session for the whole process, so it survives activity recreation. */
class G2App : Application() {
    val session: G2Session by lazy { G2Session(this) }
    val scanner: G2Scanner by lazy { G2Scanner(this) }

    private val prefs by lazy { getSharedPreferences("g2direct", Context.MODE_PRIVATE) }

    data class LastPair(val title: String, val right: String, val left: String?)

    fun lastPair(): LastPair? {
        val right = prefs.getString("right", null) ?: return null
        return LastPair(prefs.getString("title", null) ?: "G2", right, prefs.getString("left", null))
    }

    fun saveLastPair(title: String, right: String, left: String?) {
        prefs.edit {
            putString("title", title)
            putString("right", right)
            putString("left", left)
        }
    }

    fun forgetLastPair() {
        prefs.edit { clear() }
    }
}
