package nl.atlas.semtv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

/** Eenmalige/handmatige configuratie: serveradres, scherm-ID en optionele device key. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); setBackgroundColor(Color.parseColor("#501237")) }

        fun label(t: String) = TextView(this).apply { text = t; setTextColor(Color.parseColor("#ff5000")); textSize = 16f; setPadding(0, pad / 2, 0, 0) }
        fun field(v: String, hint: String) = EditText(this).apply {
            setText(v); this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setSingleLine()
        }

        root.addView(TextView(this).apply { text = "SEM-TV Atlas — instellen"; setTextColor(Color.WHITE); textSize = 24f })
        root.addView(label("Serveradres (bijv. http://192.168.1.10:8080)"))
        val url = field(prefs.serverUrl, "http://server:8080"); root.addView(url)
        root.addView(label("Scherm-ID (zoals in het beheerportaal, bijv. kleedkamer-0-1)"))
        val id = field(prefs.screenId, "scherm-id"); root.addView(id)
        root.addView(label("Device key (leeg laten als de server er geen vraagt)"))
        val key = field(prefs.deviceKey, ""); root.addView(key)

        val btn = Button(this).apply {
            text = "Opslaan en starten"
            setOnClickListener {
                prefs.serverUrl = url.text.toString(); prefs.screenId = id.text.toString(); prefs.deviceKey = key.text.toString()
                if (!prefs.configured) { Toast.makeText(this@SetupActivity, "Vul serveradres en scherm-ID in", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                KioskService.start(this@SetupActivity)
                setResult(RESULT_OK); finish()
            }
        }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#501237")); addView(root) })
    }
}
