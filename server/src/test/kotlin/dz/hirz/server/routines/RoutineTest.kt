package dz.hirz.server.routines

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RoutineDto
import dz.hirz.shared.api.RoutineRequest
import dz.hirz.shared.api.RoutineTemplate
import dz.hirz.shared.api.ShapeDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

private val SCHOOL = LatLon(36.7538, 3.0588)

class RoutineTest {
    @Test
    fun templateCreatesSchoolRoutine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val school: PlaceDto = client.postJson("/v1/circles/${circle.id}/places", "test.mom",
                CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 80.0))).body()
            val kid = client.newAsset("test.mom", circle.id)
            val r: RoutineDto = client.postJson("/v1/assets/${kid.id}/routines", "test.mom", RoutineRequest(school.id, template = RoutineTemplate.SCHOOL)).body()
            assertEquals(setOf(7, 1, 2, 3, 4), r.days, "Sunday to Thursday")
            assertEquals(8 * 60, r.arriveByMin)
            assertEquals(16 * 60 + 30, r.dismissalMin)
            assertEquals(listOf(r), client.get("/v1/assets/${kid.id}/routines") { bearerAuth("test.mom") }.body<List<RoutineDto>>())
        }
    }

    @Test
    fun routinePlaceMustBeSameCircle() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val mine = client.newCircle("test.mom")
            val theirs = client.newCircle("test.other")
            val theirPlace: PlaceDto = client.postJson("/v1/circles/${theirs.id}/places", "test.other",
                CreatePlaceRequest("X", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 80.0))).body()
            val kid = client.newAsset("test.mom", mine.id)
            assertEquals(HttpStatusCode.NotFound,
                client.postJson("/v1/assets/${kid.id}/routines", "test.mom", RoutineRequest(theirPlace.id, setOf(1), arriveByMin = 480)).status)
            assertEquals(HttpStatusCode.UnprocessableEntity,
                client.postJson("/v1/assets/${kid.id}/routines", "test.mom", RoutineRequest(theirPlace.id, emptySet(), arriveByMin = 480)).status,
                "no days is not a routine")
        }
    }

    @Test
    fun viewerCannotEditRoutines() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.aunt", Role.VIEWER)
            val school: PlaceDto = client.postJson("/v1/circles/${circle.id}/places", "test.mom",
                CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 80.0))).body()
            val kid = client.newAsset("test.mom", circle.id)
            assertEquals(HttpStatusCode.NotFound,
                client.postJson("/v1/assets/${kid.id}/routines", "test.aunt", RoutineRequest(school.id, template = RoutineTemplate.SCHOOL)).status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/assets/${kid.id}/routines") { bearerAuth("test.aunt") }.status)
        }
    }

    @Test
    fun routineChangesAreAudited() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val school: PlaceDto = client.postJson("/v1/circles/${circle.id}/places", "test.mom",
                CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 80.0))).body()
            val kid = client.newAsset("test.mom", circle.id)
            val r: RoutineDto = client.postJson("/v1/assets/${kid.id}/routines", "test.mom", RoutineRequest(school.id, template = RoutineTemplate.SCHOOL)).body()
            val changed: RoutineDto = client.put("/v1/routines/${r.id}") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(RoutineRequest(school.id, setOf(7, 1, 2, 3, 4), arriveByMin = 7 * 60 + 45, dismissalMin = 12 * 60))
            }.body()
            assertEquals(12 * 60, changed.dismissalMin)
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/routines/${r.id}") { bearerAuth("test.mom") }.status)
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM audit_log WHERE action = 'routine_changed'").use { it.next(); assertEquals(3, it.getInt(1)) }
            }
        }
    }
}
