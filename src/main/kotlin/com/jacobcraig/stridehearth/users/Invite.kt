package com.jacobcraig.stridehearth.users

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** How long an Invite stays pending after the Administrator creates it (ADR-0011). */
val INVITE_LIFETIME: Duration = Duration.ofDays(7)

/**
 * A single-use link the Administrator issues so one person can create their own account, even on a closed Server
 * (ADR-0011). Only a hash of the link's token is stored. An Invite is pending until it is used, revoked or expires.
 */
@Entity
@Table(name = "invite")
class Invite(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "token_hash", nullable = false, unique = true)
    var tokenHash: String,
    @Column(name = "created_by", nullable = false)
    var createdBy: Long,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
    @Column(name = "used_at")
    var usedAt: Instant? = null,
    @Column(name = "used_by")
    var usedBy: Long? = null,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
) {
    /** Why this Invite can no longer be used at [now], or null while it is pending. */
    fun unusableAt(now: Instant): InviteUnusableReason? =
        when {
            usedAt != null -> InviteUnusableReason.USED
            revokedAt != null -> InviteUnusableReason.REVOKED
            !now.isBefore(expiresAt) -> InviteUnusableReason.EXPIRED
            else -> null
        }
}

interface InviteRepository : JpaRepository<Invite, Long> {
    fun findByTokenHash(tokenHash: String): Invite?

    /** The Invite with [tokenHash], locked against concurrent redemption and revocation until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invite i WHERE i.tokenHash = :tokenHash")
    fun lockByTokenHash(tokenHash: String): Invite?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invite i WHERE i.id = :id")
    fun lockById(id: Long): Invite?

    @Query(
        "SELECT i FROM Invite i WHERE i.usedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now " +
            "ORDER BY i.createdAt DESC, i.id DESC",
    )
    fun findPending(now: Instant): List<Invite>
}

/** Why an Invite's link no longer lets anyone sign up, with what the invitee is told. */
enum class InviteUnusableReason(
    val message: String,
) {
    UNKNOWN("This invite link is not valid. Check that it was copied in full, or ask whoever invited you for a new one."),
    USED("This invite link has already been used. Ask whoever invited you for a new one."),
    EXPIRED("This invite link has expired. Ask whoever invited you for a new one."),
    REVOKED("This invite link has been revoked. Ask whoever invited you for a new one."),
}

/** An Invite link that cannot be used to sign up. */
class InviteUnusableException(
    val reason: InviteUnusableReason,
) : RuntimeException(reason.message)

/** The message of an `ADMINISTRATOR_ONLY` error. */
const val ADMINISTRATOR_ONLY_MESSAGE = "Only the Administrator can manage invites."

/** Something only the Server's Administrator may do, attempted by another account. */
class AdministratorOnlyException : RuntimeException(ADMINISTRATOR_ONLY_MESSAGE)

/** A newly created Invite with its link's [token], which exists only here: the Server keeps just its hash. */
class CreatedInvite(
    val invite: Invite,
    val token: String,
)

/**
 * A pending Invite locked in the current transaction by [InviteService.claim]. Passing one to
 * [RegistrationGate.admitSignUp] admits a sign-up on a closed Server.
 */
class ClaimedInvite internal constructor(
    internal val invite: Invite,
)

/**
 * Issues, lists, revokes and redeems Invites. Only the Administrator may issue, list or revoke them; anyone holding
 * a pending Invite's link may redeem it once, through [UserService.registerInvitedUser].
 */
@Service
class InviteService(
    private val inviteRepository: InviteRepository,
    private val userRepository: UserRepository,
) {
    private val random = SecureRandom()

    /** Issues a new Invite, pending for [INVITE_LIFETIME]. */
    @Transactional
    fun create(actorId: Long): CreatedInvite {
        requireAdministrator(actorId)
        val tokenBytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val now = Instant.now()
        val invite =
            inviteRepository.save(
                Invite(
                    tokenHash = hashOf(token),
                    createdBy = actorId,
                    createdAt = now,
                    expiresAt = now.plus(INVITE_LIFETIME),
                ),
            )
        return CreatedInvite(invite, token)
    }

    /** The Server's pending Invites, newest first. */
    @Transactional(readOnly = true)
    fun listPending(actorId: Long): List<Invite> {
        requireAdministrator(actorId)
        return inviteRepository.findPending(Instant.now())
    }

    /** Revokes a pending Invite, or throws [NoSuchElementException] if [inviteId] names none. */
    @Transactional
    fun revoke(
        actorId: Long,
        inviteId: Long,
    ) {
        requireAdministrator(actorId)
        val invite = inviteRepository.lockById(inviteId)
        if (invite == null || invite.unusableAt(Instant.now()) != null) {
            throw NoSuchElementException("There is no pending invite $inviteId.")
        }
        invite.revokedAt = Instant.now()
    }

    /** The pending Invite whose link carries [token], or throws [InviteUnusableException]. Advisory only. */
    @Transactional(readOnly = true)
    fun check(token: String): Invite = pending(inviteRepository.findByTokenHash(hashOf(token)))

    /**
     * Locks the pending Invite whose link carries [token] until the transaction ends, or throws
     * [InviteUnusableException]. A concurrent redemption of the same link waits here, then finds it used.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun claim(token: String): ClaimedInvite = ClaimedInvite(pending(inviteRepository.lockByTokenHash(hashOf(token))))

    /** Marks a claimed Invite used by the account it let [user] create. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun useUp(
        claimed: ClaimedInvite,
        user: User,
    ) {
        claimed.invite.usedAt = Instant.now()
        claimed.invite.usedBy = user.id
    }

    private fun pending(invite: Invite?): Invite {
        if (invite == null) throw InviteUnusableException(InviteUnusableReason.UNKNOWN)
        invite.unusableAt(Instant.now())?.let { throw InviteUnusableException(it) }
        return invite
    }

    private fun requireAdministrator(actorId: Long) {
        if (userRepository.findById(actorId).map { it.administrator }.orElse(false) != true) {
            throw AdministratorOnlyException()
        }
    }

    private companion object {
        /** 256 bits from a SecureRandom: far beyond guessing, and well over the 128 a link needs. */
        private const val TOKEN_BYTES = 32

        /**
         * The SHA-256 of a link's token, which is all the Server stores. Invites are found by an index lookup on this
         * hash rather than by comparing tokens, so lookup timing reveals nothing usable about any stored token. A
         * token carries 256 random bits, so an unsalted fast hash cannot be reversed by guessing.
         */
        private fun hashOf(token: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** A pending Invite, as the Administrator sees it. Its link is not included: the Server no longer knows it. */
data class InviteDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val createdAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val expiresAt: Instant,
)

/** A newly created Invite, with the token its link carries. This is the only time the token is ever sent. */
data class CreatedInviteDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val token: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val createdAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val expiresAt: Instant,
)

/** A pending Invite, as its link's holder sees it before signing up. */
data class PendingInviteDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val expiresAt: Instant,
)

fun Invite.toDTO(): InviteDTO = InviteDTO(id = checkNotNull(id), createdAt = createdAt, expiresAt = expiresAt)

fun CreatedInvite.toDTO(): CreatedInviteDTO =
    CreatedInviteDTO(id = checkNotNull(invite.id), token = token, createdAt = invite.createdAt, expiresAt = invite.expiresAt)
