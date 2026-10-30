package dz.hirz.server.vehicles

import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.member
import dz.hirz.shared.api.AuthorizationRequest
import dz.hirz.shared.api.ModeRequest
import dz.hirz.shared.api.ScheduleRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

fun Route.vehicleRoutes(modes: VehicleModes) {
    get("/v1/assets/{id}/vehicle") {
        call.respond(modes.state(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
    post("/v1/assets/{id}/mode") {
        call.respond(modes.setMode(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<ModeRequest>().mode))
    }
    post("/v1/assets/{id}/authorizations") {
        val req = call.receive<AuthorizationRequest>()
        call.respond(modes.authorize(uuidOrNotFound(call.parameters["id"]), call.member().memberId, req.hours, req.driverName,
            req.memberId?.let { uuidOrNotFound(it) }))
    }
    delete("/v1/assets/{id}/authorizations/{aid}") {
        call.respond(modes.revokeAuthorization(uuidOrNotFound(call.parameters["id"]), uuidOrNotFound(call.parameters["aid"]), call.member().memberId))
    }
    put("/v1/assets/{id}/armed-schedule") {
        call.respond(modes.setSchedule(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<ScheduleRequest>().windows))
    }
}
