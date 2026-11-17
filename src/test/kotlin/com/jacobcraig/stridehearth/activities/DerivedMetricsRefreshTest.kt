package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The derived-metrics refresh, tested through the import and edit paths against real Postgres: each test
 * changes an **Activity** and checks its `activity_derived_metrics` row (its **Grade-Adjusted Pace** stream).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DerivedMetricsRefreshTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var derivedMetricsRefresh: DerivedMetricsRefresh

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var runner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "gap_runner@example.com", passwordHash = "x", username = "gap_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** A 1 Hz Activity at [speed] m/s with a GPS fix per sample, climbing [grade] (rise over run). */
    private fun climbingDetails(
        n: Int = 300,
        speed: Float = 3.0f,
        grade: Double = 0.05,
    ) = FitTelemetry(
        t = IntArray(n) { it },
        lons = DoubleArray(n) { -1.5 },
        lats = DoubleArray(n) { 53.0 + it * speed / 111_195.0 },
        routeIndex = Array(n) { it },
        speed = Array(n) { speed },
        distance = Array(n) { it * speed },
        altitude = Array(n) { (100.0 + grade * it * speed).toFloat() },
    )

    private fun import(
        sport: ActivitySport = ActivitySport.RUN,
        details: FitTelemetry? = climbingDetails(),
        externalId: String = "gap-1",
        parserVersion: ParserVersion = FitActivityParser.PARSER_VERSION,
    ): Activity {
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = sport,
                    title = "GAP fixture",
                    startedAt = Instant.parse("2026-03-01T08:00:00Z"),
                    distance = 0.9,
                    timeTakenSeconds = 300,
                    externalId = externalId,
                    telemetry = details,
                    parserVersion = parserVersion,
                ),
            ),
            runner.id!!,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(runner.id!!, ActivitySource.FIT_UPLOAD, externalId)!!
    }

    private data class Row(
        val version: Int,
        val gapSpeed: List<Float?>?,
        val samples: Int,
    )

    private fun row(activity: Activity): Row? =
        jdbcTemplate
            .query(
                """
                SELECT m.algorithm_version, m.gap_speed, cardinality(d.t) AS samples
                FROM activity_derived_metrics m JOIN activity_details d ON d.activity_id = m.activity_id
                WHERE m.activity_id = ?
                """.trimIndent(),
                { rs, _ ->
                    Row(
                        version = rs.getInt("algorithm_version"),
                        gapSpeed = (rs.getArray("gap_speed")?.array as? Array<*>)?.map { (it as? Number)?.toFloat() },
                        samples = rs.getInt("samples"),
                    )
                },
                activity.id,
            ).singleOrNull()

    private fun historyEntryCount(activity: Activity): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM activity_history WHERE activity_id = ?", Int::class.java, activity.id)!!

    @Test
    fun `importing a run stores a GAP stream parallel to its Activity Details at the current version`() {
        val run = import()

        val stored = assertNotNull(row(run))

        assertEquals(DERIVED_METRICS_VERSION.toInt(), stored.version)
        assertEquals(stored.samples, stored.gapSpeed!!.size)
        // Mid-climb, 5 percent uphill at 3 m/s: 3 x C(0.05) / C(0) = 3 x 1.3014.
        assertEquals(3.0 * 1.3014, stored.gapSpeed[150]!!.toDouble(), 0.03)
    }

    @Test
    fun `importing a walk or a hike stores GAP, importing a ride stores none`() {
        val walk = import(sport = ActivitySport.WALK, details = climbingDetails(speed = 1.4f), externalId = "walk")
        val hike = import(sport = ActivitySport.HIKE, details = climbingDetails(speed = 1.0f), externalId = "hike")
        val ride = import(sport = ActivitySport.RIDE, details = climbingDetails(speed = 8.0f), externalId = "ride")

        assertNotNull(row(walk))
        assertNotNull(row(hike))
        assertNull(row(ride))
    }

    @Test
    fun `an Activity imported without Activity Details has no row`() {
        val run = import(details = null)

        assertEquals(
            0,
            jdbcTemplate.queryForObject("SELECT count(*) FROM activity_derived_metrics WHERE activity_id = ?", Int::class.java, run.id),
        )
    }

    @Test
    fun `editing the sport from run to ride removes the GAP stream, and back to run restores it`() {
        val run = import()

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Ride now", sport = ActivitySport.RIDE))
        assertNull(row(run))

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Run again", sport = ActivitySport.RUN))
        assertEquals(row(run)!!.samples, row(run)!!.gapSpeed!!.size)
    }

    @Test
    fun `a batch sport edit removes and restores GAP streams`() {
        val first = import(externalId = "a")
        val second = import(externalId = "b")

        activityService.batchUpdateActivities(
            listOf(first.id!!, second.id!!),
            runner.id!!,
            BatchMetadataChange.of(null, ActivitySport.RIDE)!!,
        )
        assertNull(row(first))
        assertNull(row(second))

        activityService.batchUpdateActivities(
            listOf(first.id!!, second.id!!),
            runner.id!!,
            BatchMetadataChange.of(null, ActivitySport.HIKE)!!,
        )
        assertNotNull(row(first))
        assertNotNull(row(second))
    }

    @Test
    fun `a title-only edit leaves the GAP stream untouched`() {
        val run = import()
        val computedAt = {
            jdbcTemplate.queryForObject("SELECT computed_at FROM activity_derived_metrics WHERE activity_id = ?", Any::class.java, run.id)
        }
        val before = computedAt()

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Renamed", sport = ActivitySport.RUN))

        assertEquals(before, computedAt())
    }

    @Test
    fun `deleting the Activity removes its row`() {
        val run = import()

        activityService.deleteActivity(run.id!!, runner.id!!)

        assertEquals(
            0,
            jdbcTemplate.queryForObject("SELECT count(*) FROM activity_derived_metrics WHERE activity_id = ?", Int::class.java, run.id),
        )
    }

    @Test
    fun `an Upgrade replaces the GAP stream with one computed from the new Activity Details`() {
        val run = import(details = climbingDetails(grade = 0.05))

        import(details = climbingDetails(n = 400, grade = -0.10), parserVersion = ParserVersion(FitActivityParser.PARSER_VERSION.value + 1))

        val stored = assertNotNull(row(run))
        assertEquals(400, stored.gapSpeed!!.size)
        // 10 percent downhill at 3 m/s: 3 x C(-0.10) / C(0) = 3 x 0.5977.
        assertEquals(3.0 * 0.5977, stored.gapSpeed[200]!!.toDouble(), 0.03)
    }

    @Test
    fun `a run without speed or distance gets a row with no GAP stream`() {
        val n = 50
        val run =
            import(
                details =
                    FitTelemetry(
                        t = IntArray(n) { it },
                        lons = doubleArrayOf(),
                        lats = doubleArrayOf(),
                        routeIndex = arrayOfNulls(n),
                        heartRate = Array(n) { 140.toShort() },
                    ),
            )

        val stored = assertNotNull(row(run))
        assertNull(stored.gapSpeed)
    }

    @Test
    fun `refreshing writes no Activity History Entry`() {
        val run = import()
        val entries = historyEntryCount(run)

        transactionTemplate.execute { derivedMetricsRefresh.refresh(activityRepository.findById(run.id!!).get()) }

        assertEquals(entries, historyEntryCount(run))
    }

    @Test
    fun `refresh requires the caller's transaction`() {
        val run = import()

        assertThrows<IllegalTransactionStateException> { derivedMetricsRefresh.refresh(run) }
    }
}
