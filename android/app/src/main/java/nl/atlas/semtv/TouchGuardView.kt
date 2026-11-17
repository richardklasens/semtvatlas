package nl.atlas.semtv

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Transparante laag boven de WebView.
 * - locked = true: alle aanraking wordt opgevangen (de WebView krijgt niets), behalve een
 *   lange druk (3 s) in de linkeronderhoek: dat is het verborgen beheergebaar (vraagt PIN).
 * - locked = false: laat alle aanraking door naar de WebView.
 * Android heeft geen API om de touch-hardware zelf uit te zetten; dit werkt op elk paneel.
 */
class TouchGuardView(ctx: Context, private val onAdminGesture: () -> Unit) : FrameLayout(ctx) {
    var locked: Boolean = false
    private var gesturePending = false
    private val trigger = Runnable { gesturePending = false; onAdminGesture() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!locked) return false // niet verwerken: sibling (WebView) krijgt het event
        val inCorner = ev.x < width * 0.15f && ev.y > height * 0.85f
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (inCorner) { gesturePending = true; postDelayed(trigger, 3000) }
            MotionEvent.ACTION_MOVE -> if (gesturePending && !inCorner) cancelGesture()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelGesture()
        }
        return true
    }

    private fun cancelGesture() {
        if (gesturePending) { removeCallbacks(trigger); gesturePending = false }
    }

    init { isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
}
