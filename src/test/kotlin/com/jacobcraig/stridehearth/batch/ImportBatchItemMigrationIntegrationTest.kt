package com.jacobcraig.stridehearth.batch

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Migration V11 on a database that already holds **Import Batch Items** from before it: upgrade messages
 * move to their own column, and every Activity link moves to the join table.
 */
class ImportBatchItemMigrationIntegrationTest {
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

    private fun activity(
        userId: Long,
        title: String,
    ): Long =
        insertId(
            """
            INSERT INTO activity (title, started_at, distance, time_taken_seconds, user_id, source, external_id, sport)
            VALUES ('$title', now(), 5.0, 1800, $userId, 'FIT_UPLOAD', '$title', 'RUN')
            """.trimIndent(),
        )

    private fun item(
        batchId: Long,
        status: String,
        activityId: Long?,
        errorMessage: String?,
    ): Long =
        insertId(
            """
            INSERT INTO import_batch_item (batch_id, original_filename, staged_file_path, status, activity_id, error_message)
            VALUES ($batchId, '$status.fit', '/staged/$status.fit', '$status', $activityId, ${errorMessage?.let { "'$it'" } ?: "NULL"})
            """.trimIndent(),
        )

    private fun linkedActivities(itemId: Long): Set<Long> =
        jdbc
            .queryForList("SELECT activity_id FROM import_batch_item_activity WHERE batch_item_id = ?", Long::class.java, itemId)
            .filterNotNull()
            .toSet()

    private fun linkRows(itemId: Long): Int =
        jdbc.queryForObject("SELECT count(*) FROM import_batch_item_activity WHERE batch_item_id = ?", Int::class.java, itemId)!!

    private fun messages(itemId: Long): Pair<String?, String?> =
        jdbc.queryForObject(
            "SELECT error_message, upgrade_message FROM import_batch_item WHERE id = ?",
            { rs, _ -> rs.getString("error_message") to rs.getString("upgrade_message") },
            itemId,
        )!!

    @Test
    fun `V11 moves upgrade messages to their own column and every former activity link to the join table`() {
        flyway("10")
        val userId = insertId("INSERT INTO app_user (email, password_hash, username) VALUES ('m@example.com', 'x', 'migrant')")
        val batchId = insertId("INSERT INTO import_batch (user_id, status, total_files) VALUES ($userId, 'COMPLETED', 5)")
        val (createdRun, upgradedRun, mixedNew, mixedUpgraded, duplicateRun) =
            listOf("created", "upgraded", "mixed-new", "mixed-upgraded", "duplicate").map { activity(userId, it) }
        // Already linked in the join table too, so V11's backfill hits ON CONFLICT DO NOTHING for it (and for mixedNew).
        val success = item(batchId, "SUCCESS", createdRun, null)
        jdbc.update("INSERT INTO import_batch_item_activity VALUES (?, ?)", success, createdRun)
        val upgraded = item(batchId, "UPGRADED", upgradedRun, "Upgraded Activity Details (Parser v1 -> v2)")
        val mixed = item(batchId, "MIXED", mixedNew, "Backfilled 3 laps")
        jdbc.update("INSERT INTO import_batch_item_activity VALUES (?, ?)", mixed, mixedNew)
        jdbc.update("INSERT INTO import_batch_item_activity VALUES (?, ?)", mixed, mixedUpgraded)
        val duplicate = item(batchId, "DUPLICATE", duplicateRun, "That activity was already imported")
        val failed = item(batchId, "FAILED", null, "FIT file failed integrity check")

        flyway("11")

        assertEquals(null to "Upgraded Activity Details (Parser v1 -> v2)", messages(upgraded))
        assertEquals(null to "Backfilled 3 laps", messages(mixed))
        assertEquals("FIT file failed integrity check" to null, messages(failed))
        assertEquals(null to null, messages(duplicate), "DUPLICATE carries no message")
        assertEquals(null to null, messages(success))
        assertEquals(setOf(createdRun), linkedActivities(success))
        assertEquals(1, linkRows(success), "the already-linked activity is not linked twice")
        assertEquals(2, linkRows(mixed))
        assertEquals(setOf(upgradedRun), linkedActivities(upgraded))
        assertEquals(setOf(mixedNew, mixedUpgraded), linkedActivities(mixed))
        assertEquals(setOf(duplicateRun), linkedActivities(duplicate))
        assertEquals(emptySet<Long>(), linkedActivities(failed))
        val activityIdColumns =
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'import_batch_item' AND column_name = 'activity_id'",
                Int::class.java,
            )
        assertFalse(activityIdColumns != 0, "activity_id is dropped")
    }
}
