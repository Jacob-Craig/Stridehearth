package com.jacobcraig.stridehearth.users

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Migration V17 on a Server that already has accounts: its earliest-registered account becomes the Administrator
 * (ADR-0011), and no other.
 */
class AdministratorMigrationIntegrationTest {
    companion object {
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
        private lateinit var dataSource: DriverManagerDataSource
        private lateinit var jdbc: JdbcTemplate

        @JvmStatic
        @BeforeAll
        fun startDatabase() {
            postgres.start()
            dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            jdbc = JdbcTemplate(dataSource)
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() = postgres.stop()
    }

    private fun flywayTo(target: String?) =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .apply { if (target != null) target(target) }
            .load()

    private fun flyway(target: String) {
        flywayTo(target).migrate()
    }

    /** Every test starts from an empty database. */
    @BeforeEach
    fun emptyDatabase() {
        flywayTo(null).clean()
    }

    private fun account(username: String): Long =
        jdbc.queryForObject(
            "INSERT INTO app_user (email, password_hash, username) VALUES (?, 'x', ?) RETURNING id",
            Long::class.java,
            "$username@example.com",
            username,
        )!!

    private fun administrators(): List<String> =
        jdbc.queryForList("SELECT username FROM app_user WHERE administrator ORDER BY id", String::class.java).filterNotNull()

    @Test
    fun `V17 makes the earliest-registered account the Administrator, and only it`() {
        flyway("16")
        // Usernames in reverse alphabetical order, so the earliest is not also the first by name.
        account("zoe")
        account("mia")
        account("ada")

        flyway("17")

        assertEquals(listOf("zoe"), administrators())
    }

    @Test
    fun `V17 on a Server with no accounts leaves the Administrator to its first sign-up`() {
        flyway("16")

        flyway("17")

        assertEquals(emptyList<String>(), administrators())
        account("first")
        assertEquals(emptyList<String>(), administrators(), "the application, not the database, makes the first account Administrator")
    }

    @Test
    fun `a Server never has two Administrators`() {
        flyway("17")
        account("first")
        account("second")
        jdbc.update("UPDATE app_user SET administrator = true WHERE username = 'first'")

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE app_user SET administrator = true WHERE username = 'second'")
        }
    }
}
