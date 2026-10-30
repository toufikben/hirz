package dz.hirz.server.vehicles

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.JammingScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.AuthorizationRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.ModeRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

private val PARKED = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(PARKED.lat + m / 111_320.0, PARKED.lon)

/** One step at [t] seconds: arm, disarm, authorize, fix, heartbeat, alarm, jam_scan. */
@Serializable
private data class Step(
    val t: Long, val action: String, val north: Double = 0.0, val speed: Double = 0.0, val ignition: Boolean? = null,
    val accuracy: Double = 8.0, val hours: Int = 2, val alarm: String = "",
)

/** [rules]: the open incidents' rules (exactly); [tiers]: rule → tier; [openedByT]: rule → latest allowed opening second. */
@Serializable
private data class Expect(val rules: List<String>, val tiers: Map<String, String> = emptyMap(), val openedByT: Map<String, Long> = emptyMap())

@Serializable
private data class Scenario(val name: String, val steps: List<Step>, val expect: Expect)

/** AC-22 replay scenarios (P4 Task 4): the real HTTP API and rules, a moving clock, one car. */
class VehicleScenariosTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun allVehicleScenariosHold() {
        val files = File(javaClass.getResource("/vehicles")!!.toURI()).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 8, "every P4 scenario is present")
        val failures = files.mapNotNull { f ->
            val s = json.decodeFromString(Scenario.serializer(), f.readText())
            runCatching { run(s) }.exceptionOrNull()?.let { "${s.name}: ${it.message}" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun run(s: Scenario) {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val car = client.newAsset("test.mom", circle.id, AssetType.CAR, "Clio")
            val p: PairResponse = client.pair(client.pairingCode("test.mom", car.id).code).body()
            val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
            val modes = VehicleModes(env.db, env.clock, circles, env.rules)
            val jam = JammingScanner(env.db, env.clock, env.rules, modes, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
            val start = env.clock.now
            var n = 0
            for (step in s.steps) {
                n++
                env.clock.now = start.plusSeconds(step.t)
                when (step.action) {
                    "arm" -> client.postJson("/v1/assets/${car.id}/mode", "test.mom", ModeRequest(VehicleMode.ARMED))
                    "disarm" -> client.postJson("/v1/assets/${car.id}/mode", "test.mom", ModeRequest(VehicleMode.DISARMED))
                    "authorize" -> client.postJson("/v1/assets/${car.id}/authorizations", "test.mom", AuthorizationRequest(hours = step.hours))
                    "fix" -> client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(
                        IngestSignal("f$n", env.clock.millis(), north(step.north), step.accuracy, speedKmh = step.speed, ignition = step.ignition))))
                    "heartbeat" -> client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("h$n", ignition = step.ignition))))
                    "alarm" -> client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(
                        IngestSignal("a$n", ignition = step.ignition, alarms = setOf(AlarmKind.entries.single { it.name.equals(step.alarm, ignoreCase = true) })))))
                    "jam_scan" -> jam.scanOnce()
                    else -> error("unknown action ${step.action}")
                }
            }
            val open = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
            val rules = open.map { it.rule.name }.sorted()
            if (rules != s.expect.rules.sorted()) error("open rules $rules, expected ${s.expect.rules.sorted()}")
            s.expect.tiers.forEach { (rule, tier) ->
                val got = open.single { it.rule.name == rule }.tier.name
                if (got != tier) error("$rule tier $got, expected $tier")
            }
            s.expect.openedByT.forEach { (rule, by) ->
                val opened = (open.single { it.rule.name == rule }.openedAtMs - start.toEpochMilli()) / 1000
                if (opened > by) error("$rule opened at t=$opened, budget t≤$by")
            }
        }
    }
}
