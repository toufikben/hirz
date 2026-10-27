package dz.hirz.sim

import java.time.Instant
import kotlin.system.exitProcess

private const val USAGE = """usage:
  sim gt06   --host H --port P --imei I --lat LAT --lon LON [--count N --interval-s S --dlat D]
  sim osmand --url U --id I --lat LAT --lon LON [--count N --interval-s S --dlat D]"""

fun main(args: Array<String>) {
    val mode = args.firstOrNull()
    val options = args.drop(1).chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    fun opt(name: String): String = options[name] ?: run { System.err.println(USAGE); exitProcess(2) }
    val count = options["count"]?.toInt() ?: 1
    val intervalMs = (options["interval-s"]?.toLong() ?: 5) * 1_000
    val dlat = options["dlat"]?.toDouble() ?: 0.0
    val lat0 = opt("lat").toDouble()
    val lon = opt("lon").toDouble()
    val fixes = (0 until count).map { Gt06Fix(lat0 + it * dlat, lon, 0, 0, Instant.EPOCH, 8) }

    when (mode) {
        "gt06" -> Gt06Client(opt("host"), opt("port").toInt()).use { client ->
            client.login(opt("imei"))
            fixes.forEachIndexed { i, f ->
                if (i > 0) Thread.sleep(intervalMs)
                val fix = f.copy(utc = Instant.now())
                client.send(fix)
                println("gt06 sent ${fix.lat},${fix.lon} at ${fix.utc}")
            }
        }
        "osmand" -> {
            val client = OsmAndClient(opt("url"))
            fixes.forEachIndexed { i, f ->
                if (i > 0) Thread.sleep(intervalMs)
                val fix = f.copy(utc = Instant.now())
                println("osmand sent ${fix.lat},${fix.lon} at ${fix.utc} -> HTTP ${client.send(opt("id"), fix, null)}")
            }
        }
        else -> {
            System.err.println(USAGE)
            exitProcess(2)
        }
    }
}
