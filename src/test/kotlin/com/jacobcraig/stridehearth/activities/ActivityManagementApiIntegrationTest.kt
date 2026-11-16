package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.FieldChange
import com.jacobcraig.stridehearth.api.CONCURRENT_CHANGE_MESSAGE
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The JSON contract behind the bulk **Activity** management workspace (`/activities/manage`):
 * filtered listing (including `size=all`), batch update, batch delete and **Best Efforts** backfill.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "manage_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ActivityManagementApiIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @MockitoSpyBean
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityHistory: ActivityHistory

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var runner: User
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "manage_runner@example.com", passwordHash = "x", username = "manage_runner"))
        otherRunner = userRepository.save(User(email = "manage_other@example.com", passwordHash = "x", username = "manage_other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun saveActivity(
        owner: User,
        title: String,
        sport: ActivitySport = ActivitySport.RUN,
        source: ActivitySource = ActivitySource.MANUAL,
        startedAt: Instant = Instant.parse("2026-05-01T08:00:00Z"),
    ): Activity =
        activityRepository.save(
            Activity(
                title = title,
                sport = sport,
                source = source,
                startedAt = startedAt,
                distance = 10.0,
                timeTakenSeconds = 3600,
                user = owner,
                externalId = if (source == ActivitySource.MANUAL) null else "$title-$startedAt",
            ),
        )

    private fun importRun(
        title: String,
        startedAt: String,
        distanceMeters: Float,
        totalSeconds: Int,
    ): Activity =
        activityImportService
            .importParsed(
                listOf(
                    parsedActivity(
                        sport = ActivitySport.RUN,
                        title = title,
                        startedAt = Instant.parse(startedAt),
                        distance = distanceMeters / 1000.0,
                        timeTakenSeconds = totalSeconds,
                        externalId = "$title-$startedAt",
                        telemetry = straightLineTelemetry(distanceMeters, totalSeconds),
                    ),
                ),
                runner.id!!,
                ActivitySource.FIT_UPLOAD,
            ).createdActivities
            .single()

    private fun straightLineTelemetry(
        distanceMeters: Float,
        totalSeconds: Int,
    ) = FitTelemetry(
        t = intArrayOf(0, totalSeconds),
        lons = doubleArrayOf(-0.16, -0.17),
        lats = doubleArrayOf(51.51, 51.52),
        routeIndex = arrayOf(0, 1),
        distance = arrayOf(0.0f, distanceMeters),
    )

    private fun expectFiveKilometerRecordHeldBy(title: String?) {
        val result =
            mockMvc
                .perform(get("/api/dashboard").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.personalRecordSlots[3].benchmarkDistance").value("FIVE_KILOMETERS"))
        if (title == null) {
            result.andExpect(jsonPath("$.personalRecordSlots[3].personalRecord").doesNotExist())
        } else {
            result.andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.activityTitle").value(title))
        }
    }

    private fun patchBatch(body: String) =
        mockMvc.perform(patch("/api/activities/batch").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun postBatchDelete(body: String) =
        mockMvc.perform(post("/api/activities/batch-delete").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `GET api activities filters the athlete's activities by title query, sport and Activity Source`() {
        saveActivity(runner, "Morning Tempo Run", ActivitySport.RUN, ActivitySource.MANUAL)
        saveActivity(runner, "Afternoon Trail Ride", ActivitySport.RIDE, ActivitySource.FIT_UPLOAD)
        saveActivity(runner, "Evening tempo spin", ActivitySport.RIDE, ActivitySource.MANUAL)
        saveActivity(otherRunner, "Other Tempo Run", ActivitySport.RUN, ActivitySource.MANUAL)

        mockMvc
            .perform(get("/api/activities").param("q", "  TEMPO ").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].title", containsInAnyOrder("Morning Tempo Run", "Evening tempo spin")))
            .andExpect(jsonPath("$.totalElements").value(2))

        mockMvc
            .perform(get("/api/activities").param("sport", "RIDE").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].title", containsInAnyOrder("Afternoon Trail Ride", "Evening tempo spin")))

        mockMvc
            .perform(
                get("/api/activities")
                    .param("q", "tempo")
                    .param("sport", "RIDE")
                    .param("source", "MANUAL")
                    .accept(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].title", contains("Evening tempo spin")))

        mockMvc
            .perform(get("/api/activities").param("source", "FIT_UPLOAD").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].title", contains("Afternoon Trail Ride")))
    }

    @Test
    fun `GET api activities matches the title query literally rather than as a LIKE pattern`() {
        saveActivity(runner, "100% Effort")
        saveActivity(runner, "Easy_Run")
        saveActivity(runner, "Plain Jog")

        mockMvc
            .perform(get("/api/activities").param("q", "%").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].title", contains("100% Effort")))

        mockMvc
            .perform(get("/api/activities").param("q", "_").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].title", contains("Easy_Run")))
    }

    @Test
    fun `GET api activities defaults to pages of 20 and returns every matching activity when size is all`() {
        repeat(25) { i ->
            saveActivity(runner, "Run %02d".format(i), startedAt = Instant.parse("2026-01-01T08:00:00Z").plusSeconds(i * 86_400L))
        }
        saveActivity(otherRunner, "Not mine")

        mockMvc
            .perform(get("/api/activities").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items", hasSize<Any>(20)))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(25))
            .andExpect(jsonPath("$.hasNext").value(true))

        mockMvc
            .perform(get("/api/activities").param("size", "all").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items", hasSize<Any>(25)))
            .andExpect(jsonPath("$.items[0].title").value("Run 24"))
            .andExpect(jsonPath("$.items[24].title").value("Run 00"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.totalElements").value(25))
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.hasPrevious").value(false))
    }

    @Test
    fun `GET api activities rejects an unsupported page size`() {
        mockMvc
            .perform(get("/api/activities").param("size", "lots").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.size").exists())

        mockMvc
            .perform(get("/api/activities").param("size", "500").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
    }

    @Test
    fun `PATCH api activities batch renames and reclassifies owned activities with BATCH_EDITED history`() {
        val manualRun = saveActivity(runner, "Old Run Title", ActivitySport.RUN, ActivitySource.MANUAL)
        val importedRide = saveActivity(runner, "Old Ride Title", ActivitySport.RIDE, ActivitySource.FIT_UPLOAD)
        val notMine = saveActivity(otherRunner, "Other Run", ActivitySport.RUN, ActivitySource.MANUAL)

        patchBatch(
            """{"activityIds":[${manualRun.id},${importedRide.id}],"title":"  Batch Renamed ","sport":"WALK"}""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.affectedCount").value(2))
            .andExpect(jsonPath("$.matchedCount").value(2))
            .andExpect(jsonPath("$.message").doesNotExist())

        listOf(manualRun, importedRide).forEach {
            val refreshed = activityRepository.findByIdOrNull(it.id!!)!!
            assertEquals("Batch Renamed", refreshed.title)
            assertEquals(ActivitySport.WALK, refreshed.sport)
            assertEquals(
                listOf(
                    ActivityChange.BatchEdited(
                        listOf(FieldChange.Title(it.title, "Batch Renamed"), FieldChange.Sport(it.sport, ActivitySport.WALK)),
                    ),
                ),
                activityHistory.entriesFor(it.id!!).map { entry -> entry.change },
            )
        }
        val untouched = activityRepository.findByIdOrNull(notMine.id!!)!!
        assertEquals("Other Run", untouched.title)
        assertEquals(ActivitySport.RUN, untouched.sport)
    }

    @Test
    fun `PATCH api activities batch reports when every selected activity already matches`() {
        val run = saveActivity(runner, "Morning Run", ActivitySport.RUN)

        patchBatch("""{"activityIds":[${run.id}],"title":"Morning Run","sport":"RUN"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.affectedCount").value(0))
            .andExpect(jsonPath("$.matchedCount").value(1))
    }

    @Test
    fun `PATCH api activities batch reclassifying a run recalibrates Personal Records`() {
        importRun("Steady 5k", "2026-04-05T08:00:00Z", 5000.0f, 1500)
        val fast = importRun("Fast 5k", "2026-04-10T09:00:00Z", 5000.0f, 1200)
        expectFiveKilometerRecordHeldBy("Fast 5k")

        patchBatch("""{"activityIds":[${fast.id}],"sport":"WALK"}""").andExpect(status().isOk)
        expectFiveKilometerRecordHeldBy("Steady 5k")

        patchBatch("""{"activityIds":[${fast.id}],"sport":"RUN"}""").andExpect(status().isOk)
        expectFiveKilometerRecordHeldBy("Fast 5k")
    }

    @Test
    fun `PATCH api activities batch rejects a request without activities or without changes`() {
        val run = saveActivity(runner, "Run")

        patchBatch("""{"activityIds":[],"title":"New Title"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.activityIds").exists())

        patchBatch("""{"activityIds":[${run.id}],"title":"   "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("Validation failed"))
            .andExpect(jsonPath("$.fieldErrors.title").value("Please specify a new title or sport to apply"))
            .andExpect(jsonPath("$.fieldErrors.sport").value("Please specify a new title or sport to apply"))

        patchBatch("""{"activityIds":[${run.id}],"title":"${"x".repeat(256)}"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.title").exists())

        assertEquals("Run", activityRepository.findByIdOrNull(run.id!!)!!.title)
    }

    @Test
    fun `PATCH api activities batch returns 404 when none of the activities belong to the athlete`() {
        val notMine = saveActivity(otherRunner, "Other Run")

        patchBatch("""{"activityIds":[${notMine.id}],"title":"Hijacked"}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))

        assertEquals("Other Run", activityRepository.findByIdOrNull(notMine.id!!)!!.title)
    }

    @Test
    fun `PATCH api activities batch is all-or-nothing when any activity is foreign or missing`() {
        val mine = saveActivity(runner, "My Run")
        val notMine = saveActivity(otherRunner, "Other Run")

        listOf(notMine.id!!, Long.MAX_VALUE).forEach { foreignOrMissing ->
            patchBatch("""{"activityIds":[${mine.id},$foreignOrMissing],"title":"Hijacked","sport":"WALK"}""")
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
        }

        listOf(mine, notMine).forEach {
            val unchanged = activityRepository.findByIdOrNull(it.id!!)!!
            assertEquals(it.title, unchanged.title)
            assertEquals(ActivitySport.RUN, unchanged.sport)
            assertTrue(activityHistory.entriesFor(it.id!!).isEmpty())
        }
    }

    @Test
    fun `POST api activities batch-delete deletes owned activities and recalibrates Personal Records`() {
        val steady = importRun("Steady 5k", "2026-04-05T08:00:00Z", 5000.0f, 1500)
        val fast = importRun("Fast 5k", "2026-04-10T09:00:00Z", 5000.0f, 1200)
        val notMine = saveActivity(otherRunner, "Do Not Delete")
        expectFiveKilometerRecordHeldBy("Fast 5k")

        postBatchDelete("""{"activityIds":[${fast.id}]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.affectedCount").value(1))
            .andExpect(jsonPath("$.matchedCount").value(1))
            .andExpect(jsonPath("$.message").doesNotExist())

        assertNull(activityRepository.findByIdOrNull(fast.id!!))
        assertNotNull(activityRepository.findByIdOrNull(notMine.id!!))
        expectFiveKilometerRecordHeldBy("Steady 5k")

        postBatchDelete("""{"activityIds":[${steady.id}]}""").andExpect(status().isOk)
        expectFiveKilometerRecordHeldBy(null)
    }

    @Test
    fun `POST api activities batch-delete cascades to the deleted activities' laps and history`() {
        val doomed = saveActivity(runner, "Run To Delete")
        jdbcTemplate.update(
            "INSERT INTO activity_laps (activity_id, lap_number, started_at, distance, time_taken_seconds) VALUES (?, 1, now(), 1000, 300)",
            doomed.id,
        )
        jdbcTemplate.update(
            "INSERT INTO activity_history (activity_id, change_type, description, created_at) VALUES (?, 'CREATED', 'Initial', now())",
            doomed.id,
        )

        postBatchDelete("""{"activityIds":[${doomed.id}]}""").andExpect(status().isOk)

        assertNull(activityRepository.findByIdOrNull(doomed.id!!))
        listOf("activity_laps", "activity_history").forEach { table ->
            assertEquals(
                0,
                jdbcTemplate.queryForObject("SELECT count(*) FROM $table WHERE activity_id = ?", Int::class.javaObjectType, doomed.id),
                "$table rows of a deleted activity must be cascaded",
            )
        }
    }

    @Test
    fun `POST api activities batch-delete rejects an empty selection and 404s for foreign activities`() {
        val notMine = saveActivity(otherRunner, "Do Not Delete")

        postBatchDelete("""{"activityIds":[]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.activityIds").exists())

        postBatchDelete("""{"activityIds":[${notMine.id}]}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))

        assertNotNull(activityRepository.findByIdOrNull(notMine.id!!))
    }

    @Test
    fun `POST api activities batch-delete is all-or-nothing when any activity is foreign or missing`() {
        val fast = importRun("Fast 5k", "2026-04-10T09:00:00Z", 5000.0f, 1200)
        val notMine = saveActivity(otherRunner, "Do Not Delete")
        expectFiveKilometerRecordHeldBy("Fast 5k")

        listOf(notMine.id!!, Long.MAX_VALUE).forEach { foreignOrMissing ->
            postBatchDelete("""{"activityIds":[${fast.id},$foreignOrMissing]}""")
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
        }

        assertNotNull(activityRepository.findByIdOrNull(fast.id!!))
        assertNotNull(activityRepository.findByIdOrNull(notMine.id!!))
        expectFiveKilometerRecordHeldBy("Fast 5k")
    }

    @Test
    fun `POST api activities batch-delete returns 409 CONCURRENT_CHANGE when it loses a lock to a concurrent change`() {
        val run = saveActivity(runner, "Contested Run")
        // What Spring translates PostgreSQL's 40P01 (deadlock detected) into.
        doThrow(PessimisticLockingFailureException("ERROR: deadlock detected"))
            .whenever(activityService)
            .batchDeleteActivities(any(), any())

        postBatchDelete("""{"activityIds":[${run.id}]}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.kind").value("CONCURRENT_CHANGE"))
            .andExpect(jsonPath("$.message").value(CONCURRENT_CHANGE_MESSAGE))
            .andExpect(jsonPath("$.fieldErrors").doesNotExist())
    }

    @Test
    fun `POST api activities backfill-best-efforts detects Best Efforts for the athlete's historical running Activities`() {
        val historical =
            activityRepository.save(
                Activity(
                    title = "Historical 5k",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.parse("2025-09-01T08:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1260,
                    user = runner,
                    source = ActivitySource.FIT_UPLOAD,
                    externalId = "historical-5k",
                ),
            )
        activityDetailsRepository.upsert(historical.id!!, straightLineTelemetry(5000.0f, 1260), parserVersion = ParserVersion(1))
        expectFiveKilometerRecordHeldBy(null)

        mockMvc
            .perform(post("/api/activities/backfill-best-efforts").with(csrf()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activitiesProcessed").value(1))
            .andExpect(jsonPath("$.bestEffortsCreated").value(4))

        expectFiveKilometerRecordHeldBy("Historical 5k")

        mockMvc
            .perform(post("/api/activities/backfill-best-efforts").with(csrf()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activitiesProcessed").value(0))
            .andExpect(jsonPath("$.bestEffortsCreated").value(0))
    }

    @Test
    @WithAnonymousUser
    fun `batch management endpoints require authentication`() {
        patchBatch("""{"activityIds":[1],"title":"x"}""")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
        postBatchDelete("""{"activityIds":[1]}""").andExpect(status().isUnauthorized)
        mockMvc
            .perform(post("/api/activities/backfill-best-efforts").with(csrf()))
            .andExpect(status().isUnauthorized)
    }
}
