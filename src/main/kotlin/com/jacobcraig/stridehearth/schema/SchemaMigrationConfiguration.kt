package com.jacobcraig.stridehearth.schema

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.pattern.ValidatePattern
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * How the Server brings its database schema up to this Release at startup. Flyway by default ignores "future"
 * migrations (applied in the database but unknown to the running code), which would let an older Release run against
 * a schema a newer One-way Release changed. ADR-0010 overrides that: an older Release refuses to start.
 */
@Configuration(proxyBeanMethods = false)
class SchemaMigrationConfiguration {
    /** Drops Flyway's default `*:future` ignore pattern, so Flyway's own validation rejects future migrations too. */
    @Bean
    fun rejectFutureMigrations() = FlywayConfigurationCustomizer { it.ignoreMigrationPatterns(*emptyArray<ValidatePattern>()) }

    /** Refuses a newer schema with an Operator-facing [NewerSchemaException] before Flyway validates or migrates. */
    @Bean
    fun refuseNewerSchemaThenMigrate() =
        FlywayMigrationStrategy { flyway ->
            refuseNewerSchema(flyway)
            flyway.migrate()
        }

    private fun refuseNewerSchema(flyway: Flyway) {
        val future = flyway.info().all().filter { it.state in FUTURE_STATES }
        future.maxByOrNull { it.version }?.let { throw NewerSchemaException(it.version.version) }
    }

    private companion object {
        val FUTURE_STATES = setOf(MigrationState.FUTURE_SUCCESS, MigrationState.FUTURE_FAILED)
    }
}
