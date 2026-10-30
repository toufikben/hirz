package dz.hirz.server.people

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.CurfewScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.NightException
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

private val ORIGIN = LatLon(36.7538, 3.0588)
private fun at(north: Double, east: Double) = LatLon(ORIGIN.lat + north / 111_320.0, ORIGIN.lon + east / (111_320.0 * 0.8016))

@Serializable
data class ScenarioPlace(val name: String, val kind: String, val north: Double = 0.0, val east: Double = 0.0, val radius: Double = 50.0)

@Serializable
data class ScenarioException(val place: String, val from: String, val to: String)

/** One step at [t] seconds after the scenario's start: a fix, or a curfew scan. */
@Serializable
data class ScenarioStep(val t: Long, val action: String, val north: Double = 0.0, val east: Double = 0.0, val acc: Double = 8.0, val speed: Double? = null)

/** [expect]: the rules open at the end, exactly, under the scenario's own profile. [positiveFor]: the rule it exists to show. */
@Serializable
data class PeopleScenario(
    val name: String, val start: String, val type: String, val condition: String = "none", val curfew: String? = null,
    val places: List<ScenarioPlace>, val exceptions: List<ScenarioException> = emptyList(),
    val steps: List<ScenarioStep>, val expect: List<String>, val positiveFor: String? = null,
)

/** The P5 replay library (`src/test/resources/people`), run over the real HTTP API with a moving clock. */
object PeopleScenarios {
    private val json = Json { ignoreUnknownKeys = false }

    fun all(): List<PeopleScenario> =
        File(PeopleScenarios::class.java.getResource("/people")!!.toURI()).listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }.map { json.decodeFromString(PeopleScenario.serializer(), it.readText()) }

    private fun minutes(hhmm: String) = hhmm.split(':').let { it[0].toInt() * 60 + it[1].toInt() }

    /** Runs [s], optionally as another [type]/[condition], and returns the rules open at the end. */
    fun run(s: PeopleScenario, type: AssetType? = null, condition: Condition? = null): Set<String> {
        val env = TestEnv()
        var open = emptySet<String>()
        withApp(env) { client, _ ->
            val start = Instant.parse(s.start)
            env.clock.now = start
            val circle = client.newCircle("test.mom")
            val placeIds = s.places.associate { p ->
                val dto: PlaceDto = client.postJson("/v1/circles/${circle.id}/places", "test.mom",
                    CreatePlaceRequest(p.name, PlaceKind.valueOf(p.kind.uppercase()), ShapeDto.Circle(at(p.north, p.east), p.radius), marginM = 50)).body()
                p.name to dto.id
            }
            val assetType = type ?: AssetType.valueOf(s.type.uppercase())
            val asset = client.newAsset("test.mom", circle.id, assetType, "Subject")
            val update = ProfileUpdate(
                condition = condition ?: Condition.valueOf(s.condition.uppercase()),
                curfewMinute = s.curfew?.let { minutes(it) },
                nightExceptions = s.exceptions.map { e -> NightException(placeIds.getValue(e.place), ActiveWindow((1..7).toSet(), minutes(e.from), minutes(e.to))) },
            )
            client.put("/v1/assets/${asset.id}/profile") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(update) }
            val adult = assetType == AssetType.ELDER || assetType == AssetType.LONE_WORKER
            val p: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code, adult = adult, consent = adult).body()
            val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
            val curfew = CurfewScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
            for ((i, step) in s.steps.withIndex()) {
                env.clock.now = start.plusSeconds(step.t)
                when (step.action) {
                    "fix" -> client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(
                        IngestSignal("s$i", env.clock.millis(), at(step.north, step.east), step.acc, speedKmh = step.speed))))
                    "curfew_scan" -> curfew.scanOnce()
                    else -> error("unknown action ${step.action}")
                }
            }
            open = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
                .map { it.rule.name }.toSet()
        }
        return open
    }
}
