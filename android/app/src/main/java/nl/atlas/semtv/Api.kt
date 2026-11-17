package nl.atlas.semtv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    /** POST JSON naar de server; geeft de JSON-respons of null bij een fout. */
    fun post(url: String, body: JSONObject, deviceKey: String): JSONObject? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (deviceKey.isNotEmpty()) c.setRequestProperty("X-Device-Key", deviceKey)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) return null
            JSONObject(c.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        }
    }
}
