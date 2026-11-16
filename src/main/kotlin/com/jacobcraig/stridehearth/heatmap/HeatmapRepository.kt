package com.jacobcraig.stridehearth.heatmap

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An athlete's heatmap revision: the pair (number of heatmap route rows, highest stamp). Every write to a heatmap
 * route row takes a fresh stamp from a sequence, so any insert, edit or delete changes the pair (ADR-0007).
 * Rendered as an opaque token.
 */
data class HeatmapRevision(
    val routes: Long,
    val highestStamp: Long,
) {
    val token: String get() = "$routes-$highestStamp"
}

/** Bounds in degrees (EPSG:4326). */
data class GeoBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

/**
 * SQL that keeps the `heatmap_route` rows of a [HeatmapScope]: the athlete's, that the **Heatmap Filter** matches.
 * Bind its parameters with [bindScope].
 */
internal const val MATCHES_SCOPE =
    "user_id = :athleteId" +
        " AND (CAST(:from AS timestamptz) IS NULL OR started_at >= CAST(:from AS timestamptz))" +
        " AND (CAST(:to AS timestamptz) IS NULL OR started_at < CAST(:to AS timestamptz))" +
        " AND (:allSports OR sport = ANY(CAST(:sports AS text[])))"

internal fun JdbcClient.StatementSpec.bindScope(scope: HeatmapScope): JdbcClient.StatementSpec {
    val filter = scope.filter
    return param("athleteId", scope.athleteId)
        .param("from", filter.from?.atOffset(ZoneOffset.UTC))
        .param("to", filter.to?.atOffset(ZoneOffset.UTC))
        .param("allSports", filter.sports == null)
        .param("sports", (filter.sports ?: emptySet()).map { it.name }.toTypedArray())
}

/** Reads of the athlete's heatmap routes that are not tiles. */
@Repository
class HeatmapRepository(
    private val jdbcClient: JdbcClient,
) {
    /** Read from the (user_id, stamp) index alone. */
    fun revision(athleteId: Long): HeatmapRevision =
        jdbcClient
            .sql("SELECT count(*) AS routes, coalesce(max(stamp), 0) AS highest FROM heatmap_route WHERE user_id = :athleteId")
            .param("athleteId", athleteId)
            .query { rs, _ -> HeatmapRevision(rs.getLong("routes"), rs.getLong("highest")) }
            .single()

    /**
     * The bounds of the busiest area of the routes in [scope], or `null` when it holds none.
     *
     * Each route falls in a [STARTING_VIEW_CELL] grid cell by the centre of its bounding box. An area is a cell and
     * its eight neighbours ([NEIGHBOURHOOD] cell each way), so a city straddling cell edges counts whole. The busiest
     * area holds the most routes, the most recently used winning a tie, and the bounds fit every route in it. Routes elsewhere, such as a few
     * Activities on another continent, are left out. Cells are Web Mercator metres, so they shrink on the ground
     * towards the poles (about 16 km at 50 degrees north, 8 km at 70).
     */
    fun bounds(scope: HeatmapScope): GeoBounds? =
        jdbcClient
            .sql(
                """
                WITH matched AS (
                    SELECT geom_z16, started_at,
                           floor((ST_XMin(geom_z16) + ST_XMax(geom_z16)) / 2 / :cell) AS cx,
                           floor((ST_YMin(geom_z16) + ST_YMax(geom_z16)) / 2 / :cell) AS cy
                    FROM heatmap_route
                    WHERE $MATCHES_SCOPE
                ),
                cells AS (
                    SELECT cx, cy, count(*) AS routes, max(started_at) AS latest
                    FROM matched
                    GROUP BY cx, cy
                ),
                busiest AS (
                    SELECT c.cx, c.cy
                    FROM cells c
                    JOIN cells n ON abs(n.cx - c.cx) <= :reach AND abs(n.cy - c.cy) <= :reach
                    GROUP BY c.cx, c.cy
                    ORDER BY sum(n.routes) DESC, max(n.latest) DESC
                    LIMIT 1
                ),
                extent AS (
                    SELECT ST_Transform(ST_SetSRID(ST_Extent(m.geom_z16)::geometry, 3857), 4326) AS box
                    FROM matched m
                    JOIN busiest b ON abs(m.cx - b.cx) <= :reach AND abs(m.cy - b.cy) <= :reach
                )
                SELECT ST_XMin(box) AS west, ST_YMin(box) AS south, ST_XMax(box) AS east, ST_YMax(box) AS north
                FROM extent
                WHERE box IS NOT NULL
                """.trimIndent(),
            ).param("cell", STARTING_VIEW_CELL)
            .param("reach", NEIGHBOURHOOD)
            .bindScope(scope)
            .query { rs, _ -> GeoBounds(rs.getDouble("west"), rs.getDouble("south"), rs.getDouble("east"), rs.getDouble("north")) }
            .optional()
            .orElse(null)

    /** The years, in [timeZone], in which the athlete started an Activity with a route, newest first. */
    fun years(
        athleteId: Long,
        timeZone: ZoneId,
    ): List<Int> =
        jdbcClient
            .sql(
                """
                SELECT DISTINCT CAST(extract(YEAR FROM started_at AT TIME ZONE :timeZone) AS integer) AS year
                FROM heatmap_route
                WHERE user_id = :athleteId
                ORDER BY year DESC
                """.trimIndent(),
            ).param("athleteId", athleteId)
            .param("timeZone", timeZone.id)
            .query { rs, _ -> rs.getInt("year") }
            .list()

    private companion object {
        /**
         * The starting view's grid cell, in Web Mercator metres: about 25 km on the ground at the equator and 16 km
         * at 50 degrees north, roughly a city. The busiest area is up to three cells across.
         */
        const val STARTING_VIEW_CELL = 25_000.0

        /**
         * How many cells each way an area reaches from its centre cell: 1 makes the 3×3 block. Both ranking the areas
         * and fitting the busiest one's bounds use it, so they always agree on what an area is.
         */
        const val NEIGHBOURHOOD = 1
    }
}
