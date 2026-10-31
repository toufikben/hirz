package dz.hirz.server.herds

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreateHerdRequest
import dz.hirz.shared.api.HerdDto
import dz.hirz.shared.api.HerdMemberRequest
import dz.hirz.shared.api.LeaderRequest
import dz.hirz.shared.api.Role
import dz.hirz.shared.asset.AssetType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

private suspend fun HttpClient.leader(herdId: String, who: String, assetId: String): HttpResponse =
    put("/v1/herds/$herdId/leader") { bearerAuth(who); contentType(ContentType.Application.Json); setBody(LeaderRequest(assetId)) }

class HerdTest {
    @Test
    fun herdHasOneLeader() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val herd: HerdDto = client.postJson("/v1/circles/${circle.id}/herds", "test.mom", CreateHerdRequest("Ouled Djellal")).body()
            val a = client.newAsset("test.mom", circle.id, AssetType.LIVESTOCK, "Kebsh")
            val b = client.newAsset("test.mom", circle.id, AssetType.LIVESTOCK, "Naaja")
            val outside = client.newAsset("test.mom", circle.id, AssetType.LIVESTOCK, "Jdi")
            listOf(a, b).forEach { client.postJson("/v1/herds/${herd.id}/members", "test.mom", HerdMemberRequest(it.id)) }
            assertEquals(HttpStatusCode.UnprocessableEntity, client.leader(herd.id, "test.mom", outside.id).status, "the leader is one of the herd")
            client.leader(herd.id, "test.mom", a.id)
            client.leader(herd.id, "test.mom", b.id)
            val got: HerdDto = client.get("/v1/herds/${herd.id}") { bearerAuth("test.mom") }.body()
            assertEquals(b.id, got.leaderId)
            assertEquals(setOf(a.id, b.id), got.members.toSet())
            val child = client.newAsset("test.mom", circle.id)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/v1/herds/${herd.id}/members", "test.mom", HerdMemberRequest(child.id)).status,
                "only livestock join a herd")
            val other: HerdDto = client.postJson("/v1/circles/${circle.id}/herds", "test.mom", CreateHerdRequest("Second")).body()
            assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/herds/${other.id}/members", "test.mom", HerdMemberRequest(a.id)).status,
                "an animal is in one herd at a time")
        }
    }

    @Test
    fun herdMembersSameCircle() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val mine = client.newCircle("test.mom")
            val theirs = client.newCircle("test.other")
            val herd: HerdDto = client.postJson("/v1/circles/${mine.id}/herds", "test.mom", CreateHerdRequest("H")).body()
            val stranger = client.newAsset("test.other", theirs.id, AssetType.LIVESTOCK, "X")
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/herds/${herd.id}/members", "test.mom", HerdMemberRequest(stranger.id)).status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/herds/${herd.id}") { bearerAuth("test.other") }.status)
        }
    }

    @Test
    fun viewerCannotEditHerd() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.aunt", Role.VIEWER)
            val herd: HerdDto = client.postJson("/v1/circles/${circle.id}/herds", "test.mom", CreateHerdRequest("H")).body()
            val a = client.newAsset("test.mom", circle.id, AssetType.LIVESTOCK, "Kebsh")
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/herds/${herd.id}/members", "test.aunt", HerdMemberRequest(a.id)).status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/herds/${herd.id}") { bearerAuth("test.aunt") }.status)
            assertEquals(1, client.get("/v1/circles/${circle.id}/herds") { bearerAuth("test.aunt") }.body<List<HerdDto>>().size)
        }
    }
}
