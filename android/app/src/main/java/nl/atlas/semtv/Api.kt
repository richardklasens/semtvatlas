package nl.atlas.semtv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    /** Reden van de laatste mislukte aanroep, in gewone taal (voor het diagnose-scherm en de server-log). */
    @Volatile var lastError: String = ""

    /** POST JSON naar de server; geeft de JSON-respons of null bij een fout (reden staat dan in lastError). */
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
            val code = c.responseCode
            if (code !in 200..299) {
                lastError = when (code) {
                    401 -> "HTTP 401: device key ontbreekt of klopt niet"
                    404 -> "HTTP 404: scherm-ID onbekend op de server (of serveradres wijst naar iets anders)"
                    else -> "HTTP $code van de server"
                }
                return null
            }
            val r = JSONObject(c.inputStream.bufferedReader().readText())
            lastError = ""
            r
        } catch (e: java.net.UnknownHostException) {
            lastError = "serveradres niet te vinden (DNS): ${e.message}"; null
        } catch (e: java.net.SocketTimeoutException) {
            lastError = "server reageert niet (timeout)"; null
        } catch (e: java.net.ConnectException) {
            lastError = "geen verbinding met de server: ${e.message}"; null
        } catch (e: org.json.JSONException) {
            lastError = "antwoord van de server is geen JSON (verkeerd serveradres/poort?)"; null
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"; null
        }
    }
}
