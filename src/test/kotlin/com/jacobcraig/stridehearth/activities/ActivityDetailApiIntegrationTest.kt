package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.DetailsUpgrade
import com.jacobcraig.stridehearth.activities.history.FieldChange
import com.jacobcraig.stridehearth.activities.history.LapsUpgrade
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * HTTP-level tests for the **Activity** detail contract, `GET /api/activities/{id}` (`ActivityDetailDTO`).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "detail_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ActivityDetailApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityLapRepository: ActivityLapRepository

    @Autowired
    private lateinit var activityHistory: ActivityHistory

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var activityBestEffortRepository: ActivityBestEffortRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    private lateinit var runner: User
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "detail_runner@example.com", passwordHash = "x", username = "detail_runner"))
        otherRunner = userRepository.save(User(email = "detail_other@example.com", passwordHash = "x", username = "detail_other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun saveActivity(
        owner: User,
        source: ActivitySource = ActivitySource.FIT_UPLOAD,
    ): Activity =
        activityRepository.save(
            Activity(
                title = "Track Session",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2026-03-01T08:00:00Z"),
                distance = 6.4,
                timeTakenSeconds = 1920,
                user = owner,
                averagePaceKmh = 12.0,
                averageHeartRate = 158,
                calories = 480,
                source = source,
                externalId = if (source == ActivitySource.MANUAL) null else "track-${owner.username}",
            ),
        )

    private fun saveLap(
        activity: Activity,
        lapNumber: Int,
        elevationGainMeters: Double?,
    ) = activityLapRepository.save(
        ActivityLap(
            activityId = activity.id!!,
            lapNumber = lapNumber,
            startedAt = Instant.parse("2026-03-01T08:00:00Z").plusSeconds(300L * (lapNumber - 1)),
            distance = 1.0,
            timeTakenSeconds = 300,
            averagePaceKmh = 12.0,
            averageHeartRate = 150 + lapNumber,
            elevationGainMeters = elevationGainMeters,
        ),
    )

    /** Records [change] through the Activity History module, then backdates it to [createdAt]. */
    private fun recordHistory(
        activity: Activity,
        change: ActivityChange,
        createdAt: String,
    ) {
        transactionTemplate.executeWithoutResult { activityHistory.record(activity, change) }
        jdbcTemplate.update(
            "UPDATE activity_history SET created_at = ? WHERE id = (SELECT max(id) FROM activity_history WHERE activity_id = ?)",
            Timestamp.from(Instant.parse(createdAt)),
            activity.id,
        )
    }

    /** A free-text entry as written before typed changes existed. */
    private fun saveLegacyHistory(
        activity: Activity,
        changeType: String,
        description: String,
        createdAt: String,
    ) = jdbcTemplate.update(
        "INSERT INTO activity_history (activity_id, change_type, description, created_at) VALUES (?, ?, ?, ?)",
        activity.id,
        changeType,
        description,
        Timestamp.from(Instant.parse(createdAt)),
    )

    private fun saveBestEffort(
        activity: Activity,
        benchmark: BenchmarkDistance,
        elapsedTimeSeconds: Double,
        startIndex: Int,
        endIndex: Int,
    ) = activityBestEffortRepository.save(
        ActivityBestEffort(
            activityId = activity.id!!,
            sport = ActivitySport.RUN,
            benchmarkDistance = benchmark,
            elapsedTimeSeconds = elapsedTimeSeconds,
            startIndex = startIndex,
            endIndex = endIndex,
            startTimeSeconds = startIndex.toDouble(),
            endTimeSeconds = startIndex + elapsedTimeSeconds,
            startDistanceMeters = 100.0,
            endDistanceMeters = 100.0 + benchmark.distanceMeters,
        ),
    )

    @Test
    fun `GET api activities id returns the activity's summary metrics, owner and Protected Metrics flag`() {
        val activity = saveActivity(runner)

        mockMvc
            .perform(get("/api/activities/{id}", activity.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(activity.id!!.toInt()))
            .andExpect(jsonPath("$.title").value("Track Session"))
            .andExpect(jsonPath("$.sport").value("RUN"))
            .andExpect(jsonPath("$.startedAt").value("2026-03-01T08:00:00Z"))
            .andExpect(jsonPath("$.distance").value(6.4))
            .andExpect(jsonPath("$.timeTakenSeconds").value(1920))
            .andExpect(jsonPath("$.averagePaceKmh").value(12.0))
            .andExpect(jsonPath("$.averageHeartRate").value(158))
            .andExpect(jsonPath("$.calories").value(480))
            .andExpect(jsonPath("$.source").value("FIT_UPLOAD"))
            .andExpect(jsonPath("$.hasProtectedMetrics").value(true))
            .andExpect(jsonPath("$.ownerUsername").value("detail_runner"))
            .andExpect(jsonPath("$.laps", hasSize<Any>(0)))
            .andExpect(jsonPath("$.history", hasSize<Any>(0)))
            .andExpect(jsonPath("$.bestEfforts", hasSize<Any>(0)))
    }

    @Test
    fun `GET api activities id reports Protected Metrics for a manual activity backed by Activity Details`() {
        val manual = saveActivity(runner, source = ActivitySource.MANUAL)
        mockMvc
            .perform(get("/api/activities/{id}", manual.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.source").value("MANUAL"))
            .andExpect(jsonPath("$.hasProtectedMetrics").value(false))

        activityDetailsRepository.upsert(
            manual.id!!,
            FitTelemetry(t = intArrayOf(0, 1), lons = DoubleArray(0), lats = DoubleArray(0), routeIndex = arrayOf(null, null)),
            ParserVersion(1),
        )

        mockMvc
            .perform(get("/api/activities/{id}", manual.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.hasProtectedMetrics").value(true))
    }

    @Test
    fun `GET api activities id lists device Laps in lap order with canonical metrics`() {
        val activity = saveActivity(runner)
        saveLap(activity, lapNumber = 2, elevationGainMeters = null)
        saveLap(activity, lapNumber = 1, elevationGainMeters = 12.5)

        mockMvc
            .perform(get("/api/activities/{id}", activity.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.laps[*].lapNumber", contains(1, 2)))
            .andExpect(jsonPath("$.laps[0].startedAt").value("2026-03-01T08:00:00Z"))
            .andExpect(jsonPath("$.laps[0].distance").value(1.0))
            .andExpect(jsonPath("$.laps[0].timeTakenSeconds").value(300))
            .andExpect(jsonPath("$.laps[0].averagePaceKmh").value(12.0))
            .andExpect(jsonPath("$.laps[0].averageHeartRate").value(151))
            .andExpect(jsonPath("$.laps[0].elevationGainMeters").value(12.5))
            .andExpect(jsonPath("$.laps[1].elevationGainMeters").value(nullValue()))
            .andExpect(jsonPath("$.laps[0].formattedDuration").doesNotExist())
    }

    @Test
    fun `GET api activities id lists Activity History Entries chronologically as typed and legacy changes`() {
        val activity = saveActivity(runner)
        recordHistory(
            activity,
            ActivityChange.Edited(listOf(FieldChange.Title("A", "B"), FieldChange.Distance(6.4, 6.5))),
            "2026-03-02T10:00:00Z",
        )
        saveLegacyHistory(activity, "CREATED", "Imported from FIT file", "2026-03-01T09:00:00Z")
        recordHistory(
            activity,
            ActivityChange.Upgraded(DetailsUpgrade.ParserVersionUpgraded(1, 2), LapsUpgrade.Replaced(3)),
            "2026-03-03T10:00:00Z",
        )

        mockMvc
            .perform(get("/api/activities/{id}", activity.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.history[*].change.kind", contains("LEGACY", "EDITED", "UPGRADED")))
            .andExpect(jsonPath("$.history[0].id").isNumber)
            .andExpect(jsonPath("$.history[0].createdAt").value("2026-03-01T09:00:00Z"))
            .andExpect(jsonPath("$.history[0].change.changeType").value("CREATED"))
            .andExpect(jsonPath("$.history[0].change.description").value("Imported from FIT file"))
            .andExpect(jsonPath("$.history[0].description").doesNotExist())
            .andExpect(jsonPath("$.history[0].changeType").doesNotExist())
            .andExpect(jsonPath("$.history[0].formattedCreatedAt").doesNotExist())
            .andExpect(jsonPath("$.history[1].change.fields[*].kind", contains("TITLE", "DISTANCE")))
            .andExpect(jsonPath("$.history[1].change.fields[0].from").value("A"))
            .andExpect(jsonPath("$.history[1].change.fields[0].to").value("B"))
            .andExpect(jsonPath("$.history[1].change.fields[1].to").value(6.5))
            .andExpect(jsonPath("$.history[2].change.details.kind").value("PARSER_VERSION_UPGRADED"))
            .andExpect(jsonPath("$.history[2].change.details.from").value(1))
            .andExpect(jsonPath("$.history[2].change.details.to").value(2))
            .andExpect(jsonPath("$.history[2].change.laps.kind").value("REPLACED"))
            .andExpect(jsonPath("$.history[2].change.laps.count").value(3))
    }

    @Test
    fun `GET api activities id lists Best Efforts shortest Benchmark Distance first with their EffortWindow`() {
        val activity = saveActivity(runner)
        saveBestEffort(activity, BenchmarkDistance.FIVE_KILOMETERS, elapsedTimeSeconds = 1250.0, startIndex = 30, endIndex = 1280)
        saveBestEffort(activity, BenchmarkDistance.ONE_KILOMETER, elapsedTimeSeconds = 230.5, startIndex = 10, endIndex = 240)

        mockMvc
            .perform(get("/api/activities/{id}", activity.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bestEfforts[*].benchmarkDistance", contains("ONE_KILOMETER", "FIVE_KILOMETERS")))
            .andExpect(jsonPath("$.bestEfforts[0].id").isNumber)
            .andExpect(jsonPath("$.bestEfforts[0].benchmarkName").value("1k"))
            .andExpect(jsonPath("$.bestEfforts[0].distanceMeters").value(1000.0))
            .andExpect(jsonPath("$.bestEfforts[0].elapsedTimeSeconds").value(230.5))
            .andExpect(jsonPath("$.bestEfforts[0].window.startIndex").value(10))
            .andExpect(jsonPath("$.bestEfforts[0].window.endIndex").value(240))
            .andExpect(jsonPath("$.bestEfforts[0].window.startTimeSeconds").value(10.0))
            .andExpect(jsonPath("$.bestEfforts[0].window.endTimeSeconds").value(240.5))
            .andExpect(jsonPath("$.bestEfforts[0].window.startDistanceMeters").value(100.0))
            .andExpect(jsonPath("$.bestEfforts[0].window.endDistanceMeters").value(1100.0))
            .andExpect(jsonPath("$.bestEfforts[0].formattedPace").doesNotExist())
    }

    /** Stores derived Run metrics for [activity] as the derived-data refresh would. */
    private fun saveRunMetrics(
        activity: Activity,
        reason: String? = null,
        decoupling: Double? = 4.25,
    ) = jdbcTemplate.update(
        """
        INSERT INTO activity_derived_metrics (activity_id, algorithm_version, moving_seconds, ngp_mps, efficiency_factor,
            aerobic_decoupling_pct, decoupling_unavailable_reason)
        VALUES (?, 2, 3540, 3.25, 1.3, ?, ?)
        ON CONFLICT (activity_id) DO UPDATE SET aerobic_decoupling_pct = EXCLUDED.aerobic_decoupling_pct,
            decoupling_unavailable_reason = EXCLUDED.decoupling_unavailable_reason
        """.trimIndent(),
        activity.id,
        decoupling,
        reason,
    )

    @Test
    fun `GET api activities id returns a run's Run metrics`() {
        val run = saveActivity(runner)
        saveRunMetrics(run)

        mockMvc
            .perform(get("/api/activities/{id}", run.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runMetrics.movingSeconds").value(3540))
            .andExpect(jsonPath("$.runMetrics.ngpMetersPerSecond").value(3.25))
            .andExpect(jsonPath("$.runMetrics.efficiencyFactor").value(1.3))
            .andExpect(jsonPath("$.runMetrics.aerobicDecouplingPercent").value(4.25))
            .andExpect(jsonPath("$.runMetrics.decouplingUnavailableReason").value(nullValue()))
    }

    @Test
    fun `GET api activities id gives the reason a run has no Aerobic Decoupling`() {
        val run = saveActivity(runner)
        saveRunMetrics(run, reason = "NOT_STEADY", decoupling = null)

        mockMvc
            .perform(get("/api/activities/{id}", run.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runMetrics.aerobicDecouplingPercent").value(nullValue()))
            .andExpect(jsonPath("$.runMetrics.decouplingUnavailableReason").value("NOT_STEADY"))
    }

    @Test
    fun `GET api activities id has null Run metrics for a run without a derived row and for a walk`() {
        val run = saveActivity(runner)
        jdbcTemplate.update("DELETE FROM activity_derived_metrics WHERE activity_id = ?", run.id)
        val walk = saveActivity(runner, source = ActivitySource.MANUAL).apply { sport = ActivitySport.WALK }
        activityRepository.save(walk)
        saveRunMetrics(walk)

        listOf(run, walk).forEach {
            mockMvc
                .perform(get("/api/activities/{id}", it.id).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.runMetrics").value(nullValue()))
        }
    }

    @Test
    fun `GET api activities id never returns another athlete's Run metrics`() {
        val notMine = saveActivity(otherRunner)
        saveRunMetrics(notMine)

        mockMvc
            .perform(get("/api/activities/{id}", notMine.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.runMetrics").doesNotExist())
    }

    @Test
    fun `GET api activities id returns a uniform 404 for another athlete's activity and a missing one`() {
        val notMine = saveActivity(otherRunner)
        saveLap(notMine, lapNumber = 1, elevationGainMeters = null)

        mockMvc
            .perform(get("/api/activities/{id}", notMine.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.laps").doesNotExist())

        mockMvc
            .perform(get("/api/activities/{id}", 999_999).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    @WithAnonymousUser
    fun `unauthenticated GET api activities id returns 401 UNAUTHORIZED envelope`() {
        mockMvc
            .perform(get("/api/activities/{id}", 1).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }
}
