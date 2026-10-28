package dz.hirz.server.assets

import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.member
import dz.hirz.server.source
import dz.hirz.shared.api.CreateAssetRequest
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.PairRequest
import dz.hirz.shared.api.PushTokenDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

fun Route.assetRoutes(service: AssetService) {
    post("/v1/circles/{id}/assets") {
        call.respond(service.create(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<CreateAssetRequest>()))
    }
    get("/v1/circles/{id}/assets") {
        call.respond(service.list(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
    post("/v1/assets/{id}/pairing") {
        call.respond(service.createPairingCode(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<CreatePairingRequest>()))
    }
    delete("/v1/assets/{id}/sources/{sourceId}") {
        service.revokeSource(uuidOrNotFound(call.parameters["id"]), uuidOrNotFound(call.parameters["sourceId"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** No authentication: the single-use pairing code is the credential. */
fun Route.pairRoutes(service: AssetService) {
    post("/v1/pair") { call.respond(service.pair(call.receive<PairRequest>())) }
}

fun Route.sourcePushRoutes(service: AssetService) {
    put("/v1/sources/me/push-token") {
        service.setSourcePushToken(call.source().sourceId, call.receive<PushTokenDto>().token)
        call.respond(HttpStatusCode.NoContent)
    }
}
