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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migration V16 (#119) on a database whose heatmap route rows were written before it: every row gets its z13, z14
 * and z15 bands from its Activity's route, the old full-detail band carries on as z16, and the route still draws at
 * z13 and z16. Rendered through the [HeatmapTileRenderer] seam, since no HTTP server runs against a database that
 * has not been migrated yet. A row whose Activity has no Activity Details cannot be recomputed, so V16 drops it
 * rather than failing (#124).
 */
class HeatmapRouteOnePixelBandsMigrationIntegrationTest {
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
    fun `heatmap routes written before V16 get their one-pixel bands, still draw at z13 and z16, and lose rows without Activity Details`() {
        migrateTo("15")
        val athlete = insertId("INSERT INTO app_user (email, password_hash, username) VALUES ('v16@example.com', 'x', 'v16')")
        val start = Tile.containing(lon = -0.165, lat = 51.507, z = 16).centre()
        // A route that zigzags 2 m either side of its line every 10 m. Its corners stand about 4 m (6.4 Web Mercator
        // metres here) off the line through their neighbours: z13 (9.55 m) straightens it, z14 (4.78 m) does not.
        val route = eastwards(start, metres = 400.0, step = 10.0).mapIndexed { i, point -> north(point, if (i % 2 == 0) 2.0 else -2.0) }
        val activity =
            insertId(
                """
                INSERT INTO activity (title, started_at, distance, time_taken_seconds, user_id, source, external_id, sport)
                VALUES ('Run', now(), 0.4, 120, $athlete, 'FIT_UPLOAD', 'before-v16', 'RUN')
                """.trimIndent(),
            )
        val lineString = route.joinToString(prefix = "LINESTRING(", postfix = ")") { "${it.lon} ${it.lat}" }
        jdbc.update(
            "INSERT INTO activity_details (activity_id, route, t, parser_version) VALUES (?, ST_GeomFromText(?, 4326), '{0}', 1)",
            activity,
            lineString,
        )
        // As the Activity Details upsert wrote it before V16 (V13's columns, through V13's function).
        jdbc.update(
            """
            INSERT INTO heatmap_route (activity_id, user_id, sport, started_at, geom_z0, geom_z6, geom_z10, geom_z13)
            SELECT a.id, a.user_id, a.sport, a.started_at, g.geom_z0, g.geom_z6, g.geom_z10, g.geom_z13
            FROM activity_details d JOIN activity a ON a.id = d.activity_id
            CROSS JOIN LATERAL heatmap_route_geometries(d.route, a.sport) AS g
            """.trimIndent(),
        )
        // A row whose Activity has no Activity Details, as hand-written SQL can leave behind: nothing to recompute from.
        val withoutDetails =
            insertId(
                """
                INSERT INTO activity (title, started_at, distance, time_taken_seconds, user_id, source, sport)
                VALUES ('Manual', now(), 0.4, 120, $athlete, 'MANUAL', 'RUN')
                """.trimIndent(),
            )
        jdbc.update(
            """
            INSERT INTO heatmap_route (activity_id, user_id, sport, started_at, geom_z0, geom_z6, geom_z10, geom_z13)
            SELECT ?, user_id, sport, started_at, geom_z0, geom_z6, geom_z10, geom_z13 FROM heatmap_route WHERE activity_id = ?
            """.trimIndent(),
            withoutDetails,
            activity,
        )
        val fullDetailBefore =
            jdbc.queryForObject("SELECT ST_AsEWKT(geom_z13) FROM heatmap_route WHERE activity_id = ?", String::class.java, activity)

        migrateTo("latest")

        assertEquals(
            listOf(activity),
            jdbc.queryForList("SELECT activity_id FROM heatmap_route", Long::class.java),
            "the row without Activity Details is dropped; the Activity itself stays",
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity WHERE id = ?", Int::class.java, withoutDetails))

        val points =
            jdbc.queryForMap(
                """
                SELECT ST_NPoints(geom_z13) AS z13, ST_NPoints(geom_z14) AS z14, ST_NPoints(geom_z15) AS z15,
                       ST_NPoints(geom_z16) AS z16, ST_AsEWKT(geom_z16) AS full_detail
                FROM heatmap_route WHERE activity_id = ?
                """.trimIndent(),
                activity,
            )
        assertEquals(fullDetailBefore, points["full_detail"], "the full-detail band carries on, unchanged, as z16")
        val (z13, z14, z15, z16) = listOf("z13", "z14", "z15", "z16").map { (points.getValue(it) as Number).toInt() }
        assertEquals(2, z13, "z13 straightens the zigzag")
        assertTrue(z13 < z14 && z14 <= z15 && z15 <= z16, "each band is at least as detailed as the one before: $points")

        val jdbcClient = JdbcClient.create(dataSource)
        val renderer = RasterDensityTileRenderer(jdbcClient, FullHeat(jdbcClient))
        val revision = HeatmapRepository(jdbcClient).revision(athlete)
        listOf(13, 16).forEach { z ->
            val tile = Tile.containing(start.lon, start.lat, z)
            val raster = RasterTile.decode(renderer.render(HeatmapScope(athlete), revision, TileCoordinate(tile.z, tile.x, tile.y)).bytes)
            listOf(50.0, 150.0).forEach { metres ->
                assertTrue(raster.isLitAt(tile.pixelOf(east(start, metres))), "z$z tile unlit $metres m along the route")
            }
        }
    }
}
