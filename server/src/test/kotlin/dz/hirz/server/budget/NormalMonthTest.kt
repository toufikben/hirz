package dz.hirz.server.budget

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.CurfewScanner
import dz.hirz.server.jobs.DigestJob
import dz.hirz.server.jobs.LearningJob
import dz.hirz.server.jobs.RoutineScanner
import dz.hirz.server.jobs.SilenceScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.RoutineRequest
import dz.hirz.shared.api.RoutineTemplate
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.asset.AssetType
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

private val ORIGIN = LatLon(36.7538, 3.0588)
private fun at(north: Double, east: Double) = LatLon(ORIGIN.lat + north / 111_320.0, ORIGIN.lon + east / (111_320.0 * 0.8016))
private val ALGIERS = ZoneId.of("Africa/Algiers")

private const val SCHOOL_N = 1113.0
private val HOME = 0.0 to 0.0
private val SCHOOL = SCHOOL_N to 0.0
private val MIDWAY = 556.0 to 0.0
private val GRANDMA = -1500.0 to 300.0 // never named: she is learned, or simply visited
private val MARKET = 0.0 to 600.0
private val MOSQUE = 300.0 to 0.0      // tarawih in Ramadan, not a named place

private enum class Kind { CHILD, TEEN, ELDER }
private class Member(val kind: Kind, val deadPhoneAtNight: Boolean = false, val drifts: Boolean = false, val visitsGrandma: Boolean = false)

/** One fix of one member at a local time of one day. */
private data class Fix(val at: Instant, val member: Int, val north: Double, val east: Double, val acc: Double = 10.0)

// 2027-02-09 … 2027-03-10: school days, weekends, all of Ramadan (estimated 8 Feb – 9 Mar) and Eid al-Fitr (a holiday).
private val FIRST = LocalDate.of(2027, 2, 9)
private const val DAYS = 30

private val FAMILY = listOf(
    Member(Kind.CHILD, drifts = true, visitsGrandma = true), Member(Kind.CHILD, drifts = true, visitsGrandma = true),
    Member(Kind.CHILD, visitsGrandma = true), Member(Kind.CHILD), Member(Kind.CHILD),
    Member(Kind.CHILD, deadPhoneAtNight = true), Member(Kind.CHILD, drifts = true),
    Member(Kind.TEEN, visitsGrandma = true), Member(Kind.ELDER), Member(Kind.ELDER),
)

private fun local(day: LocalDate, hhmm: String) = day.atTime(LocalTime.parse(hhmm)).atZone(ALGIERS).toInstant()

private fun schoolDay(day: LocalDate) = day.dayOfWeek !in setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY) && day != LocalDate.of(2027, 3, 10)
private fun ramadan(day: LocalDate) = !day.isBefore(LocalDate.of(2027, 2, 8)) && !day.isAfter(LocalDate.of(2027, 3, 9))

/** A normal month for [FAMILY]: what a family does when nothing is wrong. */
private fun month(): List<Fix> = buildList {
    for (d in 0 until DAYS) {
        val day = FIRST.plusDays(d.toLong())
        FAMILY.forEachIndexed { i, m ->
            fun add(hhmm: String, p: Pair<Double, Double>, acc: Double = 10.0) = add(Fix(local(day, hhmm), i, p.first, p.second, acc))
            when (m.kind) {
                Kind.CHILD, Kind.TEEN -> if (schoolDay(day)) {
                    if (!m.deadPhoneAtNight) add("06:30", HOME)
                    add("07:15", MIDWAY); add("07:45", SCHOOL); add("08:12", SCHOOL); add("12:00", SCHOOL)
                    add("16:35", SCHOOL); add("16:50", MIDWAY); add("17:05", HOME)
                    if (!m.deadPhoneAtNight) add("21:00", HOME)
                } else {
                    add("09:00", HOME); add("13:00", HOME)
                    if (m.visitsGrandma && day.dayOfWeek == DayOfWeek.SATURDAY) {
                        listOf("15:00", "15:20", "15:40", "16:00", "16:20", "16:40", "17:00").forEach { add(it, GRANDMA) }
                        add("17:30", HOME)
                    }
                    if (!m.deadPhoneAtNight) add("21:00", HOME)
                }
                Kind.ELDER -> {
                    add("06:00", HOME); add("10:00", MARKET); add("10:20", MARKET); add("11:00", HOME); add("21:00", HOME)
                    if (ramadan(day)) { add("21:40", MOSQUE); add("22:30", MOSQUE); add("23:10", HOME) }
                }
            }
            // GPS drift at home in the small hours: one coarse reading 70 m off.
            if (m.drifts && d % 3 == 0) add("02:00", 70.0 to 0.0, acc = 60.0)
        }
    }
}.sortedWith(compareBy({ it.at }, { it.member }))

