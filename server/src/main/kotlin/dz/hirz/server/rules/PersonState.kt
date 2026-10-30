package dz.hirz.server.rules

import dz.hirz.server.apiJson
import kotlinx.serialization.KSerializer
import java.sql.Connection
import java.util.UUID

/** Per-asset state of one P5 people rule (`person_rule_state`), as JSON. Callers hold the asset's advisory lock. */
object PersonState {
    fun <T> load(c: Connection, assetId: UUID, rule: String, ser: KSerializer<T>): T? =
        c.prepareStatement("SELECT state::text FROM person_rule_state WHERE asset_id = ? AND rule = ?")
            .apply { setObject(1, assetId); setString(2, rule) }.executeQuery().use { if (it.next()) apiJson.decodeFromString(ser, it.getString(1)) else null }

    fun <T> save(c: Connection, assetId: UUID, rule: String, ser: KSerializer<T>, value: T) {
        c.prepareStatement(
            """INSERT INTO person_rule_state (asset_id, rule, state, updated_at) VALUES (?, ?, ?::jsonb, now())
               ON CONFLICT (asset_id, rule) DO UPDATE SET state = EXCLUDED.state, updated_at = now()""",
        ).apply { setObject(1, assetId); setString(2, rule); setString(3, apiJson.encodeToString(ser, value)) }.executeUpdate()
    }

    fun clear(c: Connection, assetId: UUID, rule: String) {
        c.prepareStatement("DELETE FROM person_rule_state WHERE asset_id = ? AND rule = ?").apply { setObject(1, assetId); setString(2, rule) }.executeUpdate()
    }
}
