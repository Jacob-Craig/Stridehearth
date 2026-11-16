package com.jacobcraig.stridehearth.upload

import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.FileIdMesg
import com.garmin.fit.LapMesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityBestEffortRepository
import com.jacobcraig.stridehearth.activities.ActivityLapRepository
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.BenchmarkDistance
import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.DetailsUpgrade
import com.jacobcraig.stridehearth.activities.history.LapsUpgrade
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertTrue
import com.garmin.fit.File as FitFile

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ActivityFileUploadIntegrationTest {
    @Autowired
    lateinit var postgres: PostgreSQLContainer

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var activityRepository: ActivityRepository

    @Autowired
    lateinit var activityLapRepository: ActivityLapRepository

    @Autowired
    lateinit var activityBestEffortRepository: ActivityBestEffortRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var activityDetailsRepository: com.jacobcraig.stridehearth.activities.ActivityDetailsRepository

    @Autowired
    lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

    @Autowired
    lateinit var activityHistory: ActivityHistory

    private lateinit var testUser: User

    private val currentParserVersion = FitActivityParser.PARSER_VERSION.value

    @BeforeEach
    fun setUp() {
        testUser = userRepository.save(User(email = "runner@example.com", passwordHash = "x", username = "runner"))
    }

    @AfterEach
    fun tearDown() {
        activityBestEffortRepository.deleteAll()
        activityLapRepository.deleteAll()
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `context loads and container is running`() {
        assertTrue(postgres.isRunning)
    }

    @Test
    fun `test upload single sample FIT file`() {
        val fitFileInputStream = ClassPathResource("runs/sample.fit").inputStream
        val fitMultipartFile =
            MockMultipartFile("file", "sample.fit", "application/octet-stream", fitFileInputStream.readBytes())

        val activityId = uploadCreated(fitMultipartFile)

        mockMvc
            .perform(get("/api/activities/{id}", activityId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.distance").value(closeTo(10.20, 0.01)))
            .andExpect(jsonPath("$.sport").value("RUN"))
            .andExpect(jsonPath("$.source").value("FIT_UPLOAD"))
            .andExpect(jsonPath("$.hasProtectedMetrics").value(true))

        val telemetryJson = activityDetailsRepository.findTelemetryJson(activityId, testUser.id!!)
        kotlin.test.assertNotNull(telemetryJson, "Telemetry JSON should be populated in activity_details")
        assertTrue(telemetryJson.contains("\"t\":"), "Timeline array t must exist")
        assertTrue(telemetryJson.contains("\"route\":"), "Route GeoJSON must exist")

        val laps = activityLapRepository.findByActivityIdOrderByLapNumberAsc(activityId)
        assertEquals(1, laps.size)
        assertEquals(1, laps[0].lapNumber)

        val bestEfforts = activityBestEffortRepository.findByActivityId(activityId)
        assertEquals(5, bestEfforts.size)
        val benchmarkSet = bestEfforts.map { it.benchmarkDistance }.toSet()
        assertEquals(
            setOf(
                BenchmarkDistance.FOUR_HUNDRED_METERS,
                BenchmarkDistance.ONE_KILOMETER,
                BenchmarkDistance.ONE_MILE,
                BenchmarkDistance.FIVE_KILOMETERS,
                BenchmarkDistance.TEN_KILOMETERS,
            ),
            benchmarkSet,
        )
        assertTrue(bestEfforts.all { it.sport == ActivitySport.RUN })
        assertTrue(bestEfforts.all { it.elapsedTimeSeconds > 0.0 })

        mockMvc
            .perform(get("/api/activities/{id}", activityId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.laps.length()").value(1))
            .andExpect(jsonPath("$.bestEfforts[*].benchmarkName").value(contains("400m", "1k", "1 mile", "5k", "10k")))
    }

    private fun encodeFitFile(vararg mesgs: com.garmin.fit.Mesg): ByteArray =
        BufferEncoder(com.garmin.fit.Fit.ProtocolVersion.V2_0).run {
            mesgs.forEach { write(it) }
            close()
        }

    @Test
    fun `upload multi-lap FIT file persists activity_laps and exposes them in lap order on the activity`() {
        val startInstant = Instant.parse("2026-06-01T06:00:00Z")
        val fid =
            FileIdMesg().apply {
                type = FitFile.ACTIVITY
                serialNumber = 5555L
                manufacturer = 1
                product = 42
                timeCreated = DateTime(startInstant)
            }
        val sess =
            SessionMesg().apply {
                startTime = DateTime(startInstant)
                totalDistance = 3000f
                totalTimerTime = 900f
                totalElapsedTime = 900f
                sport = Sport.RUNNING
                firstLapIndex = 0
                numLaps = 2
            }
        val lap1 =
            LapMesg().apply {
                startTime = DateTime(startInstant)
                totalDistance = 1000f
                totalTimerTime = 300f
                totalElapsedTime = 300f
                enhancedAvgSpeed = 3.333f
                avgHeartRate = 145
                totalAscent = 15
            }
        val lap2 =
            LapMesg().apply {
                startTime = DateTime(startInstant.plusSeconds(300))
                totalDistance = 2000f
                totalTimerTime = 600f
                totalElapsedTime = 600f
                enhancedAvgSpeed = 3.333f
                avgHeartRate = 160
                totalAscent = 25
            }

        val fitBytes = encodeFitFile(fid, sess, lap1, lap2)
        val fitMultipart = MockMultipartFile("file", "multilap.fit", "application/octet-stream", fitBytes)

        val activityId = uploadCreated(fitMultipart)

        val persistedLaps = activityLapRepository.findByActivityIdOrderByLapNumberAsc(activityId)
        assertEquals(2, persistedLaps.size)

        mockMvc
            .perform(get("/api/activities/{id}", activityId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.laps.length()").value(2))
            .andExpect(jsonPath("$.laps[0].lapNumber").value(1))
            .andExpect(jsonPath("$.laps[0].distance").value(closeTo(1.0, 0.001)))
            .andExpect(jsonPath("$.laps[0].timeTakenSeconds").value(300))
            .andExpect(jsonPath("$.laps[0].averageHeartRate").value(145))
            .andExpect(jsonPath("$.laps[0].elevationGainMeters").value(closeTo(15.0, 0.001)))
            .andExpect(jsonPath("$.laps[1].lapNumber").value(2))
            .andExpect(jsonPath("$.laps[1].distance").value(closeTo(2.0, 0.001)))
            .andExpect(jsonPath("$.laps[1].timeTakenSeconds").value(600))
            .andExpect(jsonPath("$.laps[1].averageHeartRate").value(160))
            .andExpect(jsonPath("$.laps[1].elevationGainMeters").value(closeTo(25.0, 0.001)))
    }

    @Test
    fun `upload multi-lap FIT file with omitted lap summary fields computes fallback metrics from records`() {
        val startInstant = Instant.parse("2026-06-01T06:00:00Z")
        val fid =
            FileIdMesg().apply {
                type = FitFile.ACTIVITY
                serialNumber = 6666L
                manufacturer = 1
                product = 42
                timeCreated = DateTime(startInstant)
            }
        val sess =
            SessionMesg().apply {
                startTime = DateTime(startInstant)
                totalDistance = 2000f
                totalTimerTime = 600f
                totalElapsedTime = 600f
                sport = Sport.RUNNING
                firstLapIndex = 0
                numLaps = 2
            }
        val lap1 =
            LapMesg().apply {
                startTime = DateTime(startInstant)
                totalDistance = 1000f
                totalTimerTime = 300f
                totalElapsedTime = 300f
                enhancedAvgSpeed = 3.333f
            }
        val lap2 =
            LapMesg().apply {
                startTime = DateTime(startInstant.plusSeconds(300))
                totalDistance = 1000f
                totalTimerTime = 300f
                totalElapsedTime = 300f
                enhancedAvgSpeed = 3.333f
            }
        val r1 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(60))
                heartRate = 140
                enhancedAltitude = 10f
            }
        val r2 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(180))
                heartRate = 160
                enhancedAltitude = 30f
            }
        val r3 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(360))
                heartRate = 165
                enhancedAltitude = 30f
            }
        val r4 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(480))
                heartRate = 175
                enhancedAltitude = 55f
            }

        val fitBytes = encodeFitFile(fid, sess, r1, r2, r3, r4, lap1, lap2)
        val fitMultipart = MockMultipartFile("file", "omitted_metrics.fit", "application/octet-stream", fitBytes)

        val activityId = uploadCreated(fitMultipart)

        val persistedLaps = activityLapRepository.findByActivityIdOrderByLapNumberAsc(activityId)
        assertEquals(2, persistedLaps.size)

        // Lap 1 fallback: mean(140, 160) = 150 bpm; ascent: 30 - 10 = +20.00 m
        assertEquals(150, persistedLaps[0].averageHeartRate)
        assertEquals(20.0, persistedLaps[0].elevationGainMeters)

        // Lap 2 fallback: mean(165, 175) = 170 bpm; ascent: 55 - 30 = +25.00 m
        assertEquals(170, persistedLaps[1].averageHeartRate)
        assertEquals(25.0, persistedLaps[1].elevationGainMeters)

        mockMvc
            .perform(get("/api/activities/{id}", activityId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.laps[0].averageHeartRate").value(150))
            .andExpect(jsonPath("$.laps[0].elevationGainMeters").value(closeTo(20.0, 0.001)))
            .andExpect(jsonPath("$.laps[1].averageHeartRate").value(170))
            .andExpect(jsonPath("$.laps[1].elevationGainMeters").value(closeTo(25.0, 0.001)))
    }

    @Test
    fun `test for idempotency in multiple same activity upload`() {
        val fitFileInputStream = ClassPathResource("runs/sample.fit").inputStream
        val fitMultipartFile =
            MockMultipartFile("file", "sample.fit", "application/octet-stream", fitFileInputStream.readBytes())

        val activityId = uploadCreated(fitMultipartFile)

        upload(fitMultipartFile)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("DUPLICATE"))
        assertEquals(1, activityRepository.count())
        assertTrue(activityRepository.findByIdAndUserId(activityId, testUser.id!!) != null)
    }

    @Test
    fun `uploading a new FIT file records initial CREATED history entry with parser version and laps`() {
        val fitFileInputStream = ClassPathResource("runs/sample.fit").inputStream
        val fitMultipartFile =
            MockMultipartFile("file", "sample.fit", "application/octet-stream", fitFileInputStream.readBytes())

        val activityId = uploadCreated(fitMultipartFile)

        val activity = activityRepository.findByIdOrNull(activityId)
        checkNotNull(activity)

        assertEquals(
            listOf(ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = currentParserVersion, lapCount = 1)),
            activityHistory.entriesFor(activityId).map { it.change },
        )
    }

    /** The UPGRADED entries recorded for [activityId], oldest first. */
    private fun upgradesOf(activityId: Long) =
        activityHistory.entriesFor(activityId).map { it.change }.filterIsInstance<ActivityChange.Upgraded>()

    @Test
    fun `re-uploading FIT file matching existing activity with no details backfills details and laps, preserving title and sport`() {
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val upload1 = MockMultipartFile("file", "sample.fit", "application/octet-stream", fitBytes)

        val activityId = uploadCreated(upload1)

        val activity = activityRepository.findByIdOrNull(activityId)!!
        activity.title = "User Customized Title"
        activity.sport = ActivitySport.RIDE
        activityRepository.save(activity)

        // Clear details and laps to simulate activity created before telemetry/laps
        jdbcTemplate.update("DELETE FROM activity_details WHERE activity_id = ?", activityId)
        jdbcTemplate.update("DELETE FROM activity_laps WHERE activity_id = ?", activityId)

        val upload2 = MockMultipartFile("file", "sample.fit", "application/octet-stream", fitBytes)
        upload(upload2)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("UPGRADED"))
            .andExpect(jsonPath("$.activityId").value(activityId))

        // Assert title and sport preserved
        val refreshedActivity = activityRepository.findByIdOrNull(activityId)!!
        assertEquals("User Customized Title", refreshedActivity.title)
        assertEquals(ActivitySport.RIDE, refreshedActivity.sport)

        // Assert telemetry backfilled
        val parserVersion =
            jdbcTemplate.queryForObject(
                "SELECT parser_version FROM activity_details WHERE activity_id = ?",
                Short::class.javaObjectType,
                activityId,
            )
        assertEquals(com.jacobcraig.stridehearth.system.fit.FitActivityParser.PARSER_VERSION.value, parserVersion)

        // Assert laps backfilled
        val lapCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activity_laps WHERE activity_id = ?",
                Int::class.javaObjectType,
                activityId,
            )
        assertEquals(1, lapCount)

        // Assert UPGRADED history entry recorded
        assertEquals(
            listOf(ActivityChange.Upgraded(DetailsUpgrade.Backfilled(currentParserVersion), LapsUpgrade.Replaced(1))),
            upgradesOf(activityId),
        )
    }

    @Test
    fun `re-uploading FIT file matching existing activity with older parser version upgrades details and refreshes laps`() {
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val upload1 = MockMultipartFile("file", "sample.fit", "application/octet-stream", fitBytes)

        val activityId = uploadCreated(upload1)

        val activity = activityRepository.findByIdOrNull(activityId)!!
        activity.title = "Custom Title V0"
        activity.sport = ActivitySport.RIDE
        activityRepository.save(activity)

        // Downgrade stored parser version to 0
        jdbcTemplate.update("UPDATE activity_details SET parser_version = 0 WHERE activity_id = ?", activityId)

        val upload2 = MockMultipartFile("file", "sample.fit", "application/octet-stream", fitBytes)
        upload(upload2)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("UPGRADED"))
            .andExpect(jsonPath("$.activityId").value(activityId))

        val refreshedActivity = activityRepository.findByIdOrNull(activityId)!!
        assertEquals("Custom Title V0", refreshedActivity.title)
        assertEquals(ActivitySport.RIDE, refreshedActivity.sport)

        val parserVersion =
            jdbcTemplate.queryForObject(
                "SELECT parser_version FROM activity_details WHERE activity_id = ?",
                Short::class.javaObjectType,
                activityId,
            )
        assertEquals(com.jacobcraig.stridehearth.system.fit.FitActivityParser.PARSER_VERSION.value, parserVersion)

        assertEquals(
            listOf(ActivityChange.Upgraded(DetailsUpgrade.ParserVersionUpgraded(0, currentParserVersion), LapsUpgrade.Replaced(1))),
            upgradesOf(activityId),
        )
    }

    @Test
    fun `re-uploading FIT file matching existing activity with current parser version but missing laps backfills laps`() {
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val upload1 = MockMultipartFile("file", "sample.fit", "application/octet-stream", fitBytes)

        val activityId = uploadCreated(upload1)

        // Clear laps
        jdbcTemplate.update("DELETE FROM activity_laps WHERE activity_id = ?", activityId)

        val upload2 = MockMultipartFile("file", "sample.fit", "application/octet-stream", fitBytes)
        upload(upload2)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("UPGRADED"))
            .andExpect(jsonPath("$.activityId").value(activityId))

        val lapCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activity_laps WHERE activity_id = ?",
                Int::class.javaObjectType,
                activityId,
            )
        assertEquals(1, lapCount)

        assertEquals(listOf(ActivityChange.Upgraded(details = null, laps = LapsUpgrade.Backfilled(1))), upgradesOf(activityId))
    }

    private fun upload(file: MockMultipartFile): ResultActions =
        mockMvc.perform(multipart("/api/activities/upload").file(file).with(csrf()))

    /** Uploads [file], expecting exactly one new **Activity**, and returns its id. */
    private fun uploadCreated(file: MockMultipartFile): Long {
        val body =
            upload(file)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.kind").value("CREATED"))
                .andReturn()
                .response.contentAsString
        return JsonPath.read<Number>(body, "$.activityId").toLong()
    }
}
