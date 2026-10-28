package dz.hirz.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dz.hirz.server.config.AppConfig
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Opens the pool and migrates. [schema] isolates a database inside one Postgres (tests use one schema each);
 * PostGIS types resolve through `public` and `extensions` (Supabase installs extensions there).
 */
fun connectDatabase(cfg: AppConfig, schema: String? = null): DataSource {
    val searchPath = listOfNotNull(schema, "public", "extensions").joinToString(",")
    val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = cfg.dbUrl
        username = cfg.dbUser
        password = cfg.dbPassword
        maximumPoolSize = 10
        connectionInitSql = "SET search_path TO $searchPath"
    })
    Flyway.configure()
        .dataSource(pool)
        .apply { if (schema != null) schemas(schema).defaultSchema(schema) }
        .locations("classpath:db/migration")
        .load()
        .migrate()
    return pool
}

/** Runs [block] in one transaction; rolls back on any exception. */
fun <T> DataSource.tx(block: (Connection) -> T): T = connection.use { c ->
    c.autoCommit = false
    try {
        block(c).also { c.commit() }
    } catch (e: Throwable) {
        c.rollback()
        throw e
    }
}

/** Maps every row of [this] result set with [map]. */
fun <T> ResultSet.toList(map: (ResultSet) -> T): List<T> = use { rs ->
    buildList { while (rs.next()) add(map(rs)) }
}
