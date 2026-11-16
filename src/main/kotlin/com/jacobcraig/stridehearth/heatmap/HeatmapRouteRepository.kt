package com.jacobcraig.stridehearth.heatmap

import com.jacobcraig.stridehearth.system.ActivitySport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Writes of heatmap route rows outside the Activity Details upsert, which writes them on create, **Upgrade** and
 * **Backfill** (see `ActivityDetailsRepository.upsert`). Deletes need nothing here: the row cascades with its
 * Activity.
 */
@Repository
class HeatmapRouteRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * Recomputes the heatmap route rows of [activityIds] for their new [sport], after a single or batch sport edit.
     *
     * The GPS-jump threshold depends on the sport, so the row is derived again from the stored route with
     * `heatmap_route_geometries`, the same function every other write uses (ADR-0007). Each recomputed row takes
     * a fresh stamp, so the athlete's heatmap revision changes. A route that no longer survives the split loses
     * its row, and one that survives only now gains one.
     *
     * Call it after the Best Effort refresh, as with an Activity History Entry: inserting a row key-share locks
     * the Activity's row (ADR-0006).
     */
    fun recomputeForSport(
        activityIds: Collection<Long>,
        sport: ActivitySport,
    ) {
        if (activityIds.isEmpty()) return
        jdbcClient
            .sql(
                """
                WITH recomputed AS (
                    SELECT a.id AS activity_id, a.user_id, a.started_at, g.*
                    FROM activity_details d
                    JOIN activity a ON a.id = d.activity_id
                    CROSS JOIN LATERAL heatmap_route_geometries(d.route, :sport) AS g
                    WHERE d.activity_id = ANY(CAST(:activityIds AS bigint[]))
                ),
                written AS (
                    INSERT INTO heatmap_route (
                        activity_id, user_id, sport, started_at,
                        geom_z0, geom_z6, geom_z10, geom_z13, geom_z14, geom_z15, geom_z16
                    )
                    SELECT activity_id, user_id, :sport, started_at,
                           geom_z0, geom_z6, geom_z10, geom_z13, geom_z14, geom_z15, geom_z16
                    FROM recomputed
                    ON CONFLICT (activity_id) DO UPDATE SET
                        sport = EXCLUDED.sport,
                        stamp = nextval('heatmap_route_stamp_seq'),
                        geom_z0 = EXCLUDED.geom_z0,
                        geom_z6 = EXCLUDED.geom_z6,
                        geom_z10 = EXCLUDED.geom_z10,
                        geom_z13 = EXCLUDED.geom_z13,
                        geom_z14 = EXCLUDED.geom_z14,
                        geom_z15 = EXCLUDED.geom_z15,
                        geom_z16 = EXCLUDED.geom_z16
                )
                DELETE FROM heatmap_route h
                WHERE h.activity_id = ANY(CAST(:activityIds AS bigint[]))
                  AND NOT EXISTS (SELECT 1 FROM recomputed r WHERE r.activity_id = h.activity_id)
                """.trimIndent(),
            ).param("sport", sport.name)
            .param("activityIds", activityIds.toTypedArray())
            .update()
    }
}
