package dz.hirz.server.support

import dz.hirz.server.auth.Codes
import java.util.UUID
import javax.sql.DataSource

/** Direct SQL fixtures for tests that need rows before the API that creates them is under test. */
object Seed {
    fun member(db: DataSource, uid: String = "u-${UUID.randomUUID()}"): UUID = db.connection.use { c ->
        c.prepareStatement("INSERT INTO members (firebase_uid, display_name) VALUES (?, ?) RETURNING id").apply {
            setString(1, uid); setString(2, uid)
        }.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
    }

    fun circle(db: DataSource, owner: UUID, name: String = "Family"): UUID = db.connection.use { c ->
        val id = c.prepareStatement("INSERT INTO circles (name) VALUES (?) RETURNING id").apply { setString(1, name) }
            .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        c.prepareStatement("INSERT INTO circle_members (circle_id, member_id, role, priority) VALUES (?, ?, 'owner', 1)").apply {
            setObject(1, id); setObject(2, owner)
        }.executeUpdate()
        id
    }

    fun asset(db: DataSource, circle: UUID, type: String = "child", name: String = "Ahmed"): UUID = db.connection.use { c ->
        c.prepareStatement("INSERT INTO assets (circle_id, type, name) VALUES (?, ?, ?) RETURNING id").apply {
            setObject(1, circle); setString(2, type); setString(3, name)
        }.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
    }

    /** Creates a companion source and returns (sourceId, rawToken). */
    fun source(db: DataSource, asset: UUID, revoked: Boolean = false): Pair<UUID, String> {
        val token = Codes.newSourceToken()
        val id = db.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO sources (asset_id, kind, token_hash, revoked_at) VALUES (?, 'companion', ?, ${if (revoked) "now()" else "NULL"}) RETURNING id",
            ).apply { setObject(1, asset); setString(2, Codes.hash(token)) }
                .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
        return id to token
    }
}
