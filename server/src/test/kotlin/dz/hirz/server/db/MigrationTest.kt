package dz.hirz.server.db

import dz.hirz.server.support.TestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest {
    private val expectedTables = listOf(
        "members", "circles", "circle_members", "invites",
        "assets", "asset_modes", "mode_switches", "asset_links", "asset_groups", "asset_group_members",
        "sources", "pairing_codes", "places", "positions", "findings",
        "incidents", "incident_events", "push_tokens", "deliveries",
        "location_views", "consents", "handovers", "calendars", "signal_loss_zones",
        "device_ownership_proofs", "emergency_numbers", "audit_log",
    )

    @Test
    fun migratesCleanDatabase() {
        TestDb.fresh().connection.use { c ->
            c.createStatement().executeQuery("SELECT postgis_version()").use { assertTrue(it.next()) }
            val present = mutableSetOf<String>()
            c.createStatement().executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()",
            ).use { rs -> while (rs.next()) present += rs.getString(1) }
            val missing = expectedTables.filterNot { it in present }
            assertTrue(missing.isEmpty(), "missing tables: $missing")
        }
    }

    @Test
    fun seedsAlgerianEmergencyNumbers() {
        TestDb.fresh().connection.use { c ->
            c.createStatement().executeQuery(
                "SELECT count(*), count(verified_at) FROM emergency_numbers WHERE country = 'DZ'",
            ).use { rs ->
                rs.next()
                assertEquals(6, rs.getInt(1))
                assertEquals(0, rs.getInt(2), "no number may be marked verified until confirmed on first run")
            }
        }
    }

    @Test
    fun positionsAreIdempotentPerSourceAndClientId() {
        TestDb.fresh().connection.use { c ->
            c.createStatement().executeQuery(
                """SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()
                   AND tablename = 'positions' AND indexdef LIKE '%UNIQUE%source_id%client_id%'""",
            ).use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
        }
    }
}
