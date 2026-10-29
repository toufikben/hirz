package dz.hirz.server.traccar

import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.ingest.IngestService
import dz.hirz.server.member
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.TrackerClaimRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** Member routes: the model catalogue, and claiming, following and releasing trackers. */
fun Route.trackerRoutes(trackers: TrackerService) {
    get("/v1/device-models") { call.respond(dz.hirz.shared.devices.DeviceCatalog.models) }
    post("/v1/assets/{id}/trackers") {
        call.respond(trackers.claim(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<TrackerClaimRequest>()))
    }
    get("/v1/trackers/claims/{id}") {
        call.respond(trackers.claimStatus(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
    delete("/v1/assets/{id}/trackers/{sourceId}") {
        trackers.release(uuidOrNotFound(call.parameters["id"]), uuidOrNotFound(call.parameters["sourceId"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Traccar's forwarders authenticate with a shared secret; an unset secret refuses everything. */
private fun RoutingCall.fromTraccar(secret: String?): Boolean {
    if (secret.isNullOrEmpty()) return false
    val given = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim() ?: return false
    return MessageDigest.isEqual(given.toByteArray(), secret.toByteArray())
}

/**
 * Traccar → Core (design §2 rule 2): every position and every event. An unknown device answers 200 so Traccar does
 * not retry it forever; nothing is stored for it.
 */
fun Route.traccarForwardRoutes(secret: String?, trackers: TrackerService, ingest: IngestService) {
    post("/v1/traccar/positions") {
        if (!call.fromTraccar(secret)) return@post call.respond(HttpStatusCode.Unauthorized)
        val f = TraccarJson.positionForward(call.receiveText())
        trackers.sourceFor(f.device.uniqueId, f.position.serverTime)?.let { source ->
            ingest.ingest(source, IngestBatch(listOf(TraccarMapper.toSignal(f.position))))
        }
        call.respondText("{}", ContentType.Application.Json)
    }
    post("/v1/traccar/events") {
        if (!call.fromTraccar(secret)) return@post call.respond(HttpStatusCode.Unauthorized)
        val f = TraccarJson.eventForward(call.receiveText())
        val alarm = (f.event.attributes["alarm"] as? JsonPrimitive)?.content
        val position = f.position
        // Only alarms matter here; they share the position's client id, so a forward of both stores one row.
        if (f.event.type == "alarm" && alarm != null && position != null) {
            trackers.sourceFor(f.device.uniqueId, position.serverTime)?.let { source ->
                ingest.ingest(source, IngestBatch(listOf(TraccarMapper.toSignal(position, alarm))))
            }
        }
        call.respondText("{}", ContentType.Application.Json)
    }
}
