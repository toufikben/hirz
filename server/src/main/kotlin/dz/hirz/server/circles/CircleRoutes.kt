package dz.hirz.server.circles

import dz.hirz.server.member
import dz.hirz.shared.api.CreateCircleRequest
import dz.hirz.shared.api.CreateInviteRequest
import dz.hirz.shared.api.UpdateMemberRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

fun Route.circleRoutes(service: CircleService) {
    post("/v1/circles") {
        call.respond(service.create(call.member().memberId, call.receive<CreateCircleRequest>().name))
    }
    get("/v1/circles") {
        call.respond(service.list(call.member().memberId))
    }
    get("/v1/circles/{id}/members") {
        call.respond(service.members(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
    post("/v1/circles/{id}/invites") {
        call.respond(service.invite(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<CreateInviteRequest>()))
    }
    post("/v1/circles/{id}/contacts") {
        call.respond(service.addContact(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<dz.hirz.shared.api.ContactRequest>()))
    }
    post("/v1/invites/{code}/accept") {
        call.respond(service.accept(call.parameters["code"].orEmpty(), call.member().memberId))
    }
    patch("/v1/circles/{id}/members/{memberId}") {
        service.update(
            uuidOrNotFound(call.parameters["id"]), call.member().memberId,
            uuidOrNotFound(call.parameters["memberId"]), call.receive<UpdateMemberRequest>(),
        )
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/v1/circles/{id}/members/{memberId}") {
        service.remove(uuidOrNotFound(call.parameters["id"]), call.member().memberId, uuidOrNotFound(call.parameters["memberId"]))
        call.respond(HttpStatusCode.NoContent)
    }
}
