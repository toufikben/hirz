package dz.hirz.sim

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class OsmAndClientTest {
    @Test
    fun sendsQueryParameters() {
        val query = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            query.set(exchange.requestURI.rawQuery)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val fix = Gt06Fix(
                36.7538, 3.0588, speedKmh = 37, courseDeg = 90,
                utc = Instant.parse("2026-10-27T13:00:00Z"), satellites = 8,
            )
            val status = OsmAndClient("http://127.0.0.1:${server.address.port}").send("dev-1", fix, batteryPercent = 77)
            assertEquals(200, status)
            val params = query.get().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals("dev-1", params["id"])
            assertEquals("36.7538", params["lat"])
            assertEquals("3.0588", params["lon"])
            assertEquals("1793106000", params["timestamp"])
            assertEquals("19.98", params["speed"]) // 37 km/h in knots, 2 decimals
            assertEquals("90", params["bearing"])
            assertEquals("77", params["batt"])
        } finally {
            server.stop(0)
        }
    }
}
