package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests verifying the Flyway V5 database migration on the PostGIS Testcontainer.
 * Validates the PostGIS extension, activity_details schema, ON DELETE CASCADE, GiST indexing,
 * and column-level LZ4 compression.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ActivityDetailsSchemaIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private lateinit var testUser: User

    @BeforeEach
    fun setUp() {
        testUser = userRepository.save(User(email = "schema_test@example.com", passwordHash = "x", username = "schema_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `postgis extension is installed and active in database`() {
        val extensionCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'postgis'",
                Int::class.java,
            )
        assertEquals(1, extensionCount, "PostGIS extension must be installed")

        val postgisVersion =
            jdbcTemplate.queryForObject(
                "SELECT postgis_version()",
                String::class.java,
            )
        assertTrue(postgisVersion?.startsWith("3.") == true, "PostGIS version should be 3.x")
    }

    @Test
    fun `activity_details table exists with primary key and foreign key constraint to activity with cascade delete`() {
        val tableCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'activity_details'",
                Int::class.java,
            )
        assertEquals(1, tableCount, "Table activity_details must exist")

        val fkCount =
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) 
                FROM information_schema.referential_constraints rc
                JOIN information_schema.table_constraints tc ON rc.constraint_name = tc.constraint_name
                WHERE tc.table_name = 'activity_details' AND rc.delete_rule = 'CASCADE'
                """.trimIndent(),
                Int::class.java,
            )
        assertEquals(1, fkCount, "Foreign key constraint to activity with ON DELETE CASCADE must exist")

        // Test cascade deletion behavior
        val activity =
            activityRepository.save(
                Activity(
                    title = "Cascade Test",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.now(),
                    distance = 5.0,
                    timeTakenSeconds = 1500,
                    user = testUser,
                    source = ActivitySource.MANUAL,
                ),
            )
        val activityId = activity.id!!

        jdbcTemplate.update(
            "INSERT INTO activity_details (activity_id, t, parser_version) VALUES (?, ?, ?)",
            activityId,
            jdbcTemplate.dataSource?.connection?.createArrayOf("integer", arrayOf(0, 1, 2)),
            1.toShort(),
        )

        val countBefore =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activity_details WHERE activity_id = ?",
                Int::class.java,
                activityId,
            )
        assertEquals(1, countBefore)

        activityRepository.delete(activity)

        val countAfter =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activity_details WHERE activity_id = ?",
                Int::class.java,
                activityId,
            )
        assertEquals(0, countAfter, "activity_details row must be deleted when activity is deleted")
    }

    @Test
    fun `route column has geometry linestring with srid 4326 and gist index exists`() {
        val gistIndexCount =
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) 
                FROM pg_indexes 
                WHERE tablename = 'activity_details' 
                  AND indexname = 'idx_activity_details_route' 
                  AND indexdef LIKE '%gist%route%'
                """.trimIndent(),
                Int::class.java,
            )
        assertEquals(1, gistIndexCount, "GiST index on route column must exist")

        val activity =
            activityRepository.save(
                Activity(
                    title = "Route Test",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.now(),
                    distance = 5.0,
                    timeTakenSeconds = 1500,
                    user = testUser,
                    source = ActivitySource.MANUAL,
                ),
            )

        // Test inserting a LineString geometry and querying as GeoJSON
        jdbcTemplate.update(
            """
            INSERT INTO activity_details (activity_id, route, t, parser_version)
            VALUES (?, ST_SetSRID(ST_MakeLine(ST_MakePoint(-0.16, 51.51), ST_MakePoint(-0.17, 51.52)), 4326), ?, 1)
            """.trimIndent(),
            activity.id!!,
            jdbcTemplate.dataSource?.connection?.createArrayOf("integer", arrayOf(0, 1)),
        )

        val geoJson =
            jdbcTemplate.queryForObject(
                "SELECT ST_AsGeoJSON(route) FROM activity_details WHERE activity_id = ?",
                String::class.java,
                activity.id!!,
            )
        assertTrue(geoJson?.contains("LineString") == true, "route should serialize to LineString GeoJSON")
    }

    @Test
    fun `large columns have lz4 compression enabled`() {
        val compressedColumns =
            jdbcTemplate
                .queryForList(
                    """
                    SELECT attname, attcompression 
                    FROM pg_attribute 
                    WHERE attrelid = 'activity_details'::regclass 
                      AND attnum > 0 
                      AND attcompression = 'l'
                    """.trimIndent(),
                ).map { it["attname"] as String }
                .toSet()

        val expectedLz4Columns =
            setOf(
                "route",
                "t",
                "route_index",
                "heart_rate",
                "power",
                "cadence",
                "altitude",
                "speed",
                "distance",
                "vertical_oscillation",
                "stance_time",
                "step_length",
                "enhanced_speed",
                "enhanced_altitude",
            )

        assertTrue(
            compressedColumns.containsAll(expectedLz4Columns),
            "Expected columns to have LZ4 compression. Missing: ${expectedLz4Columns - compressedColumns}",
        )
    }

    @Test
    fun `parser_version column is not null smallint`() {
        val isNullable =
            jdbcTemplate.queryForObject(
                """
                SELECT is_nullable 
                FROM information_schema.columns 
                WHERE table_schema = 'public' AND table_name = 'activity_details' AND column_name = 'parser_version'
                """.trimIndent(),
                String::class.java,
            )
        assertEquals("NO", isNullable, "parser_version must be NOT NULL")

        val udtName =
            jdbcTemplate.queryForObject(
                """
                SELECT udt_name 
                FROM information_schema.columns 
                WHERE table_schema = 'public' AND table_name = 'activity_details' AND column_name = 'parser_version'
                """.trimIndent(),
                String::class.java,
            )
        assertEquals("int2", udtName, "parser_version must be smallint (int2)")
    }
}
