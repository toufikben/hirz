package dz.hirz.server.auth

import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.bearer
import java.util.UUID
import javax.sql.DataSource

data class MemberPrincipal(val memberId: UUID, val uid: String)
data class SourcePrincipal(val sourceId: UUID, val assetId: UUID, val circleId: UUID)

const val MEMBER_AUTH = "member"
const val SOURCE_AUTH = "source"

/** Members authenticate with Firebase ID tokens; sources with opaque tokens whose SHA-256 is stored. */
fun Application.installAuth(db: DataSource, verifier: TokenVerifier) {
    authentication {
        bearer(MEMBER_AUTH) {
            authenticate { credential ->
                verifier.verify(credential.token)?.let { identity ->
                    MemberPrincipal(upsertMember(db, identity), identity.uid)
                }
            }
        }
        bearer(SOURCE_AUTH) {
            authenticate { credential -> findSource(db, credential.token) }
        }
    }
}

private fun upsertMember(db: DataSource, identity: VerifiedIdentity): UUID = db.connection.use { c ->
    c.prepareStatement(
        """INSERT INTO members (firebase_uid, email, display_name) VALUES (?, ?, ?)
           ON CONFLICT (firebase_uid) DO UPDATE SET email = COALESCE(EXCLUDED.email, members.email)
           RETURNING id""",
    ).apply {
        setString(1, identity.uid); setString(2, identity.email); setString(3, identity.name)
    }.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
}

private fun findSource(db: DataSource, token: String): SourcePrincipal? = db.connection.use { c ->
    c.prepareStatement(
        """SELECT s.id, s.asset_id, a.circle_id FROM sources s JOIN assets a ON a.id = s.asset_id
           WHERE s.token_hash = ? AND s.revoked_at IS NULL""",
    ).apply { setString(1, Codes.hash(token)) }.executeQuery().use { rs ->
        if (!rs.next()) null
        else SourcePrincipal(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java))
    }
}
