package dz.hirz.server.support

import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.CircleDto
import dz.hirz.shared.api.CreateAssetRequest
import dz.hirz.shared.api.CreateCircleRequest
import dz.hirz.shared.api.CreateInviteRequest
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.InviteDto
import dz.hirz.shared.api.PairRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PairingCodeDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.asset.AssetType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** API shortcuts shared by server tests; each mirrors one route exactly. */
suspend fun HttpClient.postJson(path: String, token: String?, body: Any): HttpResponse = post(path) {
    token?.let { bearerAuth(it) }
    contentType(ContentType.Application.Json)
    setBody(body)
}

suspend fun HttpClient.newCircle(owner: String = "test.owner"): CircleDto =
    postJson("/v1/circles", owner, CreateCircleRequest("Family")).body()

suspend fun HttpClient.join(owner: String, circleId: String, member: String, role: Role = Role.GUARDIAN) {
    val invite: InviteDto = postJson("/v1/circles/$circleId/invites", owner, CreateInviteRequest(role)).body()
    post("/v1/invites/${invite.code}/accept") { bearerAuth(member) }
}

suspend fun HttpClient.newAsset(owner: String, circleId: String, type: AssetType = AssetType.CHILD, name: String = "Ahmed"): AssetDto =
    postJson("/v1/circles/$circleId/assets", owner, CreateAssetRequest(type, name)).body()

suspend fun HttpClient.pairingCode(owner: String, assetId: String, req: CreatePairingRequest = CreatePairingRequest()): PairingCodeDto =
    postJson("/v1/assets/$assetId/pairing", owner, req).body()

fun pairRequest(code: String, adult: Boolean = false, consent: Boolean = false, pushToken: String? = null) =
    PairRequest(code, "Pixel 7", "14", "0.1.0", pushToken, carrierIsAdult = adult, consentGiven = consent)

suspend fun HttpClient.pair(code: String, adult: Boolean = false, consent: Boolean = false, pushToken: String? = null): HttpResponse =
    postJson("/v1/pair", null, pairRequest(code, adult, consent, pushToken))

/** Circle + child asset + paired companion; returns (circleId, assetId, pair response). */
suspend fun HttpClient.pairedChild(owner: String = "test.owner", req: CreatePairingRequest = CreatePairingRequest()): Triple<String, String, PairResponse> {
    val circle = newCircle(owner)
    val asset = newAsset(owner, circle.id)
    val code = pairingCode(owner, asset.id, req)
    return Triple(circle.id, asset.id, pair(code.code).body())
}
