package dz.hirz.server.places

import dz.hirz.server.support.join
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.ShapeDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceRoutesTest {
    private val school = CreatePlaceRequest(
        name = "المدرسة", kind = PlaceKind.SCHOOL,
        shape = ShapeDto.Circle(LatLon(36.7538, 3.0588), 120.0),
        marginM = 60, activeHours = listOf(ActiveWindow(setOf(7, 1, 2, 3, 4), 7 * 60 + 30, 16 * 60 + 30)),
    )

    @Test
    fun createListDelete() = withApp { client, _ ->
        val circle = client.newCircle()
        val created: PlaceDto = client.postJson("/v1/circles/${circle.id}/places", "test.owner", school).body()
        assertEquals(school.shape, created.shape)
        assertEquals(60, created.marginM)
        val list: List<PlaceDto> = client.get("/v1/circles/${circle.id}/places") { bearerAuth("test.owner") }.body()
        assertEquals(listOf(created), list)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/circles/${circle.id}/places/${created.id}") { bearerAuth("test.owner") }.status)
        assertEquals(0, client.get("/v1/circles/${circle.id}/places") { bearerAuth("test.owner") }.body<List<PlaceDto>>().size)
    }

    @Test
    fun polygonRoundTrips() = withApp { client, _ ->
        val circle = client.newCircle()
        val shape = ShapeDto.Polygon(listOf(LatLon(36.785, 3.0595), LatLon(36.785, 3.0625), LatLon(36.787, 3.0625)))
        val created: PlaceDto = client.postJson("/v1/circles/${circle.id}/places", "test.owner",
            CreatePlaceRequest("Park", PlaceKind.PARK, shape)).body()
        assertEquals(shape, created.shape)
    }

    @Test
    fun marginBelow50Rejected() = withApp { client, _ ->
        val circle = client.newCircle()
        assertEquals(HttpStatusCode.UnprocessableEntity,
            client.postJson("/v1/circles/${circle.id}/places", "test.owner", school.copy(marginM = 49)).status)
    }

    @Test
    fun viewerCannotEditPlaces() = withApp { client, _ ->
        val circle = client.newCircle()
        client.join("test.owner", circle.id, "test.aunt", Role.VIEWER)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/circles/${circle.id}/places", "test.aunt", school).status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/circles/${circle.id}/places") { bearerAuth("test.aunt") }.status)
    }

    @Test
    fun strangerSees404() = withApp { client, _ ->
        val circle = client.newCircle()
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/circles/${circle.id}/places") { bearerAuth("test.stranger") }.status)
    }
}
