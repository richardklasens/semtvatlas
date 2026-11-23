package nl.atlas.semtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Start na opstarten/update de service en de kiosk-activity. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KioskService.start(context)
        if (Prefs(context).paused) return       // kiosk bewust gepauzeerd: de service start hem na de pauze weer
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { /* HOME-launcher start de activity ook zelf */ }
    }
}
