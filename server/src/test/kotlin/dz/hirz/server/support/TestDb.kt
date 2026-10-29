package dz.hirz.server.support

import dz.hirz.server.config.AppConfig
import dz.hirz.server.db.connectDatabase
import java.sql.DriverManager
import java.util.UUID
import com.zaxxer.hikari.HikariDataSource

/**
 * Database for server tests. CI provides a PostGIS service container through HIRZ_TEST_DB_URL;
 * each call gets a brand-new, fully migrated schema so tests never share rows.
 * Missing configuration fails loudly instead of skipping.
 */
object TestDb {
    private val url: String = System.getenv("HIRZ_TEST_DB_URL")
        ?: error("HIRZ_TEST_DB_URL is not set: server tests need a PostGIS database (see .github/workflows/ci.yml)")
    private val user = System.getenv("HIRZ_TEST_DB_USER") ?: "postgres"
    private val password = System.getenv("HIRZ_TEST_DB_PASSWORD") ?: "postgres"

    /** A small pool on a new schema; callers close it (withApp does) so CI Postgres never runs out of connections. */
    fun fresh(): HikariDataSource {
        val schema = "t_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        return connectDatabase(AppConfig(url, user, password, firebaseProjectId = null, dataKey = ByteArray(32)), schema = schema, poolSize = 3)
    }
}
