package dz.hirz.server.assets

import dz.hirz.server.auth.Codes
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairedChild
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.CreateAssetRequest
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.asset.AssetType
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

class PairingTest {
    @Test
    fun ownerAndGuardianCreateAssetsViewerCannot() = withApp { client, _ ->
        val circle = client.newCircle()
        client.join("test.owner", circle.id, "test.mom", Role.GUARDIAN)
        client.join("test.owner", circle.id, "test.aunt", Role.VIEWER)
        assertEquals(HttpStatusCode.OK, client.postJson("/v1/circles/${circle.id}/assets", "test.mom", CreateAssetRequest(AssetType.CHILD, "Lina")).status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/circles/${circle.id}/assets", "test.aunt", CreateAssetRequest(AssetType.CHILD, "X")).status)
        val list: List<AssetDto> = client.get("/v1/circles/${circle.id}/assets") { bearerAuth("test.aunt") }.body()
        assertEquals(listOf("Lina"), list.map { it.name })
    }

    @Test
    fun pairing_code_is_single_use_and_expires() = withApp { client, env ->
        val circle = client.newCircle()
        val asset = client.newAsset("test.owner", circle.id)
        val code = client.pairingCode("test.owner", asset.id)
        assertTrue(code.qrPayload.endsWith(code.code))
        assertEquals(HttpStatusCode.OK, client.pair(code.code).status)
        assertEquals(HttpStatusCode.Gone, client.pair(code.code).status)

        val late = client.pairingCode("test.owner", asset.id)
        env.clock.advance(10.minutes + 1.milliseconds)
        assertEquals(HttpStatusCode.Gone, client.pair(late.code).status)
        assertEquals(HttpStatusCode.NotFound, client.pair("ZZZZZZZZ").status)
    }

    @Test
    fun typedCodeIsNormalized() = withApp { client, _ ->
        val circle = client.newCircle()
        val asset = client.newAsset("test.owner", circle.id)
        val code = client.pairingCode("test.owner", asset.id).code
        val typed = code.lowercase().chunked(4).joinToString("-")
        assertEquals(HttpStatusCode.OK, client.pair(typed).status)
    }

    @Test
    fun adultCarrierNeedsConsent() = withApp { client, env ->
        val circle = client.newCircle()
        val asset = client.newAsset("test.owner", circle.id, AssetType.TEEN)
        val code = client.pairingCode("test.owner", asset.id)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.pair(code.code, adult = true, consent = false).status)
        val ok: PairResponse = client.pair(code.code, adult = true, consent = true).body()
        env.db.connection.use { c ->
            c.prepareStatement("SELECT adult, consent_at FROM sources WHERE id = ?").apply { setObject(1, UUID.fromString(ok.sourceId)) }
                .executeQuery().use { assertTrue(it.next()); assertTrue(it.getBoolean(1)); assertNotNull(it.getTimestamp(2)) }
        }
    }

    @Test
    fun elderAssetAlwaysNeedsConsent() = withApp { client, _ ->
        val circle = client.newCircle()
        val asset = client.newAsset("test.owner", circle.id, AssetType.ELDER, "Grandpa")
        val code = client.pairingCode("test.owner", asset.id)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.pair(code.code, adult = false, consent = false).status)
        assertEquals(HttpStatusCode.OK, client.pair(code.code, adult = false, consent = true).status)
    }

    @Test
    fun pairReturnsTokenOnlyOnce() = withApp { client, _ ->
        val (circleId, assetId, paired) = client.pairedChild()
        val text = client.get("/v1/circles/$circleId/assets") { bearerAuth("test.owner") }.bodyAsText()
        assertFalse(paired.sourceToken in text)
        val asset = client.get("/v1/circles/$circleId/assets") { bearerAuth("test.owner") }.body<List<AssetDto>>().single { it.id == assetId }
        assertEquals(listOf(paired.sourceId), asset.sources.map { it.id })
    }

    @Test
    fun duressPinIsStoredHashed() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild(req = CreatePairingRequest(cancelPin = "1234", duressPin = "9876"))
        val salt = assertNotNull(paired.pinSalt)
        env.db.connection.use { c ->
            c.prepareStatement("SELECT cancel_pin_hash, duress_pin_hash, pin_salt FROM sources WHERE id = ?")
                .apply { setObject(1, UUID.fromString(paired.sourceId)) }.executeQuery().use {
                    it.next()
                    assertEquals(Codes.pinHash(salt, "1234"), it.getString(1))
                    assertEquals(Codes.pinHash(salt, "9876"), it.getString(2))
                    assertEquals(salt, it.getString(3))
                }
        }
    }

    @Test
    fun cancelAndDuressPinMustDiffer() = withApp { client, _ ->
        val circle = client.newCircle()
        val asset = client.newAsset("test.owner", circle.id)
        val same = client.postJson("/v1/assets/${asset.id}/pairing", "test.owner", CreatePairingRequest("1234", "1234"))
        assertEquals(HttpStatusCode.UnprocessableEntity, same.status)
        val shortPin = client.postJson("/v1/assets/${asset.id}/pairing", "test.owner", CreatePairingRequest("12", null))
        assertEquals(HttpStatusCode.UnprocessableEntity, shortPin.status)
    }

    @Test
    fun revokedSourceLosesAccess() = withApp { client, _ ->
        val (_, assetId, paired) = client.pairedChild()
        assertEquals(HttpStatusCode.OK, client.get("/v1/sources/me") { bearerAuth(paired.sourceToken) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/assets/$assetId/sources/${paired.sourceId}") { bearerAuth("test.owner") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sources/me") { bearerAuth(paired.sourceToken) }.status)
    }

    @Test
    fun sourceCanUpdateItsPushToken() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild()
        val response = client.put("/v1/sources/me/push-token") {
            bearerAuth(paired.sourceToken); contentType(ContentType.Application.Json); setBody(PushTokenDto("fcm-companion-1"))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        env.db.connection.use { c ->
            c.prepareStatement("SELECT push_token FROM sources WHERE id = ?").apply { setObject(1, UUID.fromString(paired.sourceId)) }
                .executeQuery().use { it.next(); assertEquals("fcm-companion-1", it.getString(1)) }
        }
    }

    @Test
    fun strangersCannotCreatePairingCodes() = withApp { client, _ ->
        val circle = client.newCircle()
        val asset = client.newAsset("test.owner", circle.id)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/assets/${asset.id}/pairing", "test.stranger", CreatePairingRequest()).status)
    }
}
