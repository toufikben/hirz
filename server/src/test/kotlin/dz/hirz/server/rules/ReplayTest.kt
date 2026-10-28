package dz.hirz.server.rules

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.signal.AlarmKind
import dz.hirz.shared.signal.FixSource
import io.ktor.client.call.body
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.math.cos
import kotlin.test.assertEquals

/**
 * Replays recorded scenarios (server/src/test/resources/scenarios/*.json) through the real HTTP ingest,
 * moving the clock signal by signal, then compares the findings the engine stored with `expect`.
 */
class ReplayTest {
    @Serializable
    data class ScenarioSignal(
        val t: Long,                     // seconds from scenario start (server time)
        val n: Double = 0.0,             // metres north of the first place's centre
        val e: Double = 0.0,             // metres east
        val acc: Double = 10.0,
        val fixT: Long? = null,          // fix time in seconds from start; default = t
        val alarms: Set<AlarmKind> = emptySet(),
        val noFix: Boolean = false,
    )

    @Serializable
    data class Expected(val rule: String, val tier: String, val atSignal: Int)

    @Serializable
    data class Scenario(
        val name: String,
        val assetType: AssetType = AssetType.CHILD,
        val places: List<CreatePlaceRequest>,
        val signals: List<ScenarioSignal>,
        val expect: List<Expected>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    @TestFactory
    fun scenarios(): List<DynamicTest> {
        val dir = File(javaClass.getResource("/scenarios")!!.toURI())
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        check(files.size >= 12) { "expected at least 12 scenarios, found ${files.size}" }
        return files.map { file ->
            DynamicTest.dynamicTest(file.nameWithoutExtension) {
                run(json.decodeFromString(Scenario.serializer(), file.readText()))
            }
        }
    }

    private fun center(s: Scenario): LatLon = when (val shape = s.places.first().shape) {
        is ShapeDto.Circle -> shape.center
        is ShapeDto.Polygon -> shape.points.first()
    }

    private fun run(s: Scenario) {
        val env = TestEnv()
        val start = env.clock.now
        withApp(env) { client, _ ->
            val circle = client.newCircle()
            val asset = client.newAsset("test.owner", circle.id, s.assetType)
            s.places.forEach { client.postJson("/v1/circles/${circle.id}/places", "test.owner", it) }
            val adult = s.assetType in setOf(AssetType.ELDER, AssetType.LONE_WORKER)
            val paired: PairResponse = client.pair(client.pairingCode("test.owner", asset.id).code, consent = adult).body()
            val origin = center(s)
            val signalTimes = mutableListOf<Long>()
            s.signals.forEachIndexed { i, sig ->
                env.clock.now = start.plusSeconds(sig.t)
                signalTimes += env.clock.now.toEpochMilli()
                val fixMs = start.plusSeconds(sig.fixT ?: sig.t).toEpochMilli()
                val lat = origin.lat + sig.n / 111_320.0
                val lon = origin.lon + sig.e / (111_320.0 * cos(Math.toRadians(origin.lat)))
                val signal = if (sig.noFix) IngestSignal(clientId = "s$i", alarms = sig.alarms)
                else IngestSignal(
                    clientId = "s$i", fixTimeMs = fixMs, position = LatLon(lat, lon), accuracyM = sig.acc,
                    fixSource = FixSource.GNSS, alarms = sig.alarms,
                )
                client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(signal)))
            }
            val actual = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT rule, tier, at FROM findings ORDER BY id").use { rs ->
                    buildList {
                        while (rs.next()) add(Expected(rs.getString(1), rs.getString(2), signalTimes.indexOf(rs.getTimestamp(3).time)))
                    }
                }
            }
            assertEquals(s.expect, actual, "scenario ${s.name}")
        }
    }
}
