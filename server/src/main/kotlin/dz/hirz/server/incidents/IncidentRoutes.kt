package dz.hirz.server.incidents

import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.member
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.ReceiptRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

fun Route.incidentRoutes(service: IncidentService) {
    get("/v1/circles/{id}/incidents") {
        val openOnly = call.request.queryParameters["state"] == "open"
        call.respond(service.list(uuidOrNotFound(call.parameters["id"]), call.member().memberId, openOnly))
    }
    get("/v1/incidents/{id}") {
        call.respond(service.get(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
    post("/v1/incidents/{id}/receipts") {
        service.receipt(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<ReceiptRequest>().state)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/incidents/{id}/claim") {
        val id = uuidOrNotFound(call.parameters["id"])
        service.claim(id, call.member().memberId)
        call.respond(service.get(id, call.member().memberId))
    }
    post("/v1/incidents/{id}/contest") {
        val id = uuidOrNotFound(call.parameters["id"])
        service.contest(id, call.member().memberId)
        call.respond(service.get(id, call.member().memberId))
    }
    post("/v1/incidents/{id}/close") {
        val id = uuidOrNotFound(call.parameters["id"])
        service.close(id, call.member().memberId, call.receive<CloseRequest>().reason)
        call.respond(service.get(id, call.member().memberId))
    }
    post("/v1/incidents/{id}/my-location") {
        service.recordMyLocation(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<dz.hirz.shared.api.MyLocationRequest>())
        call.respond(HttpStatusCode.NoContent)
    }
    put("/v1/me/push-token") {
        val body = call.receive<PushTokenDto>()
        service.setMemberPushToken(call.member().memberId, body.token, body.platform)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/v1/assets/{id}/checkin") {
        call.respond(service.checkIn(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
}
