package nl.atlas.semtv

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings

/**
 * Device Owner-beleid voor de kiosk. Elke stap staat in een eigen try, zodat één mislukte stap de rest niet blokkeert
 * (eerder kon een fout in een eerdere stap voorkomen dat "deze app is de HOME-app" werd ingesteld; dan toont Android
 * na een herstart of crash de app-kiezer in plaats van de kiosk te starten).
 */
object KioskPolicy {
    @Volatile var lastApply: String = ""        // "ok" of welke stappen mislukten

    /** Wordt de HOME-knop/opstart door onze app afgehandeld? false = Android toont de app-kiezer. */
    fun homeIsUs(ctx: Context): Boolean = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == ctx.packageName
    } catch (_: Exception) { true }

    fun apply(ctx: Context) {
        if (!AdminReceiver.isDeviceOwner(ctx)) { lastApply = "geen device owner"; return }
        val dpm = AdminReceiver.dpm(ctx); val admin = AdminReceiver.component(ctx)
        val failed = ArrayList<String>()
        fun step(name: String, block: () -> Unit) { try { block() } catch (e: Throwable) { failed.add("$name: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()) } }
        // HOME eerst: dit is de belangrijkste stap
        step("home") {
            val home = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
            dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(ctx, MainActivity::class.java))
        }
        step("lockTaskPackages") { dpm.setLockTaskPackages(admin, arrayOf(ctx.packageName)) }
        step("lockTaskFeatures") { if (android.os.Build.VERSION.SDK_INT >= 28) dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
        step("keyguard") { dpm.setKeyguardDisabled(admin, true) }
        step("statusbar") { dpm.setStatusBarDisabled(admin, true) }
        step("stayOn") { dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7") }
        lastApply = if (failed.isEmpty()) "ok" else failed.joinToString("; ")
    }

    /** Tijdelijk uit de kiosk: andere startapp mag, statusbalk en keyguard weer aan, zodat je bij de Android-instellingen kunt. */
    fun release(ctx: Context) {
        if (!AdminReceiver.isDeviceOwner(ctx)) return
        val dpm = AdminReceiver.dpm(ctx); val admin = AdminReceiver.component(ctx)
        try { dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName) } catch (_: Throwable) {}
        try { dpm.setStatusBarDisabled(admin, false) } catch (_: Throwable) {}
        try { dpm.setKeyguardDisabled(admin, false) } catch (_: Throwable) {}
        lastApply = "gepauzeerd"
    }
}
