package dz.hirz.server.auth

import dz.hirz.server.support.Seed
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.MeDto
import dz.hirz.shared.api.SourceStatusDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTest {
    @Test
    fun meRequiresToken() = withApp { client, _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth("not-a-test-token") }.status)
    }

    @Test
    fun meUpsertsMemberOnce() = withApp { client, env ->
        val first: MeDto = client.get("/v1/me") { bearerAuth("test:u1") }.body()
        val second: MeDto = client.get("/v1/me") { bearerAuth("test:u1") }.body()
        assertEquals(first.memberId, second.memberId)
        env.db.connection.use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM members WHERE firebase_uid = 'u1'").use {
                it.next(); assertEquals(1, it.getInt(1))
            }
        }
    }

    @Test
    fun sourceTokenIsHashedAtRest() = withApp { client, env ->
        val owner = Seed.member(env.db)
        val asset = Seed.asset(env.db, Seed.circle(env.db, owner))
        val (sourceId, token) = Seed.source(env.db, asset)
        env.db.connection.use { c ->
            c.prepareStatement("SELECT token_hash FROM sources WHERE id = ?").apply { setObject(1, sourceId) }.executeQuery().use {
                it.next(); assertNotEquals(token, it.getString(1)); assertTrue(Codes.matches(token, it.getString(1)))
            }
        }
        assertFalse(Codes.matches(token + "x", Codes.hash(token)))
        val status: SourceStatusDto = client.get("/v1/sources/me") { bearerAuth(token) }.body()
        assertEquals(sourceId.toString(), status.sourceId)
    }

    @Test
    fun codesUseUnambiguousAlphabet() {
        repeat(10_000) {
            val code = Codes.newCode()
            assertEquals(8, code.length)
            assertTrue(code.none { it in "0O1IL" }, code)
        }
    }

    @Test
    fun revokedSourceIsRejected() = withApp { client, env ->
        val owner = Seed.member(env.db)
        val asset = Seed.asset(env.db, Seed.circle(env.db, owner))
        val (_, token) = Seed.source(env.db, asset, revoked = true)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sources/me") { bearerAuth(token) }.status)
    }

    @Test
    fun healthStaysPublic() = withApp { client, _ ->
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }
}
