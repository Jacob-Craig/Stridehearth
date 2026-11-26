package com.jacobcraig.stridehearth.server

import com.jacobcraig.stridehearth.POSTGIS_IMAGE
import com.jacobcraig.stridehearth.StridehearthApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * How the Server is configured (ADR-0009): with no profile it needs its connection settings from the environment
 * and fails fast without them, and the OpenAPI docs and SQL logging are switched on only by the `dev` profile.
 *
 * Each test boots the real application the way an Operator would, with a simulated process environment,
 * instead of through `@SpringBootTest` and `@ServiceConnection`, which would hide a missing setting.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerConfigurationTest {
    private val postgres = PostgreSQLContainer(POSTGIS_IMAGE)
    private val redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun startContainers() {
        postgres.start()
        redis.start()
    }

    @AfterAll
    fun stopContainers() {
        redis.stop()
        postgres.stop()
    }

    @Test
    fun `no profile and no settings fails fast naming every missing variable`() {
        val failure = assertThrows<MissingConnectionSettingsException> { run(environmentVariables = emptyMap()) }

        assertEquals(
            listOf(
                "SPRING_DATASOURCE_URL",
                "SPRING_DATASOURCE_USERNAME",
                "SPRING_DATASOURCE_PASSWORD",
                "SPRING_DATA_REDIS_HOST",
            ),
            failure.missing,
        )
        assertTrue(failure.message!!.contains("SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME"))
        val analysis = MissingConnectionSettingsFailureAnalyzer().analyze(failure)!!
        assertTrue(analysis.action!!.contains("SPRING_DATA_REDIS_HOST"))
        assertTrue(analysis.action!!.contains("'dev' profile"))
    }

    @Test
    fun `no profile names only the settings that are missing`() {
        val failure =
            assertThrows<MissingConnectionSettingsException> {
                run(environmentVariables = databaseVariables())
            }

        assertEquals(listOf("SPRING_DATA_REDIS_HOST"), failure.missing)
    }

    @Test
    fun `no profile with settings from the environment starts without api docs or SQL logging`() {
        run(environmentVariables = databaseVariables() + redisVariables()).use { context ->
            assertEquals(404, get(context, "/v3/api-docs"))
            assertEquals(404, get(context, "/swagger-ui.html"))
            assertEquals(false, context.environment.getProperty("spring.jpa.show-sql", Boolean::class.java))
        }
    }

    @Test
    fun `health is reported without signing in and no other actuator endpoint is exposed`() {
        // The container image's HEALTHCHECK polls /actuator/health (ADR-0009).
        run(environmentVariables = databaseVariables() + redisVariables()).use { context ->
            val health = send(context, "/actuator/health")
            assertEquals(200, health.statusCode())
            assertEquals("""{"groups":["liveness","readiness"],"status":"UP"}""", health.body())
            listOf("/actuator", "/actuator/env", "/actuator/beans", "/actuator/heapdump").forEach { path ->
                assertEquals(404, get(context, path), "$path must not be exposed")
            }
        }
    }

    @Test
    fun `dev profile serves api docs and logs SQL`() {
        // The environment points `dev` at this test's containers instead of docker-compose's ports.
        run(environmentVariables = databaseVariables() + redisVariables(), profile = "dev").use { context ->
            assertEquals(200, get(context, "/v3/api-docs"))
            assertEquals(true, context.environment.getProperty("spring.jpa.show-sql", Boolean::class.java))
        }
    }

    private fun databaseVariables() =
        mapOf(
            "SPRING_DATASOURCE_URL" to postgres.jdbcUrl,
            "SPRING_DATASOURCE_USERNAME" to postgres.username,
            "SPRING_DATASOURCE_PASSWORD" to postgres.password,
        )

    private fun redisVariables() =
        mapOf(
            "SPRING_DATA_REDIS_HOST" to redis.host,
            "SPRING_DATA_REDIS_PORT" to redis.getMappedPort(6379).toString(),
        )

    /** Starts the application on a random port as if [environmentVariables] were its whole process environment. */
    private fun run(
        environmentVariables: Map<String, String>,
        profile: String? = null,
    ): ConfigurableApplicationContext {
        val environment = StandardEnvironment()
        environment.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                environmentVariables,
            ),
        )
        profile?.let { environment.setActiveProfiles(it) }
        val application = SpringApplication(StridehearthApplication::class.java)
        application.setEnvironment(environment)
        application.setWebApplicationType(WebApplicationType.SERVLET)
        return application.run("--server.port=0")
    }

    private fun get(
        context: ConfigurableApplicationContext,
        path: String,
    ): Int = send(context, path).statusCode()

    private fun send(
        context: ConfigurableApplicationContext,
        path: String,
    ): HttpResponse<String> {
        val port = context.environment.getProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
