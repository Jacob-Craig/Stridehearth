package com.jacobcraig.stridehearth.weather

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.testutils.SampleRunGenerator
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import com.jayway.jsonpath.JsonPath
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The opt-in Open-Meteo lookup (#83, ADR-0017), against a stub server: the test never reaches the real network. */
@SpringBootTest(
    properties = [
        "stridehearth.weather.provider=open-meteo",
        "stridehearth.weather.archive-lag=PT0S",
        "stridehearth.weather.initial-delay-ms=3600000",
        "stridehearth.analytics.recompute.scheduled=false",
    ],
)
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "weather_lookup_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class WeatherLookupStageTest {
    @Autowired private lateinit var stage: WeatherLookupStage

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var activityRepository: ActivityRepository

    @Autowired private lateinit var jdbcClient: JdbcClient

    @BeforeEach
    fun setUp() {
        requests.clear()
        status.set(200)
        userRepository.save(User(email = "weather_lookup_runner@example.com", passwordHash = "x", username = "weather_lookup_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `a lookup sends only rounded start coordinates and the date, is made once, and feeds the Ambient Temperature`() {
        val activityId = upload()
        assertEquals(null, mockCondition(activityId))

        assertEquals(1, stage.runOnce())

        val query = requests.single()
        val params = query.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals(
            setOf("latitude", "longitude", "start_date", "end_date", "hourly", "wind_speed_unit", "timezone"),
            params.keys,
        )
        assertTrue(Regex("-?\\d+\\.\\d").matches(params.getValue("latitude")), "latitude ${params["latitude"]} is rounded to 0.1")
        assertTrue(Regex("-?\\d+\\.\\d").matches(params.getValue("longitude")), "longitude ${params["longitude"]} is rounded to 0.1")
        assertEquals("2026-06-06", params["start_date"])
        assertEquals(1, count("activity_weather", activityId))
        assertEquals(15.0, mockCondition(activityId))

        assertEquals(0, stage.runOnce())
        assertEquals(1, requests.size, "a cached Activity is not looked up again")

        activityRepository.deleteById(activityId)
        assertEquals(0, count("activity_weather", activityId), "the cached weather goes with its Activity")
    }

    @Test
    fun `an import commits while the provider is down and the lookup is retried later`() {
        status.set(503)
        val activityId = upload()

        assertFailsWith<Exception> { stage.runOnce() }
        assertEquals(0, count("activity_weather", activityId))
        assertEquals(null, mockCondition(activityId))

        status.set(200)
        assertEquals(1, stage.runOnce())
        assertEquals(15.0, mockCondition(activityId))
    }

    @Test
    fun `an Activity with a device temperature is not looked up`() {
        upload(temperatureC = 31)

        assertEquals(0, stage.runOnce())
        assertEquals(0, requests.size)
    }

    private fun upload(temperatureC: Int? = null): Long {
        val body =
            mockMvc
                .perform(
                    multipart("/api/activities/upload")
                        .file(MockMultipartFile("file", "run.fit", "application/octet-stream", SampleRunGenerator.sampleRun(temperatureC)))
                        .with(csrf()),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return JsonPath.read<Number>(body, "$.activityId").toLong()
    }

    /** The Ambient Temperature the Activity's detail now serves, or null. */
    private fun mockCondition(activityId: Long): Double? {
        val body =
            mockMvc
                .perform(get("/api/activities/{id}", activityId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.conditions").exists())
                .andReturn()
                .response.contentAsString
        return JsonPath.read<Number?>(body, "$.conditions.ambientTemperatureC")?.toDouble()
    }

    private fun count(
        table: String,
        activityId: Long,
    ): Int =
        jdbcClient
            .sql("SELECT count(*) FROM $table WHERE activity_id = :id")
            .param("id", activityId)
            .query(Int::class.java)
            .single()

    companion object {
        private val requests = CopyOnWriteArrayList<String>()
        private val status = AtomicInteger(200)

        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/archive") { exchange ->
                    requests.add(exchange.requestURI.rawQuery)
                    val code = status.get()
                    val body =
                        if (code == 200) {
                            val hours = (0..23).map { "\"2026-06-06T%02d:00\"".format(it) }
                            val series = { value: String -> (0..23).joinToString(",", "[", "]") { value } }
                            """{"hourly":{"time":${hours.joinToString(",", "[", "]")},
                              "temperature_2m":${series("15.0")},"dew_point_2m":${series("9.5")},
                              "relative_humidity_2m":${series("70")},"wind_speed_10m":${series("3.2")}}}"""
                        } else {
                            "{}"
                        }.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(code, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun stubProvider(registry: DynamicPropertyRegistry) {
            registry.add("stridehearth.weather.open-meteo.base-url") { "http://127.0.0.1:${server.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stopStub() = server.stop(0)
    }
}
