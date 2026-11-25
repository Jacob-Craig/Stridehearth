package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.analytics.TrainingLoadMethod
import com.jacobcraig.stridehearth.analytics.TrainingLoadUnavailableReason
import com.jacobcraig.stridehearth.athlete.AthleteThresholds
import com.jacobcraig.stridehearth.athlete.AthleteTimeZone
import com.jacobcraig.stridehearth.athlete.ThresholdKind
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.time.LocalDate
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * **Training Load** through the import, manual-create, edit and delete paths against real Postgres: each test changes
 * an **Activity** or its athlete's **Thresholds** and checks the Training Load stored on its derived-data row and
 * returned by the Activity detail. Expected values are worked out by hand (one hour at threshold is 100).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TrainingLoadRefreshTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var athleteThresholds: AthleteThresholds

    @Autowired
    private lateinit var athleteTimeZone: AthleteTimeZone

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var runner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "load_runner@example.com", passwordHash = "x", username = "load_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun threshold(
        kind: ThresholdKind,
        value: Double,
        from: String = "2026-01-01",
    ) = athleteThresholds.record(runner.id!!, kind, LocalDate.parse(from), value)

    /** Resting 50, max 190, LTHR 170: one hour at 170 bpm is an HRSS of 100. */
    private fun heartRateThresholds() {
        threshold(ThresholdKind.RESTING_HR, 50.0)
        threshold(ThresholdKind.MAX_HR, 190.0)
        threshold(ThresholdKind.LTHR, 170.0)
    }

    /** One flat hour at 3 m/s, sampled once a second from t = 0 to t = 3600, at [hr] bpm and [power] W. */
    private fun hour(
        hr: Int? = 170,
        power: Int? = null,
        seconds: Int = 3600,
    ): FitTelemetry {
        val n = seconds + 1
        return FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + it * 3.0 / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { 3.0f },
            distance = Array(n) { it * 3.0f },
            altitude = Array(n) { 30.0f },
            heartRate = Array(n) { hr?.toShort() },
            power = if (power == null) emptyArray() else Array(n) { power.toShort() },
        )
    }

    private fun import(
        details: FitTelemetry? = hour(),
        sport: ActivitySport = ActivitySport.RUN,
        startedAt: String = "2026-03-01T08:00:00Z",
        externalId: String = "load-1",
        parserVersion: ParserVersion = FitActivityParser.PARSER_VERSION,
    ): Activity {
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = sport,
                    title = "Load fixture",
                    startedAt = Instant.parse(startedAt),
                    distance = 10.8,
                    timeTakenSeconds = 3600,
                    externalId = externalId,
                    telemetry = details,
                    parserVersion = parserVersion,
                ),
            ),
            runner.id!!,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(runner.id!!, ActivitySource.FIT_UPLOAD, externalId)!!
    }

    private data class Load(
        val value: Double?,
        val method: String?,
        val reason: String?,
    )

    private fun load(activity: Activity): Load? =
        jdbcTemplate
            .query(
                """
                SELECT training_load, training_load_method, training_load_unavailable_reason
                FROM activity_derived_metrics WHERE activity_id = ?
                """.trimIndent(),
                { rs, _ -> Load(rs.getObject(1) as Double?, rs.getString(2), rs.getString(3)) },
                activity.id,
            ).singleOrNull()

    private fun detail(activity: Activity) = assertNotNull(activityService.getActivityDetail(activity.id!!, runner.id!!))

    @Test
    fun `importing a run with heart rate stores its HRSS and the detail returns it`() {
        heartRateThresholds()

        val run = import()

        val stored = assertNotNull(load(run))
        assertEquals("HRSS", stored.method)
        assertEquals(100.0, assertNotNull(stored.value), 0.01)
        assertNull(stored.reason)
        val dto = detail(run)
        assertEquals(TrainingLoadMethod.HRSS, assertNotNull(dto.trainingLoad).method)
        assertEquals(100.0, dto.trainingLoad!!.value, 0.01)
        assertNull(dto.trainingLoadUnavailableReason)
    }

    @Test
    fun `a manual Activity has no Training Load because it has no Activity Details`() {
        heartRateThresholds()
        val metrics = ProtectedMetrics(Instant.parse("2026-03-02T08:00:00Z"), 5.0, 1500, null, 150, null)

        val created = activityService.createManualActivity(runner.id!!, "Treadmill", ActivitySport.RUN, metrics)

        val manual = activityRepository.findById(created.id).get()
        assertEquals(Load(null, null, "NO_ACTIVITY_DETAILS"), load(manual))
        val dto = detail(manual)
        assertNull(dto.trainingLoad)
        assertEquals(TrainingLoadUnavailableReason.NO_ACTIVITY_DETAILS, dto.trainingLoadUnavailableReason)
    }

    @Test
    fun `heart rate without resting or max heart rate gives NO_THRESHOLDS`() {
        val run = import()

        assertEquals(Load(null, null, "NO_THRESHOLDS"), load(run))
        assertEquals(TrainingLoadUnavailableReason.NO_THRESHOLDS, detail(run).trainingLoadUnavailableReason)
    }

    @Test
    fun `a GPS-only ride gives NO_USABLE_STREAM`() {
        heartRateThresholds()

        val ride = import(details = hour(hr = null), sport = ActivitySport.RIDE)

        assertEquals(Load(null, null, "NO_USABLE_STREAM"), load(ride))
    }

    @Test
    fun `an Upgrade recomputes the load from the new Activity Details`() {
        heartRateThresholds()
        val run = import(details = hour(seconds = 1800))
        assertEquals(50.0, load(run)!!.value!!, 0.01)

        import(details = hour(), parserVersion = ParserVersion(FitActivityParser.PARSER_VERSION.value + 1))

        assertEquals(100.0, load(run)!!.value!!, 0.01)
    }

    @Test
    fun `reclassifying a run with power as a ride falls from RSS to HRSS, and back`() {
        heartRateThresholds()
        threshold(ThresholdKind.CRITICAL_POWER, 300.0)
        val run = import(details = hour(hr = 150, power = 300))
        assertEquals(Load(100.0, "RSS", null), load(run)!!.rounded())

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Ride", sport = ActivitySport.RIDE))
        assertEquals("HRSS", load(run)!!.method)

        activityService.batchUpdateActivities(listOf(run.id!!), runner.id!!, BatchMetadataChange.of(null, ActivitySport.RUN)!!)
        assertEquals("RSS", load(run)!!.method)
    }

    private fun Load.rounded() = copy(value = value?.let { Math.round(it * 100) / 100.0 })

    @Test
    fun `deleting the Activity removes its load`() {
        heartRateThresholds()
        val run = import()

        activityService.deleteActivity(run.id!!, runner.id!!)

        assertNull(load(run))
    }

    @Test
    fun `the LTHR in force on the Activity's local date is used`() {
        threshold(ThresholdKind.RESTING_HR, 50.0)
        threshold(ThresholdKind.MAX_HR, 190.0)
        threshold(ThresholdKind.LTHR, 170.0, from = "2026-01-01")
        threshold(ThresholdKind.LTHR, 150.0, from = "2026-03-01")
        athleteTimeZone.set(runner.id!!, "America/New_York")

        // 01:00 UTC on 1 March is still 28 February in New York: LTHR 170, so 100.
        val before = import(startedAt = "2026-03-01T01:00:00Z", externalId = "before")
        // 08:00 UTC on 1 March is 1 March in New York: LTHR 150, so the hour at 170 bpm scores above 100.
        val after = import(startedAt = "2026-03-01T08:00:00Z", externalId = "after")

        assertEquals(100.0, load(before)!!.value!!, 0.01)
        // HRr = 120 / 140 against HRr_LT = 100 / 140: 100 x (HRr e^(1.92 HRr)) / (HRr_LT e^(1.92 HRr_LT))
        val hrr = 120.0 / 140
        val hrrLt = 100.0 / 140
        assertEquals(100 * hrr * exp(1.92 * hrr) / (hrrLt * exp(1.92 * hrrLt)), load(after)!!.value!!, 0.01)
        assertNotEquals(load(before)!!.value, load(after)!!.value)
    }
}
