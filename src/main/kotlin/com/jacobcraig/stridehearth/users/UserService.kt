package com.jacobcraig.stridehearth.users

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class UserAlreadyExistsException(
    val field: String,
    message: String,
) : RuntimeException(message)

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val registrationGate: RegistrationGate,
    private val inviteService: InviteService,
) {
    fun findByUsername(username: String): User? = userRepository.findByUsername(username)

    /**
     * Signs up a new account, if the [RegistrationGate] admits it. The gate decides before the duplicate checks,
     * so a closed Server never reveals which usernames or emails it holds. The Server's first account becomes its
     * Administrator.
     */
    @Transactional
    fun registerUser(dto: UserFormDTO): User = createAccount(dto, registrationGate.admitSignUp())

    /**
     * Signs up a new account with the Invite whose link carries [token], whatever the Server's Registration Mode,
     * and uses the Invite up; throws [InviteUnusableException] if it is not pending. The Invite is checked before
     * the duplicate checks, so a stale link never reveals which usernames or emails the Server holds, and it stays
     * pending if the account cannot be created.
     */
    @Transactional
    fun registerInvitedUser(
        token: String,
        dto: UserFormDTO,
    ): User {
        val invite = inviteService.claim(token)
        val user = createAccount(dto, registrationGate.admitSignUp(invite))
        inviteService.useUp(invite, user)
        return user
    }

    private fun createAccount(
        dto: UserFormDTO,
        admission: SignUpAdmission,
    ): User {
        // validation - null checks first
        val rawPassword =
            dto.password?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Password is required")
        val email = dto.email ?: throw IllegalArgumentException("Email is required")
        val username = dto.username ?: throw IllegalArgumentException("Username is required")

        if (userRepository.existsByEmail(email)) {
            throw UserAlreadyExistsException("email", "Email is already registered")
        }
        if (userRepository.existsByUsername(username)) {
            throw UserAlreadyExistsException("username", "Username is already taken")
        }

        val newUser =
            User(
                email = email,
                passwordHash = passwordEncoder.encode(rawPassword)!!,
                username = username,
                firstName = dto.firstName,
                lastName = dto.lastName,
                administrator = admission.firstAccount,
            )

        return userRepository.save(newUser)
    }
}
