package com.jacobcraig.stridehearth.heatmap.performance

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The smoke version of the Heatmap performance harness (#110), run by `check`: about 300 synthetic Activities and
 * loose limits, to catch a clear query-plan or index regression such as a tile query that can no longer use the
 * spatial index. `./gradlew performanceTest` runs the full harness at about 10k Activities.
 */
class HeatmapPerformanceSmokeTest {
    companion object {
        private lateinit var bench: HeatmapBench
        private lateinit var seeding: Seeding

        @JvmStatic
        @BeforeAll
        fun seed() {
            bench = HeatmapBench.start()
            seeding = bench.seed(SyntheticTraining(seed = 110).activities(ACTIVITIES))
        }

        @JvmStatic
        @AfterAll
        fun stop() = bench.close()

        private const val ACTIVITIES = 300
        private val ZOOMS = listOf(3, 8, 14)
    }

    @Test
    fun `tile queries at z3, z8 and z14 can find routes through the spatial index`() {
        // At 300 Activities a sequential scan may be the cheaper plan, so this asks whether the planner *can* use
        // the index: the thing a dropped index or an unindexable rewrite of the tile query breaks.
        ZOOMS.forEach { z ->
            val plan = bench.explainTileQuery(bench.busiestTiles(z, 1).single(), allowSequentialScan = false)
            assertTrue("idx_heatmap_route_geom" in plan, "z$z tile query no longer uses the spatial index:\n$plan")
        }
    }

    @Test
    fun `the busiest z14 tile query chooses the spatial index over a sequential scan`() {
        // A z14 tile covers a small share of routes, so even at 300 Activities the planner prefers the index when free
        // to pick: this catches a plan that still *can* use the index but no longer does.
        val plan = bench.explainTileQuery(bench.busiestTiles(14, 1).single())
        assertTrue("Bitmap Index Scan on idx_heatmap_route_geom" in plan, "z14 tile query no longer chooses the spatial index:\n$plan")
    }

    @Test
    fun `busy tiles render within loose limits`() {
        ZOOMS.forEach { z ->
            val busy = bench.busiestTiles(z, 5).filter { bench.activitiesThrough(it) >= 10 }
            val samples = bench.measure(bench.renderer, busy)
            assertTrue(samples.all { it.routesDrawn }, "z$z: a busy tile came back empty")
            assertTrue(samples.p95 { it.wallMillis } < LOOSE_TILE_MILLIS, "z$z p95 ${samples.p95 { it.wallMillis }} ms")
            assertTrue(samples.maxOf { it.pngBytes } < LOOSE_TILE_BYTES, "z$z largest tile ${samples.maxOf { it.pngBytes }} bytes")
        }
    }

    @Test
    fun `writing a heatmap route row stays within a loose per-Activity budget`() {
        val write = seeding.writeMillis
        assertEquals(ACTIVITIES, write.size, "heatmap route writes timed")
        assertTrue(write.p95 { it } < LOOSE_WRITE_MILLIS, "p95 heatmap route write ${write.p95 { it }} ms")
    }
}

/** Loose limits: about ten times the targets, so only a clear regression trips them on a busy CI machine. */
private const val LOOSE_TILE_MILLIS = 3_000.0
private const val LOOSE_TILE_BYTES = 2_000_000
private const val LOOSE_WRITE_MILLIS = 50.0
