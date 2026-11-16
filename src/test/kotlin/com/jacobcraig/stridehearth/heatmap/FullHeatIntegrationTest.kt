package com.jacobcraig.stridehearth.heatmap

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

/**
 * The **Full Heat** counted from the segments each heatmap route row stores (V15). Routes are written straight
 * into `heatmap_route` through `heatmap_route_geometries`, as the Activity Details upsert does, so the stored
 * segments are the ones the schema generates.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class FullHeatIntegrationTest {
    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var fullHeat: FullHeat

    @Autowired
    private lateinit var heatmapRepository: HeatmapRepository

    private var athleteId = 0L

    @BeforeEach
    fun setUp() {
        athleteId =
            jdbcClient
                .sql("INSERT INTO app_user (email, password_hash, username) VALUES ('fh@example.com', 'x', 'fh') RETURNING id")
                .query(Long::class.java)
                .single()
    }

    @Test
    fun `passes in either direction along a road count as Activities on the same segments`() {
        repeat(6) { writeRoute(ROAD) }
        repeat(6) { writeRoute("ST_Reverse($ROAD)") }

        ZOOMS.forEach { zoom -> assertEquals(12.0, fullHeatAt(zoom), "zoom $zoom") }
    }

    @Test
    fun `an Activity that runs along a road and back counts once`() {
        repeat(8) { writeRoute("ST_MakeLine($ROAD, ST_Reverse($ROAD))") }

        ZOOMS.forEach { zoom -> assertEquals(8.0, fullHeatAt(zoom), "zoom $zoom") }
    }

    @Test
    fun `a Heatmap whose routes barely overlap has the least Full Heat`() {
        writeRoute(ROAD)

        ZOOMS.forEach { zoom -> assertEquals(4.0, fullHeatAt(zoom), "zoom $zoom") }
    }

    @Test
    fun `only the routes the Heatmap Filter keeps are counted`() {
        repeat(6) { writeRoute(ROAD, ActivitySport.RUN) }
        repeat(10) { writeRoute(ROAD, ActivitySport.RIDE) }

        val runs = HeatmapScope(athleteId, HeatmapFilter(sports = setOf(ActivitySport.RUN)))
        assertEquals(6.0, fullHeat.of(runs, heatmapRepository.revision(athleteId), 12))
        assertEquals(16.0, fullHeatAt(12))
    }

    @Test
    fun `a Heatmap measured on its sample of segments agrees with the exact count`() {
        // A busy road and a quiet one: the 95th percentile lands on the busy road's count either way.
        repeat(20) { writeRoute(ROAD) }
        repeat(2) { writeRoute(QUIET_ROAD) }
        val alwaysSampled = FullHeat(jdbcClient, exactPassesLimit = 0)
        val revision = heatmapRepository.revision(athleteId)

        listOf(4, 8, 12).forEach { zoom ->
            val sampled = alwaysSampled.of(HeatmapScope(athleteId), revision, zoom)
            assertEquals(fullHeatAt(zoom), sampled, "zoom $zoom")
        }
    }

    @Test
    fun `an edited route is counted where it now lies`() {
        repeat(6) { writeRoute(ROAD) }
        val moved = writeRoute(ROAD)
        assertEquals(7.0, fullHeatAt(12))

        // As the Activity Details upsert writes it: new geometries and a fresh stamp, so a new revision.
        jdbcClient
            .sql(
                """
                UPDATE heatmap_route h SET stamp = nextval('heatmap_route_stamp_seq'), geom_z0 = g.geom_z0, geom_z6 = g.geom_z6, geom_z10 = g.geom_z10, geom_z13 = g.geom_z13, geom_z14 = g.geom_z14, geom_z15 = g.geom_z15, geom_z16 = g.geom_z16
                FROM heatmap_route_geometries($QUIET_ROAD, 'RUN') g
                WHERE h.activity_id = :activity
                """.trimIndent(),
            ).param("activity", moved)
            .update()

        assertEquals(6.0, fullHeatAt(12))
    }

    private fun fullHeatAt(zoom: Int): Double = fullHeat.of(HeatmapScope(athleteId), heatmapRepository.revision(athleteId), zoom)

    /** Writes an Activity whose route is the EPSG:4326 line [route] (SQL), with its heatmap route row. */
    private fun writeRoute(
        route: String,
        sport: ActivitySport = ActivitySport.RUN,
    ): Long {
        val activity =
            jdbcClient
                .sql(
                    """
                    INSERT INTO activity (title, started_at, distance, time_taken_seconds, user_id, source, sport)
                    VALUES ('Route', now(), 1.0, 300, :athlete, 'MANUAL', :sport) RETURNING id
                    """.trimIndent(),
                ).param("athlete", athleteId)
                .param("sport", sport.name)
                .query(Long::class.java)
                .single()
        jdbcClient
            .sql(
                """
                INSERT INTO heatmap_route (activity_id, user_id, sport, started_at, geom_z0, geom_z6, geom_z10, geom_z13, geom_z14, geom_z15, geom_z16)
                SELECT :activity, :athlete, :sport, now(), g.geom_z0, g.geom_z6, g.geom_z10, g.geom_z13, g.geom_z14, g.geom_z15, g.geom_z16
                FROM heatmap_route_geometries($route, :sport) g
                """.trimIndent(),
            ).param("activity", activity)
            .param("athlete", athleteId)
            .param("sport", sport.name)
            .update()
        return activity
    }

    private companion object {
        /** One zoom from each band the Full Heat is measured on, and one from z13 and above, which reuses z10–12. */
        val ZOOMS = listOf(4, 8, 12, 15)

        /** About 40 km east across London and beyond, with a point every 50 m so no step is a GPS jump. */
        const val ROAD = "ST_Segmentize(ST_GeomFromText('LINESTRING(-0.45 51.507, 0.13 51.507)', 4326)::geography, 50)::geometry"

        /** About 20 km east, 30 km south of [ROAD]: no segment of it is shared with [ROAD] at any band. */
        const val QUIET_ROAD = "ST_Segmentize(ST_GeomFromText('LINESTRING(-0.45 51.23, -0.16 51.23)', 4326)::geography, 50)::geometry"
    }
}
