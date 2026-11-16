package com.jacobcraig.stridehearth.api

import com.jacobcraig.stridehearth.system.InvalidActivityFileException
import com.jacobcraig.stridehearth.users.ADMINISTRATOR_ONLY_MESSAGE
import com.jacobcraig.stridehearth.users.AdministratorOnlyException
import com.jacobcraig.stridehearth.users.InviteUnusableException
import com.jacobcraig.stridehearth.users.REGISTRATION_CLOSED_MESSAGE
import com.jacobcraig.stridehearth.users.RegistrationClosedException
import com.jacobcraig.stridehearth.users.TooManyAttemptsException
import com.jacobcraig.stridehearth.users.UserAlreadyExistsException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.support.MissingServletRequestPartException

/** The message of a `CONCURRENT_CHANGE` [ApiErrorResponse]: the request lost a database lock and can be retried. */
const val CONCURRENT_CHANGE_MESSAGE = "Another change to your activities is in progress. Please try again."

@RestControllerAdvice
class GlobalApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
        val fieldErrors =
            ex.bindingResult.fieldErrors.associate {
                (it.field) to (it.defaultMessage ?: "Invalid value")
            }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                kind = ApiErrorKind.VALIDATION_FAILED,
                message = VALIDATION_FAILED_MESSAGE,
                fieldErrors = fieldErrors,
            ),
        )
    }

    @ExceptionHandler(RequestValidationException::class)
    fun handleRequestValidation(ex: RequestValidationException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                kind = ApiErrorKind.VALIDATION_FAILED,
                message = ex.message ?: VALIDATION_FAILED_MESSAGE,
                fieldErrors = ex.fieldErrors,
            ),
        )

    @ExceptionHandler(UserAlreadyExistsException::class)
    fun handleDuplicateUser(ex: UserAlreadyExistsException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                kind = ApiErrorKind.DUPLICATE_RESOURCE,
                message = ex.message ?: "Resource already exists",
                fieldErrors = mapOf(ex.field to (ex.message ?: "Already in use")),
            ),
        )

    @ExceptionHandler(RegistrationClosedException::class)
    fun handleRegistrationClosed(ex: RegistrationClosedException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(
                kind = ApiErrorKind.REGISTRATION_CLOSED,
                message = ex.message ?: REGISTRATION_CLOSED_MESSAGE,
            ),
        )

    @ExceptionHandler(InviteUnusableException::class)
    fun handleInviteUnusable(ex: InviteUnusableException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.GONE).body(
            ApiErrorResponse(kind = ApiErrorKind.INVITE_UNUSABLE, message = ex.reason.message),
        )

    @ExceptionHandler(AdministratorOnlyException::class)
    fun handleAdministratorOnly(ex: AdministratorOnlyException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(kind = ApiErrorKind.ADMINISTRATOR_ONLY, message = ex.message ?: ADMINISTRATOR_ONLY_MESSAGE),
        )

    @ExceptionHandler(TooManyAttemptsException::class)
    fun handleTooManyAttempts(ex: TooManyAttemptsException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            // Whole seconds, rounded up so a client that waits exactly this long is not refused again.
            .header(HttpHeaders.RETRY_AFTER, maxOf(1L, (ex.retryAfter.toMillis() + 999) / 1000).toString())
            .body(
                ApiErrorResponse(
                    kind = ApiErrorKind.TOO_MANY_ATTEMPTS,
                    message = ex.message ?: "Too many attempts. Please try again later.",
                ),
            )

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(ex: AuthenticationException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorResponse(
                kind = ApiErrorKind.UNAUTHORIZED,
                message = "Invalid username or password",
            ),
        )

    @ExceptionHandler(InvalidActivityFileException::class)
    fun handleInvalidActivityFile(ex: InvalidActivityFileException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                kind = ApiErrorKind.INVALID_ACTIVITY_FILE,
                message = ex.message ?: "Invalid activity file",
            ),
        )

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                kind = ApiErrorKind.RESOURCE_NOT_FOUND,
                message = ex.message ?: "Resource not found",
            ),
        )

    @ExceptionHandler(MissingServletRequestPartException::class)
    fun handleMissingPart(ex: MissingServletRequestPartException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                kind = ApiErrorKind.BAD_REQUEST,
                message = "Required part '${ex.requestPartName}' is missing",
            ),
        )

    /**
     * A transaction aborted on a database lock (a deadlock, PostgreSQL's 40P01, or a lock timeout). Nothing was
     * written, so the athlete can retry. ADR-0006 orders the Personal Record locks, so this happens only when an
     * import upgrade and a delete of the same Activity race.
     */
    @ExceptionHandler(PessimisticLockingFailureException::class)
    fun handleLockFailure(ex: PessimisticLockingFailureException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                kind = ApiErrorKind.CONCURRENT_CHANGE,
                message = CONCURRENT_CHANGE_MESSAGE,
            ),
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                kind = ApiErrorKind.BAD_REQUEST,
                message = ex.message ?: "Bad request",
            ),
        )
}