class NormalMonthTest {
    /**
     * The false-alarm budget (P7 exit gate): a month of normal life for ten people raises no P1 and no P0. Quiet P2
     * findings are allowed; they go to the evening digest.
     */
    @Test
    fun normalMonthWithinBudget() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            env.clock.now = local(FIRST, "05:00")
            val circle = client.newCircle("test.mom")
            val school: PlaceDto = client.postJson("/v1/circles/${circle.id}/places", "test.mom",
                CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(at(SCHOOL.first, SCHOOL.second), 80.0))).body()
            client.postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Dar", PlaceKind.HOME, ShapeDto.Circle(at(0.0, 0.0), 50.0)))
            val tokens = FAMILY.mapIndexed { i, m ->
                val type = when (m.kind) { Kind.CHILD -> AssetType.CHILD; Kind.TEEN -> AssetType.TEEN; Kind.ELDER -> AssetType.ELDER }
                val a = client.newAsset("test.mom", circle.id, type, "M$i")
                if (m.kind != Kind.ELDER) client.postJson("/v1/assets/${a.id}/routines", "test.mom", RoutineRequest(school.id, template = RoutineTemplate.SCHOOL))
                if (m.kind == Kind.TEEN) client.put("/v1/assets/${a.id}/profile") {
                    bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(curfewMinute = 22 * 60))
                }
                val adult = m.kind == Kind.ELDER
                client.pair(client.pairingCode("test.mom", a.id).code, adult = adult, consent = adult).body<PairResponse>().sourceToken
            }
            val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
            val recorder = FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles))
            val routine = RoutineScanner(env.db, env.clock, env.rules, recorder)
            val silence = SilenceScanner(env.db, env.clock, env.rules, recorder)
            val curfew = CurfewScanner(env.db, env.clock, env.rules, recorder)
            val learning = LearningJob(env.db, env.clock, env.rules)
            val digest = DigestJob(env.db, env.clock, env.notifier)
            var lastDay: LocalDate? = null
            for ((instant, batch) in month().groupBy { it.at }) {
                val day = instant.atZone(ALGIERS).toLocalDate()
                if (day != lastDay) {
                    // Each night: learn, then yesterday's digest has already gone at 20:00.
                    env.clock.now = local(day, "03:00").coerceAtMost(instant); learning.runOnce(); lastDay = day
                }
                env.clock.now = instant
                batch.forEach { f ->
                    client.postJson("/v1/ingest", tokens[f.member], IngestBatch(listOf(
                        IngestSignal("m${f.member}-${instant.toEpochMilli()}", instant.toEpochMilli(), at(f.north, f.east), f.acc))))
                }
                routine.scanOnce(); silence.scanOnce(); curfew.scanOnce()
                if (instant.atZone(ALGIERS).toLocalTime() == LocalTime.of(21, 0)) digest.runOnce()
            }
            val raised = env.db.connection.use { c ->
                c.createStatement().executeQuery(
                    """SELECT a.name, f.rule, f.tier, f.at FROM findings f JOIN assets a ON a.id = f.asset_id ORDER BY f.at""",
                ).use { rs -> buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant().toString())) } }
            }
            println("Normal month: ${raised.size} findings; by rule and tier: " +
                raised.groupingBy { "${it[1]}/${it[2]}" }.eachCount().toSortedMap())
            val loud = raised.filter { it[2] == "P0" || it[2] == "P1" }
            if (loud.isNotEmpty()) fail("a normal month rang ${loud.size} times:\n" + loud.take(30).joinToString("\n") { it.joinToString(" ") })
            val digests = env.db.connection.use { c -> c.createStatement().executeQuery("SELECT count(*) FROM digests").use { it.next(); it.getInt(1) } }
            assertTrue(raised.isEmpty() || digests > 0, "quiet findings reach the evening digest")
        }
    }
}
