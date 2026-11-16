package com.jacobcraig.stridehearth.users

import com.jacobcraig.stridehearth.api.ApiErrorKind
import com.jacobcraig.stridehearth.api.ApiErrorResponse
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthApiController(
    private val authenticationManager: AuthenticationManager,
    private val securityContextRepository: SecurityContextRepository,
    private val userService: UserService,
    private val registrationGate: RegistrationGate,
    private val signInThrottle: SignInThrottle,
    private val signUpThrottle: SignUpThrottle,
    private val inviteService: InviteService,
) {
    @PostMapping("/login")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Successfully authenticated"),
        ApiResponse(
            responseCode = "401",
            description = "Authentication failed",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Too many failed sign-ins from this source or against this username; see Retry-After",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun login(
        @RequestBody request: LoginRequestDTO,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<UserResponseDTO> {
        val authReq = UsernamePasswordAuthenticationToken.unauthenticated(request.username, request.password)
        val authentication =
            signInThrottle.attempt(throttleSourceOf(httpRequest), request.username) {
                authenticationManager.authenticate(authReq)
            }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, httpRequest, httpResponse)

        val user =
            userService.findByUsername(request.username)
                ?: throw IllegalStateException("Authenticated user not found in repository")
        return ResponseEntity.ok(user.toResponseDTO())
    }

    @PostMapping("/register")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Successfully registered"),
        ApiResponse(
            responseCode = "400",
            description = "Validation or duplicate error",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "The Server's Registration Mode does not allow this sign-up",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Too many sign-up attempts from this source; see Retry-After",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun register(
        @Valid @RequestBody dto: UserFormDTO,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<UserResponseDTO> {
        signUpThrottle.admit(throttleSourceOf(httpRequest))
        val newUser = userService.registerUser(dto)
        signInNewAccount(dto, httpRequest, httpResponse)
        return ResponseEntity.status(HttpStatus.CREATED).body(newUser.toResponseDTO())
    }

    /** Whether the sign-in and sign-up screens should offer a sign-up form; the sign-up itself is checked again. */
    @GetMapping("/registration")
    fun registrationStatus(): RegistrationStatusDTO = RegistrationStatusDTO(acceptsSignUps = registrationGate.acceptsSignUps())

    /** Whether the Invite whose link carries [token] is pending, so the invite screen can offer its sign-up form. */
    @GetMapping("/invites/{token}")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The Invite is pending"),
        ApiResponse(
            responseCode = "410",
            description = "The Invite is unknown, used, expired or revoked",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun inviteStatus(
        @PathVariable token: String,
    ): PendingInviteDTO = PendingInviteDTO(expiresAt = inviteService.check(token).expiresAt)

    /**
     * Signs up a new account with the Invite whose link carries [token], whatever the Server's Registration Mode,
     * and signs it in. The Invite is used up.
     */
    @PostMapping("/invites/{token}/redeem")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Successfully registered with the Invite"),
        ApiResponse(
            responseCode = "400",
            description = "Validation or duplicate error",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "410",
            description = "The Invite is unknown, used, expired or revoked",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "Too many sign-up attempts from this source; see Retry-After",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun redeemInvite(
        @PathVariable token: String,
        @Valid @RequestBody dto: UserFormDTO,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<UserResponseDTO> {
        signUpThrottle.admit(throttleSourceOf(httpRequest))
        val newUser = userService.registerInvitedUser(token, dto)
        signInNewAccount(dto, httpRequest, httpResponse)
        return ResponseEntity.status(HttpStatus.CREATED).body(newUser.toResponseDTO())
    }

    private fun signInNewAccount(
        dto: UserFormDTO,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ) {
        val authReq = UsernamePasswordAuthenticationToken.unauthenticated(dto.username, dto.password)
        val authentication = authenticationManager.authenticate(authReq)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, httpRequest, httpResponse)
    }

    @PostMapping("/logout")
    fun logout(httpRequest: HttpServletRequest): ResponseEntity<Void> {
        val session = httpRequest.getSession(false)
        session?.invalidate()
        SecurityContextHolder.clearContext()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Current authenticated user profile",
            content = [Content(schema = Schema(implementation = UserResponseDTO::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun me(
        @AuthenticationPrincipal userDetails: AppUserDetails?,
    ): ResponseEntity<Any> {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiErrorResponse(
                    kind = ApiErrorKind.UNAUTHORIZED,
                    message = "User is not authenticated",
                ),
            )
        }
        val user =
            userService.findByUsername(userDetails.username)
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiErrorResponse(
                        kind = ApiErrorKind.UNAUTHORIZED,
                        message = "User is not authenticated",
                    ),
                )
        return ResponseEntity.ok(user.toResponseDTO())
    }
}
