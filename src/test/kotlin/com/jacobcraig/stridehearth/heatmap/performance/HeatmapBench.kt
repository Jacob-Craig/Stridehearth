package com.jacobcraig.stridehearth.heatmap.performance

import com.jacobcraig.stridehearth.POSTGIS_IMAGE
import com.jacobcraig.stridehearth.activities.HEATMAP_ROUTE_OF_DETAILS
import com.jacobcraig.stridehearth.activities.UPSERT_HEATMAP_ROUTE
import com.jacobcraig.stridehearth.heatmap.DensityRaster
import com.jacobcraig.stridehearth.heatmap.FullHeat
import com.jacobcraig.stridehearth.heatmap.HeatmapRepository
import com.jacobcraig.stridehearth.heatmap.HeatmapRevision
import com.jacobcraig.stridehearth.heatmap.HeatmapScope
import com.jacobcraig.stridehearth.heatmap.HeatmapTileRenderer
import com.jacobcraig.stridehearth.heatmap.LonLat
import com.jacobcraig.stridehearth.heatmap.RasterDensityTileRenderer
import com.jacobcraig.stridehearth.heatmap.RenderedTile
import com.jacobcraig.stridehearth.heatmap.Tile
import com.jacobcraig.stridehearth.heatmap.TileCoordinate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.random.Random

/** One render of one tile, as the harness measured it. */
class TileSample(
    val tile: Tile,
    val wallMillis: Double,
    /** CPU time of the rendering thread, so the JVM's share: fetching rows, drawing, blurring and encoding. */
    val cpuMillis: Double,
    /** Wall time spent in PostgreSQL statements, including fetching their rows. */
    val databaseMillis: Double,
    val allocatedBytes: Long,
    /** The size of the encoded tile. */
    val pngBytes: Int,
    val routesDrawn: Boolean,
    /** The statements the renderer ran for this tile. */
    val statements: List<RecordedStatement>,
)

/** Several tiles rendered at once, as a browser requests them. */
class ConcurrentRun(
    val samples: List<TileSample>,
    val wallMillis: Double,
    val peakHeapBytes: Long,
    /** PostgreSQL's CPU time over the whole run, from its container's cgroup, or null where that is unreadable. */
    val databaseCpuMillis: Double?,
)

/** What seeding wrote, and how long each timed heatmap route row write took. */
class Seeding(
    val activities: Int,
    val points: Long,
    val writeMillis: List<Double>,
    val elapsedSeconds: Double,
)

/** The renderer's address for this tile. */
val Tile.coordinate: TileCoordinate get() = TileCoordinate(z, x, y)

