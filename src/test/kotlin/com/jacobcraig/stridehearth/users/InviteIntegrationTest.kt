package com.jacobcraig.stridehearth.users

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.testutils.clearThrottle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Administrator and their Invites on a closed Server (ADR-0011). Leaves the Registration Mode unset, so the
 * Server is closed and an Invite is the only way in after its first account.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
class InviteIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var inviteRepository: InviteRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var redis: StringRedisTemplate

    private val jsonMapper = JsonMapper.builder().build()

    private lateinit var administrator: User

    @BeforeEach
    fun closedServerWithItsFirstAccount() {
        redis.clearThrottle()
        activityRepository.deleteAll()
        inviteRepository.deleteAll()
        userRepository.deleteAll()
        administrator = userService.registerUser(form("admin"))
    }

    @AfterEach
    fun tearDown() {
        inviteRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun form(username: String) =
        UserFormDTO(
            email = "$username@example.com",
            password = "secret123",
            username = username,
            firstName = null,
            lastName = null,
        )

    private fun signedInAs(account: User): RequestPostProcessor =
        user(AppUserDetails(id = account.id!!, username = account.username, passwordHash = account.passwordHash))

    private fun createInvite(as_: User = administrator): ResultActions =
        mockMvc.perform(post("/api/invites").with(csrf()).with(signedInAs(as_)).accept(MediaType.APPLICATION_JSON))

    /** Creates an Invite as the Administrator and returns its link's token. */
    private fun newInviteToken(): String {
        val body =
            createInvite()
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return jsonMapper.readTree(body).get("token").asString()
    }

    private fun pendingInvites(as_: User = administrator): ResultActions =
        mockMvc.perform(get("/api/invites").with(signedInAs(as_)).accept(MediaType.APPLICATION_JSON))

    private fun revoke(
        inviteId: Long,
        as_: User = administrator,
    ): ResultActions = mockMvc.perform(delete("/api/invites/$inviteId").with(csrf()).with(signedInAs(as_)))

    private fun inviteStatus(token: String): ResultActions =
        mockMvc.perform(get("/api/auth/invites/$token").accept(MediaType.APPLICATION_JSON))

    private fun redeem(
        token: String,
        username: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/auth/invites/$token/redeem")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username": "$username", "email": "$username@example.com", "password": "secret123"}"""),
        )

    private fun signIn(username: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username": "$username", "password": "secret123"}"""),
        )

    private fun ResultActions.refusedAs(reason: InviteUnusableReason): ResultActions =
        andExpect(status().isGone)
            .andExpect(jsonPath("$.kind").value("INVITE_UNUSABLE"))
            .andExpect(jsonPath("$.message").value(reason.message))

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `the first account on a Server is its Administrator`() {
        assertTrue(administrator.administrator)
        mockMvc
            .perform(get("/api/auth/me").with(signedInAs(administrator)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.administrator").value(true))
    }

    @Test
    fun `on a closed Server an invited person creates their own account and signs in with it`() {
        val token = newInviteToken()
        mockMvc.perform(get("/api/auth/registration")).andExpect(jsonPath("$.acceptsSignUps").value(false))

        inviteStatus(token).andExpect(status().isOk).andExpect(jsonPath("$.expiresAt").exists())
        redeem(token, "invitee")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.username").value("invitee"))
            .andExpect(jsonPath("$.administrator").value(false))
        signIn("invitee").andExpect(status().isOk).andExpect(jsonPath("$.username").value("invitee"))

        val invitee = userRepository.findByUsername("invitee")!!
        assertFalse(invitee.administrator)
        val used = inviteRepository.findAll().single()
        assertEquals(invitee.id, used.usedBy)
        pendingInvites().andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `an Invite that has been used is refused`() {
        val token = newInviteToken()
        redeem(token, "invitee").andExpect(status().isCreated)

        redeem(token, "second").refusedAs(InviteUnusableReason.USED)
        inviteStatus(token).refusedAs(InviteUnusableReason.USED)
        assertEquals(null, userRepository.findByUsername("second"))
    }

    @Test
    fun `an Invite past its 7 days is refused and no longer pending`() {
        val token = newInviteToken()
        val invite = inviteRepository.findAll().single()
        assertEquals(Duration.ofDays(7), Duration.between(invite.createdAt, invite.expiresAt))
        invite.createdAt = Instant.now().minus(Duration.ofDays(7)).minusSeconds(60)
        invite.expiresAt = Instant.now().minusSeconds(60)
        inviteRepository.save(invite)

        inviteStatus(token).refusedAs(InviteUnusableReason.EXPIRED)
        redeem(token, "late").refusedAs(InviteUnusableReason.EXPIRED)
        pendingInvites().andExpect(jsonPath("$.length()").value(0))
        revoke(invite.id!!).andExpect(status().isNotFound)
    }

    @Test
    fun `a revoked Invite is refused and no longer pending`() {
        val token = newInviteToken()
        val kept = newInviteToken()
        val inviteId = inviteRepository.findAll().single { it.tokenHash == sha256Hex(token) }.id!!
        val keptId = inviteRepository.findAll().single { it.tokenHash == sha256Hex(kept) }.id!!
        pendingInvites().andExpect(jsonPath("$.length()").value(2))

        revoke(inviteId).andExpect(status().isNoContent)

        pendingInvites().andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(keptId.toInt()))
        inviteStatus(token).refusedAs(InviteUnusableReason.REVOKED)
        redeem(token, "unwelcome").refusedAs(InviteUnusableReason.REVOKED)
        revoke(inviteId).andExpect(status().isNotFound).andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
        inviteStatus(kept).andExpect(status().isOk)
    }

    @Test
    fun `a link that names no Invite is refused`() {
        redeem("not-a-real-token", "stranger").refusedAs(InviteUnusableReason.UNKNOWN)
        inviteStatus("not-a-real-token").refusedAs(InviteUnusableReason.UNKNOWN)
    }

    @Test
    fun `only the Administrator can create, list or revoke Invites`() {
        val token = newInviteToken()
        redeem(token, "member").andExpect(status().isCreated)
        val member = userRepository.findByUsername("member")!!
        newInviteToken()
        val pendingId = inviteRepository.findAll().single { it.usedAt == null }.id!!

        createInvite(as_ = member)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.kind").value("ADMINISTRATOR_ONLY"))
            .andExpect(jsonPath("$.message").value("Only the Administrator can manage invites."))
        pendingInvites(as_ = member).andExpect(status().isForbidden)
        revoke(pendingId, as_ = member).andExpect(status().isForbidden)

        assertEquals(2, inviteRepository.count())
        assertEquals(null, inviteRepository.findById(pendingId).get().revokedAt)
        mockMvc.perform(post("/api/invites").with(csrf())).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the link's token carries 256 random bits and only its hash is stored`() {
        val first = newInviteToken()
        val second = newInviteToken()

        assertNotEquals(first, second)
        assertEquals(32, Base64.getUrlDecoder().decode(first).size)
        val hashes = inviteRepository.findAll().map { it.tokenHash }.toSet()
        assertEquals(setOf(sha256Hex(first), sha256Hex(second)), hashes)
        pendingInvites().andExpect(jsonPath("$[0].token").doesNotExist())
    }

    @Test
    fun `a redemption that fails leaves the Invite pending`() {
        val token = newInviteToken()

        redeem(token, "admin").andExpect(status().isBadRequest).andExpect(jsonPath("$.kind").value("DUPLICATE_RESOURCE"))

        redeem(token, "invitee").andExpect(status().isCreated)
    }

    @Test
    fun `concurrent redemptions of one Invite create only one account`() {
        val token = newInviteToken()
        val attempts = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(attempts)
        try {
            val outcomes =
                (1..attempts).map { n ->
                    pool.submit<Result<User>> {
                        start.await()
                        runCatching { userService.registerInvitedUser(token, form("racer$n")) }
                    }
                }
            start.countDown()
            val results = outcomes.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(
                attempts - 1,
                results.count { (it.exceptionOrNull() as? InviteUnusableException)?.reason == InviteUnusableReason.USED },
            )
            assertEquals(2L, userRepository.count())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `invite redemptions are throttled like sign-ups`() {
        repeat(10) { redeem("guess$it", "guesser$it").andExpect(status().isGone) }

        redeem(newInviteToken(), "guesser")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.kind").value("TOO_MANY_ATTEMPTS"))
    }
}
