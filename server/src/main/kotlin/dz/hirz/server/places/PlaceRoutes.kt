package dz.hirz.server.places

import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.member
import dz.hirz.shared.api.CreatePlaceRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.placeRoutes(service: PlaceService) {
    post("/v1/circles/{id}/places") {
        call.respond(service.create(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<CreatePlaceRequest>()))
    }
    get("/v1/circles/{id}/places") {
        call.respond(service.list(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
    delete("/v1/circles/{id}/places/{placeId}") {
        service.delete(uuidOrNotFound(call.parameters["id"]), uuidOrNotFound(call.parameters["placeId"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
}
