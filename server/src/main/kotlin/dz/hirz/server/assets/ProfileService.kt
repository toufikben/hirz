package dz.hirz.server.assets

import dz.hirz.server.ApiException
import dz.hirz.server.apiJson
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.tx
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.rules.Profile
import dz.hirz.server.rules.Profiles
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.ProfileDto
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RuleOverride
import dz.hirz.shared.api.RuleSettingDto
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.Condition
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.builtins.ListSerializer
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

private val overridesSer = ListSerializer(RuleOverride.serializer())
private val windowsSer = ListSerializer(ActiveWindow.serializer())
private val exceptionsSer = ListSerializer(dz.hirz.shared.api.NightException.serializer())

/** The stored inputs of an asset's profile. */
data class ProfileRow(
    val circleId: UUID, val type: dz.hirz.shared.asset.AssetType, val condition: Condition, val overrides: List<RuleOverride>,
    val nightWindow: List<ActiveWindow>?, val birthDate: LocalDate?, val conditionOnSheet: Boolean,
    val nightExceptions: List<dz.hirz.shared.api.NightException> = emptyList(),
    val curfewMinute: Int? = null,
    val nightBySun: Boolean? = null,
    val ramadanNightOff: Boolean = false,
) {
    fun profile(
        overrides: List<RuleOverride> = this.overrides, condition: Condition = this.condition, night: List<ActiveWindow>? = nightWindow,
        exceptions: List<dz.hirz.shared.api.NightException> = nightExceptions,
    ): Profile = Profiles.of(type, condition, overrides, night, exceptions, nightBySun)
}

