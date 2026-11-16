package com.jacobcraig.stridehearth.schema

import com.jacobcraig.stridehearth.POSTGIS_IMAGE
import com.jacobcraig.stridehearth.StridehearthApplication
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.core.api.exception.FlywayValidateException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A Server upgraded to a newer One-way Release has migrations in its database that an older Release does not know.
 * The older Release must refuse to start rather than run against a schema it does not understand (ADR-0010, #131).
 * Normal startup and migration are covered by every other `@SpringBootTest`, which migrates a fresh database.
 */
class NewerSchemaIntegrationTest {
    companion object {
        private val postgres = PostgreSQLContainer(POSTGIS_IMAGE)

        @JvmStatic
        @BeforeAll
        fun upgradeDatabaseWithANewerRelease() {
            postgres.start()
            // As the newer Release left it: every migration this Release knows, plus one it does not.
            flyway()
                .locations("classpath:db/migration", "classpath:db/future-migration")
                .load()
                .migrate()
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() = postgres.stop()

        /** Flyway as this Release configures it, before Spring Boot's customizers. */
        private fun flyway(): FluentConfiguration =
            Flyway
                .configure()
                .dataSource(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
                .locations("classpath:db/migration")
    }

    @Test
    fun `a Server whose database a newer One-way Release migrated refuses to start, pointing at the pre-upgrade backup`() {
        val failure =
            assertFailsWith<Exception> {
                // Command-line arguments, so they outrank application.yaml (builder properties are only defaults).
                SpringApplicationBuilder(StridehearthApplication::class.java)
                    .run(
                        "--spring.datasource.url=${postgres.jdbcUrl}",
                        "--spring.datasource.username=${postgres.username}",
                        "--spring.datasource.password=${postgres.password}",
                        // The Server needs a Redis setting to start (#129). Startup is refused at Flyway, before
                        // Redis is used; an unresolvable host keeps a regression away from any developer's Redis.
                        "--spring.data.redis.host=redis.invalid",
                        "--server.port=0",
                    ).close()
            }

        val refusal = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<NewerSchemaException>().firstOrNull()
        assertEquals(
            "This Server's database was upgraded by a newer One-way Release (database schema version 1000), which this " +
                "Release does not know, so it will not start. Going back to this Release means restoring the backup " +
                "taken before that upgrade; otherwise, start the newer Release again.",
            refusal?.message,
            "startup was not refused for a newer schema: $failure",
        )
    }

    @Test
    fun `Flyway ignores future migrations by default, and validation rejects them once the Server overrides that`() {
        flyway().load().validate()

        val customized = flyway()
        SchemaMigrationConfiguration().rejectFutureMigrations().customize(customized)

        assertFailsWith<FlywayValidateException> { customized.load().validate() }
    }
}
