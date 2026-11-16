package com.jacobcraig.stridehearth.users

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** The setting an Operator uses to choose the Server's [RegistrationMode], as a property and as an environment variable. */
const val REGISTRATION_MODE_PROPERTY = "stridehearth.registration-mode"
const val REGISTRATION_MODE_ENV_VAR = "STRIDEHEARTH_REGISTRATION_MODE"

/** The message of a `REGISTRATION_CLOSED` error, and what the SPA says on a closed Server. */
const val REGISTRATION_CLOSED_MESSAGE = "Registration is closed on this Server."

/**
 * Whether a Server lets anyone create an account ([OPEN]) or only lets its very first account register itself
 * ([CLOSED]). The Operator chooses it in the Server's environment (ADR-0011); closed is the default.
 */
enum class RegistrationMode(
    val setting: String,
) {
    OPEN("open"),
    CLOSED("closed"),
    ;

    companion object {
        /**
         * The Registration Mode an Operator's [setting] names, ignoring case and surrounding whitespace, and [CLOSED]
         * when it is not set. Anything else is refused rather than guessed at, so a typo never opens a Server.
         */
        fun fromSetting(setting: String?): RegistrationMode {
            val normalised = setting?.trim()?.lowercase()
            if (normalised.isNullOrEmpty()) return CLOSED
            return entries.firstOrNull { it.setting == normalised }
                ?: throw UnknownRegistrationModeException(setting)
        }
    }
}

/** The Operator set a Registration Mode that is neither `open` nor `closed`; the Server refuses to start. */
class UnknownRegistrationModeException(
    val setting: String,
) : IllegalStateException(
        "Unknown Registration Mode '$setting' ($REGISTRATION_MODE_ENV_VAR / $REGISTRATION_MODE_PROPERTY). " +
            "Set it to 'open' or 'closed', or leave it unset for closed. The Server will not start until it is one of these.",
    )

/** A sign-up the Server's Registration Mode does not allow. */
class RegistrationClosedException : RuntimeException(REGISTRATION_CLOSED_MESSAGE)

/**
 * A sign-up the [RegistrationGate] admitted. [firstAccount] says the new account is the Server's first, and so
 * becomes its Administrator. The gate decides that under the same lock as the admission, so two concurrent first
 * sign-ups can never both become the Administrator.
 */
class SignUpAdmission internal constructor(
    val firstAccount: Boolean,
)

/**
 * The one check that accepts or rejects a sign-up, and the only reader of the Server's Registration Mode
 * (ADR-0011), so open and closed Servers otherwise run identical code.
 *
 * An open Server accepts every sign-up. A closed Server accepts one only while it has no accounts at all, so its
 * very first account can register itself, or when a pending Invite vouches for it.
 */
@Component
class RegistrationGate(
    @Value("\${$REGISTRATION_MODE_PROPERTY:}") registrationModeSetting: String,
    private val userRepository: UserRepository,
    private val jdbcClient: JdbcClient,
) {
    private val registrationMode = RegistrationMode.fromSetting(registrationModeSetting)

    init {
        when (registrationMode) {
            RegistrationMode.OPEN ->
                log.warn(
                    "Registration Mode is open: anyone who can reach this Server can create an account. " +
                        "Open mode is not yet hardened for the public internet (there are no per-athlete storage " +
                        "or upload quotas), so do not expose an open Server to strangers. " +
                        "Set {}=closed to allow only the first account.",
                    REGISTRATION_MODE_ENV_VAR,
                )
            RegistrationMode.CLOSED ->
                log.info("Registration Mode is closed: only the first account on this Server may register itself.")
        }
    }

    /** Whether a sign-up made now would be accepted. Advisory only: [admitSignUp] makes the binding decision. */
    fun acceptsSignUps(): Boolean = decide()

    /**
     * Admits one sign-up, or throws [RegistrationClosedException]. An [invite] claimed in this transaction admits it
     * whatever the Registration Mode; without one, the Registration Mode decides.
     *
     * Must run in the transaction that saves the new account: it takes a transaction-scoped advisory lock first, so
     * concurrent sign-ups, invited or not, are decided one after the other. Only the first on an empty closed Server
     * becomes an account, and only the first on any empty Server becomes its Administrator.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun admitSignUp(invite: ClaimedInvite? = null): SignUpAdmission {
        jdbcClient
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", SIGN_UP_LOCK_KEY)
            // pg_advisory_xact_lock returns void: run the query for its effect and discard the row.
            .query { _, _ -> }
            .list()
        val firstAccount = userRepository.count() == 0L
        if (invite == null && !decide(firstAccount)) throw RegistrationClosedException()
        return SignUpAdmission(firstAccount)
    }

    private fun decide(firstAccount: Boolean = userRepository.count() == 0L): Boolean =
        when (registrationMode) {
            RegistrationMode.OPEN -> true
            RegistrationMode.CLOSED -> firstAccount
        }

    private companion object {
        private val log = LoggerFactory.getLogger(RegistrationGate::class.java)

        /** The name hashed into the advisory lock that serialises sign-ups; distinct from every other lock's prefix. */
        private const val SIGN_UP_LOCK_KEY = "sign_up"
    }
}
