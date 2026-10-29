package dz.hirz.app.api

import dz.hirz.shared.api.ActionRequest
import dz.hirz.shared.api.ApiErrorDto
import dz.hirz.shared.api.AssetDto
import dz.hirz.shared.api.CheckInSentDto
import dz.hirz.shared.api.CircleDto
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.CodeWordDto
import dz.hirz.shared.api.CreateAssetRequest
import dz.hirz.shared.api.CreateCircleRequest
import dz.hirz.shared.api.CreateInviteRequest
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.EmergencyNumberDto
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestResult
import dz.hirz.shared.api.InviteDto
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LiveEvent
import dz.hirz.shared.api.MeDto
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.MyLocationRequest
import dz.hirz.shared.api.PairRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PairingCodeDto
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PositionDto
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.ReceiptRequest
import dz.hirz.shared.api.ReceiptState
import dz.hirz.shared.api.ReviewRequest
import dz.hirz.shared.api.ShareDto
import dz.hirz.shared.api.ShareRequest
import dz.hirz.shared.api.SheetDto
import dz.hirz.shared.api.SourceStatusDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class HirzApiException(val status: Int, val code: String) : RuntimeException("HTTP $status $code")

val hirzJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/**
 * Typed client for Hirz Core. Member calls use the Firebase ID token from [tokenProvider] and retry once
 * with a forced refresh on 401. Companion calls take the source token explicitly.
 */
