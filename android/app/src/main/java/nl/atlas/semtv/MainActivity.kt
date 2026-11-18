package nl.atlas.semtv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Kiosk-scherm: WebView met de player, een touch-guard laag en (indien Device Owner) lock task.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private lateinit var guard: TouchGuardView

    private var lastTouch = System.currentTimeMillis()
    private var appliedMode: String? = null
    private var appliedBrightness = Int.MIN_VALUE
    private var retryPending = false

    private val idleCheck = object : Runnable {
        override fun run() {
            val sec = prefs.touchDisableAfterSec
            if (prefs.touchMode == "timed" && sec > 0 && !guard.locked &&
                System.currentTimeMillis() - lastTouch >= sec * 1000L) setLocked(true)
            hideSystemUi()
            KioskState.main.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#501237")) }
        guard = TouchGuardView(this) { askPin() }
        buildWebView()
        root.addView(guard, FrameLayout.LayoutParams(-1, -1))
        // zwarte laag voor 'scherm uit' zonder Device Owner; vangt ook alle aanraking af
        offView = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true; isFocusable = false }
        root.addView(offView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        // de systeembalken meteen weer weg als ze door een veeg verschijnen
        window.decorView.setOnSystemUiVisibilityChangeListener { vis ->
            if (vis and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) KioskState.main.postDelayed({ hideSystemUi() }, 250)
        }

        KioskState.activity = this
        setupKiosk()
        KioskService.start(this)

        if (!prefs.configured) startActivityForResult(Intent(this, SetupActivity::class.java), 1) else loadPlayer()
        applyConfig()
        KioskState.main.post(idleCheck)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        web?.let { root.removeView(it); it.destroy() }
        val w = WebView(this)
        w.setBackgroundColor(Color.parseColor("#501237"))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        w.isLongClickable = false
        w.setOnLongClickListener { true }
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame || retryPending) return
                // Server onbereikbaar: probeer over 10 s opnieuw, en val terug op de WebView-cache.
                retryPending = true
                v.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                KioskState.main.postDelayed({ retryPending = false; loadPlayer() }, 10_000)
            }
            override fun onPageFinished(v: WebView, url: String?) { v.settings.cacheMode = WebSettings.LOAD_DEFAULT }
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                KioskState.main.post { buildWebView(); loadPlayer() }; return true
            }
        }
        web = w
        root.addView(w, 0, FrameLayout.LayoutParams(-1, -1))
    }

    private fun loadPlayer() { if (prefs.configured) web?.loadUrl(prefs.playerUrl) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) loadPlayer()
    }

    /** Kiosk-modus: lock task, geen statusbalk/keyguard, deze app als vaste HOME (alleen als Device Owner). */
    private fun setupKiosk() {
        hideSystemUi()
        if (!AdminReceiver.isDeviceOwner(this)) return
        val dpm = AdminReceiver.dpm(this); val admin = AdminReceiver.component(this)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
            if (Build.VERSION.SDK_INT >= 28) dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            dpm.setKeyguardDisabled(admin, true)
            dpm.setStatusBarDisabled(admin, true)
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7")
            val home = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
            dpm.addPersistentPreferredActivity(admin, home, android.content.ComponentName(this, MainActivity::class.java))
            startLockTask()
        } catch (e: Exception) {
            KioskState.lastResult = "kiosk-setup: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private var offView: View? = null
    private var edge: View? = null

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }

    override fun onResume() { super.onResume(); addEdgeBlocker() }
    override fun onPause() { removeEdgeBlocker(); super.onPause() }

    /** Onzichtbare strook onderaan (boven de Android-knoppenbalk) die aanraking opvangt, zodat een veeg de balk niet bruikbaar maakt.
     *  Vereist 'Weergeven over andere apps' (adb shell appops set nl.atlas.semtv SYSTEM_ALERT_WINDOW allow). Laat de linkeronderhoek vrij voor het beheergebaar. */
    private fun addEdgeBlocker() {
        if (edge != null || Build.VERSION.SDK_INT < 26 || !Settings.canDrawOverlays(this)) return
        try {
            val dm = resources.displayMetrics
            val v = View(this).apply { setBackgroundColor(Color.TRANSPARENT); isClickable = true }
            val lp = WindowManager.LayoutParams((dm.widthPixels * 0.8f).toInt(), (72 * dm.density).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.END }
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp); edge = v
        } catch (e: Exception) { KioskState.lastResult = "edge-blocker: ${e.message}" }
    }
    private fun removeEdgeBlocker() {
        edge?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }; edge = null
    }

    /** Scherm 'uit': zwart + minimale helderheid. Werkt op elk paneel; met Device Owner/-beheerder zet de service het scherm daarna ook echt uit. */
    fun setScreenOff(off: Boolean) {
        KioskState.softOff = off
        offView?.visibility = if (off) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (off) 0f else (if (prefs.brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else prefs.brightness.coerceIn(1, 100) / 100f)
        window.attributes = lp
        if (!off) reload()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!guard.locked) lastTouch = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Back is uitgeschakeld in kiosk")
    override fun onBackPressed() { /* negeren */ }

    override fun onDestroy() {
        KioskState.main.removeCallbacks(idleCheck)
        if (KioskState.activity === this) KioskState.activity = null
        web?.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ aangeroepen door KioskService (UI-thread)

    /** Config van de server toepassen. Modus/helderheid alleen bij een wijziging, zodat remote-commando's niet worden overschreven. */
    fun applyConfig() {
        if (appliedMode != prefs.touchMode) {
            appliedMode = prefs.touchMode
            when (prefs.touchMode) { "off" -> setLocked(true); "always" -> setLocked(false); else -> setLocked(false) }
        }
        if (appliedBrightness != prefs.brightness) { appliedBrightness = prefs.brightness; setBrightness(prefs.brightness) }
    }

    fun setLocked(locked: Boolean) {
        guard.locked = locked
        KioskState.touchLocked = locked
        if (!locked) lastTouch = System.currentTimeMillis() // nieuwe inactiviteitsperiode
    }

    fun setBrightness(pct: Int) {
        val lp = window.attributes
        lp.screenBrightness = if (pct < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else pct.coerceIn(1, 100) / 100f
        window.attributes = lp
    }

    fun reload() { retryPending = false; loadPlayer() }
    fun clearCache() { web?.clearCache(true); loadPlayer() }

    // ------------------------------------------------------------------ verborgen beheergebaar

    private fun askPin() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Pincode" }
        AlertDialog.Builder(this).setTitle("Beheer").setView(input)
            .setPositiveButton("OK") { _, _ -> if (input.text.toString() == prefs.adminPin) adminMenu() }
            .setNegativeButton("Annuleren", null).show()
    }

    private fun adminMenu() {
        val items = arrayOf("Touch weer aanzetten", "Pagina herladen", "Instellingen (server/scherm-ID)", "Kiosk verlaten", "Apparaatbeheerder activeren (scherm echt uitzetten)")
        AlertDialog.Builder(this).setTitle("Beheer").setItems(items) { _, which ->
            when (which) {
                0 -> setLocked(false)
                1 -> reload()
                2 -> startActivityForResult(Intent(this, SetupActivity::class.java), 1)
                3 -> { try { stopLockTask() } catch (_: Exception) {}; finish() }
                4 -> startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.component(this)))
            }
        }.show()
    }
}
