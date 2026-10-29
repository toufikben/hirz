package dz.hirz.server.share

import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.conflict
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.live.POSITION_COLUMNS
import dz.hirz.server.live.positionRow
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.shared.api.PositionDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.ShareDataDto
import dz.hirz.shared.api.ShareDto
import dz.hirz.shared.api.ShareRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

private const val TOKEN_BYTES = 32
private const val TRACK_MINUTES = 30L
private val TTL_RANGE = 15..240

private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Revocable, expiring public links to an open incident (design §5.8). The token is shown once; only its hash is
 * stored. A link dies when it expires, is revoked, or the incident closes. Every open is logged.
 */
class ShareService(
    private val db: DataSource,
    private val clock: Clock,
    private val circles: CircleService,
    private val baseUrl: String,
    /** Keys the IP hash, so a stored hash cannot be reversed by trying every address. */
    private val ipKey: ByteArray = ByteArray(32),
) {
    private fun ipHash(ip: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(ipKey, "HmacSHA256")) }
        return mac.doFinal(ip.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private val random = SecureRandom()
    private val page: String = ShareService::class.java.getResource("/share/page.html")!!.readText()

    fun create(incidentId: UUID, memberId: UUID, ttlMinutes: Int): ShareDto {
        require(ttlMinutes in TTL_RANGE) { "ttlMinutes must be ${TTL_RANGE.first}–${TTL_RANGE.last}" }
        val now = clock.instant()
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        val expires = now.plusSeconds(ttlMinutes * 60L)
        db.tx { c ->
            val (circleId, open) = c.prepareStatement("SELECT circle_id, state = 'open' FROM incidents WHERE id = ? FOR UPDATE")
                .apply { setObject(1, incidentId) }.executeQuery()
                .use { if (it.next()) it.getObject(1, UUID::class.java) to it.getBoolean(2) else notFound() }
            circles.requireRole(c, circleId, memberId, setOf(Role.OWNER, Role.GUARDIAN))
            if (!open) conflict("incident_closed")
            // One live link per incident: a new link replaces the old one.
            revokeAll(c, incidentId, now)
            c.prepareStatement("INSERT INTO share_links (incident_id, token_hash, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?)").apply {
                setObject(1, incidentId); setString(2, sha256(token)); setObject(3, memberId); setTimestamp(4, now.ts()); setTimestamp(5, expires.ts())
            }.executeUpdate()
            audit(c, circleId, memberId, "share_created", """{"incident":"$incidentId","ttlMinutes":$ttlMinutes}""")
        }
        return ShareDto("${baseUrl.trimEnd('/')}/s/$token", expires.toEpochMilli())
    }

    fun revoke(incidentId: UUID, memberId: UUID) = db.tx { c ->
        val circleId = c.prepareStatement("SELECT circle_id FROM incidents WHERE id = ?").apply { setObject(1, incidentId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
        circles.requireRole(c, circleId, memberId, setOf(Role.OWNER, Role.GUARDIAN))
        revokeAll(c, incidentId, clock.instant())
        audit(c, circleId, memberId, "share_revoked", """{"incident":"$incidentId"}""")
    }

    /** When the active link for an incident expires, or null; the sheet shows it. */
    fun activeUntil(incidentId: UUID): Instant? = db.connection.use { c ->
        c.prepareStatement("SELECT max(expires_at) FROM share_links WHERE incident_id = ? AND revoked_at IS NULL AND expires_at > ?")
            .apply { setObject(1, incidentId); setTimestamp(2, clock.instant().ts()) }.executeQuery().use { it.next(); it.getTimestamp(1)?.toInstant() }
    }

    private fun revokeAll(c: Connection, incidentId: UUID, now: Instant) {
        c.prepareStatement("UPDATE share_links SET revoked_at = ? WHERE incident_id = ? AND revoked_at IS NULL")
            .apply { setTimestamp(1, now.ts()); setObject(2, incidentId) }.executeUpdate()
    }

    /** Resolves a token to its data, logging the open; null (→ 404) when expired, revoked, closed or unknown. */
    fun open(token: String, ip: String?, userAgent: String?): ShareDataDto? {
        val now = clock.instant()
        val hash = sha256(token)
        return db.tx { c ->
            data class Link(val assetId: UUID, val assetName: String, val expires: Instant)
            val link = c.prepareStatement(
                """SELECT i.asset_id, a.name, l.expires_at FROM share_links l JOIN incidents i ON i.id = l.incident_id JOIN assets a ON a.id = i.asset_id
                   WHERE l.token_hash = ? AND l.revoked_at IS NULL AND l.expires_at > ? AND i.state = 'open'""",
            ).apply { setString(1, hash); setTimestamp(2, now.ts()) }.executeQuery()
                .use { if (it.next()) Link(it.getObject(1, UUID::class.java), it.getString(2), it.getTimestamp(3).toInstant()) else null }
                ?: return@tx null
            c.prepareStatement("INSERT INTO share_views (token_hash, at, ip_hash, user_agent) VALUES (?, ?, ?, ?)").apply {
                setString(1, hash); setTimestamp(2, now.ts()); setString(3, ip?.let(::ipHash)); setString(4, userAgent?.take(200))
            }.executeUpdate()
            val track = c.prepareStatement(
                """SELECT $POSITION_COLUMNS FROM positions p WHERE p.asset_id = ? AND p.quality = 'ok' AND p.geog IS NOT NULL
                   AND p.fix_time >= ? AND p.fix_time <= ? ORDER BY p.fix_time""",
            ).apply { setObject(1, link.assetId); setTimestamp(2, now.minusSeconds(TRACK_MINUTES * 60).ts()); setTimestamp(3, now.ts()) }
                .executeQuery().toList { positionRow(it) }
            val last = track.lastOrNull() ?: c.prepareStatement(
                "SELECT $POSITION_COLUMNS FROM positions p WHERE p.asset_id = ? AND p.quality = 'ok' AND p.geog IS NOT NULL ORDER BY p.fix_time DESC NULLS LAST LIMIT 1",
            ).apply { setObject(1, link.assetId) }.executeQuery().use { if (it.next()) positionRow(it) else null }
            ShareDataDto(link.assetName, last, last?.let { (now.toEpochMilli() - it.fixTimeMs) / 1000 }, track, link.expires.toEpochMilli())
        }
    }

    fun html(d: ShareDataDto): String {
        val last = d.last?.let { p ->
            val osm = "https://www.openstreetmap.org/?mlat=${p.position.lat}&amp;mlon=${p.position.lon}#map=17/${p.position.lat}/${p.position.lon}"
            """<a href="$osm" rel="noopener noreferrer">${p.position.lat}, ${p.position.lon}</a> · ±${p.accuracyM?.toInt() ?: "?"} m · ${ageText(d.lastAgeS)}"""
        } ?: "—"
        val rows = d.track.reversed().joinToString("\n") { p: PositionDto ->
            "<tr><td>${Instant.ofEpochMilli(p.fixTimeMs)}</td><td>${p.position.lat}, ${p.position.lon}</td><td>${p.accuracyM?.toInt() ?: ""}</td></tr>"
        }
        return page.replace("{{NAME}}", escape(d.assetName)).replace("{{LAST}}", last).replace("{{ROWS}}", rows)
            .replace("{{EXPIRES}}", "⌛ ${Instant.ofEpochMilli(d.expiresAtMs)}")
    }

    private fun ageText(s: Long?) = when {
        s == null -> ""
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h"
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}

private fun RoutingCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Robots-Tag", "noindex, nofollow")
    response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'")
    response.header("Referrer-Policy", "no-referrer")
}

/** Member routes: create and revoke. */
fun Route.shareRoutes(service: ShareService) {
    post("/v1/incidents/{id}/share") {
        call.respond(service.create(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<ShareRequest>().ttlMinutes))
    }
    delete("/v1/incidents/{id}/share") {
        service.revoke(uuidOrNotFound(call.parameters["id"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Public routes: no account, no third-party scripts. */
fun Route.publicShareRoutes(service: ShareService) {
    get("/s/{token}") {
        val data = service.open(call.parameters["token"].orEmpty(), call.request.origin.remoteAddress, call.request.headers[HttpHeaders.UserAgent])
        call.noStore()
        if (data == null) call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
        else call.respondText(service.html(data), ContentType.Text.Html.withParameter("charset", "utf-8"))
    }
    get("/s/{token}/data") {
        val data = service.open(call.parameters["token"].orEmpty(), call.request.origin.remoteAddress, call.request.headers[HttpHeaders.UserAgent])
        call.noStore()
        if (data == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found")) else call.respond(data)
    }
}
