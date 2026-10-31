package dz.hirz.server.routines

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.RoutineScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.RoutineRequest
import dz.hirz.shared.api.RoutineTemplate
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.asset.AssetType
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

private val ORIGIN = LatLon(36.7538, 3.0588)
private fun at(north: Double, east: Double) = LatLon(ORIGIN.lat + north / 111_320.0, ORIGIN.lon + east / (111_320.0 * 0.8016))

@Serializable
data class RoutinePlace(val name: String, val kind: String, val north: Double = 0.0, val east: Double = 0.0, val radius: Double = 50.0)

@Serializable
data class RoutineSpec(val place: String, val template: String)

/**
 * One step at [t] seconds after the start. `fix`: one fix. `scan`: the routine scanner. `walk`: [n] fixes every 30 s from
 * (north, east) to (toNorth, toEast). `laps`: [laps] walks around a 200 m square whose corner is (north, east), a fix every 30 s.
 */
@Serializable
data class RoutineStep(
    val t: Long, val action: String, val north: Double = 0.0, val east: Double = 0.0, val acc: Double = 10.0,
    val toNorth: Double = 0.0, val toEast: Double = 0.0, val n: Int = 0, val laps: Int = 0,
)

/** [expect]: exactly the P7 rules open at the end. [positiveFor]/[negativeFor]: the rule it shows firing, or staying quiet. */
@Serializable
data class RoutineScenario(
    val name: String, val start: String, val type: String, val places: List<RoutinePlace>, val routine: RoutineSpec? = null,
    val steps: List<RoutineStep>, val expect: List<String>, val positiveFor: String? = null, val negativeFor: String? = null,
)

private val P7_RULES = listOf(RuleId.MISSED_ARRIVAL, RuleId.STATIONARY_AFTER_DISMISSAL, RuleId.UNUSUAL_STOP, RuleId.PACING_LOOP)

private object RoutineScenarios {
    private val json = Json { ignoreUnknownKeys = false }

    fun all(): List<RoutineScenario> =
        File(RoutineScenarios::class.java.getResource("/routines")!!.toURI()).listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }.map { json.decodeFromString(RoutineScenario.serializer(), it.readText()) }

    private fun fixes(s: RoutineStep): List<Pair<Long, LatLon>> = when (s.action) {
        "fix" -> listOf(s.t to at(s.north, s.east))
        "walk" -> (0 until s.n).map { i ->
            val f = if (s.n == 1) 0.0 else i.toDouble() / (s.n - 1)
            s.t + 30L * i to at(s.north + (s.toNorth - s.north) * f, s.east + (s.toEast - s.east) * f)
        }
        "laps" -> {
            val corners = listOf(0.0 to 0.0, 0.0 to 200.0, 200.0 to 200.0, 200.0 to 0.0)
            val one = corners.indices.flatMap { i ->
                val (n1, e1) = corners[i]; val (n2, e2) = corners[(i + 1) % 4]
                (0 until 5).map { k -> at(s.north + n1 + (n2 - n1) * k / 5, s.east + e1 + (e2 - e1) * k / 5) }
            }
            (1..s.laps).flatMap { one }.mapIndexed { i, p -> s.t + 30L * i to p }
        }
        else -> emptyList()
    }

    /** The P7 rules open at the end of [s]. */
    fun run(s: RoutineScenario): Set<String> {
        val env = TestEnv()
        var open = emptySet<String>()
        withApp(env) { client, _ ->
            val start = Instant.parse(s.start)
            env.clock.now = start
            val circle = client.newCircle("test.mom")
            val ids = s.places.associate { p ->
                val dto: PlaceDto = client.postJson("/v1/circles/${circle.id}/places", "test.mom",
                    CreatePlaceRequest(p.name, PlaceKind.valueOf(p.kind.uppercase()), ShapeDto.Circle(at(p.north, p.east), p.radius))).body()
                p.name to dto.id
            }
            val type = AssetType.valueOf(s.type.uppercase())
            val asset = client.newAsset("test.mom", circle.id, type, "Subject")
            s.routine?.let { r ->
                client.postJson("/v1/assets/${asset.id}/routines", "test.mom", RoutineRequest(ids.getValue(r.place), template = RoutineTemplate.valueOf(r.template.uppercase())))
            }
            val adult = type == AssetType.ELDER || type == AssetType.LONE_WORKER
            val p: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code, adult = adult, consent = adult).body()
            val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
            val scanner = RoutineScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
            var n = 0
            for (step in s.steps) {
                if (step.action == "scan") { env.clock.now = start.plusSeconds(step.t); scanner.scanOnce(); continue }
                val points = fixes(step).ifEmpty { error("unknown action ${step.action}") }
                for ((t, point) in points) {
                    env.clock.now = start.plusSeconds(t)
                    client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("s${n++}", env.clock.millis(), point, step.acc))))
                }
            }
            open = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
                .map { it.rule }.filter { it in P7_RULES }.map { it.name }.toSet()
        }
        return open
    }
}

class RoutineScenariosTest {
    /** AC-19: every P7 rule has a scenario that raises it and one where it must stay quiet. */
    @Test
    fun everyRoutineScenarioHolds() {
        val all = RoutineScenarios.all()
        assertTrue(all.size >= 8, "the P7 library is present")
        val failures = all.mapNotNull { s ->
            val got = runCatching { RoutineScenarios.run(s) }.getOrElse { return@mapNotNull "${s.name}: ${it.message}" }
            if (got != s.expect.toSet()) "${s.name}: open $got, expected ${s.expect.toSet()}" else null
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        P7_RULES.forEach { r ->
            assertTrue(all.any { it.positiveFor == r.name }, "no positive scenario for $r")
            assertTrue(all.any { it.negativeFor == r.name && r.name !in it.expect }, "no negative scenario for $r")
        }
    }
}
