package dz.hirz.server.sms

import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.shared.api.GatewayPairRequest
import dz.hirz.shared.api.InboundSmsRequest
import dz.hirz.shared.api.SmsResultRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant

const val GATEWAY_AUTH = "gateway"

/** Gateways authenticate with an opaque token whose SHA-256 is stored, like sources. */
fun Application.installGatewayAuth(service: GatewayService) {
    authentication {
        bearer(GATEWAY_AUTH) { authenticate { credential -> service.find(credential.token) } }
    }
}

private fun RoutingCall.gateway(): GatewayPrincipal = principal<GatewayPrincipal>() ?: notFound()

/** Member routes: the owner creates a gateway pairing code. */
fun Route.gatewayMemberRoutes(service: GatewayService) {
    post("/v1/circles/{id}/gateways") {
        call.respond(service.createCode(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
}

/** Unauthenticated: the one-time code is the credential. */
fun Route.gatewayPairRoutes(service: GatewayService) {
    post("/v1/gateways/pair") {
        val req = call.receive<GatewayPairRequest>()
        call.respond(service.pair(req.code, req.pushToken))
    }
}

fun Route.gatewayRoutes(service: GatewayService) {
    authenticate(GATEWAY_AUTH) {
        get("/v1/gateways/me/outbox") {
            call.respond(service.outbox(call.gateway()))
        }
        post("/v1/gateways/me/outbox/{id}/result") {
            val id = call.parameters["id"]?.toLongOrNull() ?: notFound()
            val req = call.receive<SmsResultRequest>()
            service.result(call.gateway(), id, req.status, req.error)
            call.respond(HttpStatusCode.NoContent)
        }
        // Authentication already recorded last_seen_at; the heartbeat only proves the gateway is alive.
        post("/v1/gateways/me/heartbeat") {
            call.gateway()
            call.respond(HttpStatusCode.NoContent)
        }
        post("/v1/gateways/me/inbox") {
            val req = call.receive<InboundSmsRequest>()
            service.inbox(call.gateway(), req.from, req.body, Instant.ofEpochMilli(req.receivedAtMs))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
