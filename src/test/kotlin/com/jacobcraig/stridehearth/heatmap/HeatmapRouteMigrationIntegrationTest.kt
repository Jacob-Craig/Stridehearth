package com.jacobcraig.stridehearth.heatmap

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertTrue

/**
 * Migration V13 on a database that already holds Activities with routes: they appear in Heatmap tiles straight
 * after it and the later migrations run, with no action from the athlete. Rendered through the [HeatmapTileRenderer] seam, since no HTTP
 * server runs against a database that has not been migrated yet.
 */
class HeatmapRouteMigrationIntegrationTest {
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

        private fun migrateTo(target: String) {
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate()
        }
    }

    private fun insertId(sql: String): Long = jdbc.queryForObject("$sql RETURNING id", Long::class.java)!!

    @Test
    fun `Activities with routes before V13 appear in tiles after it runs`() {
        migrateTo("12")
        val athlete = insertId("INSERT INTO app_user (email, password_hash, username) VALUES ('v13@example.com', 'x', 'v13')")
        val tile = Tile.containing(lon = -0.165, lat = 51.507, z = 14)
        // Two Activities on separate routes, so each must show up on its own.
        val starts = mapOf("before-1" to tile.centre(), "before-2" to north(tile.centre(), 200.0))
        starts.forEach { (externalId, start) ->
            val route = eastwards(start, metres = 400.0)
            val activity =
                insertId(
                    """
                    INSERT INTO activity (title, started_at, distance, time_taken_seconds, user_id, source, external_id, sport)
                    VALUES ('Run', now(), 0.4, 120, $athlete, 'FIT_UPLOAD', '$externalId', 'RUN')
                    """.trimIndent(),
                )
            val lineString = route.joinToString(prefix = "LINESTRING(", postfix = ")") { "${it.lon} ${it.lat}" }
            jdbc.update(
                "INSERT INTO activity_details (activity_id, route, t, parser_version) VALUES (?, ST_GeomFromText(?, 4326), '{0}', 1)",
                activity,
                lineString,
            )
        }
        // An Activity without a route takes no part.
        insertId(
            """
            INSERT INTO activity (title, started_at, distance, time_taken_seconds, user_id, source, sport)
            VALUES ('Manual', now(), 5.0, 1800, $athlete, 'MANUAL', 'RUN')
            """.trimIndent(),
        )

        // To the latest schema, which the renderer reads; V15 then derives the Full Heat segments of the backfilled rows.
        migrateTo("latest")

        val jdbcClient = JdbcClient.create(dataSource)
        val rendered =
            RasterDensityTileRenderer(jdbcClient, FullHeat(jdbcClient))
                .render(HeatmapScope(athlete), HeatmapRepository(jdbcClient).revision(athlete), TileCoordinate(tile.z, tile.x, tile.y))
        val raster = RasterTile.decode(rendered.bytes)
        starts.forEach { (externalId, start) ->
            listOf(50.0, 200.0, 350.0).forEach { metres ->
                assertTrue(raster.isLitAt(tile.pixelOf(east(start, metres))), "$externalId unlit $metres m along its route")
            }
        }
    }
}
