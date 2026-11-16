package com.jacobcraig.stridehearth.api

import com.fasterxml.jackson.annotation.JsonInclude

enum class ApiErrorKind {
    VALIDATION_FAILED,
    DUPLICATE_RESOURCE,
    INVALID_ACTIVITY_FILE,
    RESOURCE_NOT_FOUND,
    BAD_REQUEST,
    UNAUTHORIZED,

    /** The Server's Registration Mode does not allow this sign-up (ADR-0011). */
    REGISTRATION_CLOSED,

    /** The Throttle refused this sign-in or sign-up attempt; the response's Retry-After says when to try again. */
    TOO_MANY_ATTEMPTS,

    /** The Invite link is unknown, used, expired or revoked, so it cannot be used to sign up. */
    INVITE_UNUSABLE,

    /** Only the Server's Administrator may do this. */
    ADMINISTRATOR_ONLY,

    /** A concurrent change to the athlete's data won a database lock; retrying the request is safe. */
    CONCURRENT_CHANGE,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiErrorResponse(
    val kind: ApiErrorKind,
    val message: String,
    val fieldErrors: Map<String, String>? = null,
)
