package dz.hirz.sim

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale

/** Sends fixes with the OsmAnd/Traccar Client HTTP protocol (speed in knots). */
class OsmAndClient(private val baseUrl: String) {
    private val http = HttpClient.newHttpClient()

    fun send(id: String, fix: Gt06Fix, batteryPercent: Int?): Int {
        val params = buildList {
            add("id" to id)
            add("lat" to fix.lat.toString())
            add("lon" to fix.lon.toString())
            add("timestamp" to fix.utc.epochSecond.toString())
            add("speed" to String.format(Locale.ROOT, "%.2f", fix.speedKmh / 1.852))
            add("bearing" to fix.courseDeg.toString())
            if (batteryPercent != null) add("batt" to batteryPercent.toString())
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/?$query"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}
