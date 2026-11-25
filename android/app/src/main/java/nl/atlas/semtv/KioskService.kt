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
        private const val STUCK_MS = 90_000L                  // zo lang geen levensteken van de heartbeat-taak = vastgelopen: nieuwe taak starten
        private const val SELF_RESTART_AFTER_MS = 20 * 60_000L // zo lang geen geslaagde heartbeat = paneel (of app) start zichzelf opnieuw
        private const val SELF_RESTART_GAP_MS = 3 * 3600_000L  // en dat hooguit eens per 3 uur
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
    @Volatile private var loopGen = 0
    @Volatile private var loopTickAt = 0L          // laatste levensteken van de heartbeat-taak (elapsedRealtime)
    @Volatile private var lastOkAt = 0L            // laatste geslaagde heartbeat (elapsedRealtime)
    @Volatile private var pendingReason = ""
    private var lastAliveLogAt = 0L
    private var logRetryAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this)
        DeviceLog.init(this)
        startForeground(1, notification())
        if (!running) {
            running = true
            KioskState.serviceStartedAt = System.currentTimeMillis()
            lastOkAt = SystemClock.elapsedRealtime()
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
            DeviceLog.add("service gestart: app $ver, paneel draait ${SystemClock.elapsedRealtime() / 60000} min, device owner ${if (AdminReceiver.isDeviceOwner(this)) "ja" else "nee"}")
            markDirty("service gestart")
            acquireLocks()
            startLoop()
            thread(name = "supervisor", isDaemon = true) { supervise() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DeviceLog.add("service gestopt (onDestroy)")
        if (::prefs.isInitialized) markDirty("service gestopt")
        running = false; cpuLock?.release(); wifiLock?.release(); super.onDestroy()
    }

    /** Er is iets gebeurd dat de server nog niet weet: de log gaat mee zodra een heartbeat weer lukt. */
    private fun markDirty(reason: String) {
        if (!pendingReason.contains(reason)) pendingReason = if (pendingReason.isEmpty()) reason else "$pendingReason, $reason"
        if (!prefs.logDirty) prefs.logDirty = true
    }

    private fun startLoop() {
        val gen = ++loopGen
        loopTickAt = SystemClock.elapsedRealtime()
        thread(name = "heartbeat-$gen", isDaemon = true) { loop(gen) }
    }

    /**
     * Bewaking, los van de heartbeat-taak: (1) staat die taak stil (vastgelopen netwerkaanroep, onverwachte fout),
     * dan start hij opnieuw; (2) lukt er langer dan SELF_RESTART_AFTER_MS geen heartbeat, dan herstart het paneel
     * zichzelf (device owner) of anders de app, hooguit eens per SELF_RESTART_GAP_MS.
     */
    private fun supervise() {
        while (running) {
            SystemClock.sleep(20_000)
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - loopTickAt > STUCK_MS) {
                    DeviceLog.add("heartbeat-taak reageerde al ${(now - loopTickAt) / 1000} s niet: nieuwe taak gestart")
                    markDirty("heartbeat-taak vastgelopen")
                    startLoop()
                }
                if (prefs.configured && !prefs.paused && now - lastOkAt > SELF_RESTART_AFTER_MS &&
                    System.currentTimeMillis() - prefs.lastSelfRestart > SELF_RESTART_GAP_MS) selfRestart(now - lastOkAt)
            } catch (_: Throwable) { /* bewaking mag nooit stoppen */ }
        }
    }

    private fun selfRestart(noOkMs: Long) {
        prefs.lastSelfRestart = System.currentTimeMillis()
        val owner = AdminReceiver.isDeviceOwner(this)
        DeviceLog.add("ZELFHERSTEL: al ${noOkMs / 60000} min geen geslaagde heartbeat; ${if (owner) "paneel herstart" else "app herstart"}")
        markDirty("zelfherstel")
        if (owner) { reboot(); SystemClock.sleep(15_000) }   // lukt de herstart niet, dan valt hij terug op de app
        restartApp()
    }

    /** Levensteken in de log (elke 10 min): laat achteraf zien of de service leefde en in welke toestand het paneel was. */
    @Suppress("DEPRECATION")
    private fun aliveLog() {
        val now = SystemClock.elapsedRealtime()
        if (lastAliveLogAt != 0L && now - lastAliveLogAt < 600_000L) return
        lastAliveLogAt = now
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        val ip = wi?.ipAddress ?: 0
        DeviceLog.add("levend: paneel ${now / 60000} min aan, wifi ${wi?.rssi ?: "?"} dBm ip ${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}, " +
            "scherm ${if (pm.isInteractive) "wakker" else "SLAAPT"}${if (KioskState.softOff) " (zwart)" else ""}, doze=${pm.isDeviceIdleMode}, " +
            "energiebesparing=${pm.isPowerSaveMode}, laatste geslaagde heartbeat ${(now - lastOkAt) / 1000} s geleden, mislukt=${KioskState.beatFails}")
    }

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

    private fun loop(gen: Int) {
        while (running && gen == loopGen) {
            loopTickAt = SystemClock.elapsedRealtime()
            try { beat() } catch (e: Throwable) {   // ook zware fouten: de taak mag nooit stilletjes stoppen
                DeviceLog.add("fout in heartbeat: ${e.javaClass.simpleName}: ${e.message}")
                try { markDirty("fout in heartbeat") } catch (_: Throwable) {}
            }
            if (gen != loopGen) return              // een nieuwere taak heeft het overgenomen
            try { aliveLog() } catch (_: Throwable) {}
            try {
                if (prefs.paused) {
                    // "Kiosk verlaten": watchdog en zelfherstel staan uit tot de pauze voorbij is
                } else {
                    if (prefs.pausedUntil != 0L) {      // pauze net afgelopen: kiosk herstellen en de app weer naar voren halen
                        prefs.pausedUntil = 0L
                        try { KioskPolicy.apply(this) } catch (_: Exception) {}
                        try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    }
                    watchdog()
                    // Zelfherstel: is deze app (om wat voor reden) niet meer de HOME-app, zet dat dan opnieuw
                    try { if (AdminReceiver.isDeviceOwner(this) && !KioskPolicy.homeIsUs(this)) KioskPolicy.apply(this) } catch (_: Exception) {}
                }
            } catch (e: Throwable) { DeviceLog.add("fout in bewaking: ${e.javaClass.simpleName}: ${e.message}") }
            loopTickAt = SystemClock.elapsedRealtime()
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
            DeviceLog.add("scherm-app was al 30 s weg: opnieuw gestart")
            markDirty("scherm-app was weg")
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
            .put("overlayGranted", Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this))
            .put("activityAlive", KioskState.activity != null)
            .put("lastResult", KioskState.lastResult)
            .put("homeIsUs", KioskPolicy.homeIsUs(this))
            .put("kioskPausedUntil", prefs.pausedUntil)
            .put("kioskSetup", KioskPolicy.lastApply)
            .put("hbFails", KioskState.beatFails)
            .put("hbError", KioskState.beatError)
            .put("updateStatus", KioskState.updateStatus)
            .put("localIp", "${ipInt and 0xff}.${ipInt shr 8 and 0xff}.${ipInt shr 16 and 0xff}.${ipInt shr 24 and 0xff}")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            .put("uptimeMin", SystemClock.elapsedRealtime() / 60000)
            .put("ackIds", JSONArray(ack))
    }

    private fun beat() {
        if (!prefs.configured) return
        val ack = synchronized(pendingAck) { pendingAck.toList().also { pendingAck.clear() } }
        val url = "${prefs.serverUrl}/api/device/${java.net.URLEncoder.encode(prefs.screenId, "UTF-8")}/heartbeat"
        KioskState.beatAt = System.currentTimeMillis()
        val resp = Api.post(url, payload(ack), prefs.deviceKey)
        if (resp == null) {
            KioskState.beatFails++; KioskState.beatError = Api.lastError
            if (KioskState.beatFails == 1 || KioskState.beatFails % 20 == 0) DeviceLog.add("heartbeat MISLUKT (${KioskState.beatFails}x): ${Api.lastError}")
            markDirty("heartbeat mislukt")
            synchronized(pendingAck) { pendingAck.addAll(ack) }; return
        }
        KioskState.beatOkAt = System.currentTimeMillis()
        lastOkAt = SystemClock.elapsedRealtime()
        if (KioskState.beatFails > 0) DeviceLog.add("heartbeat hersteld na ${KioskState.beatFails} mislukte poging(en)")
        // fouten van de periode ervoor zijn met deze (geslaagde) heartbeat meegestuurd; nu resetten
        KioskState.beatFails = 0

        resp.optJSONObject("config")?.let { c ->
            prefs.touchMode = c.optString("touchMode", prefs.touchMode)
            prefs.touchDisableAfterSec = c.optInt("touchDisableAfterSec", prefs.touchDisableAfterSec)
            prefs.brightness = c.optInt("brightness", prefs.brightness)
            prefs.adminPin = c.optString("adminPin", prefs.adminPin)
            KioskState.onUi { it.applyConfig() }
        }
        sendLogIfNeeded(url)
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

    /** Na een storing (heartbeat mislukt, herstart, ...) en zodra de verbinding terug is: de log als pakket naar de server. */
    private fun sendLogIfNeeded(heartbeatUrl: String) {
        if (!prefs.logDirty || SystemClock.elapsedRealtime() < logRetryAt) return
        val lines = DeviceLog.linesAfter(prefs.logSentUntil)
        if (lines.isEmpty()) { prefs.logDirty = false; pendingReason = ""; return }
        val arr = JSONArray()
        lines.forEach { arr.put(JSONObject().put("t", it.first).put("m", it.second)) }
        val body = JSONObject()
            .put("reason", pendingReason.ifEmpty { "onbekend" })
            .put("appVersion", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" })
            .put("uptimeMin", SystemClock.elapsedRealtime() / 60000)
            .put("lines", arr)
        val keepErr = Api.lastError   // een mislukte log-upload (bijv. oude server) mag de heartbeat-diagnose niet overschrijven
        val r = Api.post(heartbeatUrl.removeSuffix("/heartbeat") + "/devlog", body, prefs.deviceKey)
        Api.lastError = keepErr
        if (r != null) { prefs.logSentUntil = lines.last().first; prefs.logDirty = false; pendingReason = "" }
        else logRetryAt = SystemClock.elapsedRealtime() + 300_000L   // over 5 min opnieuw proberen
    }

    private fun execute(type: String, params: JSONObject) {
        DeviceLog.add("commando: $type")
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
            "update_app" -> thread(name = "update") { Updater.run(this, prefs) }
        }
    }

    /**
     * Scherm 'uit' = zwart beeld + minimale helderheid, bewust ZONDER lockNow(): het paneel blijft wakker,
     * zodat de heartbeat doorloopt en "Scherm aan" altijd direct aankomt (een slapend paneel verliest soms wifi).
     */
    private fun screenOff() {
        KioskState.onUi { it.setScreenOff(true) }
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
