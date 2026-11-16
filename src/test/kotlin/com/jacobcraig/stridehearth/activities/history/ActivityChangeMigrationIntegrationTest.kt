package com.jacobcraig.stridehearth.activities.history

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Migration V12 on a database that already holds free-text **Activity History Entries** and Import Batch Item
 * upgrade messages: it only adds, so every existing row reads back exactly as it was (and is served as legacy).
 */
class ActivityChangeMigrationIntegrationTest {
    companion object {
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
        private lateinit var jdbc: JdbcTemplate
        private lateinit var flyway: (String) -> Unit

        @JvmStatic
        @BeforeAll
        fun startDatabase() {
            postgres.start()
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            jdbc = JdbcTemplate(dataSource)
            flyway = { target ->
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target(target)
                    .load()
                    .migrate()
            }
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() = postgres.stop()
    }

    private fun insertId(sql: String): Long = jdbc.queryForObject("$sql RETURNING id", Long::class.java)!!

    private fun rows(sql: String): List<Map<String, Any?>> = jdbc.queryForList(sql)

    @Test
    fun `V12 leaves every free-text history row and upgrade message exactly as it was`() {
        flyway("11")
        val userId = insertId("INSERT INTO app_user (email, password_hash, username) VALUES ('v12@example.com', 'x', 'v12')")
        val activityId =
            insertId(
                """
                INSERT INTO activity (title, started_at, distance, time_taken_seconds, user_id, source, external_id, sport)
                VALUES ('Run', now(), 5.0, 1800, $userId, 'FIT_UPLOAD', 'run', 'RUN')
                """.trimIndent(),
            )
        listOf("CREATED" to "Imported from FIT file (Parser v1, 3 laps)", "EDITED" to "title: A, B -> C").forEach { (type, text) ->
            jdbc.update("INSERT INTO activity_history (activity_id, change_type, description) VALUES (?, ?, ?)", activityId, type, text)
        }
        val batchId = insertId("INSERT INTO import_batch (user_id, status, total_files) VALUES ($userId, 'COMPLETED', 1)")
        jdbc.update(
            """
            INSERT INTO import_batch_item (batch_id, original_filename, staged_file_path, status, upgrade_message)
            VALUES (?, 'old.fit', '/staged/old.fit', 'UPGRADED', 'Upgraded Activity Details (Parser v1 -> v2)')
            """.trimIndent(),
            batchId,
        )
        val historyBefore = rows("SELECT id, activity_id, change_type, description, created_at FROM activity_history ORDER BY id")
        val itemsBefore = rows("SELECT id, status, error_message, upgrade_message FROM import_batch_item ORDER BY id")

        flyway("12")

        assertEquals(historyBefore, rows("SELECT id, activity_id, change_type, description, created_at FROM activity_history ORDER BY id"))
        assertEquals(itemsBefore, rows("SELECT id, status, error_message, upgrade_message FROM import_batch_item ORDER BY id"))
        assertEquals(listOf<Any?>(null, null), rows("SELECT change FROM activity_history").map { it["change"] })
        assertEquals(listOf<Any?>(null), rows("SELECT upgrades FROM import_batch_item").map { it["upgrades"] })
    }
}
