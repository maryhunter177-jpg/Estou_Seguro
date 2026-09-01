package br.com.estouseguro.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

object DatabaseFactory {
    fun create(config: AppConfig): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = 4
        minimumIdle = 0
        connectionTimeout = 3_000
        validationTimeout = 2_000
        keepaliveTime = 120_000
        maxLifetime = 1_800_000
        isAutoCommit = true
        addDataSourceProperty("tcpKeepAlive", "true")
        addDataSourceProperty("ApplicationName", "estou-seguro-api")
    })
}

class MigrationRunner(private val dataSource: DataSource) {
    fun migrate() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(781230145)") }
                connection.createStatement().use {
                    it.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migration (
                            version INTEGER PRIMARY KEY,
                            checksum TEXT NOT NULL,
                            applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                    """.trimIndent())
                }
                migrations().forEach { migration -> apply(connection, migration) }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun apply(connection: Connection, migration: Migration) {
        val existing = connection.prepareStatement("SELECT checksum FROM schema_migration WHERE version = ?").use {
            it.setInt(1, migration.version)
            it.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
        if (existing != null) {
            check(existing == migration.checksum) { "Migration ${migration.version} checksum mismatch" }
            return
        }
        connection.createStatement().use { it.execute(migration.sql) }
        connection.prepareStatement("INSERT INTO schema_migration(version, checksum) VALUES (?, ?)").use {
            it.setInt(1, migration.version)
            it.setString(2, migration.checksum)
            it.executeUpdate()
        }
    }

    private fun migrations(): List<Migration> = listOf(1).map { version ->
        val path = "/db/migration/V${version}__init.sql"
        val sql = requireNotNull(javaClass.getResourceAsStream(path)) { "Missing migration $path" }
            .bufferedReader().use { it.readText() }
        Migration(version, sql, MessageDigest.getInstance("SHA-256").digest(sql.toByteArray()).toHex())
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private data class Migration(val version: Int, val sql: String, val checksum: String)
}

inline fun <T> DataSource.transaction(block: (Connection) -> T): T = connection.use { connection ->
    connection.autoCommit = false
    try {
        block(connection).also { connection.commit() }
    } catch (error: Exception) {
        connection.rollback()
        throw error
    }
}
