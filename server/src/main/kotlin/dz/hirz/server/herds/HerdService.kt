package dz.hirz.server.herds

import dz.hirz.server.apiJson
import dz.hirz.server.assets.assetTypeOf
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.conflict
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.CreateHerdRequest
import dz.hirz.shared.api.HerdDto
import dz.hirz.shared.api.HerdMemberRequest
import dz.hirz.shared.api.LeaderRequest
import dz.hirz.shared.api.Role
import dz.hirz.shared.asset.AssetType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.builtins.ListSerializer
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

private val EDITORS = setOf(Role.OWNER, Role.GUARDIAN)
private val windowsSer = ListSerializer(ActiveWindow.serializer())

/** Herds (design §1 groups, P6): livestock of one circle under one leader. */
class HerdService(private val db: DataSource, private val circles: CircleService) {
    companion object {
        /** The herd [assetId] belongs to, if any. */
        fun herdOf(c: Connection, assetId: UUID): UUID? =
            c.prepareStatement("SELECT group_id FROM asset_group_members WHERE asset_id = ? AND kind = 'herd'")
                .apply { setObject(1, assetId) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }

        fun load(c: Connection, herdId: UUID): HerdDto? {
            val head = c.prepareStatement(
                "SELECT circle_id, name, leader_asset, market_window::text, eid_date FROM asset_groups WHERE id = ? AND kind = 'herd'",
            ).apply { setObject(1, herdId) }.executeQuery().use {
                if (!it.next()) return null
                HerdDto(herdId.toString(), it.getString(1), it.getString(2), leaderId = it.getString(3),
                    marketWindow = apiJson.decodeFromString(windowsSer, it.getString(4)), eidDate = it.getDate(5)?.toString())
            }
            val members = c.prepareStatement("SELECT asset_id FROM asset_group_members WHERE group_id = ? ORDER BY asset_id")
                .apply { setObject(1, herdId) }.executeQuery().toList { it.getString(1) }
            return head.copy(members = members)
        }
    }

    private fun herd(c: Connection, herdId: UUID, memberId: UUID, roles: Set<Role> = Role.entries.toSet()): HerdDto {
        val h = load(c, herdId) ?: notFound()
        circles.requireRole(c, UUID.fromString(h.circleId), memberId, roles)
        return h
    }

    fun create(circleId: UUID, memberId: UUID, req: CreateHerdRequest): HerdDto = db.tx { c ->
        circles.requireRole(c, circleId, memberId, EDITORS)
        if (req.name.isBlank() || req.name.length > 80) unprocessable("bad_name")
        val id = c.prepareStatement("INSERT INTO asset_groups (circle_id, name, kind) VALUES (?, ?, 'herd') RETURNING id")
            .apply { setObject(1, circleId); setString(2, req.name.trim()) }.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        audit(c, circleId, memberId, "herd_created", """{"herd":"$id"}""")
        load(c, id)!!
    }

    fun list(circleId: UUID, memberId: UUID): List<HerdDto> = db.tx { c ->
        circles.requireRole(c, circleId, memberId)
        c.prepareStatement("SELECT id FROM asset_groups WHERE circle_id = ? AND kind = 'herd' ORDER BY name")
            .apply { setObject(1, circleId) }.executeQuery().toList { it.getObject(1, UUID::class.java) }.mapNotNull { load(c, it) }
    }

    fun get(herdId: UUID, memberId: UUID): HerdDto = db.tx { c -> herd(c, herdId, memberId) }

