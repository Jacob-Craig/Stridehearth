package com.jacobcraig.stridehearth.users

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.testutils.clearThrottle
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

/**
 * The Throttle on sign-in and sign-up, at its default limits: 10 failed sign-ins per source and 20 per username in
 * 15 minutes, and 10 sign-up attempts per source in an hour. Runs on an open Server, like [AuthApiIntegrationTest],
 * so the two share a Spring context; [ClosedRegistrationIntegrationTest] covers sign-up on a closed Server.
 */
@SpringBootTest(properties = ["stridehearth.registration-mode=open"])
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
class ThrottleIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var redis: StringRedisTemplate

    @Autowired
    private lateinit var attemptCounter: AttemptCounter

    @BeforeEach
    fun setUp() {
        redis.clearThrottle()
        userRepository.deleteAll()
        userRepository.save(
            User(
                email = "runner@example.com",
                passwordHash = passwordEncoder.encode(PASSWORD)!!,
                username = "runner",
                firstName = null,
                lastName = null,
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        userRepository.deleteAll()
        redis.clearThrottle()
    }

    private fun from(source: String) = RequestPostProcessor { it.apply { remoteAddr = source } }

    private fun signIn(
        password: String,
        source: String = HOME,
        username: String = "runner",
    ): ResultActions =
        mockMvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .with(from(source))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username": "$username", "password": "$password"}"""),
        )

    private fun signUp(
        username: String,
        source: String = HOME,
    ): ResultActions =
        mockMvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .with(from(source))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username": "$username", "email": "$username@example.com", "password": "secret123"}"""),
        )

    private fun ResultActions.andExpectThrottled(maxWait: Duration): ResultActions =
        andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.kind").value("TOO_MANY_ATTEMPTS"))
            .andExpect(jsonPath("$.message").value(startsWith("Too many attempts. Please try again in ")))
            .andExpect { result ->
                val retryAfter = result.response.getHeader("Retry-After")?.toLongOrNull()
                assertTrue(retryAfter != null && retryAfter in 1..maxWait.seconds, "Retry-After was $retryAfter")
            }

    @Test
    fun `failed sign-ins from one source are throttled after ten, even with the right password`() {
        repeat(10) { signIn("wrong-$it").andExpect(status().isUnauthorized) }

        signIn("wrong-again").andExpectThrottled(Duration.ofMinutes(15))
        signIn(PASSWORD).andExpectThrottled(Duration.ofMinutes(15))
        signIn("wrong", username = "someone-else").andExpectThrottled(Duration.ofMinutes(15))

        signIn(PASSWORD, source = "198.51.100.1").andExpect(status().isOk)
    }

    @Test
    fun `successful sign-ins are never counted, however many there are`() {
        repeat(30) { signIn(PASSWORD).andExpect(status().isOk) }

        // All ten failures are still allowed after thirty successes.
        repeat(10) { signIn("wrong-$it").andExpect(status().isUnauthorized) }
        signIn("wrong-again").andExpectThrottled(Duration.ofMinutes(15))
    }

    @Test
    fun `occasional mistyped passwords between sign-ins are never throttled`() {
        repeat(9) {
            signIn("typo").andExpect(status().isUnauthorized)
            signIn(PASSWORD).andExpect(status().isOk)
        }
    }

    @Test
    fun `failed sign-ins against one username from many sources are throttled after twenty`() {
        repeat(20) { signIn("guess-$it", source = "192.0.2.$it").andExpect(status().isUnauthorized) }

        signIn(PASSWORD, source = "192.0.2.200").andExpectThrottled(Duration.ofMinutes(15))
        signIn("guess", source = "192.0.2.201", username = "someone-else").andExpect(status().isUnauthorized)
    }

    @Test
    fun `sign-ups from one source are throttled after ten, and another source can still sign up`() {
        repeat(10) { signUp("athlete$it").andExpect(status().isCreated) }

        signUp("athlete10").andExpectThrottled(Duration.ofHours(1))
        assertNull(userRepository.findByUsername("athlete10"))

        signUp("athlete10", source = "198.51.100.1").andExpect(status().isCreated)
    }

    @Test
    fun `refused sign-ups count too, so taken usernames cannot be probed in bulk`() {
        repeat(10) { signUp("runner").andExpect(status().isBadRequest) }

        signUp("runner").andExpectThrottled(Duration.ofHours(1))
    }

    @Test
    fun `a source may try again once its window has ended`() {
        val limit = AttemptLimit(maxAttempts = 2, window = Duration.ofMillis(500))
        val key = "test:window:${System.nanoTime()}"

        assertNull(attemptCounter.take(key, limit))
        assertNull(attemptCounter.take(key, limit))
        val wait = attemptCounter.take(key, limit)
        assertNotNull(wait)
        assertTrue(wait!! <= Duration.ofMillis(500), "waits at most the window, not $wait")

        Thread.sleep(700)
        assertNull(attemptCounter.take(key, limit))
    }

    @Test
    fun `a throttled attempt does not count, so the wait never grows`() {
        val limit = AttemptLimit(maxAttempts = 1, window = Duration.ofMinutes(1))
        val key = "test:count:${System.nanoTime()}"

        assertNull(attemptCounter.take(key, limit))
        repeat(5) { assertNotNull(attemptCounter.take(key, limit)) }
        assertEquals("1", redis.opsForValue().get(AttemptCounter.KEY_PREFIX + key))
    }

    private companion object {
        const val PASSWORD = "secret123"
        const val HOME = "203.0.113.10"
    }
}
