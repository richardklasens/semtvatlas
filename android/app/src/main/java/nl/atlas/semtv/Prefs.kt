package nl.atlas.semtv

import android.content.Context

/** Lokale instellingen van dit paneel (waar staat de server, welk scherm ben ik). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("semtv", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("serverUrl", "") ?: ""
        set(v) = sp.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()
    var screenId: String
        get() = sp.getString("screenId", "") ?: ""
        set(v) = sp.edit().putString("screenId", v.trim()).apply()
    var deviceKey: String
        get() = sp.getString("deviceKey", "") ?: ""
        set(v) = sp.edit().putString("deviceKey", v.trim()).apply()

    // Laatst bekende serverconfig (zodat het paneel ook zonder server correct opstart)
    var touchMode: String
        get() = sp.getString("touchMode", "timed") ?: "timed"
        set(v) = sp.edit().putString("touchMode", v).apply()
    var touchDisableAfterSec: Int
        get() = sp.getInt("touchDisableAfterSec", 60)
        set(v) = sp.edit().putInt("touchDisableAfterSec", v).apply()
    var brightness: Int
        get() = sp.getInt("brightness", -1)
        set(v) = sp.edit().putInt("brightness", v).apply()
    // Schermvastzetten (Android "screen pinning") zonder Device Owner; aan/uit via het beheermenu
    /** Tot dit tijdstip (ms) is de kiosk bewust gepauzeerd ("Kiosk verlaten"); daarna herstelt de service hem vanzelf. */
    var pausedUntil: Long
        get() = sp.getLong("pausedUntil", 0L)
        set(v) = sp.edit().putLong("pausedUntil", v).apply()
    val paused: Boolean get() = pausedUntil > System.currentTimeMillis()

    var pinScreen: Boolean
        get() = sp.getBoolean("pinScreen", false)
        set(v) = sp.edit().putBoolean("pinScreen", v).apply()
    var adminPin: String
        get() = sp.getString("adminPin", "1234") ?: "1234"
        set(v) = sp.edit().putString("adminPin", v).apply()

    val configured: Boolean get() = serverUrl.isNotEmpty() && screenId.isNotEmpty()
    val playerUrl: String get() = "$serverUrl/player/?screen=${java.net.URLEncoder.encode(screenId, "UTF-8")}&app=1"
}
