package dz.hirz.server.calendar

import dz.hirz.server.rules.Profiles
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.CreateHerdRequest
import dz.hirz.shared.api.EidRequest
import dz.hirz.shared.api.HerdDto
import dz.hirz.shared.api.MarketRequest
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EidTest {
    @Test
    fun eidDatesAndSeason() {
        assertEquals(LocalDate.parse("2026-05-27"), AlgerianCalendar.eidAlAdha(2026))
        assertTrue(AlgerianCalendar.inEidSeason(LocalDate.parse("2026-05-13"), null), "14 days before")
        assertTrue(AlgerianCalendar.inEidSeason(LocalDate.parse("2026-05-27"), null), "the day itself")
        assertFalse(AlgerianCalendar.inEidSeason(LocalDate.parse("2026-05-12"), null))
        assertFalse(AlgerianCalendar.inEidSeason(LocalDate.parse("2026-05-28"), null))
        assertTrue(AlgerianCalendar.inEidSeason(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-10")), "the owner's date wins")
        assertFalse(AlgerianCalendar.inEidSeason(LocalDate.parse("2026-05-20"), LocalDate.parse("2026-06-10")))
    }

    @Test
    fun eidRaisesLivestockTiersTwoWeeksBefore() {
        val base = Profiles.of(AssetType.LIVESTOCK, Condition.NONE)
        val season = Profiles.seasonal(base, AssetType.LIVESTOCK, Instant.parse("2026-05-20T10:00:00Z"), null)
        assertEquals(Tier.P1, season.tierOf(RuleId.PLACE_EXIT))
        assertEquals(Tier.P1, season.tierOf(RuleId.ANIMAL_STILL))
        assertEquals(Tier.P1, season.tierOf(RuleId.HERD_NIGHT_MOVE), "P1 stays P1: the calendar never makes a P0")
        assertTrue(season.eidSeason)
        val after = Profiles.seasonal(base, AssetType.LIVESTOCK, Instant.parse("2026-06-20T10:00:00Z"), null)
        assertEquals(Tier.P2, after.tierOf(RuleId.PLACE_EXIT))
        assertFalse(after.eidSeason)
    }

    @Test
    fun eidLeavesPeopleAlone() {
        val child = Profiles.of(AssetType.CHILD, Condition.NONE)
        assertEquals(child, Profiles.seasonal(child, AssetType.CHILD, Instant.parse("2026-05-20T10:00:00Z"), null))
    }

    @Test
    fun ownerCorrectsEidDateAndMarketDays() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val herd: HerdDto = client.postJson("/v1/circles/${circle.id}/herds", "test.mom", CreateHerdRequest("H")).body()
            client.put("/v1/herds/${herd.id}/eid") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(EidRequest("2026-06-10")) }
            val market = listOf(ActiveWindow(setOf(2), 5 * 60, 14 * 60))
            val got: HerdDto = client.put("/v1/herds/${herd.id}/market") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(MarketRequest(market))
            }.body()
            assertEquals("2026-06-10", got.eidDate)
            assertEquals(market, got.marketWindow)
        }
    }

    @Test
    fun lastYearsAnnouncedDateIsIgnored() {
        assertTrue(AlgerianCalendar.inEidSeason(LocalDate.parse("2027-05-10"), LocalDate.parse("2026-05-27")),
            "the 2026 date set by the owner must not switch off the 2027 season")
        assertTrue(AlgerianCalendar.inEidSeason(LocalDate.parse("2027-05-05"), LocalDate.parse("2027-05-17")), "a date a day off is the announced one")
    }
}
