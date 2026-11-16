package com.jacobcraig.stridehearth.users

import com.jacobcraig.stridehearth.api.ApiErrorResponse
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The Administrator's Invites (ADR-0011). Every endpoint answers 403 `ADMINISTRATOR_ONLY` to any other account.
 * Redeeming an Invite is a sign-up, under `/api/auth/invites`.
 */
@RestController
@RequestMapping("/api/invites")
@ApiResponses(
    ApiResponse(
        responseCode = "403",
        description = "The signed-in account is not the Administrator",
        content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
    ),
)
class InviteApiController(
    private val inviteService: InviteService,
) {
    /** The Server's pending Invites, newest first. */
    @GetMapping
    @ApiResponses(ApiResponse(responseCode = "200", description = "The pending Invites"))
    fun listPendingInvites(
        @AuthenticationPrincipal user: AppUserDetails,
    ): List<InviteDTO> = inviteService.listPending(user.id).map { it.toDTO() }

    /** Issues a new Invite. Its link's token is in this response and nowhere else. */
    @PostMapping
    @ApiResponses(ApiResponse(responseCode = "201", description = "The Invite was created"))
    fun createInvite(
        @AuthenticationPrincipal user: AppUserDetails,
    ): ResponseEntity<CreatedInviteDTO> = ResponseEntity.status(HttpStatus.CREATED).body(inviteService.create(user.id).toDTO())

    /** Revokes a pending Invite, so its link no longer lets anyone sign up. */
    @DeleteMapping("/{inviteId}")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "The Invite was revoked"),
        ApiResponse(
            responseCode = "404",
            description = "No pending Invite has this id",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun revokeInvite(
        @AuthenticationPrincipal user: AppUserDetails,
        @PathVariable inviteId: Long,
    ): ResponseEntity<Void> {
        inviteService.revoke(user.id, inviteId)
        return ResponseEntity.noContent().build()
    }
}
