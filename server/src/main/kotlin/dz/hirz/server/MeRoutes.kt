package dz.hirz.server

import dz.hirz.server.auth.MemberPrincipal
import dz.hirz.server.auth.SourcePrincipal
import dz.hirz.shared.api.MeDto
import dz.hirz.shared.api.SourceStatusDto
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import java.sql.Timestamp

fun RoutingCall.member(): MemberPrincipal = principal<MemberPrincipal>() ?: error("member auth missing")
fun RoutingCall.source(): SourcePrincipal = principal<SourcePrincipal>() ?: error("source auth missing")

fun Route.meRoutes(deps: Deps) {
    get("/v1/me") {
        val me = call.member()
        val dto = deps.db.connection.use { c ->
            c.prepareStatement("SELECT display_name, lang FROM members WHERE id = ?").apply { setObject(1, me.memberId) }
                .executeQuery().use { it.next(); MeDto(me.memberId.toString(), it.getString(1), it.getString(2)) }
        }
        call.respond(dto)
    }
}

fun Route.sourceSelfRoutes(deps: Deps) {
    get("/v1/sources/me") {
        val s = call.source()
        val dto = deps.db.connection.use { c ->
            c.prepareStatement(
                """SELECT a.name, ci.name, s.last_heartbeat_at, s.last_fix_at, a.type, s.paused_reason
                   FROM sources s JOIN assets a ON a.id = s.asset_id JOIN circles ci ON ci.id = a.circle_id
                   WHERE s.id = ?""",
            ).apply { setObject(1, s.sourceId) }.executeQuery().use { rs ->
                rs.next()
                SourceStatusDto(
                    sourceId = s.sourceId.toString(),
                    assetId = s.assetId.toString(),
                    assetName = rs.getString(1),
                    circleName = rs.getString(2),
                    lastHeartbeatAtMs = rs.getTimestamp(3)?.time,
                    lastFixAtMs = rs.getTimestamp(4)?.time,
                    assetType = dz.hirz.server.assets.assetTypeOf(rs.getString(5)),
                    pausedReason = rs.getString(6),
                )
            }
        }
        call.respond(dto)
    }
}

fun java.time.Instant.ts(): Timestamp = Timestamp.from(this)
