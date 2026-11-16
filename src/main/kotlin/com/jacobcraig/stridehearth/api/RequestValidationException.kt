package com.jacobcraig.stridehearth.api

/** The top-level [ApiErrorResponse] message of a `VALIDATION_FAILED` error; the specifics live in its field errors. */
const val VALIDATION_FAILED_MESSAGE = "Validation failed"

/**
 * Thrown when a request is well-formed but fails a domain rule that bean validation cannot express
 * (e.g. an attempt to edit **Protected Metrics**). Rendered as a `VALIDATION_FAILED` [ApiErrorResponse].
 *
 * @param fieldErrors a message for each offending request field.
 */
class RequestValidationException(
    message: String,
    val fieldErrors: Map<String, String>,
) : RuntimeException(message) {
    /** A validation failure described entirely by its [fieldErrors], like bean-validation failures. */
    constructor(fieldErrors: Map<String, String>) : this(VALIDATION_FAILED_MESSAGE, fieldErrors)
}
