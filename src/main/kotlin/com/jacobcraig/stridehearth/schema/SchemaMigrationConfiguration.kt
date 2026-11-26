package com.jacobcraig.stridehearth.schema

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.pattern.ValidatePattern
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/**
 * How the Server brings its database schema up to this Release at startup. Flyway by default ignores "future"
 * migrations (applied in the database but unknown to the running code), which would let an older Release run against
 * a schema a newer One-way Release changed. ADR-0010 overrides that: an older Release refuses to start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackupProperties::class)
class SchemaMigrationConfiguration {
    /** Drops Flyway's default `*:future` ignore pattern, so Flyway's own validation rejects future migrations too. */
    @Bean
    fun rejectFutureMigrations() = FlywayConfigurationCustomizer { it.ignoreMigrationPatterns(*emptyArray<ValidatePattern>()) }

    /**
     * Refuses a newer schema with an Operator-facing [NewerSchemaException]; then, if migrations are pending on a
     * database that already has a schema, backs it up (refusing to migrate if that fails); then migrates. A fresh
     * database has nothing to back up, and a restart with nothing pending takes no backup.
     */
    @Bean
    fun refuseNewerSchemaThenBackUpThenMigrate(
        properties: BackupProperties,
        connectionDetails: JdbcConnectionDetails,
    ) = FlywayMigrationStrategy { flyway ->
        refuseNewerSchema(flyway)
        backUpIfMigrationsPending(flyway, properties, connectionDetails)
        flyway.migrate()
    }

    private fun refuseNewerSchema(flyway: Flyway) {
        val future = flyway.info().all().filter { it.state in FUTURE_STATES }
        future.maxByOrNull { it.version }?.let { throw NewerSchemaException(it.version.version) }
    }

    private fun backUpIfMigrationsPending(
        flyway: Flyway,
        properties: BackupProperties,
        connectionDetails: JdbcConnectionDetails,
    ) {
        if (!properties.enabled) {
            log.warn("stridehearth.backup.enabled is false: migrating without a pre-migration backup.")
            return
        }
        val info = flyway.info()
        val from = info.current()?.version?.version ?: return
        val to =
            info
                .pending()
                .maxByOrNull { it.version }
                ?.version
                ?.version ?: return
        val connection = DatabaseConnection(connectionDetails.jdbcUrl, connectionDetails.username, connectionDetails.password)
        PreMigrationBackup(Path.of(properties.dir), PgDumpDumper(properties.pgDumpCommand), properties.keep)
            .take(connection, from, to)
    }

    private companion object {
        val log = LoggerFactory.getLogger(SchemaMigrationConfiguration::class.java)
        val FUTURE_STATES = setOf(MigrationState.FUTURE_SUCCESS, MigrationState.FUTURE_FAILED)
    }
}
