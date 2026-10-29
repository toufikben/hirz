package dz.hirz.server.circles

import dz.hirz.server.escalation.EscalationEngine
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CodeWordDto
import dz.hirz.shared.api.ContactRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val WORD = "zaytouna-77"

private suspend fun io.ktor.client.HttpClient.setWord(token: String, circleId: String, word: String) = put("/v1/circles/$circleId/code-word") {
    bearerAuth(token); contentType(ContentType.Application.Json); setBody(CodeWordDto(word))
}

class CodeWordTest {
    @Test
    fun codeWordEncryptedAtRest() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            assertEquals(HttpStatusCode.NoContent, client.setWord("test.mom", circle.id, WORD).status)
            val raw = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT code_word_enc FROM circles WHERE id = '${circle.id}'").use { it.next(); it.getBytes(1) }
            }
            assertTrue(raw.isNotEmpty())
            assertFalse(String(raw, Charsets.ISO_8859_1).contains(WORD))
            assertEquals(WORD, client.get("/v1/circles/${circle.id}/code-word") { bearerAuth("test.mom") }.body<CodeWordDto>().word)
        }
    }

    @Test
    fun codeWordOnlyForMembers() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.aunt", Role.VIEWER)
            client.setWord("test.mom", circle.id, WORD)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/circles/${circle.id}/code-word") { bearerAuth("test.stranger") }.status)
            assertEquals(HttpStatusCode.NotFound, client.setWord("test.aunt", circle.id, "other-word").status)
            assertEquals(HttpStatusCode.BadRequest, client.setWord("test.mom", circle.id, "ab").status)
            assertEquals(WORD, client.get("/v1/circles/${circle.id}/code-word") { bearerAuth("test.aunt") }.body<CodeWordDto>().word)
            val audited = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM audit_log WHERE action = 'code_word_viewed'").use { it.next(); it.getInt(1) }
            }
            assertEquals(1, audited)
        }
    }

    @Test
    fun codeWordNeverInPushOrSmsOrExport() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.setWord("test.mom", circle.id, WORD)
            client.put("/v1/me/push-token") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-mom")) }
            client.postJson("/v1/circles/${circle.id}/contacts", "test.mom", ContactRequest("Jaddah", "0550123456"))
            val asset = client.newAsset("test.mom", circle.id)
            val paired: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code).body()
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val engine = EscalationEngine(env.db, env.clock, env.notifier, env.rules, CircleService(env.db, env.clock, CircleEvents.NONE))
            env.clock.advance(20.seconds); engine.tick()
            env.clock.advance(61.seconds); engine.tick()
            val sms = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT body FROM outbound_sms").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            assertTrue(sms.isNotEmpty(), "the contact stage queued an SMS")
            assertTrue(sms.none { WORD in it })
            assertTrue(env.notifier.sent.isNotEmpty())
            assertTrue(env.notifier.sent.none { WORD in it.second.toString() })
            val incidents = client.get("/v1/circles/${circle.id}/incidents") { bearerAuth("test.mom") }
            assertFalse(WORD in incidents.bodyAsText())
            assertEquals(1, incidents.body<List<IncidentDto>>().size)
        }
    }
}