    fun addMember(herdId: UUID, memberId: UUID, assetId: UUID): HerdDto = db.tx { c ->
        val h = herd(c, herdId, memberId, EDITORS)
        val (circle, type) = c.prepareStatement("SELECT circle_id, type FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else notFound() }
        if (circle != h.circleId) notFound()
        if (assetTypeOf(type) != AssetType.LIVESTOCK) unprocessable("not_livestock")
        val current = herdOf(c, assetId)
        if (current == herdId) return@tx h
        if (current != null) conflict("already_in_a_herd")
        c.prepareStatement("INSERT INTO asset_group_members (group_id, asset_id, kind) VALUES (?, ?, 'herd')")
            .apply { setObject(1, herdId); setObject(2, assetId) }.executeUpdate()
        audit(c, UUID.fromString(h.circleId), memberId, "herd_member_added", """{"herd":"$herdId","asset":"$assetId"}""")
        load(c, herdId)!!
    }

    fun removeMember(herdId: UUID, memberId: UUID, assetId: UUID): HerdDto = db.tx { c ->
        val h = herd(c, herdId, memberId, EDITORS)
        c.prepareStatement("DELETE FROM asset_group_members WHERE group_id = ? AND asset_id = ?").apply { setObject(1, herdId); setObject(2, assetId) }.executeUpdate()
        c.prepareStatement("UPDATE asset_groups SET leader_asset = NULL WHERE id = ? AND leader_asset = ?").apply { setObject(1, herdId); setObject(2, assetId) }.executeUpdate()
        audit(c, UUID.fromString(h.circleId), memberId, "herd_member_removed", """{"herd":"$herdId","asset":"$assetId"}""")
        load(c, herdId)!!
    }

    fun setMarket(herdId: UUID, memberId: UUID, window: List<ActiveWindow>): HerdDto = db.tx { c ->
        val h = herd(c, herdId, memberId, EDITORS)
        c.prepareStatement("UPDATE asset_groups SET market_window = ?::jsonb WHERE id = ?")
            .apply { setString(1, apiJson.encodeToString(windowsSer, window)); setObject(2, herdId) }.executeUpdate()
        audit(c, UUID.fromString(h.circleId), memberId, "herd_market_set", """{"herd":"$herdId","windows":${window.size}}""")
        load(c, herdId)!!
    }

    fun setEid(herdId: UUID, memberId: UUID, date: String?): HerdDto = db.tx { c ->
        val h = herd(c, herdId, memberId, EDITORS)
        val day = date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() ?: unprocessable("bad_date") }
        c.prepareStatement("UPDATE asset_groups SET eid_date = ? WHERE id = ?").apply { setObject(1, day); setObject(2, herdId) }.executeUpdate()
        audit(c, UUID.fromString(h.circleId), memberId, "herd_eid_set", """{"herd":"$herdId"}""")
        load(c, herdId)!!
    }

    fun setLeader(herdId: UUID, memberId: UUID, assetId: UUID): HerdDto = db.tx { c ->
        val h = herd(c, herdId, memberId, EDITORS)
        if (assetId.toString() !in h.members) unprocessable("leader_not_in_herd")
        c.prepareStatement("UPDATE asset_groups SET leader_asset = ? WHERE id = ?").apply { setObject(1, assetId); setObject(2, herdId) }.executeUpdate()
        audit(c, UUID.fromString(h.circleId), memberId, "herd_leader_set", """{"herd":"$herdId","asset":"$assetId"}""")
        load(c, herdId)!!
    }
}

fun Route.herdRoutes(service: HerdService) {
    post("/v1/circles/{id}/herds") {
        call.respond(HttpStatusCode.Created, service.create(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<CreateHerdRequest>()))
    }
    get("/v1/circles/{id}/herds") { call.respond(service.list(uuidOrNotFound(call.parameters["id"]), call.member().memberId)) }
    get("/v1/herds/{id}") { call.respond(service.get(uuidOrNotFound(call.parameters["id"]), call.member().memberId)) }
    post("/v1/herds/{id}/members") {
        call.respond(service.addMember(uuidOrNotFound(call.parameters["id"]), call.member().memberId, uuidOrNotFound(call.receive<HerdMemberRequest>().assetId)))
    }
    delete("/v1/herds/{id}/members/{assetId}") {
        call.respond(service.removeMember(uuidOrNotFound(call.parameters["id"]), call.member().memberId, uuidOrNotFound(call.parameters["assetId"])))
    }
    put("/v1/herds/{id}/market") {
        call.respond(service.setMarket(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<dz.hirz.shared.api.MarketRequest>().window))
    }
    put("/v1/herds/{id}/eid") {
        call.respond(service.setEid(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<dz.hirz.shared.api.EidRequest>().date))
    }
    put("/v1/herds/{id}/leader") {
        call.respond(service.setLeader(uuidOrNotFound(call.parameters["id"]), call.member().memberId, uuidOrNotFound(call.receive<LeaderRequest>().assetId)))
    }
}
