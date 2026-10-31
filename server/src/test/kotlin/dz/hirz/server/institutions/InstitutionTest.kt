package dz.hirz.server.institutions

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.CircleDto
import dz.hirz.shared.api.CircleKind
import dz.hirz.shared.api.ConsentDto
import dz.hirz.shared.api.ConsentRequest
import dz.hirz.shared.api.CreateCircleRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LinkCodeDto
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sunday to Thursday, 07:00–17:30 Algiers: the school's hours. */
private val SCHOOL_HOURS = listOf(ActiveWindow(setOf(7, 1, 2, 3, 4), 7 * 60, 17 * 60 + 30))

private class School(val schoolId: String, val familyId: String, val childId: String, val token: String)

/** The Ecole Ibn Khaldoun (owner test.head) and a family (owner test.mom) with one paired child, Sunday 2026-11-08. */
private suspend fun HttpClient.school(env: TestEnv): School {
    env.clock.now = Instant.parse("2026-11-08T07:00:00Z") // 08:00 Algiers, Sunday
    val school: CircleDto = postJson("/v1/circles", "test.head", CreateCircleRequest("Ecole Ibn Khaldoun", CircleKind.INSTITUTION)).body()
    assertEquals(CircleKind.INSTITUTION, school.kind)
    val family = newCircle("test.mom")
    val kid = newAsset("test.mom", family.id, name = "Yanis")
    val p: PairResponse = pair(pairingCode("test.mom", kid.id).code).body()
    return School(school.id, family.id, kid.id, p.sourceToken)
}

private suspend fun HttpClient.share(s: School, windows: List<ActiveWindow> = SCHOOL_HOURS): io.ktor.client.statement.HttpResponse {
    val code: LinkCodeDto = post("/v1/circles/${s.schoolId}/link-code") { bearerAuth("test.head") }.body()
    return postJson("/v1/assets/${s.childId}/consents", "test.mom", ConsentRequest(code.code, windows))
}

private suspend fun HttpClient.shareAndAccept(s: School): ConsentDto {
    val consent: ConsentDto = share(s).body()
    return post("/v1/consents/${consent.id}/accept") { bearerAuth("test.head") }.body()
}

private suspend fun HttpClient.schoolSees(s: School): List<IncidentDto> =
    get("/v1/circles/${s.schoolId}/incidents?state=open") { bearerAuth("test.head") }.body()

private suspend fun HttpClient.sos(s: School) =
    postJson("/v1/ingest", s.token, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))

class InstitutionTest {
    @Test
    fun parentSharesChildWithSchool() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.school(env)
            val consent = client.shareAndAccept(s)
            assertEquals("active", consent.state)
            assertEquals("institution", consent.scope)
            assertEquals(SCHOOL_HOURS, consent.windows)
        }
    }

    @Test
    fun schoolNeedsWindows() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.school(env)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.share(s, windows = emptyList()).status, "a school sees a child only in its hours")
        }
    }

    @Test
    fun schoolSeesChildOnlyInWindows() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.school(env)
            client.shareAndAccept(s)
            client.sos(s)
            assertEquals(1, client.schoolSees(s).size, "08:00 on a Sunday: school hours")
            env.clock.now = Instant.parse("2026-11-08T19:00:00Z") // 20:00
            assertTrue(client.schoolSees(s).isEmpty(), "the evening is the family's")
            val shared: List<dz.hirz.shared.api.AssetLiveDto> = client.get("/v1/circles/${s.schoolId}/shared-assets") { bearerAuth("test.head") }.body()
            assertTrue(shared.isEmpty())
            env.clock.now = Instant.parse("2026-11-13T09:00:00Z") // Friday 10:00
            assertTrue(client.schoolSees(s).isEmpty(), "no school on Friday")
        }
    }

    @Test
    fun schoolCannotEditProfileOrPlaces() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.school(env)
            client.shareAndAccept(s)
            val profile = client.put("/v1/assets/${s.childId}/profile") {
                bearerAuth("test.head"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(curfewMinute = 1200))
            }
            assertEquals(HttpStatusCode.NotFound, profile.status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/circles/${s.familyId}/places", "test.head",
                CreatePlaceRequest("x", PlaceKind.HOME, ShapeDto.Circle(LatLon(36.75, 3.05), 50.0))).status)
        }
    }

    @Test
    fun parentRevokesAtOnce() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.school(env)
            val consent = client.shareAndAccept(s)
            client.sos(s)
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/consents/${consent.id}") { bearerAuth("test.mom") }.status)
            assertTrue(client.schoolSees(s).isEmpty())
        }
    }

    @Test
    fun consentExpiresAtYearEnd() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.school(env)
            val consent = client.shareAndAccept(s)
            val end = LocalDate.parse("2027-07-01").atStartOfDay(ZoneId.of("Africa/Algiers")).toInstant()
            assertEquals(end.toEpochMilli(), consent.expiresAtMs, "the school year ends on 30 June")
            env.clock.now = Instant.parse("2027-07-04T08:00:00Z") // a Sunday in July, 09:00
            client.sos(s)
            assertTrue(client.schoolSees(s).isEmpty())
        }
    }

    @Test
    fun familyCustodyIsUnchanged() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.school(env)
            val dad = client.newCircle("test.dad")
            val code: LinkCodeDto = client.post("/v1/circles/${dad.id}/link-code") { bearerAuth("test.dad") }.body()
            val consent: ConsentDto = client.postJson("/v1/assets/${s.childId}/consents", "test.mom", ConsentRequest(code.code)).body()
            assertEquals("custody", consent.scope)
            assertEquals(null, consent.expiresAtMs, "shared custody has no school hours and no end")
        }
    }
}
