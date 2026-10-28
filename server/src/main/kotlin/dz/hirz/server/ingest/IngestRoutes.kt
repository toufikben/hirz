package dz.hirz.server.ingest

import dz.hirz.server.source
import dz.hirz.shared.api.IngestBatch
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.ingestRoutes(service: IngestService) {
    post("/v1/ingest") { call.respond(service.ingest(call.source(), call.receive<IngestBatch>())) }
}
