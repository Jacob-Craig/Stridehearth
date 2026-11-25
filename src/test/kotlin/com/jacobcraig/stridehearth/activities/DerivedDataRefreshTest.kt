package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.athlete.AthleteRollups
import com.jacobcraig.stridehearth.athlete.RollupMarks
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
import kotlin.test.assertTrue

/**
 * The derived-data refresh, tested through the import, manual-create, edit and delete paths against real Postgres:
 * each test changes an **Activity** and checks its `activity_derived_metrics` row (its **Grade-Adjusted Pace**
 * stream), which every Activity of every sport has, or the athlete rollups the change marks.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DerivedDataRefreshTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var derivedDataRefresh: DerivedDataRefresh

    @Autowired
    private lateinit var athleteRollups: AthleteRollups

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
                FROM activity_derived_metrics m LEFT JOIN activity_details d ON d.activity_id = m.activity_id
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

        assertEquals(DERIVED_DATA_VERSION.toInt(), stored.version)
        assertEquals(stored.samples, stored.gapSpeed!!.size)
        // Mid-climb, 5 percent uphill at 3 m/s: 3 x C(0.05) / C(0) = 3 x 1.3014.
        assertEquals(3.0 * 1.3014, stored.gapSpeed[150]!!.toDouble(), 0.03)
    }

    /** A flat hour of running at 3 m/s and 150 bpm, sampled once a second. */
    private fun steadyHour(): FitTelemetry {
        val n = 3600
        return FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + it * 3.0 / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { 3.0f },
            distance = Array(n) { it * 3.0f },
            altitude = Array(n) { 30.0f },
            heartRate = Array(n) { 150.toShort() },
        )
    }

    private data class RunColumns(
        val movingSeconds: Int?,
        val ngpMps: Double?,
        val efficiencyFactor: Double?,
        val aerobicDecouplingPct: Double?,
        val reason: String?,
    )

    private fun runColumns(activity: Activity): RunColumns =
        jdbcTemplate.queryForObject(
            """
            SELECT moving_seconds, ngp_mps, efficiency_factor, aerobic_decoupling_pct, decoupling_unavailable_reason
            FROM activity_derived_metrics WHERE activity_id = ?
            """.trimIndent(),
            { rs, _ ->
                RunColumns(
                    rs.getObject("moving_seconds") as Int?,
                    rs.getObject("ngp_mps") as Double?,
                    rs.getObject("efficiency_factor") as Double?,
                    rs.getObject("aerobic_decoupling_pct") as Double?,
                    rs.getString("decoupling_unavailable_reason"),
                )
            },
            activity.id,
        )!!

    private val noRunMetrics = RunColumns(null, null, null, null, null)

    @Test
    fun `importing a run stores its Moving Time, NGP, Efficiency Factor and Aerobic Decoupling`() {
        val run = import(details = steadyHour())

        val stored = runColumns(run)

        assertEquals(3600, stored.movingSeconds)
        assertEquals(3.0, assertNotNull(stored.ngpMps), 1e-3)
        // 60 x 3.0 / 150
        assertEquals(1.2, assertNotNull(stored.efficiencyFactor), 1e-3)
        assertEquals(0.0, assertNotNull(stored.aerobicDecouplingPct), 1e-3)
        assertNull(stored.reason)
        assertEquals(
            RunMetricsDTO(3600, stored.ngpMps, stored.efficiencyFactor, stored.aerobicDecouplingPct, null),
            activityService.getActivityDetail(run.id!!, runner.id!!)!!.runMetrics,
        )
    }

    private data class ConditionColumns(
        val deviceTemperatureC: Float?,
        val ambientTemperatureC: Float?,
        val meanAltitudeM: Float?,
    )

    private fun conditionColumns(activity: Activity): ConditionColumns =
        jdbcTemplate.queryForObject(
            "SELECT device_temperature_c, ambient_temperature_c, mean_altitude_m FROM activity_derived_metrics WHERE activity_id = ?",
            { rs, _ ->
                ConditionColumns(
                    (rs.getObject(1) as Number?)?.toFloat(),
                    (rs.getObject(2) as Number?)?.toFloat(),
                    (rs.getObject(3) as Number?)?.toFloat(),
                )
            },
            activity.id,
        )!!

    @Test
    fun `importing an Activity of any sport stores its device and Ambient Temperature and its Mean Altitude`() {
        // 300 samples at 29 °C; altitude 100 m + 5% of 3 m per second: the cleaned mean is about 122 m.
        val warm = climbingDetails().copy(temperature = Array(300) { 29.toShort() })
        listOf(ActivitySport.RUN, ActivitySport.RIDE).forEach { sport ->
            val activity = import(sport = sport, details = warm, externalId = "warm-${sport.name}")

            val stored = conditionColumns(activity)

            assertEquals(29.0f, stored.deviceTemperatureC, sport.name)
            assertEquals(23.0f, stored.ambientTemperatureC, sport.name)
            assertEquals(122.0f, assertNotNull(stored.meanAltitudeM, sport.name), 1.5f, sport.name)
        }
    }

    @Test
    fun `an Activity without a temperature stream or Activity Details stores no conditions`() {
        val cool = import(externalId = "no-temperature")
        val bare = import(details = null, externalId = "no-details")

        assertEquals(null, conditionColumns(cool).deviceTemperatureC)
        assertNotNull(conditionColumns(cool).meanAltitudeM)
        assertEquals(ConditionColumns(null, null, null), conditionColumns(bare))
    }

    @Test
    fun `a short run stores NGP with the reason decoupling is unavailable`() {
        val run = import()

        val stored = runColumns(run)

        assertEquals(300, stored.movingSeconds)
        assertNotNull(stored.ngpMps)
        assertNull(stored.efficiencyFactor)
        assertEquals("NO_HEART_RATE", stored.reason)
    }

    @Test
    fun `walks, hikes and rides store no Run metrics`() {
        listOf(ActivitySport.WALK, ActivitySport.HIKE, ActivitySport.RIDE).forEach { sport ->
            val activity = import(sport = sport, details = climbingDetails(speed = 1.4f), externalId = sport.name)

            assertEquals(noRunMetrics, runColumns(activity), sport.name)
            assertNull(activityService.getActivityDetail(activity.id!!, runner.id!!)!!.runMetrics, sport.name)
        }
    }

    @Test
    fun `editing a run into a walk clears its Run metrics, and back to run restores them`() {
        val run = import(details = steadyHour())
        val before = runColumns(run)

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Walk now", sport = ActivitySport.WALK))
        assertEquals(noRunMetrics, runColumns(run))
        assertNull(activityService.getActivityDetail(run.id!!, runner.id!!)!!.runMetrics)

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Run again", sport = ActivitySport.RUN))
        assertEquals(before, runColumns(run))
    }

    @Test
    fun `importing a walk or a hike stores GAP, importing a ride stores a row with no GAP`() {
        val walk = import(sport = ActivitySport.WALK, details = climbingDetails(speed = 1.4f), externalId = "walk")
        val hike = import(sport = ActivitySport.HIKE, details = climbingDetails(speed = 1.0f), externalId = "hike")
        val ride = import(sport = ActivitySport.RIDE, details = climbingDetails(speed = 8.0f), externalId = "ride")

        assertNotNull(row(walk)!!.gapSpeed)
        assertNotNull(row(hike)!!.gapSpeed)
        val rideRow = assertNotNull(row(ride))
        assertEquals(DERIVED_DATA_VERSION.toInt(), rideRow.version)
        assertNull(rideRow.gapSpeed)
    }

    @Test
    fun `an Activity imported without Activity Details gets a row with no GAP at the current version`() {
        val run = import(details = null)

        val stored = assertNotNull(row(run))
        assertEquals(DERIVED_DATA_VERSION.toInt(), stored.version)
        assertNull(stored.gapSpeed)
    }

    private fun manualMetrics(startedAt: String = "2026-03-02T08:00:00Z") =
        ProtectedMetrics(
            startedAt = Instant.parse(startedAt),
            distance = 5.0,
            timeTakenSeconds = 1500,
            averagePaceKmh = null,
            averageHeartRate = null,
            calories = null,
        )

    private fun logManual(sport: ActivitySport = ActivitySport.RUN): Activity {
        val created = activityService.createManualActivity(runner.id!!, "Treadmill", sport, manualMetrics())
        return activityRepository.findById(created.id).get()
    }

    private fun computedAt(activity: Activity): Instant =
        jdbcTemplate.queryForObject(
            "SELECT computed_at FROM activity_derived_metrics WHERE activity_id = ?",
            { rs, _ -> rs.getTimestamp(1).toInstant() },
            activity.id,
        )!!

    /** Runs [work] in one transaction and returns the athlete rollups it marked, read just before it commits. */
    private fun marksOf(work: () -> Unit): Map<Long, RollupMarks> =
        transactionTemplate.execute {
            work()
            athleteRollups.pendingMarks()
        }!!

    @Test
    fun `logging a manual Activity writes its row`() {
        val stored = assertNotNull(row(logManual()))
        assertEquals(DERIVED_DATA_VERSION.toInt(), stored.version)
        assertNull(stored.gapSpeed)
    }

    @Test
    fun `editing the sport from run to ride clears the GAP stream, and back to run restores it`() {
        val run = import()

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Ride now", sport = ActivitySport.RIDE))
        assertNull(assertNotNull(row(run)).gapSpeed)

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
        assertNull(assertNotNull(row(first)).gapSpeed)
        assertNull(assertNotNull(row(second)).gapSpeed)

        activityService.batchUpdateActivities(
            listOf(first.id!!, second.id!!),
            runner.id!!,
            BatchMetadataChange.of(null, ActivitySport.HIKE)!!,
        )
        assertNotNull(row(first)!!.gapSpeed)
        assertNotNull(row(second)!!.gapSpeed)
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

        transactionTemplate.execute { derivedDataRefresh.refresh(activityRepository.findById(run.id!!).get()) }

        assertEquals(entries, historyEntryCount(run))
    }

    @Test
    fun `refresh requires the caller's transaction`() {
        val run = import()

        assertThrows<IllegalTransactionStateException> { derivedDataRefresh.refresh(run) }
    }

    @Test
    fun `editing a manual Activity's start recomputes its row and marks the rollups at the old and new starts`() {
        val manual = logManual()
        val before = computedAt(manual)
        val request =
            ActivityUpdateRequestDTO(
                title = "Treadmill",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2026-02-01T08:00:00Z"),
                distance = 5.0,
                timeTakenSeconds = 1500,
            )

        val marks = marksOf { activityService.updateActivity(manual.id!!, runner.id!!, request) }

        val starts = setOf(Instant.parse("2026-03-02T08:00:00Z"), Instant.parse("2026-02-01T08:00:00Z"))
        assertEquals(mapOf(runner.id!! to RollupMarks(Instant.parse("2026-02-01T08:00:00Z"), starts)), marks)
        assertTrue(computedAt(manual) > before, "the row was recomputed")
    }

    @Test
    fun `a ride marks only the daily rollups, and a sport edit away from run marks peaks too`() {
        val start = Instant.parse("2026-03-01T08:00:00Z")
        val ride = import(sport = ActivitySport.RIDE, externalId = "ride")
        val run = import(externalId = "run")

        assertEquals(
            mapOf(runner.id!! to RollupMarks(start, emptySet())),
            marksOf { derivedDataRefresh.refresh(activityRepository.findById(ride.id!!).get()) },
        )
        assertEquals(
            mapOf(runner.id!! to RollupMarks(start, setOf(start))),
            marksOf {
                activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Ride", sport = ActivitySport.RIDE))
            },
        )
    }

    @Test
    fun `deleting Activities marks their athlete's rollups`() {
        val start = Instant.parse("2026-03-01T08:00:00Z")
        val first = import(externalId = "a")
        val second = import(externalId = "b")
        val third = import(externalId = "c")

        assertEquals(
            mapOf(runner.id!! to RollupMarks(start, setOf(start))),
            marksOf { activityService.deleteActivity(first.id!!, runner.id!!) },
        )
        assertEquals(
            mapOf(runner.id!! to RollupMarks(start, setOf(start))),
            marksOf { activityService.batchDeleteActivities(listOf(second.id!!, third.id!!), runner.id!!) },
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activity_derived_metrics WHERE activity_id IN (?, ?, ?)",
                Int::class.java,
                first.id,
                second.id,
                third.id,
            ),
        )
    }
}
