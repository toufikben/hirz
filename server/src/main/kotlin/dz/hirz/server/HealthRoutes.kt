package dz.hirz.server

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class Health(val status: String, val service: String, val version: String)

fun Route.healthRoutes(version: String) {
    get("/health") {
        call.respond(Health(status = "ok", service = "hirz-core", version = version))
    }
}
