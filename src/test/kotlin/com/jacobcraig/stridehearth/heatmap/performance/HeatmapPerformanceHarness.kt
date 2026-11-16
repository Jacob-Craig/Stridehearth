package com.jacobcraig.stridehearth.heatmap.performance

import com.jacobcraig.stridehearth.POSTGIS_IMAGE
import com.jacobcraig.stridehearth.heatmap.HeatmapTileRenderer
import com.jacobcraig.stridehearth.heatmap.RasterBand
import com.jacobcraig.stridehearth.heatmap.RasterSettings
import com.jacobcraig.stridehearth.heatmap.Tile
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The Heatmap performance harness (#110), run by `./gradlew performanceTest` and never by `check`.
 *
 * It seeds about 10k synthetic Activities (about 20M route points) for one athlete into Testcontainers PostGIS,
 * then measures tiles through the [HeatmapTileRenderer] strategy against the targets of #103: under 300 ms at p95
 * for a cold tile at any zoom, under about 500 KB per tile, and about 5 ms or less per Activity for the heatmap
 * route write on import. It compares variants of the raster density renderer, prints a results table with
 * `EXPLAIN ANALYZE` evidence, and writes it, with sample tiles of each variant to check by eye, to
 * `build/reports/heatmap-performance/`.
 *
 * `-PheatmapActivities=N` seeds N Activities instead of 10,000.
 */
@Tag("performance")
class HeatmapPerformanceHarness {
    private val results =
        File("build/reports/heatmap-performance/results.md").apply {
            parentFile.mkdirs()
            writeText("")
        }
    private val reportDir = results.parentFile

    @Test
    fun `Heatmap tiles and heatmap route writes meet their targets at about 10k Activities`() {
        val activities = System.getProperty("heatmap.performance.activities")?.toInt() ?: 10_000
        val started = System.nanoTime()
        HeatmapBench.start().use { bench ->
            val seeding = bench.seed(SyntheticTraining(seed = 110).activities(activities), timedWrites = 1_000, threads = 6)
            line("# Heatmap performance harness (#110)")
            line()
            line(
                String.format(
                    Locale.ROOT,
                    "Seeded %d Activities, %d route points, in %.0f s. Database: %s in Testcontainers; JVM: %s, %d processors.",
                    seeding.activities,
                    seeding.points,
                    seeding.elapsedSeconds,
                    POSTGIS_IMAGE.asCanonicalNameString(),
                    System.getProperty("java.version"),
                    Runtime.getRuntime().availableProcessors(),
                ),
            )
            line(bench.queryOne(TABLE_SIZE) { rs -> "heatmap_route: ${rs.getString(1)} for ${rs.getLong(2)} rows." })

            routeWrites(bench, seeding)
            // Fail fast: a variant that claims the shipped look must draw what ships, or its timings compare unlike work.
            assertTrue(
                sameLookVariantsMatchShipped(bench),
                "A variant that claims the shipped look draws differently: fix its RasterSettings overrides before comparing",
            )
            coldTiles(bench)
            coldBufferTiles(bench)
            explainTileQueries(bench)
            concurrentLoad(bench)
            variants(bench)
            jumpThresholds(bench)

            line()
            line(String.format(Locale.ROOT, "Harness ran in %.0f s.", (System.nanoTime() - started) / 1e9))
        }
    }

    private fun routeWrites(
        bench: HeatmapBench,
        seeding: Seeding,
    ) {
        val atFull = bench.rewriteHeatmapRoutes(300)
        section("Heatmap route write per Activity (target ~5 ms)")
        line(
            "The write is the heatmap route part of `ActivityDetailsRepository.upsert`, sharing its SQL " +
                "(`HEATMAP_ROUTE_OF_DETAILS` and `UPSERT_HEATMAP_ROUTE`), run on its own for an Activity whose details are stored.",
        )
        line()
        line("| When | Writes | p50 ms | p95 ms | max ms | mean ms | Target |")
        line("|---|---:|---:|---:|---:|---:|---|")
        writeRow("Import of the first ${seeding.writeMillis.size} Activities, one at a time", seeding.writeMillis)
        writeRow("Rewrite (Upgrade) of 300 random rows at full size", atFull)
    }

    private fun writeRow(
        label: String,
        millis: List<Double>,
    ) {
        val mean = millis.average()
        line(
            "| $label | ${millis.size} | ${percentileCells(millis)} | ${ms(mean)} | " +
                "${verdict(mean <= WRITE_TARGET_MILLIS)} mean |",
        )
    }

    /**
     * Whether the variants that claim to draw exactly what ships (the shipped settings, and the chunked mask sweep) do.
     * Variants share the shipped renderer's code and override only its settings, so this checks those overrides, not
     * a copy. Pixels may differ by a step or two: a parallel scan returns rows in a varying order, and the
     * density adds up in that order, so even the shipped renderer is not byte-for-byte repeatable.
     */
    private fun sameLookVariantsMatchShipped(bench: HeatmapBench): Boolean {
        val tiles = HeatmapBench.ZOOMS.flatMap { bench.busiestTiles(it, 2) }
        section("Variant fidelity")
        line("Largest difference in any colour or alpha channel from the shipped renderer's tile, over ${tiles.size} tiles (0-255):")
        line()
        line("| Variant | Max channel difference | Matches |")
        line("|---|---:|---|")
        // Every variant gets its row, even after one fails.
        val matches =
            listOf(RasterVariant.SHIPPED, CHUNKED_SWEEP).map { variant ->
                val renderer = variant.renderer(bench.jdbcClient)
                val difference =
                    tiles.maxOf { tile ->
                        channelDifference(render(bench, bench.renderer, tile), render(bench, renderer, tile))
                    }
                val match = difference <= FIDELITY_TOLERANCE
                line("| ${variant.name} | $difference | ${if (match) "yes" else "**NO: it changes the look, comparisons unfair**"} |")
                match
            }
        return matches.all { it }
    }

    private fun channelDifference(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        val first = ImageIO.read(ByteArrayInputStream(a))
        val second = ImageIO.read(ByteArrayInputStream(b))
        if (first.width != second.width || first.height != second.height) return 255
        var worst = 0
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                val p = first.getRGB(x, y)
                val q = second.getRGB(x, y)
                for (shift in listOf(0, 8, 16, 24)) worst = maxOf(worst, abs(((p ushr shift) and 0xff) - ((q ushr shift) and 0xff)))
            }
        }
        return worst
    }

    private fun render(
        bench: HeatmapBench,
        renderer: HeatmapTileRenderer,
        tile: Tile,
    ): ByteArray = bench.render(renderer, tile).bytes

    private fun coldTiles(bench: HeatmapBench) {
        section("Cold tiles, shipped renderer, one at a time (target p95 < 300 ms, < ~500 KB)")
        line(
            "Each tile is rendered once here: there is no tile cache, so every render is cold for the renderer. PostgreSQL is " +
                "not cold: its buffers and the OS cache are warm from seeding, so DB times are optimistic; the next section " +
                "measures the busiest tiles with cold buffers. DB ms is wall time in statements; JVM CPU ms is the rendering " +
                "thread's CPU time; DB CPU ms is PostgreSQL's CPU time from its container's cgroup, over the zoom's tiles, " +
                "per tile. " +
                "A spread of $SPREAD tiles per zoom: the busiest half and a random half of the tiles with routes. " +
                "z3, z8 and z14 are the ticket's zooms; z6 and z10 are the first zooms of " +
                "the z6-9 and z10-12 bands, where a band's geometry is most detailed for the pixels and tiles cost most; " +
                "from z13 each zoom is its own band.",
        )
        line()
        line(
            "| Zoom | Tiles | p50 ms | p95 ms | max ms | p95 DB ms | p95 JVM CPU ms | mean DB CPU ms | p95 alloc MB | " +
                "p50 KB | max KB | Lit tiles | Target |",
        )
        line("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
        val statementMillis = mutableMapOf<String, MutableList<Double>>()
        HeatmapBench.ZOOMS.forEach { z ->
            val tiles = bench.spreadOfTiles(z, SPREAD)
            val databaseCpuBefore = bench.databaseCpuMicros()
            val samples = bench.measure(bench.renderer, tiles)
            val databaseCpuMillis = databaseCpuBefore?.let { before -> bench.databaseCpuMicros()?.let { (it - before) / 1e3 / tiles.size } }
            tileRow("z$z", samples, databaseCpuMillis)
            samples.forEach { sample ->
                sample.statements.forEach { statementMillis.getOrPut(label(it.sql)) { mutableListOf() } += it.millis }
            }
            val unlit = samples.filter { !it.routesDrawn }
            if (unlit.isNotEmpty()) {
                notes += "z$z: ${unlit.size} tiles with routes through them rendered empty: " +
                    unlit.joinToString { "${it.tile.x}/${it.tile.y} (${bench.activitiesThrough(it.tile)} Activities)" }
            }
        }
        flushNotes()
        section("Renderer statements over those tiles")
        line(
            "The Full Heat (#107) is counted from each route's stored segments once per revision, filter and zoom band, " +
                "then cached, so it runs only on the first tile of each band; its runs here are those first tiles.",
        )
        line()
        line("| Statement | Runs | p50 ms | p95 ms | max ms |")
        line("|---|---:|---:|---:|---:|")
        statementMillis.forEach { (label, millis) -> line("| $label | ${millis.size} | ${percentileCells(millis)} |") }
    }

    private fun tileRow(
        label: String,
        samples: List<TileSample>,
        databaseCpuMillis: Double?,
    ) {
        val p95 = samples.p95 { it.wallMillis }
        val maxBytes = samples.maxOf { it.pngBytes }
        line(
            "| $label | ${samples.size} | ${percentileCells(samples.map { it.wallMillis })} | " +
                "${ms(
                    samples.p95 { it.databaseMillis },
                )} | ${ms(samples.p95 { it.cpuMillis })} | ${databaseCpuMillis?.let(::ms) ?: "n/a"} | " +
                "${mb(samples.p95 { it.allocatedBytes.toDouble() })} | ${kb(samples.p50 { it.pngBytes.toDouble() })} | " +
                "${kb(maxBytes.toDouble())} | ${samples.count { it.routesDrawn }} | " +
                "${verdict(p95 < TILE_TARGET_MILLIS && maxBytes < TILE_TARGET_BYTES)} |",
        )
    }

    /**
     * The busiest tiles with PostgreSQL's shared buffers cold: before each tile, `heatmap_route`, its TOAST and its
     * indexes are pushed out of shared buffers, so every page the tile reads comes from outside them.
     */
    private fun coldBufferTiles(bench: HeatmapBench) {
        section("Busiest tiles with cold PostgreSQL buffers, shipped renderer (target p95 < 300 ms)")
        line(
            "Before each tile, `heatmap_route` is evicted from PostgreSQL's shared buffers by reading an unrelated table twice " +
                "their size through an index. The OS page cache stays warm (a container can't drop it), so this is cold buffers, " +
                "not a cold disk. Buffers left is the most `heatmap_route` pages still in shared buffers before any of the tiles.",
        )
        line()
        line("| Zoom | Tiles | p50 ms | p95 ms | max ms | p95 DB ms | Buffers left | Target |")
        line("|---|---:|---:|---:|---:|---:|---:|---|")
        listOf(3, 8, 13, 14).forEach { z ->
            var buffersLeft = 0
            val samples =
                bench.busiestTiles(z, COLD_BUFFER_TILES).map { tile ->
                    buffersLeft = maxOf(buffersLeft, bench.evictHeatmapFromSharedBuffers())
                    bench.measure(bench.renderer, listOf(tile)).single()
                }
            val p95 = samples.p95 { it.wallMillis }
            line(
                "| z$z | ${samples.size} | ${percentileCells(
                    samples.map { it.wallMillis },
                )} | ${ms(samples.p95 { it.databaseMillis })} | " +
                    "$buffersLeft | ${verdict(p95 < TILE_TARGET_MILLIS)} |",
            )
        }
    }

    private fun explainTileQueries(bench: HeatmapBench) {
        section("EXPLAIN ANALYZE of the busiest tile's queries")
        line(
            "Each tile is rendered by a fresh shipped renderer, so its Full Heat cache is empty and the Full Heat statement " +
                "runs and is explained too; the shared renderer cached it long before this section.",
        )
        listOf(3, 8, 13, 14).forEach { z ->
            val tile = bench.busiestTiles(z, 1).single()
            val statements = bench.statementsFor(RasterVariant.SHIPPED.renderer(bench.jdbcClient), tile)
            line()
            line("### z$z tile ${tile.x}/${tile.y} (${bench.activitiesThrough(tile)} Activities pass through it)")
            statements.forEach { statement ->
                line()
                line("${label(statement.sql)}:")
                line()
                line("```")
                line(bench.explain(statement))
                line("```")
            }
        }
    }

    private fun concurrentLoad(bench: HeatmapBench) {
        section("Concurrent load, shipped renderer (several tiles at once, as a browser requests them)")
        val tiles = listOf(3, 8, 13, 14).flatMap { bench.busiestTiles(it, 12) }
        line(
            "The ${tiles.size} busiest tiles at z3, z8, z13 and z14, rendered on a pool of threads. Peak heap is the whole test " +
                "JVM's heap in use, garbage included, sampled every 2 ms: an upper bound, not memory per tile. DB CPU s is " +
                "PostgreSQL's CPU time over the whole run, from its container's cgroup.",
        )
        line()
        line(
            "| Threads | Tiles | Wall s | Tiles/s | p50 ms | p95 ms | max ms | p95 JVM CPU ms | DB CPU s | p95 alloc MB | " +
                "Peak heap MB | Target |",
        )
        line("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
        listOf(1, 4, 8).forEach { threads ->
            val run = bench.measureConcurrently(bench.renderer, tiles, threads)
            val p95 = run.samples.p95 { it.wallMillis }
            line(
                "| $threads | ${tiles.size} | ${ms(run.wallMillis / 1000)} | ${ms(tiles.size / (run.wallMillis / 1000))} | " +
                    "${percentileCells(run.samples.map { it.wallMillis })} | ${ms(run.samples.p95 { it.cpuMillis })} | " +
                    "${run.databaseCpuMillis?.let { ms(it / 1000) } ?: "n/a"} | " +
                    "${mb(run.samples.p95 { it.allocatedBytes.toDouble() })} | ${mb(run.peakHeapBytes.toDouble())} | " +
                    "${verdict(p95 < TILE_TARGET_MILLIS)} |",
            )
        }
    }

    private fun variants(bench: HeatmapBench) {
        section("Variants through the renderer")
        line(
            "The $VARIANT_TILES busiest tiles per zoom, rendered by the shipped renderer with each `RasterVariant`'s settings " +
                "overriding the shipped `RasterSettings`. p95 ms per zoom, then the largest tile. Variants that change the look " +
                "write their busiest z8, z13 and z14 tiles to `build/reports/heatmap-performance/tiles/` to check by eye. " +
                "Coordinate precision is how finely PostgreSQL returns tile coordinates; supersampled drawing (drawing at 2x " +
                "and downsampling) and WebP encoding were not measured.",
        )
        line()
        line("| Variant | Look | " + HeatmapBench.ZOOMS.joinToString(" | ") { "z$it p95 ms" } + " | max KB |")
        line("|---|---|" + HeatmapBench.ZOOMS.joinToString("") { "---:|" } + "---:|")
        val tilesDir = File(reportDir, "tiles").apply { mkdirs() }
        VARIANTS.forEach { variant ->
            val renderer = variant.renderer(bench.jdbcClient)
            val byZoom = HeatmapBench.ZOOMS.map { z -> bench.measure(renderer, bench.busiestTiles(z, VARIANT_TILES)) }
            line(
                "| ${variant.name} | ${if (variant.changesLook) "changes" else "same"} | " +
                    byZoom.joinToString(" | ") { ms(it.p95 { s -> s.wallMillis }) } +
                    " | ${kb(byZoom.flatten().maxOf { it.pngBytes }.toDouble())} |",
            )
            if (variant.changesLook || variant == RasterVariant.SHIPPED) {
                listOf(8, 13, 14).forEach { z ->
                    val tile = bench.busiestTiles(z, 1).single()
                    File(tilesDir, "${variant.name}-z$z-${tile.x}-${tile.y}.png").writeBytes(render(bench, renderer, tile))
                }
            }
        }
    }

    private fun jumpThresholds(bench: HeatmapBench) {
        section("GPS-jump thresholds: heatmap route write cost and pieces")
        line(
            "The shipped `heatmap_route_geometries` with its foot and ride thresholds swapped, writing 300 random Activities " +
                "into a scratch copy of `heatmap_route`. Pieces are the lines a route is split into; about 1% of synthetic " +
                "Activities carry a 1.5 km GPS jump.",
        )
        line()
        line("| Foot / ride threshold | p50 ms | p95 ms | max ms | mean ms | Pieces per route | Stored KB per route |")
        line("|---|---:|---:|---:|---:|---:|---:|")
        val definition =
            bench.queryOne("SELECT pg_get_functiondef('heatmap_route_geometries(geometry,text)'::regprocedure)") { rs -> rs.getString(1) }
        check(SHIPPED_THRESHOLDS in definition) { "heatmap_route_geometries no longer holds '$SHIPPED_THRESHOLDS': update the harness" }
        bench.execute("CREATE TABLE heatmap_route_variant (LIKE heatmap_route INCLUDING ALL)")
        listOf(100 to 500, SHIPPED_FOOT_JUMP to SHIPPED_RIDE_JUMP, 400 to 2_000).forEach { (foot, ride) ->
            val function = "heatmap_route_geometries_${foot}_$ride"
            bench.execute(
                definition
                    .replace("public.heatmap_route_geometries(", "public.$function(")
                    .replace(SHIPPED_THRESHOLDS, "CASE sport WHEN 'RIDE' THEN $ride ELSE $foot END"),
            )
            bench.execute("TRUNCATE heatmap_route_variant")
            val millis = bench.rewriteHeatmapRoutes(300, variantWrite(function))
            val (pieces, kb) =
                bench.queryOne(
                    "SELECT avg(ST_NumGeometries(geom_z16)), avg(pg_column_size(geom_z0) + pg_column_size(geom_z6) + " +
                        "pg_column_size(geom_z10) + pg_column_size(geom_z13) + pg_column_size(geom_z14) + " +
                        "pg_column_size(geom_z15) + pg_column_size(geom_z16)) / 1024 FROM heatmap_route_variant",
                ) { rs -> rs.getDouble(1) to rs.getDouble(2) }
            val shipped = if (foot == SHIPPED_FOOT_JUMP) " (shipped)" else ""
            line(
                "| $foot m / $ride m$shipped | ${percentileCells(millis)} | ${ms(millis.average())} | " +
                    "${String.format(Locale.ROOT, "%.2f", pieces)} | ${String.format(Locale.ROOT, "%.1f", kb)} |",
            )
        }
    }

    /** The shipped heatmap route write, into the scratch `heatmap_route_variant` table through [function]. */
    private fun variantWrite(function: String): String {
        val write = HeatmapBench.WRITE_HEATMAP_ROUTE
        check(write.split(SHIPPED_TABLE).size == 2 && write.split(SHIPPED_FUNCTION).size == 2) {
            "The heatmap route write no longer names '$SHIPPED_TABLE' and '$SHIPPED_FUNCTION' once each: update the harness"
        }
        return write.replace(SHIPPED_TABLE, "INSERT INTO heatmap_route_variant (").replace(SHIPPED_FUNCTION, "$function(")
    }

    private val notes = mutableListOf<String>()

    private fun flushNotes() {
        if (notes.isEmpty()) return
        line()
        notes.forEach { line("- $it") }
        notes.clear()
    }

    private fun section(title: String) {
        line()
        line("## $title")
        line()
    }

    private fun line(text: String = "") {
        // Printed and written as it goes, so a run that dies part way still leaves what it measured.
        println(text)
        results.appendText("$text\n")
    }

    companion object {
        private const val TILE_TARGET_MILLIS = 300.0
        private const val TILE_TARGET_BYTES = 500 * 1024
        private const val WRITE_TARGET_MILLIS = 5.0
        private const val SPREAD = 40
        private const val FIDELITY_TOLERANCE = 2
        private const val VARIANT_TILES = 10
        private const val COLD_BUFFER_TILES = 5
        private const val SHIPPED_TABLE = "INSERT INTO heatmap_route ("
        private const val SHIPPED_FUNCTION = "heatmap_route_geometries("
        private const val SHIPPED_FOOT_JUMP = 200
        private const val SHIPPED_RIDE_JUMP = 1_000
        private const val SHIPPED_THRESHOLDS = "CASE sport WHEN 'RIDE' THEN $SHIPPED_RIDE_JUMP ELSE $SHIPPED_FOOT_JUMP END"

        private const val TABLE_SIZE =
            "SELECT pg_size_pretty(pg_total_relation_size('heatmap_route')), (SELECT count(*) FROM heatmap_route)"

        /**
         * The z0-12 bands each starting one zoom later than shipped, so a band's first zoom reads the coarser band's
         * geometry; z13 then reads the z10 band's, and z14 up their own.
         */
        private val LATER_BANDS = bandsEndingAt(6, 10, 13)

        /** The z0-12 bands each starting one zoom earlier than shipped; z12 then reads the z13 band's geometry. */
        private val EARLIER_BANDS = bandsEndingAt(4, 8, 11)

        /**
         * The shipped 7 bands' geometry, with the first three (z0, z6 and z10) ending at [lastZooms]; the one-pixel
         * bands from z13 up keep theirs, and any that a moved band now covers is dropped. The last runs to z16.
         */
        private fun bandsEndingAt(vararg lastZooms: Int): List<RasterBand> {
            val moved =
                RasterSettings.ZOOM_BANDS.mapIndexed { i, band ->
                    if (i <
                        lastZooms.size
                    ) {
                        band.copy(maxZoom = lastZooms[i])
                    } else {
                        band
                    }
                }
            return moved.filterIndexed { i, band -> i == 0 || band.maxZoom > moved[i - 1].maxZoom }
        }

        /** The bands of renderer v2 (before #119): one z13-16 band drawing the full-detail geometry, 1.2 m. */
        private val PREVIOUS_SHIPPED_BANDS = RasterSettings.ZOOM_BANDS.filter { it.maxZoom <= 12 } + RasterBand(16, "geom_z16")

        private val CHUNKED_SWEEP = RasterVariant("chunked mask sweep", changesLook = false, chunkedSweep = true)

        private val VARIANTS =
            listOf(
                RasterVariant.SHIPPED,
                RasterVariant(
                    "previous shipped (v2): one z13-16 band at 1.2 m, ARGB PNG",
                    changesLook = true,
                    bands = PREVIOUS_SHIPPED_BANDS,
                    encoding = Encoding.PNG_ARGB,
                ),
                RasterVariant(
                    "bands one zoom later (0-6, 7-10, 11-13, 14, 15, 16)",
                    changesLook = true,
                    bands = LATER_BANDS,
                ),
                RasterVariant(
                    "bands one zoom earlier (0-4, 5-8, 9-11, 12-13, 14, 15, 16)",
                    changesLook = true,
                    bands = EARLIER_BANDS,
                ),
                RasterVariant("on-the-fly simplification, shipped tolerances", changesLook = false, onTheFlyTolerance = 1.0),
                RasterVariant("on-the-fly simplification, 2x tolerances", changesLook = true, onTheFlyTolerance = 2.0),
                RasterVariant("on-the-fly simplification, 0.5x tolerances", changesLook = true, onTheFlyTolerance = 0.5),
                RasterVariant("line width x0.75", changesLook = true, lineWidthScale = 0.75f),
                RasterVariant("line width x1.5", changesLook = true, lineWidthScale = 1.5f),
                RasterVariant("glow sigma x0.5", changesLook = true, glowScale = 0.5f),
                RasterVariant("glow sigma x1.5", changesLook = true, glowScale = 1.5f),
                RasterVariant("coordinate precision 1 (whole pixels)", changesLook = true, coordinatePrecision = 1),
                RasterVariant("coordinate precision 1/8 pixel", changesLook = false, coordinatePrecision = 8),
                RasterVariant("simplified to 0.5 px at the tile's zoom", changesLook = true, tilePixelTolerance = 0.5),
                RasterVariant("simplified to 1 px at the tile's zoom", changesLook = true, tilePixelTolerance = 1.0),
                RasterVariant(
                    "bands one zoom later + 1 px at the tile's zoom",
                    changesLook = true,
                    bands = LATER_BANDS,
                    tilePixelTolerance = 1.0,
                ),
                CHUNKED_SWEEP,
                RasterVariant("PNG, deflate level 1", changesLook = false, encoding = Encoding.PNG_FAST_DEFLATE),
                RasterVariant("ARGB PNG (32-bit, v2's encoding)", changesLook = true, encoding = Encoding.PNG_ARGB),
            )

        /** A short name for a renderer statement, by what it does. */
        fun label(sql: String): String =
            when {
                "ST_AsMVTGeom" in sql -> "Tile routes"
                "heat_segments_" in sql -> "Full Heat (count of stored segments)"
                else -> "Other: " + sql.lineSequence().first().take(60)
            }

        /** `p50 | p95 | max` table cells of [millis]. */
        private fun percentileCells(millis: List<Double>) = "${ms(millis.p50 { it })} | ${ms(millis.p95 { it })} | ${ms(millis.max())}"

        private fun ms(value: Double) = String.format(Locale.ROOT, "%.1f", value)

        private fun kb(bytes: Double) = String.format(Locale.ROOT, "%.0f", bytes / 1024)

        private fun mb(bytes: Double) = String.format(Locale.ROOT, "%.1f", bytes / (1024 * 1024))

        private fun verdict(met: Boolean) = if (met) "met" else "**MISSED**"
    }
}