class HirzClient(
    baseUrl: String,
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
    engine: HttpClientEngine,
) {
    private val base = baseUrl.trimEnd('/')
    private val http = HttpClient(engine) {
        install(ContentNegotiation) { json(hirzJson) }
        install(WebSockets)
        expectSuccess = false
    }

    private suspend fun member(block: suspend (token: String?) -> HttpResponse): HttpResponse {
        val first = block(tokenProvider(false))
        if (first.status != HttpStatusCode.Unauthorized) return first
        return block(tokenProvider(true))
    }

    private suspend inline fun <reified T> HttpResponse.ok(): T {
        if (!status.isSuccess()) {
            val code = runCatching { hirzJson.decodeFromString(ApiErrorDto.serializer(), bodyAsText()).error }.getOrNull()
            throw HirzApiException(status.value, code ?: "http_${status.value}")
        }
        return body()
    }

    private suspend fun HttpResponse.done() { ok<Unit>() }

    private fun HttpRequestBuilder.json(body: Any) { contentType(ContentType.Application.Json); setBody(body) }
    private fun HttpRequestBuilder.auth(token: String?) { token?.let { bearerAuth(it) } }

    // ---- member ----
    suspend fun me(): MeDto = member { t -> http.get("$base/v1/me") { auth(t) } }.ok()
    suspend fun setPushToken(token: String) = member { t -> http.put("$base/v1/me/push-token") { auth(t); json(PushTokenDto(token)) } }.done()

    suspend fun circles(): List<CircleDto> = member { t -> http.get("$base/v1/circles") { auth(t) } }.ok()
    suspend fun createCircle(name: String): CircleDto = member { t -> http.post("$base/v1/circles") { auth(t); json(CreateCircleRequest(name)) } }.ok()
    suspend fun members(circleId: String): List<MemberDto> = member { t -> http.get("$base/v1/circles/$circleId/members") { auth(t) } }.ok()
    suspend fun invite(circleId: String, req: CreateInviteRequest): InviteDto =
        member { t -> http.post("$base/v1/circles/$circleId/invites") { auth(t); json(req) } }.ok()
    suspend fun acceptInvite(code: String): CircleDto = member { t -> http.post("$base/v1/invites/$code/accept") { auth(t) } }.ok()

    suspend fun assets(circleId: String): List<AssetDto> = member { t -> http.get("$base/v1/circles/$circleId/assets") { auth(t) } }.ok()
    suspend fun createAsset(circleId: String, req: CreateAssetRequest): AssetDto =
        member { t -> http.post("$base/v1/circles/$circleId/assets") { auth(t); json(req) } }.ok()
    suspend fun pairingCode(assetId: String, req: CreatePairingRequest): PairingCodeDto =
        member { t -> http.post("$base/v1/assets/$assetId/pairing") { auth(t); json(req) } }.ok()
    suspend fun checkIn(assetId: String): CheckInSentDto = member { t -> http.post("$base/v1/assets/$assetId/checkin") { auth(t) } }.ok()
    suspend fun positions(assetId: String, sinceMs: Long, limit: Int = 500): List<PositionDto> =
        member { t -> http.get("$base/v1/assets/$assetId/positions") { auth(t); parameter("sinceMs", sinceMs); parameter("limit", limit) } }.ok()

    suspend fun places(circleId: String): List<PlaceDto> = member { t -> http.get("$base/v1/circles/$circleId/places") { auth(t) } }.ok()
    suspend fun createPlace(circleId: String, req: CreatePlaceRequest): PlaceDto =
        member { t -> http.post("$base/v1/circles/$circleId/places") { auth(t); json(req) } }.ok()
    suspend fun deletePlace(circleId: String, placeId: String) =
        member { t -> http.delete("$base/v1/circles/$circleId/places/$placeId") { auth(t) } }.done()

    suspend fun incidents(circleId: String, openOnly: Boolean = true): List<IncidentDto> =
        member { t -> http.get("$base/v1/circles/$circleId/incidents") { auth(t); if (openOnly) parameter("state", "open") } }.ok()
    suspend fun incident(id: String): IncidentDto = member { t -> http.get("$base/v1/incidents/$id") { auth(t) } }.ok()
    suspend fun receipt(id: String, state: ReceiptState) =
        member { t -> http.post("$base/v1/incidents/$id/receipts") { auth(t); json(ReceiptRequest(state)) } }.done()
    suspend fun claim(id: String): IncidentDto = member { t -> http.post("$base/v1/incidents/$id/claim") { auth(t) } }.ok()
    suspend fun contest(id: String): IncidentDto = member { t -> http.post("$base/v1/incidents/$id/contest") { auth(t) } }.ok()
    suspend fun close(id: String, reason: CloseReason): IncidentDto =
        member { t -> http.post("$base/v1/incidents/$id/close") { auth(t); json(CloseRequest(reason)) } }.ok()
    suspend fun confirmClose(id: String): IncidentDto = member { t -> http.post("$base/v1/incidents/$id/close/confirm") { auth(t) } }.ok()
    suspend fun action(id: String, kind: String, value: String? = null): IncidentDto =
        member { t -> http.post("$base/v1/incidents/$id/actions") { auth(t); json(ActionRequest(kind, value)) } }.ok()
    suspend fun review(id: String, falseAlarm: Boolean, note: String? = null) =
        member { t -> http.post("$base/v1/incidents/$id/review") { auth(t); json(ReviewRequest(falseAlarm, note)) } }.done()
    suspend fun myLocation(id: String, position: LatLon, accuracyM: Double?) =
        member { t -> http.post("$base/v1/incidents/$id/my-location") { auth(t); json(MyLocationRequest(position, accuracyM)) } }.done()
    suspend fun sheet(id: String): SheetDto = member { t -> http.get("$base/v1/incidents/$id/sheet") { auth(t) } }.ok()
    suspend fun policePack(id: String): ByteArray = member { t -> http.get("$base/v1/incidents/$id/police-pack") { auth(t) } }.ok()
    suspend fun share(id: String, ttlMinutes: Int): ShareDto =
        member { t -> http.post("$base/v1/incidents/$id/share") { auth(t); json(ShareRequest(ttlMinutes)) } }.ok()
    suspend fun revokeShare(id: String) = member { t -> http.delete("$base/v1/incidents/$id/share") { auth(t) } }.done()
    suspend fun ring(assetId: String): CheckInSentDto = member { t -> http.post("$base/v1/assets/$assetId/ring") { auth(t) } }.ok()

    suspend fun codeWord(circleId: String): CodeWordDto = member { t -> http.get("$base/v1/circles/$circleId/code-word") { auth(t) } }.ok()
    suspend fun setCodeWord(circleId: String, word: String) =
        member { t -> http.put("$base/v1/circles/$circleId/code-word") { auth(t); json(CodeWordDto(word)) } }.done()
    suspend fun emergencyNumbers(circleId: String, country: String = "DZ"): List<EmergencyNumberDto> =
        member { t -> http.get("$base/v1/emergency-numbers") { auth(t); parameter("country", country); parameter("circleId", circleId) } }.ok()
    /** Sign-out removes this install's token first, so a signed-out phone never rings (P1 minor #20). */
    suspend fun deletePushToken(token: String) =
        member { t -> http.delete("$base/v1/me/push-token/${token.encodeURLPathPart()}") { auth(t) } }.done()

    /** Live events for every circle of the signed-in member; completes when the socket closes. */
    fun live(): Flow<LiveEvent> = flow {
        val token = tokenProvider(false)
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        http.webSocket("$wsBase/v1/live?token=$token") {
            for (frame in incoming) {
                if (frame is Frame.Text) emit(hirzJson.decodeFromString(LiveEvent.serializer(), frame.readText()))
            }
        }
    }

    // ---- companion ----
    suspend fun pair(req: PairRequest): PairResponse = http.post("$base/v1/pair") { json(req) }.ok()
    suspend fun ingest(sourceToken: String, batch: IngestBatch): IngestResult =
        http.post("$base/v1/ingest") { auth(sourceToken); json(batch) }.ok()
    suspend fun sourceMe(sourceToken: String): SourceStatusDto = http.get("$base/v1/sources/me") { auth(sourceToken) }.ok()
    suspend fun setSourcePushToken(sourceToken: String, token: String) =
        http.put("$base/v1/sources/me/push-token") { auth(sourceToken); json(PushTokenDto(token)) }.done()
}
