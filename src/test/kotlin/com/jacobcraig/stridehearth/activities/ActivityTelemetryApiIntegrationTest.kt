package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * HTTP-level tests for `GET /api/activities/{id}/telemetry` and the activity page that consumes it.
 *
 * `/v3/api-docs` is served only with the `dev` profile (ADR-0009); these tests read it, so they switch it on.
 */
@SpringBootTest(properties = ["springdoc.api-docs.enabled=true"])
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ActivityTelemetryApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var activityBestEffortRepository: ActivityBestEffortRepository

    @Autowired
    private lateinit var derivedMetricsRepository: ActivityDerivedMetricsRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private lateinit var runner: User
    private lateinit var otherUser: User

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "runner@example.com", passwordHash = "x", username = "runner"))
        otherUser = userRepository.save(User(email = "other@example.com", passwordHash = "x", username = "other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun activityWithTelemetry(owner: User): Activity {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Telemetry Run",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.parse("2026-01-01T08:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1800,
                    user = owner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        activityDetailsRepository.upsert(
            activity.id!!,
            FitTelemetry(
                t = intArrayOf(0, 1, 2),
                lons = doubleArrayOf(-0.16, -0.17, -0.18),
                lats = doubleArrayOf(51.51, 51.52, 51.53),
                routeIndex = arrayOf(0, 1, 2),
                heartRate = arrayOf(140.toShort(), 145.toShort(), null),
            ),
            ParserVersion(1),
        )
        return activity
    }

    @Test
    fun `owner receives 200 with the nested telemetry JSON`() {
        val activity = activityWithTelemetry(runner)

        mockMvc
            .perform(get("/api/activities/{id}/telemetry", activity.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.activityId").value(activity.id!!.toInt()))
            .andExpect(jsonPath("$.sport").value("RUN"))
            .andExpect(jsonPath("$.route.type").value("LineString"))
            .andExpect(jsonPath("$.telemetry.t[2]").value(2))
            .andExpect(jsonPath("$.telemetry.heartRate[1]").value(145))
    }

    @Test
    fun `derivedStreams carries the stored GAP stream in m per s, parallel to t`() {
        val activity = activityWithTelemetry(runner)
        derivedMetricsRepository.upsert(activity.id!!, DERIVED_METRICS_VERSION, arrayOf(3.25f, null, 4.5f))

        mockMvc
            .perform(get("/api/activities/{id}/telemetry", activity.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.derivedStreams.gapSpeed.length()").value(3))
            .andExpect(jsonPath("$.derivedStreams.gapSpeed[0]").value(3.25))
            .andExpect(jsonPath("$.derivedStreams.gapSpeed[1]").doesNotExist())
            .andExpect(jsonPath("$.derivedStreams.gapSpeed[2]").value(4.5))
    }

    @Test
    fun `derivedStreams gapSpeed is null without a GAP stream, or when it is not parallel to t`() {
        val withoutGap = activityWithTelemetry(runner)
        val stale = activityWithTelemetry(runner)
        derivedMetricsRepository.upsert(stale.id!!, DERIVED_METRICS_VERSION, arrayOf(3.0f, 3.0f))

        for (activity in listOf(withoutGap, stale)) {
            val body = readJson(get("/api/activities/{id}/telemetry", activity.id))
            assertTrue(body.path("derivedStreams").isObject, "derivedStreams is always present")
            assertTrue(body.path("derivedStreams").path("gapSpeed").isNull, "gapSpeed for ${activity.id}")
        }
    }

    @Test
    fun `another athlete cannot read an Activity's GAP stream`() {
        val activity = activityWithTelemetry(otherUser)
        derivedMetricsRepository.upsert(activity.id!!, DERIVED_METRICS_VERSION, arrayOf(3.0f, 3.0f, 3.0f))

        mockMvc
            .perform(get("/api/activities/{id}/telemetry", activity.id))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.derivedStreams").doesNotExist())
    }

    @Test
    fun `another user's activity returns 404`() {
        val activity = activityWithTelemetry(otherUser)

        mockMvc
            .perform(get("/api/activities/{id}/telemetry", activity.id))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `non-existent activity returns 404`() {
        mockMvc
            .perform(get("/api/activities/{id}/telemetry", 999_999))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `activity without telemetry returns 404`() {
        val manual =
            activityRepository.save(
                Activity(
                    title = "Manual Run",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.parse("2026-01-01T08:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1800,
                    user = runner,
                    source = ActivitySource.MANUAL,
                ),
            )

        mockMvc
            .perform(get("/api/activities/{id}/telemetry", manual.id))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    @WithAnonymousUser
    fun `unauthenticated request returns 401 rather than a login redirect`() {
        mockMvc
            .perform(get("/api/activities/{id}/telemetry", 1))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `OpenAPI declares ActivityTelemetryResponseDTO with exactly the keys of the streamed telemetry JSON`() {
        val activity = activityWithTelemetry(runner)
        derivedMetricsRepository.upsert(activity.id!!, DERIVED_METRICS_VERSION, arrayOf(3.0f, 3.0f, 3.0f))
        activityBestEffortRepository.save(
            ActivityBestEffort(
                activityId = activity.id!!,
                sport = ActivitySport.RUN,
                benchmarkDistance = BenchmarkDistance.FOUR_HUNDRED_METERS,
                elapsedTimeSeconds = 75.5,
                startIndex = 0,
                endIndex = 2,
                startTimeSeconds = 0.0,
                endTimeSeconds = 75.5,
                startDistanceMeters = 100.0,
                endDistanceMeters = 500.0,
            ),
        )
        val telemetry = readJson(get("/api/activities/{id}/telemetry", activity.id))
        val spec = readJson(get("/v3/api-docs"))
        val schemas = spec.path("components").path("schemas")

        val okSchema =
            spec
                .path("paths")
                .path("/api/activities/{id}/telemetry")
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("\$ref")
                .asString()
        assertEquals("#/components/schemas/ActivityTelemetryResponseDTO", okSchema)

        assertSchemaDeclaresExactly(schemas, "ActivityTelemetryResponseDTO", telemetry)
        assertSchemaDeclaresExactly(schemas, "ActivityTelemetryStreamsDTO", telemetry.path("telemetry"))
        assertSchemaDeclaresExactly(schemas, "ActivityTelemetryBestEffortDTO", telemetry.path("bestEfforts").path(0))
        assertSchemaDeclaresExactly(schemas, "GeoJsonLineStringDTO", telemetry.path("route"))
        assertSchemaDeclaresExactly(schemas, "ActivityDerivedStreamsDTO", telemetry.path("derivedStreams"))
    }

    private fun readJson(request: RequestBuilder): JsonNode =
        JsonMapper.builder().build().readTree(
            mockMvc
                .perform(request)
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString,
        )

    /** Every key the database emits is declared, every declared property is emitted, and all are required. */
    private fun assertSchemaDeclaresExactly(
        schemas: JsonNode,
        schemaName: String,
        emitted: JsonNode,
    ) {
        val schema = schemas.path(schemaName)
        assertTrue(schema.isObject, "Missing schema $schemaName")
        val declared = schema.path("properties").propertyNames().toSortedSet()
        assertEquals(emitted.propertyNames().toSortedSet(), declared, "$schemaName properties")
        assertEquals(
            declared,
            schema
                .path("required")
                .values()
                .map { it.asString() }
                .toSortedSet(),
            "$schemaName required",
        )
    }

    @Test
    fun `telemetry endpoint and activity detail expose best effort index and time boundaries for route and chart highlighting`() {
        val activity = activityWithTelemetry(runner)
        activityBestEffortRepository.save(
            ActivityBestEffort(
                activityId = activity.id!!,
                sport = ActivitySport.RUN,
                benchmarkDistance = BenchmarkDistance.FOUR_HUNDRED_METERS,
                elapsedTimeSeconds = 75.5,
                startIndex = 0,
                endIndex = 2,
                startTimeSeconds = 0.0,
                endTimeSeconds = 75.5,
                startDistanceMeters = 100.0,
                endDistanceMeters = 500.0,
            ),
        )

        mockMvc
            .perform(get("/api/activities/{id}/telemetry", activity.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bestEfforts").isArray)
            .andExpect(jsonPath("$.bestEfforts[0].benchmarkDistance").value("FOUR_HUNDRED_METERS"))
            .andExpect(jsonPath("$.bestEfforts[0].startIndex").value(0))
            .andExpect(jsonPath("$.bestEfforts[0].endIndex").value(2))
            .andExpect(jsonPath("$.bestEfforts[0].startTimeSeconds").value(0.0))
            .andExpect(jsonPath("$.bestEfforts[0].endTimeSeconds").value(75.5))
            .andExpect(jsonPath("$.bestEfforts[0].startDistanceMeters").value(100.0))
            .andExpect(jsonPath("$.bestEfforts[0].endDistanceMeters").value(500.0))

        mockMvc
            .perform(get("/api/activities/{id}", activity.id).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bestEfforts[0].benchmarkDistance").value("FOUR_HUNDRED_METERS"))
            .andExpect(jsonPath("$.bestEfforts[0].window.startIndex").value(0))
            .andExpect(jsonPath("$.bestEfforts[0].window.endIndex").value(2))
            .andExpect(jsonPath("$.bestEfforts[0].window.startTimeSeconds").value(0.0))
            .andExpect(jsonPath("$.bestEfforts[0].window.endTimeSeconds").value(75.5))
            .andExpect(jsonPath("$.bestEfforts[0].window.startDistanceMeters").value(100.0))
            .andExpect(jsonPath("$.bestEfforts[0].window.endDistanceMeters").value(500.0))
    }
}
