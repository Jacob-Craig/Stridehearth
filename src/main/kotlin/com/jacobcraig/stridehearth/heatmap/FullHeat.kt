package com.jacobcraig.stridehearth.heatmap

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * The **Full Heat** (ADR-0008): for a zoom band, the ~95th-percentile number of distinct Activities on a snapped
 * route segment across the athlete's whole filtered Heatmap, never the tile in view. Neighbouring tiles therefore
 * agree on what "hot" means, and a lone Activity in a second city stays dim however far in the map is zoomed.
 *
 * Each heatmap route row stores its distinct snapped segments per band, worked out when the row is written
 * (`heatmap_route_segments`, V15), so counting is a GROUP BY over plain numbers. The segments are stored in two
 * halves: a fixed ~1-in-[SAMPLE_RATE] sample, chosen by segment so its counts are exact, and the rest. A Heatmap
 * whose routes pass along fewer than [exactPassesLimit] segments in all is counted on both halves, exactly; a
 * larger one on the sample alone, which estimates the percentile closely and keeps the count fast. Every band
 * from zoom 13 up reuses the z10–12 value rather than storing segments of more detailed routes.
 *
 * **Why a cache, given there is no server-side tile cache?** Every non-empty tile needs the Full Heat, and counting
 * it reads the athlete's whole filtered Heatmap, so recounting it per tile would waste the tile budget. Unlike
 * tiles, it is one number per athlete, revision, Heatmap Filter and zoom band, so a small bounded map holds it.
 * The key carries the revision, so the cache is correct by construction: new routes mean a new revision and so a
 * new key, and stale entries simply age out. Tiles themselves are still not cached on the server (see
 * [HeatmapTileRenderer]).
 */
@Component
class FullHeat(
    private val jdbcClient: JdbcClient,
    private val exactPassesLimit: Long,
) {
    @Autowired
    constructor(jdbcClient: JdbcClient) : this(jdbcClient, EXACT_PASSES_LIMIT)

    private data class Key(
        val scope: HeatmapScope,
        val revision: HeatmapRevision,
        val band: ZoomBand,
    )

    /** A 95th-percentile count of Activities per segment, and how many route-segment passes it was counted from. */
    private data class Measure(
        val fullHeat: Long,
        val passes: Long,
    )

    // Entries per key are lazy, so the concurrent first tiles of a view share one count rather than each running it.
    private val fullHeatByKey =
        object : LinkedHashMap<Key, Lazy<Double>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Lazy<Double>>) = size > CACHE_ENTRIES
        }

    /**
     * The Full Heat of the Heatmap [scope] names, at [revision], for tiles at [zoom], in distinct Activities. Pass
     * the revision read in the tile's snapshot, so the Full Heat is the one of the routes the tile is drawn from.
     */
    fun of(
        scope: HeatmapScope,
        revision: HeatmapRevision,
        zoom: Int,
    ): Double {
        val key = Key(scope, revision, measuredBand(ZoomBand.of(zoom)))
        val entry = synchronized(fullHeatByKey) { fullHeatByKey.getOrPut(key) { lazy { count(key) } } }
        return entry.value
    }

    private fun count(key: Key): Double {
        val segments = "heat_segments_" + key.band.column.removePrefix("geom_")
        val sampled = measure(key.scope, "${segments}_sampled")
        val measured =
            if (sampled.passes * SAMPLE_RATE < exactPassesLimit) {
                measure(key.scope, "${segments}_sampled || ${segments}_rest")
            } else {
                sampled
            }
        return measured.fullHeat.toDouble().coerceAtLeast(MIN_FULL_HEAT)
    }

    /** The Full Heat of [scope] counted on the stored segments [segments] (SQL naming a `bigint[]` per route). */
    private fun measure(
        scope: HeatmapScope,
        segments: String,
    ): Measure =
        jdbcClient
            .sql(
                """
                WITH counted AS (
                    -- A route stores each of its segments once, so rows per segment are distinct Activities.
                    SELECT count(*) AS activities
                    FROM heatmap_route, unnest($segments) AS segment
                    WHERE $MATCHES_SCOPE
                    GROUP BY segment
                )
                SELECT coalesce(percentile_disc(:percentile) WITHIN GROUP (ORDER BY activities), 0) AS full_heat,
                       coalesce(sum(activities), 0) AS passes
                FROM counted
                """.trimIndent(),
            ).param("percentile", PERCENTILE)
            .bindScope(scope)
            .query { rs, _ -> Measure(rs.getLong("full_heat"), rs.getLong("passes")) }
            .single()

    companion object {
        private const val PERCENTILE = 0.95

        /** One in this many segments is sampled: `hashint8extended(segment, 0) & 7 = 0` in V15. */
        private const val SAMPLE_RATE = 8

        /**
         * Below this many route-segment passes (about 1,500 10 km runs at z10–12), the Full Heat is counted
         * exactly, in about 100 ms at most; a sample of a small Heatmap holds too few segments to estimate it well.
         * Above it, the sample's count takes about 70 ms at 10,000 Activities.
         */
        private const val EXACT_PASSES_LIMIT = 300_000L

        /**
         * The least Full Heat, so a Heatmap whose routes barely overlap does not show a pair of passes white-hot
         * (ADR-0008). It also keeps the Full Heat above one Activity, as the log scale needs.
         */
        private const val MIN_FULL_HEAT = 4.0

        /** Athletes x revisions x filters x bands kept; each entry is a few dozen bytes. */
        private const val CACHE_ENTRIES = 2_000

        /** Every band from zoom 13 up reuses the z10–12 Full Heat rather than storing segments of more detailed routes. */
        private fun measuredBand(band: ZoomBand): ZoomBand = if (band > ZoomBand.Z10) ZoomBand.Z10 else band
    }
}
