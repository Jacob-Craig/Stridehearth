package com.jacobcraig.stridehearth.heatmap

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional

/**
 * Heatmap route rows belong to an existing athlete, as every other per-athlete table does. No API deletes an
 * athlete or writes a row directly, so this holds at the schema.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class HeatmapRouteSchemaIntegrationTest {
    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Test
    fun `a heatmap route row cannot name an athlete who does not exist`() {
        val athlete =
            jdbcClient
                .sql("INSERT INTO app_user (email, password_hash, username) VALUES ('fk@example.com', 'x', 'fk') RETURNING id")
                .query(Long::class.java)
                .single()
        val activity =
            jdbcClient
                .sql(
                    """
                    INSERT INTO activity (title, started_at, distance, time_taken_seconds, user_id, source, sport)
                    VALUES ('Run', now(), 1.0, 300, :athlete, 'MANUAL', 'RUN') RETURNING id
                    """.trimIndent(),
                ).param("athlete", athlete)
                .query(Long::class.java)
                .single()

        assertThrows<DataIntegrityViolationException> {
            jdbcClient
                .sql(
                    """
                    INSERT INTO heatmap_route (activity_id, user_id, sport, started_at, geom_z0, geom_z6, geom_z10, geom_z13, geom_z14, geom_z15, geom_z16)
                    SELECT :activity, :missingAthlete, 'RUN', now(), g.geom_z0, g.geom_z6, g.geom_z10, g.geom_z13, g.geom_z14, g.geom_z15, g.geom_z16
                    FROM heatmap_route_geometries(ST_GeomFromText('LINESTRING(-0.165 51.507, -0.1636 51.507)', 4326), 'RUN') g
                    """.trimIndent(),
                ).param("activity", activity)
                .param("missingAthlete", athlete + 1_000_000)
                .update()
        }
    }
}
