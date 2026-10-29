package dz.hirz.server.circles

import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CircleDto
import dz.hirz.shared.api.CreateCircleRequest
import dz.hirz.shared.api.CreateInviteRequest
import dz.hirz.shared.api.InviteDto
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.UpdateMemberRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

private suspend fun HttpClient.createCircle(token: String, name: String = "Family"): CircleDto =
    post("/v1/circles") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreateCircleRequest(name)) }.body()

private suspend fun HttpClient.invite(token: String, circle: String, req: CreateInviteRequest) =
    post("/v1/circles/$circle/invites") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(req) }

private suspend fun HttpClient.accept(token: String, code: String) = post("/v1/invites/$code/accept") { bearerAuth(token) }

class CircleRoutesTest {
    @Test
    fun creatorIsOwner() = withApp { client, _ ->
        val circle = client.createCircle("test.owner")
        assertEquals(Role.OWNER, circle.myRole)
        val list: List<CircleDto> = client.get("/v1/circles") { bearerAuth("test.owner") }.body()
        assertEquals(listOf(circle.id), list.map { it.id })
    }

    @Test
    fun invite_is_single_use_and_expires() = withApp { client, env ->
        val circle = client.createCircle("test.owner")
        val first: InviteDto = client.invite("test.owner", circle.id, CreateInviteRequest(Role.GUARDIAN)).body()
        assertTrue(first.deepLink.endsWith(first.code))
        assertEquals(HttpStatusCode.OK, client.accept("test.mom", first.code).status)
        assertTrue(client.accept("test.dad", first.code).status in setOf(HttpStatusCode.Conflict, HttpStatusCode.Gone))

        val second: InviteDto = client.invite("test.owner", circle.id, CreateInviteRequest(Role.VIEWER)).body()
        env.clock.advance(24.hours + 1.milliseconds)
        assertEquals(HttpStatusCode.Gone, client.accept("test.uncle", second.code).status)
    }

    @Test
    fun alreadyMemberCannotAcceptAgain() = withApp { client, _ ->
        val circle = client.createCircle("test.owner")
        val invite: InviteDto = client.invite("test.owner", circle.id, CreateInviteRequest(Role.GUARDIAN)).body()
        assertEquals(HttpStatusCode.Conflict, client.accept("test.owner", invite.code).status)
    }

    @Test
    fun onlyOwnerCanInvite() = withApp { client, _ ->
        val circle = client.createCircle("test.owner")
        val invite: InviteDto = client.invite("test.owner", circle.id, CreateInviteRequest(Role.GUARDIAN)).body()
        client.accept("test.mom", invite.code)
        assertEquals(HttpStatusCode.NotFound, client.invite("test.mom", circle.id, CreateInviteRequest(Role.VIEWER)).status)
    }

    @Test
    fun nonMemberSees404() = withApp { client, _ ->
        val circle = client.createCircle("test.owner")
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/circles/${circle.id}/members") { bearerAuth("test.stranger") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/circles/not-a-uuid/members") { bearerAuth("test.stranger") }.status)
    }

    @Test
    fun temporaryMemberExpires() = withApp { client, env ->
        val circle = client.createCircle("test.owner")
        val until = env.clock.now.plusSeconds(3600).toEpochMilli()
        val invite: InviteDto = client.invite("test.owner", circle.id, CreateInviteRequest(Role.TEMPORARY, memberExpiresAtMs = until)).body()
        client.accept("test.sitter", invite.code)
        assertEquals(1, client.get("/v1/circles") { bearerAuth("test.sitter") }.body<List<CircleDto>>().size)
        env.clock.advance(1.hours + 1.milliseconds)
        assertEquals(0, client.get("/v1/circles") { bearerAuth("test.sitter") }.body<List<CircleDto>>().size)
    }

    @Test
    fun temporaryInviteNeedsMemberExpiry() = withApp { client, _ ->
        val circle = client.createCircle("test.owner")
        assertEquals(HttpStatusCode.UnprocessableEntity, client.invite("test.owner", circle.id, CreateInviteRequest(Role.TEMPORARY)).status)
    }

    @Test
    fun ownerRoleCannotBeInvited() = withApp { client, _ ->
        val circle = client.createCircle("test.owner")
        assertEquals(HttpStatusCode.UnprocessableEntity, client.invite("test.owner", circle.id, CreateInviteRequest(Role.OWNER)).status)
    }

    @Test
    fun lastOwnerCannotDemoteSelf() = withApp { client, _ ->
        val circle = client.createCircle("test.owner")
        val me = client.get("/v1/circles/${circle.id}/members") { bearerAuth("test.owner") }.body<List<MemberDto>>().single()
        val response = client.patch("/v1/circles/${circle.id}/members/${me.memberId}") {
            bearerAuth("test.owner"); contentType(ContentType.Application.Json); setBody(UpdateMemberRequest(role = Role.GUARDIAN))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun membersAreOrderedByPriority() = withApp { client, _ ->
        val circle = client.createCircle("test.owner")
        val invite: InviteDto = client.invite("test.owner", circle.id, CreateInviteRequest(Role.GUARDIAN)).body()
        client.accept("test.mom", invite.code)
        val mom = client.get("/v1/circles/${circle.id}/members") { bearerAuth("test.owner") }.body<List<MemberDto>>()
            .single { it.role == Role.GUARDIAN }
        client.patch("/v1/circles/${circle.id}/members/${mom.memberId}") {
            bearerAuth("test.owner"); contentType(ContentType.Application.Json); setBody(UpdateMemberRequest(priority = 0))
        }
        val ordered = client.get("/v1/circles/${circle.id}/members") { bearerAuth("test.owner") }.body<List<MemberDto>>()
        assertEquals(mom.memberId, ordered.first().memberId)
    }

    @Test
    fun removalIsAudited() = withApp { client, env ->
        val circle = client.createCircle("test.owner")
        val invite: InviteDto = client.invite("test.owner", circle.id, CreateInviteRequest(Role.GUARDIAN)).body()
        client.accept("test.mom", invite.code)
        val mom = client.get("/v1/circles/${circle.id}/members") { bearerAuth("test.owner") }.body<List<MemberDto>>()
            .single { it.role == Role.GUARDIAN }
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/circles/${circle.id}/members/${mom.memberId}") { bearerAuth("test.owner") }.status)
        assertEquals(0, client.get("/v1/circles") { bearerAuth("test.mom") }.body<List<CircleDto>>().size)
        env.db.connection.use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM audit_log WHERE action = 'member_removed'").use {
                it.next(); assertEquals(1, it.getInt(1))
            }
        }
    }
}
