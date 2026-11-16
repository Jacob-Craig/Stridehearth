package com.jacobcraig.stridehearth.users

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

// The Write Model (Incoming) - carries the RAW password
data class UserFormDTO(
    @field:NotBlank(message = "Email cannot be empty")
    @field:Email(message = "Email must be valid")
    val email: String?,
    @field:NotBlank(message = "Password cannot be empty")
    val password: String?,
    @field:NotBlank(message = "Username cannot be empty")
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

data class LoginRequestDTO(
    val username: String,
    val password: String,
)

data class UserResponseDTO(
    val id: Long?,
    val username: String,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    /** Whether this account is the Server's Administrator, who may issue Invites. */
    val administrator: Boolean,
)

fun User.toResponseDTO(): UserResponseDTO =
    UserResponseDTO(
        id = id,
        username = username,
        email = email,
        firstName = firstName,
        lastName = lastName,
        administrator = administrator,
    )

/** Whether this Server would accept a sign-up right now, as its Registration Mode decides. */
data class RegistrationStatusDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val acceptsSignUps: Boolean,
)
