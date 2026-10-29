package dz.hirz.server.traccar

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.validImei
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.TrackerClaimDto
import dz.hirz.shared.api.TrackerClaimRequest
import dz.hirz.shared.devices.DeviceCatalog
import dz.hirz.shared.devices.DeviceModel
import dz.hirz.shared.devices.SupportLevel
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceModelsTest {
    @Test
    fun portsMatchTheTraccarTemplate() {
        val template = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "infra/traccar/hirz.template.xml") }.first { it.exists() }.readText()
        val enabled = Regex("<entry key='protocols.enable'>([^<]*)</entry>").find(template)!!.groupValues[1].split(',').map { it.trim() }
        DeviceCatalog.models.filter { it.level != SupportLevel.REJECTED }.forEach { m ->
            assertTrue(m.protocol in enabled, "${m.id}: ${m.protocol} not enabled")
            assertTrue("<entry key='${m.protocol}.port'>${m.port}</entry>" in template, "${m.id}: port ${m.port}")
        }
    }

    @Test
    fun modelsAreListedForMembers() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            client.newCircle("test.mom")
            val models: List<DeviceModel> = client.get("/v1/device-models") { bearerAuth("test.mom") }.body()
            assertTrue(models.any { it.id == "gt06n" } && models.any { it.level == SupportLevel.REJECTED })
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/device-models").status)
        }
    }

    @Test
    fun rejectedAndUnknownModelsCannotBeClaimed() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            val imei = validImei("35933907512345")
            assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/assets/${asset.id}/trackers", "test.mom", TrackerClaimRequest("setracker-class", imei, apn = "internet")).status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/assets/${asset.id}/trackers", "test.mom", TrackerClaimRequest("no-such-model", imei, apn = "internet")).status)
        }
    }

    @Test
    fun claimReturnsRenderedCommandsInOrder() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val asset = client.newAsset("test.mom", client.newCircle("test.mom").id)
            val claim: TrackerClaimDto = client.postJson(
                "/v1/assets/${asset.id}/trackers", "test.mom",
                TrackerClaimRequest("sinotrack-st901", validImei("86000000000001"), simPhone = "0661000000", apn = "internet", intervalS = 30),
            ).body()
            assertEquals(listOf("password", "server", "apn", "interval"), claim.commands.map { it.purpose }.take(4))
            assertTrue(claim.commands.all { it.to == "0661000000" })
            val server = claim.commands.single { it.purpose == "server" }.text
            assertTrue("devices.hirz.test" in server && "5013" in server, server)
            assertTrue(claim.commands.single { it.purpose == "interval" }.text.contains("30"))
            assertEquals(HttpStatusCode.UnprocessableEntity,
                client.postJson("/v1/assets/${asset.id}/trackers", "test.mom", TrackerClaimRequest("gt06n", validImei("35933907512345"))).status,
                "the APN is needed to render the setup SMS")
        }
    }
}
