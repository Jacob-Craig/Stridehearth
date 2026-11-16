package com.jacobcraig.stridehearth.users

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.testutils.clearThrottle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Sign-in, sign-up and session handling on an open Server, where anyone may create an account (ADR-0011).
 * [ClosedRegistrationIntegrationTest] covers sign-up on a closed Server.
 *
 * `/v3/api-docs` is served only with the `dev` profile (ADR-0009); these tests read it, so they switch it on.
 */
@SpringBootTest(properties = ["stridehearth.registration-mode=open", "springdoc.api-docs.enabled=true"])
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
class AuthApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var redis: StringRedisTemplate

    private lateinit var testUser: User

    @BeforeEach
    fun setUp() {
        redis.clearThrottle()
        userRepository.deleteAll()
        testUser =
            userRepository.save(
                User(
                    email = "runner@example.com",
                    passwordHash = passwordEncoder.encode("secret123")!!,
                    username = "runner",
                    firstName = "Test",
                    lastName = "Runner",
                ),
            )
    }

    @AfterEach
    fun tearDown() {
        userRepository.deleteAll()
    }

    @Test
    fun `GET api auth me when unauthenticated returns 401 UNAUTHORIZED envelope`() {
        mockMvc
            .perform(
                get("/api/auth/me")
                    .accept(MediaType.APPLICATION_JSON),
            ).andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Authentication required"))
    }

    @Test
    @WithUserDetails(value = "runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
    fun `GET api auth me when authenticated returns 200 with UserResponseDTO`() {
        mockMvc
            .perform(
                get("/api/auth/me")
                    .accept(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.username").value("runner"))
            .andExpect(jsonPath("$.email").value("runner@example.com"))
            .andExpect(jsonPath("$.firstName").value("Test"))
            .andExpect(jsonPath("$.lastName").value("Runner"))
    }

    @Test
    fun `POST api auth login with valid credentials authenticates and returns UserResponseDTO`() {
        val result =
            mockMvc
                .perform(
                    post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"username": "runner", "password": "secret123"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.username").value("runner"))
                .andExpect(jsonPath("$.email").value("runner@example.com"))
                .andReturn()

        val session = result.request.getSession(false) as? MockHttpSession
        assert(session != null) { "Session should be created after successful login" }

        // Use the returned session for subsequent request to /api/auth/me
        mockMvc
            .perform(
                get("/api/auth/me")
                    .session(session!!)
                    .accept(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("runner"))
    }

    @Test
    fun `POST api auth login without CSRF token is rejected with 403`() {
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username": "runner", "password": "secret123"}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST api auth login with invalid password returns 401 UNAUTHORIZED envelope`() {
        mockMvc
            .perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username": "runner", "password": "wrongpassword"}"""),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Invalid username or password"))
    }

    @Test
    fun `GET api auth registration on an open Server accepts sign-ups although an account exists`() {
        mockMvc
            .perform(get("/api/auth/registration").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.acceptsSignUps").value(true))
    }

    @Test
    fun `POST api auth register on an open Server creates a second account and establishes its session`() {
        val registerResult =
            mockMvc
                .perform(
                    post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "username": "newrunner",
                                "email": "newrunner@example.com",
                                "password": "strongPassword123",
                                "firstName": "New",
                                "lastName": "Runner"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.username").value("newrunner"))
                .andExpect(jsonPath("$.email").value("newrunner@example.com"))
                .andExpect(jsonPath("$.firstName").value("New"))
                .andExpect(jsonPath("$.lastName").value("Runner"))
                .andExpect(jsonPath("$.administrator").value(false))
                .andReturn()

        val session = registerResult.request.getSession(false)
        org.junit.jupiter.api.Assertions
            .assertNotNull(session, "Registered user must have an active session created")

        mockMvc
            .perform(
                get("/api/auth/me")
                    .session(session as org.springframework.mock.web.MockHttpSession)
                    .accept(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("newrunner"))
    }

    @Test
    fun `on an open Server too, only the first account to register becomes the Administrator`() {
        userRepository.deleteAll()

        fun register(username: String) =
            mockMvc.perform(
                post("/api/auth/register")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username": "$username", "email": "$username@example.com", "password": "secret123"}"""),
            )

        register("first").andExpect(status().isCreated).andExpect(jsonPath("$.administrator").value(true))
        register("second").andExpect(status().isCreated).andExpect(jsonPath("$.administrator").value(false))
    }

    @Test
    fun `POST api auth register with duplicate email or username returns 400 DUPLICATE_RESOURCE`() {
        mockMvc
            .perform(
                post("/api/auth/register")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "username": "runner",
                            "email": "another@example.com",
                            "password": "password123"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("DUPLICATE_RESOURCE"))
            .andExpect(jsonPath("$.fieldErrors.username").value("Username is already taken"))
    }

    @Test
    fun `POST api auth register with duplicate email returns 400 DUPLICATE_RESOURCE`() {
        mockMvc
            .perform(
                post("/api/auth/register")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "username": "uniquename",
                            "email": "runner@example.com",
                            "password": "password123"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("DUPLICATE_RESOURCE"))
            .andExpect(jsonPath("$.fieldErrors.email").value("Email is already registered"))
    }

    @Test
    fun `POST api auth register with blank required fields returns 400 VALIDATION_FAILED`() {
        mockMvc
            .perform(
                post("/api/auth/register")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "username": "",
                            "email": "not-an-email",
                            "password": ""
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.username").exists())
            .andExpect(jsonPath("$.fieldErrors.email").exists())
            .andExpect(jsonPath("$.fieldErrors.password").exists())
    }

    @Test
    fun `POST api auth logout invalidates session`() {
        val loginResult =
            mockMvc
                .perform(
                    post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"username": "runner", "password": "secret123"}"""),
                ).andExpect(status().isOk)
                .andReturn()

        val session = loginResult.request.getSession(false) as MockHttpSession

        mockMvc
            .perform(
                post("/api/auth/logout")
                    .with(csrf())
                    .session(session),
            ).andExpect(status().isNoContent)

        // Verifying session is now invalidated / unauthorized
        mockMvc
            .perform(
                get("/api/auth/me")
                    .session(session)
                    .accept(MediaType.APPLICATION_JSON),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET v3 api-docs returns 200 with OpenAPI specification`() {
        mockMvc
            .perform(
                get("/v3/api-docs")
                    .accept(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
    }
}
