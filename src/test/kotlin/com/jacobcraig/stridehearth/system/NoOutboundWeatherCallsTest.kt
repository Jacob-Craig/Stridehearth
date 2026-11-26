package com.jacobcraig.stridehearth.system

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.analytics.ActivityAnalysis
import com.jacobcraig.stridehearth.analytics.ActivityConditions
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.testutils.SampleRunGenerator
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import com.jacobcraig.stridehearth.weather.OpenMeteoClient
import com.jacobcraig.stridehearth.weather.WeatherLookupStage
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.closeTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Weather comes from the device unless an Operator opts in (ADR-0017). With `stridehearth.weather.provider` at its
 * default `none`, the application holds no HTTP client, no main source outside the `weather` package refers to one, and
 * an imported Activity's conditions come only from its own file. [WeatherLookupStageTest] covers `open-meteo`.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "weather_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class NoOutboundWeatherCallsTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @BeforeEach
    fun setUp() {
        userRepository.save(User(email = "weather_runner@example.com", passwordHash = "x", username = "weather_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `the application holds no HTTP client or weather lookup`() {
        assertEquals(emptyList(), applicationContext.getBeanNamesForType(OpenMeteoClient::class.java).toList())
        assertEquals(emptyList(), applicationContext.getBeanNamesForType(WeatherLookupStage::class.java).toList())
        val clients =
            listOf(
                "org.springframework.web.client.RestTemplate",
                "org.springframework.web.client.RestClient",
                "org.springframework.web.reactive.function.client.WebClient",
                "java.net.http.HttpClient",
            ).mapNotNull { name -> runCatching { Class.forName(name) }.getOrNull() }

        clients.forEach { type ->
            assertEquals(emptyList(), applicationContext.getBeanNamesForType(type).toList(), "a ${type.simpleName} bean")
        }
    }

    @Test
    fun `no main source outside the weather package refers to an HTTP client`() {
        val forbidden =
            listOf(
                "java.net.http",
                "org.springframework.web.client",
                "org.springframework.web.reactive.function.client",
                "HttpURLConnection",
                "URL(",
            )
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size > 100, "the scan reads the main sources")

        val offenders =
            sources.filterNot { it.parentFile.name == "weather" }.flatMap { file ->
                forbidden.filter { it in file.readText() }.map { "${file.name}: $it" }
            }

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `an imported FIT file's conditions come only from the file`() {
        val bytes = SampleRunGenerator.sampleRun(temperatureC = 31)
        val expected = expectedConditions(bytes)
        assertEquals(31.0, expected.deviceTemperatureC)

        val body =
            mockMvc
                .perform(
                    multipart("/api/activities/upload")
                        .file(MockMultipartFile("file", "warm.fit", "application/octet-stream", bytes))
                        .with(csrf()),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val activityId = JsonPath.read<Number>(body, "$.activityId").toLong()

        mockMvc
            .perform(get("/api/activities/{id}", activityId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.conditions.deviceTemperatureC").value(31.0))
            .andExpect(jsonPath("$.conditions.ambientTemperatureC").value(25.0))
            .andExpect(jsonPath("$.conditions.meanAltitudeM").value(closeTo(assertNotNull(expected.meanAltitudeM), 0.01)))
            // A 25 °C run of over 20 minutes is the athlete's first heat exposure: one day of 100/14.
            .andExpect(jsonPath("$.conditions.heatAcclimationPct").value(closeTo(100.0 / 14, 1e-9)))
    }

    /** The conditions [bytes] give by themselves, through the parser and the pure analysis. */
    private fun expectedConditions(bytes: ByteArray): ActivityConditions {
        val parsed = FitActivityParser().parse(ByteArrayResource(bytes)).single()
        return ActivityAnalysis.analyse(assertNotNull(parsed.telemetry), parsed.sport).conditions
    }
}
