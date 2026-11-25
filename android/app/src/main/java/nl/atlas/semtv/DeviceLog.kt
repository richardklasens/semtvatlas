package nl.atlas.semtv

import android.content.Context
import java.io.File

/**
 * Eigen logboek van het paneel in een bestand (overleeft een herstart van app en paneel).
 * Regelformaat: "<epoch-ms>\t<tekst>". Het bestand blijft klein: boven de limiet gaat de oudste helft eruit.
 * De service stuurt de regels naar de server zodra de verbinding terug is na een storing (zie KioskService).
 */
object DeviceLog {
    private const val MAX_BYTES = 120_000
    private const val MAX_MSG = 240
    private var file: File? = null

    @Synchronized
    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "devlog.txt")
    }

    @Synchronized
    fun add(msg: String) {
        val f = file ?: return
        try {
            val clean = msg.replace('\n', ' ').replace('\t', ' ').take(MAX_MSG)
            f.appendText("${System.currentTimeMillis()}\t$clean\n")
            if (f.length() > MAX_BYTES) {
                val lines = f.readLines()
                f.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
        } catch (_: Throwable) { /* loggen mag nooit de app laten crashen */ }
    }

    /** Regels met een tijdstip NA [afterMs] (maximaal de laatste [max]); lijst van (tijdstip, tekst). */
    @Synchronized
    fun linesAfter(afterMs: Long, max: Int = 400): List<Pair<Long, String>> {
        val f = file ?: return emptyList()
        return try {
            f.readLines().mapNotNull { l ->
                val i = l.indexOf('\t')
                val t = if (i > 0) l.substring(0, i).toLongOrNull() else null
                if (t != null && t > afterMs) Pair(t, l.substring(i + 1)) else null
            }.takeLast(max)
        } catch (_: Throwable) { emptyList() }
    }
}
