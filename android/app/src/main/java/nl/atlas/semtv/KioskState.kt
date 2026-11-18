package nl.atlas.semtv

import android.os.Handler
import android.os.Looper

/**
 * Gedeelde status tussen service (netwerk/commando's) en activity (scherm/touch).
 * Alles draait in hetzelfde proces, dus een singleton volstaat.
 */
object KioskState {
    @Volatile var activity: MainActivity? = null
    @Volatile var touchLocked: Boolean = false
    @Volatile var screenOn: Boolean = true
    @Volatile var softOff: Boolean = false   // zwart scherm + minimale helderheid (werkt ook zonder Device Owner)
    @Volatile var lastResult: String = ""
    val main = Handler(Looper.getMainLooper())

    fun onUi(block: (MainActivity) -> Unit): Boolean {
        val a = activity ?: return false
        main.post { block(a) }
        return true
    }
}
