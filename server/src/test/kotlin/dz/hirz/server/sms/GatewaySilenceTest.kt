package dz.hirz.server.sms

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.GatewayCodeDto
import dz.hirz.shared.api.GatewayPairRequest
import dz.hirz.shared.api.GatewayPairResponse
import dz.hirz.shared.api.PushTokenDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class GatewaySilenceTest {
    @Test
    fun gatewaySilenceRaisesP2() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.put("/v1/me/push-token") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-mom")) }
            val code: GatewayCodeDto = client.post("/v1/circles/${circle.id}/gateways") { bearerAuth("test.mom") }.body()
            val gw: GatewayPairResponse = client.postJson("/v1/gateways/pair", null, GatewayPairRequest(code.code)).body()
            assertEquals(HttpStatusCode.NoContent, client.post("/v1/gateways/me/heartbeat") { bearerAuth(gw.token) }.status)
            val scanner = GatewaySilenceScanner(env.db, env.clock, env.notifier)
            fun silentNotices() = env.notifier.sent.count { it.first.token == "tok-mom" && it.second.kind == "gateway_silent" && it.second.tier == "P2" }

            env.clock.advance(14.minutes); scanner.scanOnce()
            assertEquals(0, silentNotices(), "three missed 5-minute heartbeats are tolerated")
            env.clock.advance(2.minutes); scanner.scanOnce()
            assertEquals(1, silentNotices())
            scanner.scanOnce()
            assertEquals(1, silentNotices(), "one notice per silence")

            client.post("/v1/gateways/me/heartbeat") { bearerAuth(gw.token) }
            env.clock.advance(16.minutes); scanner.scanOnce()
            assertEquals(2, silentNotices(), "a new silence after the gateway came back is a new notice")
        }
    }
}