/** The [p]th percentile, by nearest rank, of [selector] over these. */
fun <T> List<T>.percentile(
    p: Double,
    selector: (T) -> Double,
): Double {
    val sorted = map(selector).sorted()
    return sorted[(ceil(p / 100 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
}

fun <T> List<T>.p50(selector: (T) -> Double) = percentile(50.0, selector)

fun <T> List<T>.p95(selector: (T) -> Double) = percentile(95.0, selector)

/**
 * A PostGIS database in Testcontainers holding one synthetic athlete's Heatmap, and the instruments the Heatmap
 * performance harness measures it with. Tiles are rendered through the [HeatmapTileRenderer] strategy, so any
 * renderer is measured against the same data and targets.
 */
class HeatmapBench private constructor(
    private val postgres: PostgreSQLContainer,
    private val pool: HikariDataSource,
) : AutoCloseable {
    private val timer = StatementTimer(pool)

    /** A [JdbcClient] whose statements the bench times; renderers under test are built on it. */
    val jdbcClient: JdbcClient = JdbcClient.create(timer.dataSource)

    /** The shipped renderer, with its own Full Heat cache, as the application builds it. */
    val renderer: HeatmapTileRenderer = RasterDensityTileRenderer(jdbcClient, FullHeat(jdbcClient))

    val athleteId: Long =
        pool.connection.use { connection ->
            connection
                .createStatement()
                .executeQuery(
                    "INSERT INTO app_user (email, password_hash, username) VALUES ('perf@example.com', 'x', 'perf') RETURNING id",
                ).use { rs -> rs.single { it.getLong(1) } }
        }

    /** For each measured zoom, how many Activities pass through each tile. */
    private val tileActivities = ConcurrentHashMap<Tile, AtomicLong>()

    /**
     * Seeds [activities] as Activities with Activity Details, then writes each one's heatmap route row with the
     * same SQL function the Activity Details upsert uses. The first [timedWrites] row writes run one at a time and
     * are timed; the rest run on [threads] threads to seed faster.
     */
    fun seed(
        activities: Sequence<SyntheticActivity>,
        timedWrites: Int = Int.MAX_VALUE,
        threads: Int = 1,
    ): Seeding {
        val started = System.nanoTime()
        val writeMillis = mutableListOf<Double>()
        val points = AtomicLong()
        var count = 0
        val executor = Executors.newFixedThreadPool(threads)
        try {
            // Only a few chunks wait at once, so 10k Activities' routes never sit in memory together.
            val inFlight = Semaphore(threads * 2)
            val pending = mutableListOf<Future<*>>()
            activities.chunked(CHUNK).forEach { chunk ->
                val firstIndex = count
                count += chunk.size
                chunk.forEach { activity -> points.addAndGet(activity.route.size.toLong()) }
                if (firstIndex + chunk.size <= timedWrites) {
                    writeMillis += insert(chunk, timed = true)
                } else {
                    inFlight.acquire()
                    pending +=
                        executor.submit {
                            try {
                                insert(chunk, timed = false)
                            } finally {
                                inFlight.release()
                            }
                        }
                }
            }
            pending.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        pool.connection.use { it.createStatement().execute("VACUUM ANALYZE") }
        return Seeding(count, points.get(), writeMillis, elapsedSeconds = (System.nanoTime() - started) / 1e9)
    }

    /** Inserts [chunk]'s Activities and details, then their heatmap route rows, returning each row write's millis. */
    private fun insert(
        chunk: List<SyntheticActivity>,
        timed: Boolean,
    ): List<Double> {
        chunk.forEach { countTiles(it) }
        return pool.connection.use { connection ->
            val ids = insertActivities(connection, chunk)
            connection.prepareStatement(INSERT_DETAILS).use { statement ->
                chunk.zip(ids).forEach { (activity, id) ->
                    statement.setLong(1, id)
                    statement.setBytes(2, wkb(activity.route))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection
                .prepareStatement(WRITE_HEATMAP_ROUTE)
                .use { statement ->
                    ids.map { id ->
                        statement.setLong(1, id)
                        val before = System.nanoTime()
                        statement.executeUpdate()
                        (System.nanoTime() - before) / 1e6
                    }
                }.takeIf { timed }
                .orEmpty()
        }
    }

    private fun insertActivities(
        connection: Connection,
        chunk: List<SyntheticActivity>,
    ): List<Long> =
        connection.prepareStatement(INSERT_ACTIVITY, Statement.RETURN_GENERATED_KEYS).use { statement ->
            chunk.forEach { activity ->
                statement.setTimestamp(1, Timestamp.from(activity.startedAt))
                statement.setLong(2, athleteId)
                statement.setString(3, "perf-${activity.startedAt.toEpochMilli()}")
                statement.setString(4, activity.sport.name)
                statement.addBatch()
            }
            statement.executeBatch()
            statement.generatedKeys.use { keys -> buildList { while (keys.next()) add(keys.getLong("id")) } }
        }

    /**
     * Rewrites the heatmap route rows of [count] Activities picked at random, one at a time and timed, as an
     * **Upgrade** of their Activity Details does: the per-Activity write cost on a full Heatmap.
     */
    fun rewriteHeatmapRoutes(
        count: Int,
        sql: String = WRITE_HEATMAP_ROUTE,
    ): List<Double> =
        pool.connection.use { connection ->
            val ids =
                connection.createStatement().executeQuery("SELECT activity_id FROM heatmap_route ORDER BY activity_id").use { rs ->
                    buildList { while (rs.next()) add(rs.getLong(1)) }
                }
            connection.prepareStatement(sql).use { statement ->
                ids.shuffled(Random(count)).take(count).map { id ->
                    statement.setLong(1, id)
                    val before = System.nanoTime()
                    statement.executeUpdate()
                    (System.nanoTime() - before) / 1e6
                }
            }
        }

    /** Runs [sql] once on its own connection, e.g. to install a variant SQL function. */
    fun execute(sql: String) {
        pool.connection.use { it.createStatement().execute(sql) }
    }

    /** Reads the single row [sql] returns with [read]; fails if it returns no row. */
    fun <T> queryOne(
        sql: String,
        read: (ResultSet) -> T,
    ): T = pool.connection.use { connection -> connection.createStatement().executeQuery(sql).use { it.single(read) } }

    /**
     * PostgreSQL's CPU time so far, in microseconds, from its container's cgroup: every PostgreSQL process, unlike
     * [TileSample.cpuMillis], which is the JVM's share. Null where the cgroup can't be read.
     */
    fun databaseCpuMicros(): Long? {
        // cgroup v2 reports `usage_usec` in cpu.stat; cgroup v1 reports nanoseconds in cpuacct.usage.
        val result =
            postgres.execInContainer(
                "sh",
                "-c",
                "cat /sys/fs/cgroup/cpu.stat 2>/dev/null || cat /sys/fs/cgroup/cpuacct/cpuacct.usage",
            )
        val text = result.stdout.trim()
        Regex("""usage_usec (\d+)""").find(text)?.let { return it.groupValues[1].toLong() }
        return text.toLongOrNull()?.let { it / 1_000 }
    }

    /**
     * Pushes `heatmap_route` and its indexes out of PostgreSQL's shared buffers by reading an unrelated table larger
     * than them through an index (index scans, unlike sequential scans, don't use a small ring of buffers), until at
     * most [maxLeft] of their buffers remain or [attempts] runs out. Returns how many remain. The OS page cache stays
     * warm: an unprivileged container can't drop it.
     */
    fun evictHeatmapFromSharedBuffers(
        maxLeft: Int = 0,
        attempts: Int = 6,
    ): Int {
        pool.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE EXTENSION IF NOT EXISTS pg_buffercache")
                statement.execute("CREATE TABLE IF NOT EXISTS cold_buffer_filler (id bigint PRIMARY KEY, pad text NOT NULL)")
                // About twice shared buffers of ~1 KB rows, written in small batches to keep the server's memory flat.
                val rows = statement.executeQuery(FILLER_ROWS).use { rs -> rs.single { it.getLong(1) } }
                var written = statement.executeQuery("SELECT count(*) FROM cold_buffer_filler").use { rs -> rs.single { it.getLong(1) } }
                while (written < rows) {
                    val last = minOf(rows, written + FILLER_BATCH)
                    statement.execute(
                        "INSERT INTO cold_buffer_filler SELECT id, repeat('x', 1000) FROM generate_series(${written + 1}, $last) AS id",
                    )
                    written = last
                }
                statement.execute("SET enable_seqscan = off")
                statement.execute("SET enable_bitmapscan = off")
            }
            repeat(attempts) {
                val left = heatmapBuffers(connection)
                if (left <= maxLeft) return left
                connection.createStatement().use { it.executeQuery("SELECT count(pad) FROM cold_buffer_filler WHERE id > 0").close() }
            }
            return heatmapBuffers(connection)
        }
    }

    private fun heatmapBuffers(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT count(*) FROM pg_buffercache b JOIN pg_class c ON b.relfilenode = pg_relation_filenode(c.oid) " +
                        "WHERE b.reldatabase = (SELECT oid FROM pg_database WHERE datname = current_database()) " +
                        "AND (c.relname LIKE 'heatmap_route%' OR c.relname LIKE 'idx_heatmap_route%' " +
                        "OR c.oid = (SELECT reltoastrelid FROM pg_class WHERE relname = 'heatmap_route'))",
                ).use { rs -> rs.single { it.getInt(1) } }
        }

    private fun countTiles(activity: SyntheticActivity) {
        ZOOMS.forEach { z ->
            activity.route
                .filterIndexed { i, _ -> i % 10 == 0 }
                .mapTo(mutableSetOf()) { Tile.containing(it.lon, it.lat, z) }
                .forEach { tileActivities.computeIfAbsent(it) { AtomicLong() }.incrementAndGet() }
        }
    }

    /** The [count] tiles at [z] that the most Activities pass through, busiest first. */
    fun busiestTiles(
        z: Int,
        count: Int,
    ): List<Tile> =
        tileActivities.entries
            .filter { it.key.z == z }
            .sortedByDescending { it.value.get() }
            .take(count)
            .map { it.key }

    /**
     * A spread of [count] tiles at [z] with routes in them: the busiest half, and the rest picked at random from
     * every tile with a route, so quiet tiles and far-flung trips are measured too.
     */
    fun spreadOfTiles(
        z: Int,
        count: Int,
    ): List<Tile> {
        val busiest = busiestTiles(z, count / 2)
        val rest =
            tileActivities.keys
                .filter { it.z == z && it !in busiest }
                .sortedBy { "$it" }
                .shuffled(Random(z))
        return busiest + rest.take(count - busiest.size)
    }

    /** How many Activities pass through [tile], by the seeded routes. */
    fun activitiesThrough(tile: Tile): Long = tileActivities[tile]?.get() ?: 0

    /** Renders each of [tiles] once, one after another, measuring each render. */
    fun measure(
        renderer: HeatmapTileRenderer,
        tiles: List<Tile>,
    ): List<TileSample> = tiles.map { measureOne(renderer, it) }

    /** Renders [tiles] on [threads] threads at once, measuring each render and the JVM's peak heap meanwhile. */
    fun measureConcurrently(
        renderer: HeatmapTileRenderer,
        tiles: List<Tile>,
        threads: Int,
    ): ConcurrentRun {
        System.gc()
        val memory = ManagementFactory.getMemoryMXBean()
        val peak = AtomicLong(memory.heapMemoryUsage.used)
        val sampling = AtomicBoolean(true)
        val sampler =
            thread(isDaemon = true) {
                while (sampling.get()) {
                    peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf)
                    Thread.sleep(2)
                }
            }
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val databaseCpuBefore = databaseCpuMicros()
            val started = System.nanoTime()
            val samples = tiles.map { tile -> executor.submit<TileSample> { measureOne(renderer, tile) } }.map { it.get() }
            val wall = (System.nanoTime() - started) / 1e6
            val databaseCpuMillis = databaseCpuBefore?.let { before -> databaseCpuMicros()?.let { (it - before) / 1e3 } }
            return ConcurrentRun(samples, wall, peak.get(), databaseCpuMillis)
        } finally {
            executor.shutdownNow()
            sampling.set(false)
            sampler.join()
        }
    }

    private fun measureOne(
        renderer: HeatmapTileRenderer,
        tile: Tile,
    ): TileSample {
        val threadMx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val threadId = Thread.currentThread().id
        val allocatedBefore = threadMx.getThreadAllocatedBytes(threadId)
        val cpuBefore = threadMx.currentThreadCpuTime
        val started = System.nanoTime()
        lateinit var timed: Pair<RenderedTile, Double>
        val statements =
            timer.recorded {
                timed =
                    timer.timed { render(renderer, tile) }
            }
        val (rendered, databaseMillis) = timed
        return TileSample(
            tile = tile,
            wallMillis = (System.nanoTime() - started) / 1e6,
            cpuMillis = (threadMx.currentThreadCpuTime - cpuBefore) / 1e6,
            databaseMillis = databaseMillis,
            allocatedBytes = threadMx.getThreadAllocatedBytes(threadId) - allocatedBefore,
            pngBytes = rendered.bytes.size,
            routesDrawn = !rendered.bytes.contentEquals(DensityRaster.EMPTY_PNG),
            statements = statements,
        )
    }

    /** The statements [renderer] runs for [tile], recorded with their bindings. */
    fun statementsFor(
        renderer: HeatmapTileRenderer,
        tile: Tile,
    ): List<RecordedStatement> = timer.recorded { render(renderer, tile) }

    /** [renderer]'s tile for [tile] of the whole Heatmap. */
    fun render(
        renderer: HeatmapTileRenderer,
        tile: Tile,
    ): RenderedTile = renderer.render(HeatmapScope(athleteId), revision, tile.coordinate)

    /** The athlete's heatmap revision once seeded, read untimed: the application reads it before each tile. */
    private val revision: HeatmapRevision by lazy { HeatmapRepository(JdbcClient.create(pool)).revision(athleteId) }

    /**
     * `EXPLAIN (ANALYZE, BUFFERS)` of [statement], as the renderer ran it. With [allowSequentialScan] false the
     * planner avoids sequential scans wherever an index can serve, which shows whether an index is still usable.
     */
    fun explain(
        statement: RecordedStatement,
        allowSequentialScan: Boolean = true,
    ): String =
        pool.connection.use { connection ->
            connection.createStatement().use { it.execute("SET enable_seqscan = ${if (allowSequentialScan) "on" else "off"}") }
            try {
                connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) ${statement.sql}").use { explain ->
                    statement.bindings.forEach { it.bindTo(explain) }
                    explain.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) }.joinToString("\n") }
                }
            } finally {
                connection.createStatement().use { it.execute("RESET enable_seqscan") }
            }
        }

    /** `EXPLAIN ANALYZE` of the query that reads the routes of [tile], as the shipped renderer runs it. */
    fun explainTileQuery(
        tile: Tile,
        allowSequentialScan: Boolean = true,
    ): String = explain(tileQuery(statementsFor(renderer, tile)), allowSequentialScan)

    override fun close() {
        pool.close()
        postgres.stop()
    }

    companion object {
        /**
         * The ticket's zooms (3, 8, 14), the first zoom of each later band (6, 10, 13), where tiles cost most, and the
         * deepest tile zooms (15, 16).
         */
        val ZOOMS = listOf(3, 6, 8, 10, 13, 14, 15, 16)
        private const val CHUNK = 200
        private const val FILLER_BATCH = 20_000
        private const val FILLER_ROWS = "SELECT 2 * setting::bigint * 8 FROM pg_settings WHERE name = 'shared_buffers'"

        private const val INSERT_ACTIVITY =
            "INSERT INTO activity (title, started_at, distance, time_taken_seconds, user_id, source, external_id, sport) " +
                "VALUES ('Synthetic', ?, 0, 0, ?, 'FIT_UPLOAD', ?, ?)"
        private const val INSERT_DETAILS =
            "INSERT INTO activity_details (activity_id, route, t, parser_version) VALUES (?, ST_GeomFromWKB(?, 4326), '{0}', 1)"

        /**
         * The heatmap route write of the Activity Details upsert (`ActivityDetailsRepository.upsert`), on its own,
         * for one Activity whose details are stored: the same SQL, reading the route back from `activity_details`
         * where the upsert has just written it.
         */
        const val WRITE_HEATMAP_ROUTE =
            "WITH details AS (SELECT activity_id, route FROM activity_details WHERE activity_id = ?), " +
                "heatmap AS ($HEATMAP_ROUTE_OF_DETAILS) $UPSERT_HEATMAP_ROUTE"

        /** The statement that reads a tile's routes: the one that clips them to the tile. */
        fun tileQuery(statements: List<RecordedStatement>): RecordedStatement = statements.single { "ST_AsMVTGeom" in it.sql }

        /** Starts PostGIS, the project's image, and migrates it to the latest schema. */
        fun start(): HeatmapBench {
            val postgres =
                PostgreSQLContainer(POSTGIS_IMAGE).withSharedMemorySize(1L shl 30)
            postgres.start()
            val pool =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = postgres.jdbcUrl
                        username = postgres.username
                        password = postgres.password
                        // The harness never runs more than 8 renders at once; the pool stays small as other test databases share the Docker host.
                        maximumPoolSize = 9
                        minimumIdle = 1
                    },
                )
            Flyway
                .configure()
                .dataSource(pool)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            return HeatmapBench(postgres, pool)
        }

        /** [read] of the one row in this result; fails if there is none. */
        private fun <T> ResultSet.single(read: (ResultSet) -> T): T {
            check(next()) { "Expected a row, got none" }
            return read(this)
        }

        /** A route as WKB: a little-endian LineString in longitude and latitude. */
        private fun wkb(route: List<LonLat>): ByteArray {
            val buffer = ByteBuffer.allocate(1 + 4 + 4 + route.size * 16).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(1).putInt(2).putInt(route.size)
            route.forEach { buffer.putDouble(it.lon).putDouble(it.lat) }
            return buffer.array()
        }
    }
}
