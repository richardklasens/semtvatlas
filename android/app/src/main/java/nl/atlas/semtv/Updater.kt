package nl.atlas.semtv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "App bijwerken": haalt de apk op van de eigen server (/downloads/app-release.apk, via het portaal te uploaden)
 * en installeert die als update. Met Device Owner gebeurt dat zonder tussenkomst; anders toont Android één
 * bevestigingsvenster op het paneel. Een update lukt alleen met een HOGER versienummer en dezelfde handtekening.
 */
object Updater {
    const val ACTION = "nl.atlas.semtv.INSTALL_RESULT"

    @Suppress("DEPRECATION")
    private fun versionOf(ctx: Context, info: android.content.pm.PackageInfo?): Long =
        if (info == null) -1 else if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    fun run(ctx: Context, prefs: Prefs) {
        try {
            KioskState.updateStatus = "apk downloaden van de server…"
            val file = File(ctx.cacheDir, "update.apk")
            val c = URL("${prefs.serverUrl}/downloads/app-release.apk").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 60_000
            if (c.responseCode != 200) {
                KioskState.updateStatus = if (c.responseCode == 404) "mislukt: er staat geen apk op de server (upload hem eerst in het portaal)" else "mislukt: download gaf HTTP ${c.responseCode}"
                return
            }
            c.inputStream.use { i -> file.outputStream().use { o -> i.copyTo(o) } }

            // Versiecontrole vooraf: een update moet een hoger versienummer hebben dan wat nu draait.
            val newInfo = ctx.packageManager.getPackageArchiveInfo(file.path, 0)
            val cur = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val newV = versionOf(ctx, newInfo); val curV = versionOf(ctx, cur)
            if (newInfo == null) { KioskState.updateStatus = "mislukt: gedownload bestand is geen geldige apk"; return }
            if (newInfo.packageName != ctx.packageName) { KioskState.updateStatus = "mislukt: apk hoort bij een andere app (${newInfo.packageName})"; return }
            if (newV <= curV) {
                KioskState.updateStatus = "niet nodig: server heeft versie ${newInfo.versionName} (code $newV), hier draait ${cur.versionName} (code $curV)"
                file.delete(); return
            }

            KioskState.updateStatus = "installeren: ${cur.versionName} → ${newInfo.versionName}…"
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                file.inputStream().use { i -> s.openWrite("app", 0, file.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(ctx, id, Intent(ACTION).setPackage(ctx.packageName), flags)
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            KioskState.updateStatus = "mislukt: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Verbindingstest voor het diagnose-venster: bereikt dit paneel de server, kent de server dit scherm-ID, en accepteert hij de heartbeat? */
    fun testConnection(ctx: Context, prefs: Prefs): String {
        val sb = StringBuilder()
        val base = prefs.serverUrl
        try {
            val c = URL("$base/api/player/${java.net.URLEncoder.encode(prefs.screenId, "UTF-8").replace("+", "%20")}/state").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 10000
            val code = c.responseCode
            sb.append("Server bereikbaar: ja\n")
            sb.append(if (code == 200) "Scherm-ID \"${prefs.screenId}\" bekend: ja\n" else "Scherm-ID \"${prefs.screenId}\" bekend: NEE (HTTP $code)\n")
        } catch (e: Exception) { sb.append("Server bereikbaar: NEE — ${e.javaClass.simpleName}: ${e.message}\n"); return sb.toString() }
        val body = JSONObject().put("kind", "app").put("appVersion", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName).put("diagTest", true)
        val r = Api.post("$base/api/device/${java.net.URLEncoder.encode(prefs.screenId, "UTF-8")}/heartbeat", body, prefs.deviceKey)
        sb.append(if (r != null) "Heartbeat: geaccepteerd door de server\n" else "Heartbeat: GEWEIGERD — ${Api.lastError}\n")
        return sb.toString()
    }
}

/** Ontvangt de uitkomst van de installatie. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                KioskState.updateStatus = "wacht op bevestiging op het paneel (tik op Installeren/Bijwerken)"
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                try { confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(confirm) } catch (e: Exception) { KioskState.updateStatus = "mislukt: bevestigingsvenster kon niet openen (${e.message})" }
            }
            PackageInstaller.STATUS_SUCCESS -> KioskState.updateStatus = "bijgewerkt; app start opnieuw op"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> KioskState.updateStatus = "mislukt: installeren is geblokkeerd — zet in Android \"Onbekende apps installeren\" aan voor deze app (beheermenu → App bijwerken)"
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                KioskState.updateStatus = "mislukt: andere handtekening dan de geïnstalleerde app (eenmalig verwijderen en opnieuw installeren nodig): $msg"
            else -> KioskState.updateStatus = "mislukt (code $status): $msg"
        }
    }
}
