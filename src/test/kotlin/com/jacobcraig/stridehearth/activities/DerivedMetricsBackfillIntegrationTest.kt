package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The derived-metrics **Backfill**: Activities imported before Grade-Adjusted Pace existed (or computed by an
 * older algorithm version) get their GAP stream, one committed transaction per page, and a rerun is a no-op.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DerivedMetricsBackfillIntegrationTest {
    @Autowired
    private lateinit var backfillService: DerivedMetricsBackfillService

    @MockitoSpyBean
    private lateinit var derivedMetricsRefresh: DerivedMetricsRefresh

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var runner: User
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "gap_backfill@example.com", passwordHash = "x", username = "gap_backfill"))
        otherRunner = userRepository.save(User(email = "gap_backfill2@example.com", passwordHash = "x", username = "gap_backfill2"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** An Activity stored with Activity Details but, like one imported before GAP existed, no derived metrics. */
    private fun historical(
        sport: ActivitySport = ActivitySport.RUN,
        owner: User = runner,
        n: Int = 120,
    ): Activity {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Historical",
                    sport = sport,
                    startedAt = Instant.parse("2025-05-01T08:00:00Z"),
                    distance = 0.36,
                    timeTakenSeconds = n,
                    user = owner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        activityDetailsRepository.upsert(
            activity.id!!,
            FitTelemetry(
                t = IntArray(n) { it },
                lons = DoubleArray(n) { -1.5 },
                lats = DoubleArray(n) { 53.0 + it * 3.0 / 111_195.0 },
                routeIndex = Array(n) { it },
                speed = Array(n) { 3.0f },
                distance = Array(n) { it * 3.0f },
                altitude = Array(n) { 100.0f + 0.05f * it * 3.0f },
            ),
            ParserVersion(1),
        )
        return activity
    }

    private fun gapLength(activity: Activity): Int? =
        jdbcTemplate
            .query(
                "SELECT cardinality(gap_speed) FROM activity_derived_metrics WHERE activity_id = ?",
                { rs, _ -> rs.getInt(1) },
                activity.id,
            ).singleOrNull()

    private fun computedAt(activity: Activity): Any? =
        jdbcTemplate.queryForObject("SELECT computed_at FROM activity_derived_metrics WHERE activity_id = ?", Any::class.java, activity.id)

    private fun storedGap(activity: Activity): List<Float?>? =
        jdbcTemplate.queryForObject(
            "SELECT gap_speed FROM activity_derived_metrics WHERE activity_id = ?",
            { rs, _ -> (rs.getArray(1)?.array as? Array<*>)?.map { (it as? Number)?.toFloat() } },
            activity.id,
        )

    @Test
    fun `fills GAP for every athlete's runs, walks and hikes, and a rerun is safe and processes nothing`() {
        val run = historical()
        val walk = historical(sport = ActivitySport.WALK)
        val othersHike = historical(sport = ActivitySport.HIKE, owner = otherRunner)
        val ride = historical(sport = ActivitySport.RIDE)

        val first = backfillService.backfillAll(pageSize = 2)

        assertEquals(DerivedMetricsBackfillResult(activitiesProcessed = 3), first)
        assertEquals(120, gapLength(run))
        assertEquals(120, gapLength(walk))
        assertEquals(120, gapLength(othersHike))
        assertNull(gapLength(ride))
        val stamps = listOf(run, walk, othersHike).map(::computedAt)

        val second = backfillService.backfillAll(pageSize = 2)

        assertEquals(DerivedMetricsBackfillResult(activitiesProcessed = 0), second)
        assertEquals(stamps, listOf(run, walk, othersHike).map(::computedAt))
    }

    @Test
    fun `refills rows that were deleted`() {
        val run = historical()
        backfillService.backfillAll()
        val computed = storedGap(run)
        jdbcTemplate.update("DELETE FROM activity_derived_metrics")

        assertEquals(DerivedMetricsBackfillResult(activitiesProcessed = 1), backfillService.backfillAll())

        assertEquals(computed, storedGap(run))
    }

    @Test
    fun `recomputes a row stored by an older algorithm version`() {
        val run = historical()
        jdbcTemplate.update(
            "INSERT INTO activity_derived_metrics (activity_id, algorithm_version, gap_speed) VALUES (?, 0, '{9}')",
            run.id,
        )

        assertEquals(DerivedMetricsBackfillResult(activitiesProcessed = 1), backfillService.backfillAll())

        assertEquals(120, gapLength(run))
        assertEquals(
            DERIVED_METRICS_VERSION.toInt(),
            jdbcTemplate.queryForObject(
                "SELECT algorithm_version FROM activity_derived_metrics WHERE activity_id = ?",
                Int::class.java,
                run.id,
            ),
        )
    }

    @Test
    fun `never overwrites a row already at the current version, such as one a concurrent import wrote`() {
        val run = historical()
        jdbcTemplate.update(
            "INSERT INTO activity_derived_metrics (activity_id, algorithm_version, gap_speed) VALUES (?, ?, '{9}')",
            run.id,
            DERIVED_METRICS_VERSION,
        )

        // The guarded write itself, as a backfill page that picked the run before the import committed would run it.
        val written = transactionTemplate.execute { derivedMetricsRefresh.backfill(listOf(activityRepository.findById(run.id!!).get())) }

        assertEquals(0, written)
        assertEquals(listOf(9.0f), storedGap(run))
        assertEquals(DerivedMetricsBackfillResult(activitiesProcessed = 0), backfillService.backfillAll())
    }

    @Test
    fun `a failing page rolls back on its own, keeps earlier pages, and a rerun completes the rest`() {
        val first = historical()
        val second = historical()
        val third = historical()
        val calls = AtomicInteger()
        doAnswer { invocation ->
            val written = invocation.callRealMethod()
            if (calls.incrementAndGet() == 2) throw IllegalStateException("page 2 fails")
            written
        }.whenever(AopTestUtils.getUltimateTargetObject<DerivedMetricsRefresh>(derivedMetricsRefresh)).backfill(any())

        val failure = assertThrows<IllegalStateException> { backfillService.backfillAll(pageSize = 1) }
        assertEquals("page 2 fails", failure.message)

        assertEquals(120, gapLength(first))
        assertNull(gapLength(second))
        assertNull(gapLength(third))

        assertEquals(DerivedMetricsBackfillResult(activitiesProcessed = 2), backfillService.backfillAll(pageSize = 1))
        assertEquals(120, gapLength(second))
        assertEquals(120, gapLength(third))
    }

    @Test
    fun `refuses to run inside a caller's transaction`() {
        assertThrows<IllegalTransactionStateException> {
            transactionTemplate.execute { backfillService.backfillAll() }
        }
    }
}
