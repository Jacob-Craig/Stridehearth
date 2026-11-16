package com.jacobcraig.stridehearth.users

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.testutils.clearThrottle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sign-up on a closed Server (ADR-0011). Leaves the Registration Mode unset, so it also proves closed is the default.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
class ClosedRegistrationIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var redis: StringRedisTemplate

    @BeforeEach
    fun emptyServer() {
        redis.clearThrottle()
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        userRepository.deleteAll()
    }

    private fun signUp(username: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username": "$username", "email": "$username@example.com", "password": "secret123"}"""),
        )

    private fun registrationStatus(): ResultActions = mockMvc.perform(get("/api/auth/registration").accept(MediaType.APPLICATION_JSON))

    @Test
    fun `the first account may register itself and every later sign-up is refused`() {
        registrationStatus().andExpect(status().isOk).andExpect(jsonPath("$.acceptsSignUps").value(true))

        signUp("first").andExpect(status().isCreated).andExpect(jsonPath("$.username").value("first"))

        registrationStatus().andExpect(status().isOk).andExpect(jsonPath("$.acceptsSignUps").value(false))
        signUp("second")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.kind").value("REGISTRATION_CLOSED"))
            .andExpect(jsonPath("$.message").value("Registration is closed on this Server."))
        assertEquals(listOf("first"), userRepository.findAll().map { it.username })
    }

    @Test
    fun `a refused sign-up does not reveal whether its username is taken`() {
        signUp("first").andExpect(status().isCreated)

        signUp("first")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.kind").value("REGISTRATION_CLOSED"))
    }

    @Test
    fun `refused sign-ups are throttled after ten, as on an open Server`() {
        signUp("first").andExpect(status().isCreated)
        repeat(9) { signUp("later$it").andExpect(status().isForbidden) }

        signUp("later")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.kind").value("TOO_MANY_ATTEMPTS"))
    }

    @Test
    fun `two concurrent first sign-ups create only one account`() {
        val attempts = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(attempts)
        try {
            val outcomes =
                (1..attempts).map { n ->
                    pool.submit<Result<User>> {
                        start.await()
                        runCatching {
                            userService.registerUser(
                                UserFormDTO(
                                    email = "racer$n@example.com",
                                    password = "secret123",
                                    username = "racer$n",
                                    firstName = null,
                                    lastName = null,
                                ),
                            )
                        }
                    }
                }
            start.countDown()
            val results = outcomes.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(attempts - 1, results.count { it.exceptionOrNull() is RegistrationClosedException })
            assertEquals(1L, userRepository.count())
            assertEquals(listOf(true), userRepository.findAll().map { it.administrator }, "the one account is the Administrator")
        } finally {
            pool.shutdownNow()
        }
    }
}