/** Reads and changes behavior profiles (design §4.4). Owners decide the condition, birth date and anything lowered. */
class ProfileService(
    private val db: DataSource, private val circles: CircleService,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(), private val cfg: dz.hirz.shared.rules.RuleConfig = dz.hirz.shared.rules.RuleConfig.DEFAULT,
) {
    companion object {
        fun row(c: Connection, assetId: UUID): ProfileRow? =
            c.prepareStatement(
                "SELECT circle_id, type, condition, profile_overrides::text, night_window::text, birth_date, condition_on_sheet, night_exceptions::text, curfew_minute, night_by_sun, ramadan_night_off FROM assets WHERE id = ?",
            ).apply { setObject(1, assetId) }.executeQuery().use {
                if (!it.next()) null
                else ProfileRow(
                    it.getObject(1, UUID::class.java), assetTypeOf(it.getString(2)), Condition.valueOf(it.getString(3).uppercase()),
                    apiJson.decodeFromString(overridesSer, it.getString(4)),
                    it.getString(5)?.let { w -> apiJson.decodeFromString(windowsSer, w) },
                    it.getDate(6)?.toLocalDate(), it.getBoolean(7), apiJson.decodeFromString(exceptionsSer, it.getString(8)),
                    it.getInt(9).takeUnless { _ -> it.wasNull() },
                    it.getObject(10) as Boolean?, it.getBoolean(11),
                )
            }

        /** The asset's profile for the rules engine; the type's defaults when the asset is gone. */
        fun load(c: Connection, assetId: UUID): Profile? = row(c, assetId)?.profile()

        /** [load] with the calendar applied at [at]: livestock before Eid al-Adha, with its herd's announced date if set. */
        fun loadAt(c: Connection, assetId: UUID, at: java.time.Instant): Profile? {
            val row = row(c, assetId) ?: return null
            if (row.type != dz.hirz.shared.asset.AssetType.LIVESTOCK) return Profiles.seasonal(row.profile(), row.type, at, null, row.ramadanNightOff)
            val eid = dz.hirz.server.herds.HerdService.herdOf(c, assetId)?.let { dz.hirz.server.herds.HerdService.load(c, it) }
                ?.eidDate?.let { LocalDate.parse(it) }
            return Profiles.seasonal(row.profile(), row.type, at, eid)
        }
    }

    fun get(assetId: UUID, memberId: UUID): ProfileDto = db.tx { c ->
        val row = row(c, assetId) ?: notFound()
        // Not contacts or temporary members (a babysitter): the profile names a condition.
        circles.requireRole(c, row.circleId, memberId, setOf(Role.OWNER, Role.GUARDIAN, Role.VIEWER))
        dto(row, row.profile(), assetId, c)
    }

    fun update(assetId: UUID, memberId: UUID, req: ProfileUpdate): ProfileDto = db.tx { c ->
        c.prepareStatement("SELECT 1 FROM assets WHERE id = ? FOR UPDATE").apply { setObject(1, assetId) }.executeQuery().close()
        val row = row(c, assetId) ?: notFound()
        val role = circles.requireRole(c, row.circleId, memberId, setOf(Role.OWNER, Role.GUARDIAN))
        val owner = role == Role.OWNER
        // Windows, exceptions and the curfew can silence NIGHT_EXIT or CURFEW as surely as a switch: the owner's call.
        val ownerOnly = req.condition != null || req.birthDate != null || req.conditionOnSheet != null ||
            req.nightWindow != null || req.nightExceptions != null || req.curfewMinute != null || req.nightBySun != null || req.ramadanNightOff != null
        if (!owner && ownerOnly) throw ApiException(HttpStatusCode.Forbidden, "owner_only")
        val birth = req.birthDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: unprocessable("bad_birth_date") }
        val condition = req.condition ?: row.condition
        val overrides = req.overrides ?: row.overrides
        val before = row.profile()
        // -1 clears the curfew; a curfew between 06:00 and noon would be due all day.
        if (req.curfewMinute != null && req.curfewMinute != -1 && (req.curfewMinute !in 0..1439 || req.curfewMinute in 360 until 720)) unprocessable("bad_curfew")
        val exceptions = req.nightExceptions ?: row.nightExceptions
        exceptions.forEach { e ->
            // An exception must name a place of this circle.
            val ok = runCatching { UUID.fromString(e.placeId) }.getOrNull()?.let { pid ->
                c.prepareStatement("SELECT 1 FROM places WHERE id = ? AND circle_id = ?").apply { setObject(1, pid); setObject(2, row.circleId) }
                    .executeQuery().use { it.next() }
            } ?: false
            if (!ok) unprocessable("bad_exception_place")
        }
        val after = row.profile(overrides, condition, req.nightWindow ?: row.nightWindow, exceptions)
        when (val err = Profiles.validate(before, after, owner)) {
            null -> {}
            "owner_only" -> throw ApiException(HttpStatusCode.Forbidden, err)
            else -> unprocessable(err)
        }
        c.prepareStatement(
            """UPDATE assets SET condition = ?, profile_overrides = ?::jsonb, night_window = COALESCE(?::jsonb, night_window),
                 birth_date = COALESCE(?, birth_date), condition_on_sheet = COALESCE(?, condition_on_sheet), night_exceptions = ?::jsonb,
                 curfew_minute = CASE WHEN ?::int = -1 THEN NULL ELSE COALESCE(?::int, curfew_minute) END,
                 night_by_sun = COALESCE(?, night_by_sun), ramadan_night_off = COALESCE(?, ramadan_night_off),
                 verify_first_off = COALESCE(?, verify_first_off) WHERE id = ?""",
        ).apply {
            setString(1, condition.name.lowercase()); setString(2, apiJson.encodeToString(overridesSer, overrides))
            setString(3, req.nightWindow?.let { apiJson.encodeToString(windowsSer, it) })
            setObject(4, birth); setObject(5, req.conditionOnSheet); setString(6, apiJson.encodeToString(exceptionsSer, exceptions))
            setObject(7, req.curfewMinute); setObject(8, req.curfewMinute)
            setObject(9, req.nightBySun); setObject(10, req.ramadanNightOff); setObject(11, req.verifyFirstOff); setObject(12, assetId)
        }.executeUpdate()
        // The audit records what changed, never the condition itself.
        audit(c, row.circleId, memberId, "profile_changed",
            """{"asset":"$assetId","condition_changed":${req.condition != null && req.condition != row.condition},"overrides":${overrides.size}}""")
        dto(row(c, assetId)!!, after, assetId, c)
    }

    private fun dto(row: ProfileRow, p: Profile, assetId: UUID, c: Connection) = ProfileDto(
        condition = row.condition,
        rules = p.rules.entries.sortedBy { it.key.ordinal }.map { (rule, s) ->
            RuleSettingDto(rule, s.enabled, s.tier, s.defaultTier, locked = s.defaultTier == Tier.P0)
        },
        nightWindow = p.nightWindow, birthDate = row.birthDate?.toString(), conditionOnSheet = row.conditionOnSheet,
        nightExceptions = row.nightExceptions, curfewMinute = row.curfewMinute,
        nightBySun = p.nightBySun, ramadanNightOff = row.ramadanNightOff,
        verifyFirst = dz.hirz.server.incidents.VerifyFirst.rules(c, assetId, clock.instant(), cfg).mapNotNull { r -> dz.hirz.shared.api.RuleId.entries.firstOrNull { it.name == r } }
            .filter { p.tierOf(it) == dz.hirz.shared.api.Tier.P1 }.sortedBy { it.ordinal },
        verifyFirstOff = c.prepareStatement("SELECT verify_first_off FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { it.next() && it.getBoolean(1) },
    )
}

fun Route.profileRoutes(service: ProfileService) {
    get("/v1/assets/{id}/profile") { call.respond(service.get(uuidOrNotFound(call.parameters["id"]), call.member().memberId)) }
    put("/v1/assets/{id}/profile") {
        call.respond(service.update(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<ProfileUpdate>()))
    }
}
