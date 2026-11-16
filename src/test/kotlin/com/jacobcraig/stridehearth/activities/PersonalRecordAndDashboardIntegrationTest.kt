package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.DetailsUpgrade
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "pr_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class PersonalRecordAndDashboardIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var activityHistory: ActivityHistory

    @Autowired
    private lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var runner: User
    private lateinit var otherUser: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner =
            userRepository.save(
                User(
                    email = "pr_runner@example.com",
                    passwordHash = passwordEncoder.encode("secret123")!!,
                    username = "pr_runner",
                ),
            )
        otherUser =
            userRepository.save(
                User(
                    email = "other_runner@example.com",
                    passwordHash = passwordEncoder.encode("secret123")!!,
                    username = "other_runner",
                ),
            )
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** The athlete's **Dashboard** **Personal Record** slots as served to the SPA by `GET /api/dashboard`. */
    private fun dashboardSlots(): List<PersonalRecordSlotDTO> {
        val body =
            mockMvc
                .perform(get("/api/dashboard").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readValue(body, DashboardResponseDTO::class.java).personalRecordSlots
    }

    private fun buildRunTelemetry(
        totalDistanceMeters: Float,
        totalSeconds: Int,
    ): FitTelemetry =
        FitTelemetry(
            t = intArrayOf(0, totalSeconds),
            lons = doubleArrayOf(-0.16, -0.17),
            lats = doubleArrayOf(51.51, 51.52),
            routeIndex = arrayOf(0, 1),
            distance = arrayOf(0.0f, totalDistanceMeters),
        )

    @Test
    fun `importing faster and slower runs updates personal records with earliest-activity tie breaking and user isolation`() {
        // 1. Import initial 5k run in 25:00 (1500s) -> 300s per km (05:00 /km), 400m in 120s (02:00)
        val slowerRun =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.RUN,
                            title = "Steady 5k Run",
                            startedAt = Instant.parse("2026-04-05T08:00:00Z"),
                            distance = 5.0,
                            timeTakenSeconds = 1500,
                            externalId = "steady-5k",
                            telemetry = buildRunTelemetry(5000.0f, 1500),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        // 2. Import faster 5k run in 20:00 (1200s) -> 240s per km (04:00 /km), 400m in 96s (01:36)
        val fasterRun =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.RUN,
                            title = "Fast 5k Race",
                            startedAt = Instant.parse("2026-04-10T09:00:00Z"),
                            distance = 5.0,
                            timeTakenSeconds = 1200,
                            externalId = "fast-5k",
                            telemetry = buildRunTelemetry(5000.0f, 1200),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        // 3. Import another slower 5k run in 22:00 (1320s) -> should NOT overwrite the 20:00 PR
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = ActivitySport.RUN,
                    title = "Later Slower 5k",
                    startedAt = Instant.parse("2026-04-12T08:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1320,
                    externalId = "later-slower-5k",
                    telemetry = buildRunTelemetry(5000.0f, 1320),
                ),
            ),
            runner.id!!,
        )

        // 4. Import a tied 5k run (1200s) with a LATER startedAt -> should NOT overwrite fasterRun
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = ActivitySport.RUN,
                    title = "Tied Later 5k",
                    startedAt = Instant.parse("2026-04-15T09:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1200,
                    externalId = "tied-later-5k",
                    telemetry = buildRunTelemetry(5000.0f, 1200),
                ),
            ),
            runner.id!!,
        )

        // 5. Import a tied 5k run (1200s) with an EARLIER startedAt -> SHOULD win tie-breaker!
        val tiedEarlierRun =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.RUN,
                            title = "Historical Tied 5k Winner",
                            startedAt = Instant.parse("2026-03-01T07:00:00Z"),
                            distance = 5.0,
                            timeTakenSeconds = 1200,
                            externalId = "tied-earlier-5k",
                            telemetry = buildRunTelemetry(5000.0f, 1200),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        // 6. Other user imports an even faster 5k (900s) -> must NOT leak onto runner's dashboard
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = ActivitySport.RUN,
                    title = "Other User World Record",
                    startedAt = Instant.parse("2026-04-01T08:00:00Z"),
                    distance = 10.0,
                    timeTakenSeconds = 1800,
                    externalId = "other-wr",
                    telemetry = buildRunTelemetry(10000.0f, 1800),
                ),
            ),
            otherUser.id!!,
        )

        val slots = dashboardSlots()
        val slot5k = slots.single { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
        assertNotNull(slot5k.personalRecord)
        assertEquals(tiedEarlierRun.id, slot5k.personalRecord.activityId)
        assertEquals("Historical Tied 5k Winner", slot5k.personalRecord.activityTitle)
        assertEquals(Instant.parse("2026-03-01T07:00:00Z"), slot5k.personalRecord.activityStartedAt)
        assertEquals(1200.0, slot5k.personalRecord.elapsedTimeSeconds, 1e-6)
        assertEquals(5000.0, slot5k.distanceMeters)
        assertTrue(
            slots.mapNotNull { it.personalRecord?.activityTitle }.none { it == "Other User World Record" || it == "Steady 5k Run" },
            "neither a slower Activity nor another athlete's record may appear on the Dashboard",
        )

        val slot10k = slots.single { it.benchmarkDistance == BenchmarkDistance.TEN_KILOMETERS }
        assertNull(slot10k.personalRecord)
        val slotHm = slots.single { it.benchmarkDistance == BenchmarkDistance.HALF_MARATHON }
        assertNull(slotHm.personalRecord)
        assertNotNull(slowerRun.id)
        assertNotNull(fasterRun.id)
    }

    @Test
    fun `concurrent multi-threaded imports for the same user atomically converge on the fastest personal record without deadlock`() {
        val durations = listOf(1800, 1650, 1500, 1350, 1200, 1400, 1600, 1750)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks =
                durations.mapIndexed { idx, durationSec ->
                    Callable {
                        activityImportService.importParsed(
                            listOf(
                                parsedActivity(
                                    sport = ActivitySport.RUN,
                                    title = "Concurrent Run $durationSec",
                                    startedAt = Instant.parse("2026-04-01T08:00:00Z").plusSeconds(idx * 3600L),
                                    distance = 5.0,
                                    timeTakenSeconds = durationSec,
                                    externalId = "conc-$idx",
                                    telemetry = buildRunTelemetry(5000.0f, durationSec),
                                ),
                            ),
                            runner.id!!,
                        )
                    }
                }
            val futures = pool.invokeAll(tasks)
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val slots = dashboardSlots()
        val slot5k = slots.single { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
        assertNotNull(slot5k.personalRecord)
        assertEquals("Concurrent Run 1200", slot5k.personalRecord.activityTitle)
        assertEquals(1200.0, slot5k.personalRecord.elapsedTimeSeconds, 1e-6)
    }

    @Test
    fun `deleting PR activity via single delete or batch delete recalibrates to second fastest or clears record when none remain`() {
        val fastest10k =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.RUN,
                            title = "Fastest 10k and 5k",
                            startedAt = Instant.parse("2026-04-01T08:00:00Z"),
                            distance = 10.0,
                            timeTakenSeconds = 2400, // 5k in 1200s (20:00), 10k in 2400s (40:00)
                            externalId = "del-fast-10k",
                            telemetry = buildRunTelemetry(10000.0f, 2400),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        val secondFastest5k =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.RUN,
                            title = "Second Fastest 5k",
                            startedAt = Instant.parse("2026-04-02T08:00:00Z"),
                            distance = 5.0,
                            timeTakenSeconds = 1350, // 5k in 1350s (22:30)
                            externalId = "del-second-5k",
                            telemetry = buildRunTelemetry(5000.0f, 1350),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        // Single delete the fastest activity (holds both 5k and 10k PRs)
        mockMvc
            .perform(delete("/api/activities/{id}", fastest10k.id).with(csrf()))
            .andExpect(status().isNoContent)

        // Verify 5k fell back to Second Fastest 5k, and 10k is cleared (null)
        val slotsAfterSingle = dashboardSlots()
        val slot5kAfterSingle = slotsAfterSingle.single { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
        assertNotNull(slot5kAfterSingle.personalRecord)
        assertEquals(secondFastest5k.id, slot5kAfterSingle.personalRecord.activityId)
        assertEquals("Second Fastest 5k", slot5kAfterSingle.personalRecord.activityTitle)
        assertEquals(1350.0, slot5kAfterSingle.personalRecord.elapsedTimeSeconds, 1e-6)

        val slot10kAfterSingle = slotsAfterSingle.single { it.benchmarkDistance == BenchmarkDistance.TEN_KILOMETERS }
        assertNull(slot10kAfterSingle.personalRecord)

        // Batch delete the remaining activity -> all PR slots become null
        mockMvc
            .perform(
                post("/api/activities/batch-delete")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"activityIds":[${secondFastest5k.id}]}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.affectedCount").value(1))

        val slotsAfterBatch = dashboardSlots()
        assertTrue(slotsAfterBatch.all { it.personalRecord == null })
    }

    @Test
    fun `reclassifying sport away from RUN and back to RUN via single and batch edit recalibrates and rescans telemetry`() {
        val fastRun =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.RUN,
                            title = "Fast 5k Run",
                            startedAt = Instant.parse("2026-04-01T08:00:00Z"),
                            distance = 5.0,
                            timeTakenSeconds = 1140, // 19:00
                            externalId = "reclass-fast",
                            telemetry = buildRunTelemetry(5000.0f, 1140),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        val backupRun =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.RUN,
                            title = "Backup 5k Run",
                            startedAt = Instant.parse("2026-04-02T08:00:00Z"),
                            distance = 5.0,
                            timeTakenSeconds = 1380, // 23:00
                            externalId = "reclass-backup",
                            telemetry = buildRunTelemetry(5000.0f, 1380),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        val misclassifiedWalk =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.WALK,
                            title = "Misclassified 10k Rocket",
                            startedAt = Instant.parse("2026-04-03T08:00:00Z"),
                            distance = 10.0,
                            timeTakenSeconds = 2100, // 5k in 1050s (17:30), 10k in 2100s (35:00)
                            externalId = "reclass-walk",
                            telemetry = buildRunTelemetry(10000.0f, 2100),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        // 1. Single edit: change fastRun from RUN to RIDE -> 5k PR falls back to backupRun (23:00)
        mockMvc
            .perform(
                put("/api/activities/{id}", fastRun.id)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"title":"Fast 5k Run","sport":"RIDE"}"""),
            ).andExpect(status().isOk)

        val slotsAfterDemote = dashboardSlots()
        val slot5kDemoted = slotsAfterDemote.single { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
        assertNotNull(slot5kDemoted.personalRecord)
        assertEquals(backupRun.id, slot5kDemoted.personalRecord.activityId)

        // 2. Batch edit: change misclassifiedWalk from WALK to RUN -> scans stored telemetry and sets new 5k (17:30) and 10k (35:00) PRs
        mockMvc
            .perform(
                patch("/api/activities/batch")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"activityIds":[${misclassifiedWalk.id}],"sport":"RUN"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.affectedCount").value(1))

        val slotsAfterPromote = dashboardSlots()
        val slot5kPromoted = slotsAfterPromote.single { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
        assertNotNull(slot5kPromoted.personalRecord)
        assertEquals(misclassifiedWalk.id, slot5kPromoted.personalRecord.activityId)
        assertEquals(1050.0, slot5kPromoted.personalRecord.elapsedTimeSeconds, 1e-6)

        val slot10kPromoted = slotsAfterPromote.single { it.benchmarkDistance == BenchmarkDistance.TEN_KILOMETERS }
        assertNotNull(slot10kPromoted.personalRecord)
        assertEquals(misclassifiedWalk.id, slot10kPromoted.personalRecord.activityId)
        assertEquals(2100.0, slot10kPromoted.personalRecord.elapsedTimeSeconds, 1e-6)

        // 3. Batch edit: change misclassifiedWalk from RUN back to WALK -> 10k cleared, 5k falls back to backupRun
        mockMvc
            .perform(
                patch("/api/activities/batch")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"activityIds":[${misclassifiedWalk.id}],"sport":"WALK"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.affectedCount").value(1))

        val slotsAfterBatchDemote = dashboardSlots()
        assertEquals(
            backupRun.id,
            slotsAfterBatchDemote
                .single { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
                .personalRecord
                ?.activityId,
        )
        assertNull(
            slotsAfterBatchDemote.single { it.benchmarkDistance == BenchmarkDistance.TEN_KILOMETERS }.personalRecord,
        )
    }

    @Test
    fun `parsed fixtures stamped with any Parser Version import through the public import path`() {
        val stamped =
            listOf(ParserVersion(0), ParserVersion(42)).mapIndexed { i, version ->
                parsedActivity(
                    sport = ActivitySport.RUN,
                    title = "Stamped v${version.value} Run",
                    startedAt = Instant.parse("2026-05-0${i + 1}T08:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1500,
                    externalId = "stamped-run-$i",
                    telemetry = buildRunTelemetry(5000.0f, 1500),
                    parserVersion = version,
                )
            }

        val outcome = activityImportService.importParsed(stamped, runner.id!!)

        assertIs<ImportOutcome.Created>(outcome)
        assertEquals(listOf("Stamped v0 Run", "Stamped v42 Run"), outcome.created.map { it.title })
    }

    @Test
    fun `parser version upgrade on existing activity recalibrates personal records when interpolated time changes`() {
        // Import an activity initially with 5k in 1100s (fastest)
        val upgradedRun =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.RUN,
                            title = "Upgraded Run",
                            startedAt = Instant.parse("2026-04-01T08:00:00Z"),
                            distance = 5.0,
                            timeTakenSeconds = 1100,
                            externalId = "upgrade-pr-run",
                            telemetry = buildRunTelemetry(5000.0f, 1100),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        // Import a second run with 5k in 1200s
        val steadyRun =
            activityImportService
                .importParsed(
                    listOf(
                        parsedActivity(
                            sport = ActivitySport.RUN,
                            title = "Steady True PR Run",
                            startedAt = Instant.parse("2026-04-02T08:00:00Z"),
                            distance = 5.0,
                            timeTakenSeconds = 1200,
                            externalId = "steady-pr-run",
                            telemetry = buildRunTelemetry(5000.0f, 1200),
                        ),
                    ),
                    runner.id!!,
                ).createdActivities
                .single()

        // Re-import upgradedRun decoded by a newer Parser Version, whose corrected telemetry is slower (1300s)
        // -> PR should recalibrate to steadyRun (1200s)
        val upgrade =
            activityImportService.importParsed(
                listOf(
                    parsedActivity(
                        sport = ActivitySport.RUN,
                        title = "Upgraded Run",
                        startedAt = Instant.parse("2026-04-01T08:00:00Z"),
                        distance = 5.0,
                        timeTakenSeconds = 1300,
                        externalId = "upgrade-pr-run",
                        telemetry = buildRunTelemetry(5000.0f, 1300),
                        parserVersion = ParserVersion(FitActivityParser.PARSER_VERSION.value + 1),
                    ),
                ),
                runner.id!!,
            )
        assertEquals(listOf(upgradedRun.id), assertIs<ImportOutcome.Upgraded>(upgrade).upgrades.map { it.activity.id })

        val slots = dashboardSlots()
        val slot5k = slots.single { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
        assertNotNull(slot5k.personalRecord)
        assertEquals(steadyRun.id, slot5k.personalRecord.activityId)
        assertEquals(1200.0, slot5k.personalRecord.elapsedTimeSeconds, 1e-6)
    }

    @Test
    fun `importing and re-importing FIT file with omitted record distance calculates and persists personal records on dashboard`() {
        val startInstant = Instant.parse("2026-04-20T08:00:00Z")
        val degToSemicircles = 2147483648.0 / 180.0
        val fileId =
            com.garmin.fit.FileIdMesg().apply {
                type = com.garmin.fit.File.ACTIVITY
                manufacturer = 255
                product = 0
                timeCreated = com.garmin.fit.DateTime(startInstant)
            }
        val session =
            com.garmin.fit.SessionMesg().apply {
                startTime = com.garmin.fit.DateTime(startInstant)
                totalDistance = 1111.95f
                totalTimerTime = 240f
                totalElapsedTime = 240f
                sport = com.garmin.fit.Sport.RUNNING
            }
        // 3 records spanning ~1111.95m along the equator with NO RecordMesg.distance field set
        val rec0 =
            com.garmin.fit.RecordMesg().apply {
                timestamp = com.garmin.fit.DateTime(startInstant)
                positionLat = 0
                positionLong = 0
                enhancedSpeed = 4.63f
            }
        val rec1 =
            com.garmin.fit.RecordMesg().apply {
                timestamp = com.garmin.fit.DateTime(startInstant.plusSeconds(120))
                positionLat = 0
                positionLong = (0.005 * degToSemicircles).toInt()
                enhancedSpeed = 4.63f
            }
        val rec2 =
            com.garmin.fit.RecordMesg().apply {
                timestamp = com.garmin.fit.DateTime(startInstant.plusSeconds(240))
                positionLat = 0
                positionLong = (0.010 * degToSemicircles).toInt()
                enhancedSpeed = 4.63f
            }
        val fitBytes =
            com.garmin.fit.BufferEncoder(com.garmin.fit.Fit.ProtocolVersion.V2_0).run {
                listOf(fileId, session, rec0, rec1, rec2).forEach { write(it) }
                close()
            }

        // 1. Fresh import of FIT file with omitted RecordMesg.distance populates 400m and 1k PRs
        val imported = activityImportService.import(ByteArrayResource(fitBytes), runner.id!!).createdActivities.single()

        val freshSlots = dashboardSlots()
        assertNotNull(freshSlots.single { it.benchmarkDistance == BenchmarkDistance.FOUR_HUNDRED_METERS }.personalRecord)
        assertNotNull(freshSlots.single { it.benchmarkDistance == BenchmarkDistance.ONE_KILOMETER }.personalRecord)

        // 2. Simulate legacy state: null out distance array and clear best efforts / PRs at same parser_version = 1
        jdbcTemplate.update(
            "UPDATE activity_details SET distance = ARRAY[NULL::real, NULL::real, NULL::real] WHERE activity_id = ?",
            imported.id,
        )
        jdbcTemplate.update("DELETE FROM activity_best_effort WHERE activity_id = ?", imported.id)
        jdbcTemplate.update("DELETE FROM user_personal_record WHERE user_id = ?", runner.id)

        // Re-importing the same FIT file without deleting upgrades the all-null distance array and restores PRs
        val reimportOutcome = activityImportService.import(ByteArrayResource(fitBytes), runner.id!!)
        // A same-Parser-Version distance repair, never a "v1 -> v1" Parser Version upgrade.
        val distanceRepair = ActivityChange.Upgraded(DetailsUpgrade.DistanceBackfilled(FitActivityParser.PARSER_VERSION.value), laps = null)
        assertEquals(
            listOf(distanceRepair),
            assertIs<ImportOutcome.Upgraded>(reimportOutcome).upgrades.map { ActivityChange.Upgraded.of(it.change) },
        )
        assertEquals(distanceRepair, activityHistory.entriesFor(imported.id!!).last().change)

        val reimportedSlots = dashboardSlots()
        val slot1k = reimportedSlots.single { it.benchmarkDistance == BenchmarkDistance.ONE_KILOMETER }
        assertNotNull(slot1k.personalRecord)
        assertEquals(imported.id, slot1k.personalRecord.activityId)
    }
}
