package dz.hirz.server.jobs

import dz.hirz.server.support.pairedChild
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

class RetentionTest {
    @Test
    fun purgeRemovesOldPositions() = withApp { client, env ->
        val (_, _, paired) = client.pairedChild()
        client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("old", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
        env.clock.advance(31.days)
        client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("new", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
        RetentionPurge(env.db, env.clock).runOnce()
        assertEquals(listOf("new"), clientIds(env.db))
    }

    @Test
    fun purgeKeepsIncidentWindow() = withApp { client, env ->
        val (circleId, assetId, paired) = client.pairedChild()
        client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("evidence", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
        env.db.connection.use {
            it.createStatement().execute(
                "INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('$assetId', '$circleId', 'SOS', 'P0', now())",
            )
        }
        env.clock.advance(31.days)
        RetentionPurge(env.db, env.clock).runOnce()
        assertEquals(listOf("evidence"), clientIds(env.db))
    }

    private fun clientIds(db: javax.sql.DataSource): List<String> = db.connection.use { c ->
        c.createStatement().executeQuery("SELECT client_id FROM positions ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }
}
