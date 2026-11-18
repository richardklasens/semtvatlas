package nl.atlas.semtv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Draait altijd (foreground service): stuurt elke 15 s een heartbeat naar de server,
 * ontvangt opdrachten (herladen, herstart, scherm aan/uit, touch, helderheid) en voert ze uit.
 */
class KioskService : Service() {
    companion object {
        private const val INTERVAL_MS = 15_000L
        fun start(ctx: Context) {
            val i = Intent(ctx, KioskService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    @Volatile private var running = false
    private lateinit var prefs: Prefs
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val pendingAck = mutableListOf<String>()
    private var activityMissingSince = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this)
        startForeground(1, notification())
        if (!running) {
            running = true
            acquireLocks()
            thread(name = "heartbeat", isDaemon = true) { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() { running = false; cpuLock?.release(); wifiLock?.release(); super.onDestroy() }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("kiosk", "Kiosk", NotificationManager.IMPORTANCE_MIN))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "kiosk") else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("SEM-TV Atlas actief").setSmallIcon(android.R.drawable.ic_menu_info_details).build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "semtv:heartbeat").apply { setReferenceCounted(false); acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "semtv:wifi").apply { setReferenceCounted(false); acquire() }
    }

    private fun loop() {
        while (running) {
            try { beat() } catch (_: Exception) { /* volgende ronde opnieuw */ }
            watchdog()
            SystemClock.sleep(INTERVAL_MS)
        }
    }

    /** Als de kiosk-activity om wat voor reden dan ook weg is, start hem opnieuw. */
    private fun watchdog() {
        if (!prefs.configured) return
        if (KioskState.activity != null) { activityMissingSince = 0; return }
        val now = SystemClock.elapsedRealtime()
        if (activityMissingSince == 0L) { activityMissingSince = now; return }
        if (now - activityMissingSince > 30_000) {
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            activityMissingSince = now
        }
    }

    private fun payload(ack: List<String>): JSONObject {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        @Suppress("DEPRECATION")
        val ipInt = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ipAddress ?: 0
        return JSONObject()
            .put("kind", "app")
            .put("appVersion", packageManager.getPackageInfo(packageName, 0).versionName)
            .put("battery", if (level >= 0) level * 100 / scale else -1)
            .put("screenOn", pm.isInteractive && !KioskState.softOff)
            .put("touchLocked", KioskState.touchLocked)
            .put("deviceOwner", AdminReceiver.isDeviceOwner(this))
            .put("activityAlive", KioskState.activity != null)
            .put("lastResult", KioskState.lastResult)
            .put("localIp", "${ipInt and 0xff}.${ipInt shr 8 and 0xff}.${ipInt shr 16 and 0xff}.${ipInt shr 24 and 0xff}")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            .put("uptimeMin", SystemClock.elapsedRealtime() / 60000)
            .put("ackIds", JSONArray(ack))
    }

    private fun beat() {
        if (!prefs.configured) return
        val ack = synchronized(pendingAck) { pendingAck.toList().also { pendingAck.clear() } }
        val url = "${prefs.serverUrl}/api/device/${java.net.URLEncoder.encode(prefs.screenId, "UTF-8")}/heartbeat"
        val resp = Api.post(url, payload(ack), prefs.deviceKey)
        if (resp == null) { synchronized(pendingAck) { pendingAck.addAll(ack) }; return }

        resp.optJSONObject("config")?.let { c ->
            prefs.touchMode = c.optString("touchMode", prefs.touchMode)
            prefs.touchDisableAfterSec = c.optInt("touchDisableAfterSec", prefs.touchDisableAfterSec)
            prefs.brightness = c.optInt("brightness", prefs.brightness)
            prefs.adminPin = c.optString("adminPin", prefs.adminPin)
            KioskState.onUi { it.applyConfig() }
        }
        val cmds = resp.optJSONArray("commands") ?: return
        for (i in 0 until cmds.length()) {
            val c = cmds.getJSONObject(i)
            val id = c.getString("id"); val type = c.getString("type"); val params = c.optJSONObject("params") ?: JSONObject()
            // Eerst bevestigen, dan uitvoeren: bij scherm uit / herstart kan de verbinding daarna wegvallen
            // en dan zou het portaal blijven wachten. Mislukt de bevestiging, dan gaat hij mee met de volgende heartbeat.
            val acked = Api.post(url, payload(listOf(id)), prefs.deviceKey) != null
            if (!acked) synchronized(pendingAck) { pendingAck.add(id) }
            execute(type, params)
            if (type == "reboot" || type == "restart_app") return
        }
    }

    private fun execute(type: String, params: JSONObject) {
        KioskState.lastResult = "$type @ ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}"
        when (type) {
            "reload" -> KioskState.onUi { it.reload() }
            "clear_cache" -> KioskState.onUi { it.clearCache() }
            "touch_lock" -> KioskState.onUi { it.setLocked(true) }
            "touch_unlock" -> KioskState.onUi { it.setLocked(false) }
            "set_brightness" -> KioskState.onUi { it.setBrightness(params.optInt("value", -1)) }
            "screen_off" -> screenOff()
            "screen_on" -> screenOn()
            "restart_app" -> restartApp()
            "reboot" -> reboot()
        }
    }

    private fun screenOff() {
        KioskState.onUi { it.setScreenOff(true) }
        // met Device Owner / apparaatbeheerder het scherm daarna ook echt vergrendelen
        if (AdminReceiver.isDeviceOwner(this) || AdminReceiver.dpm(this).isAdminActive(AdminReceiver.component(this))) {
            KioskState.main.postDelayed({
                try { AdminReceiver.dpm(this).lockNow() } catch (e: Exception) { KioskState.lastResult = "screen_off: ${e.message}" }
            }, 800)
        }
    }

    @Suppress("DEPRECATION")
    private fun screenOn() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "semtv:wake").acquire(5000)
        val ok = KioskState.onUi { it.setScreenOff(false) }
        if (!ok) try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private fun restartApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pi)
        Process.killProcess(Process.myPid())
    }

    private fun reboot() {
        if (AdminReceiver.isDeviceOwner(this)) {
            try { AdminReceiver.dpm(this).reboot(AdminReceiver.component(this)); return } catch (e: Exception) { KioskState.lastResult = "reboot: ${e.message}" }
        }
        // Gerooted paneel als terugval
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")); return } catch (_: Exception) {}
        KioskState.lastResult = "reboot niet mogelijk: app is geen device owner (zie README)"
    }
}
