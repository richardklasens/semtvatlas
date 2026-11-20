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
    // Status van de heartbeat naar de server (voor het diagnose-scherm in het beheermenu)
    @Volatile var serviceStartedAt: Long = 0
    @Volatile var beatAt: Long = 0          // tijdstip laatste poging
    @Volatile var beatOkAt: Long = 0        // tijdstip laatste geslaagde heartbeat
    @Volatile var beatFails: Int = 0        // aantal mislukte pogingen sinds het laatste succes
    @Volatile var beatError: String = ""    // reden van de laatste mislukte poging
    @Volatile var updateStatus: String = "" // voortgang/uitkomst van "App bijwerken"
    val main = Handler(Looper.getMainLooper())

    fun onUi(block: (MainActivity) -> Unit): Boolean {
        val a = activity ?: return false
        main.post { block(a) }
        return true
    }
}
