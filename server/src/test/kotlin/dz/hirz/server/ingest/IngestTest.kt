package dz.hirz.server.ingest

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.pairedChild
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestResult
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.signal.FixSource
import dz.hirz.server.auth.Codes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ALGIERS = LatLon(36.7538, 3.0588)
private val ORAN = LatLon(35.6971, -0.6308)

private suspend fun HttpClient.ingest(token: String, vararg signals: IngestSignal): HttpResponse = post("/v1/ingest") {
    bearerAuth(token); contentType(ContentType.Application.Json); setBody(IngestBatch(signals.toList()))
}

class IngestTest {
    private fun fix(id: String, tMs: Long, at: LatLon = ALGIERS) =
        IngestSignal(clientId = id, fixTimeMs = tMs, position = at, accuracyM = 12.0, fixSource = FixSource.GNSS, battery = 80)

    @Test
    fun acceptsBatchAndStoresPositions() = withApp { client, env ->
        val (_, assetId, paired) = client.pairedChild()
        val t = env.clock.millis()
        val result: IngestResult = client.ingest(paired.sourceToken, fix("a", t - 60_000), fix("b", t)).body()
        assertEquals(2, result.accepted)
        assertEquals(0, result.duplicates)
        assertEquals(2, count(env, "SELECT count(*) FROM positions WHERE asset_id = '$assetId' AND geog IS NOT NULL"))
    }

    @Test
    fun duplicateClientIdCountsOnce() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild()
        val t = env.clock.millis()
        client.ingest(paired.sourceToken, fix("a", t))
        val again: IngestResult = client.ingest(paired.sourceToken, fix("a", t)).body()
        assertEquals(0, again.accepted)
        assertEquals(1, again.duplicates)
        assertEquals(1, count(env, "SELECT count(*) FROM positions"))
    }

    @Test
    fun rejectsFutureFix() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild()
        val result: IngestResult = client.ingest(paired.sourceToken, fix("f", env.clock.millis() + 5 * 60_000 + 1)).body()
        assertEquals(listOf("future_fix"), result.rejected.map { it.reason })
        assertEquals(0, count(env, "SELECT count(*) FROM positions"))
    }

    @Test
    fun flagsImpossibleJump() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild()
        val t = env.clock.millis()
        client.ingest(paired.sourceToken, fix("alg", t - 30_000, ALGIERS), fix("oran", t, ORAN))
        assertEquals(1, count(env, "SELECT count(*) FROM positions WHERE client_id = 'oran' AND quality = 'impossible_jump'"))
        assertEquals(1, count(env, "SELECT count(*) FROM positions WHERE client_id = 'alg' AND quality = 'ok'"))
    }

    @Test
    fun ingest_out_of_order_keeps_latest() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild()
        val t0 = env.clock.millis() - 120_000
        client.ingest(paired.sourceToken, fix("late", t0 + 100_000))
        client.ingest(paired.sourceToken, fix("early", t0 + 40_000))
        assertEquals(t0 + 100_000, lastFixMs(env, paired.sourceId))
        assertEquals(2, count(env, "SELECT count(*) FROM positions"))
    }

    @Test
    fun heartbeatWithoutFixUpdatesHeartbeatOnly() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild()
        client.ingest(paired.sourceToken, IngestSignal(clientId = "hb", battery = 50))
        assertEquals(null, lastFixMs(env, paired.sourceId))
        assertEquals(1, count(env, "SELECT count(*) FROM sources WHERE id = '${paired.sourceId}' AND last_heartbeat_at IS NOT NULL"))
    }

    @Test
    fun revokedSourceCannotIngest() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild()
        env.db.connection.use { it.createStatement().execute("UPDATE sources SET revoked_at = now() WHERE id = '${paired.sourceId}'") }
        assertEquals(HttpStatusCode.Unauthorized, client.ingest(paired.sourceToken, fix("x", env.clock.millis())).status)
    }

    @Test
    fun batchSizeIsBounded() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild()
        val t = env.clock.millis()
        val many = (1..101).map { fix("s$it", t - it * 1000L) }.toTypedArray()
        assertEquals(HttpStatusCode.PayloadTooLarge, client.ingest(paired.sourceToken, *many).status)
        assertEquals(HttpStatusCode.BadRequest, client.ingest(paired.sourceToken).status)
    }

    @Test
    fun duressPinBecomesDuressAlarm() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild(req = CreatePairingRequest(cancelPin = "1234", duressPin = "9876"))
        client.ingest(paired.sourceToken, IngestSignal(clientId = "p", pinHash = Codes.pinHash(paired.pinSalt!!, "9876")))
        assertEquals(1, count(env, "SELECT count(*) FROM positions WHERE client_id = 'p' AND 'duress' = ANY(alarms)"))
    }

    @Test
    fun cancelPinBecomesOkReply() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild(req = CreatePairingRequest(cancelPin = "1234", duressPin = "9876"))
        client.ingest(paired.sourceToken, IngestSignal(clientId = "p", pinHash = Codes.pinHash(paired.pinSalt!!, "1234")))
        assertEquals(1, count(env, "SELECT count(*) FROM positions WHERE client_id = 'p' AND checkin_reply = 'ok' AND alarms = '{}'"))
    }

    @Test
    fun pinResponsesAreIndistinguishable() = withApp { client, _ ->
        val (_, _, paired) = client.pairedChild(req = CreatePairingRequest(cancelPin = "1234", duressPin = "9876"))
        val bodies = listOf("1234", "9876", "5555").mapIndexed { i, pin ->
            val r = client.ingest(paired.sourceToken, IngestSignal(clientId = "pin$i", pinHash = Codes.pinHash(paired.pinSalt!!, pin)))
            r.status to r.bodyAsText().replace(Regex("\"highRate\":(true|false)"), "")
        }
        assertEquals(1, bodies.toSet().size, "responses differ: $bodies")
    }

    @Test
    fun highRateWhileIncidentOpen() = withApp { client, env ->
        val (circleId, assetId, paired) = client.pairedChild()
        val before: IngestResult = client.ingest(paired.sourceToken, fix("a", env.clock.millis())).body()
        assertEquals(false, before.highRate)
        env.db.connection.use {
            it.createStatement().execute(
                "INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('$assetId', '$circleId', 'SOS', 'P0', now())",
            )
        }
        val after: IngestResult = client.ingest(paired.sourceToken, fix("b", env.clock.millis())).body()
        assertTrue(after.highRate)
    }

    @Test
    fun listenersSeeSignalsInFixTimeOrder() {
        val seen = CopyOnWriteArrayList<String>()
        val env = TestEnv(ingestListeners = listOf(IngestListener { seen += it.clientId }))
        withApp(env) { client, _ ->
            val (_, _, paired) = client.pairedChild()
            val t = env.clock.millis()
            client.ingest(paired.sourceToken, fix("third", t), fix("first", t - 20_000), fix("second", t - 10_000))
            assertEquals(listOf("first", "second", "third"), seen.toList())
        }
    }

    private fun count(env: TestEnv, sql: String): Int =
        env.db.connection.use { c -> c.createStatement().executeQuery(sql).use { it.next(); it.getInt(1) } }

    private fun lastFixMs(env: TestEnv, sourceId: String): Long? = env.db.connection.use { c ->
        c.prepareStatement("SELECT last_fix_at FROM sources WHERE id = ?").apply { setObject(1, UUID.fromString(sourceId)) }
            .executeQuery().use { it.next(); it.getTimestamp(1)?.time }
    }
}
