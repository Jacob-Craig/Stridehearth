package com.jacobcraig.stridehearth.api

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.core.annotation.AliasFor

/**
 * Documents an error response whose body is the [ApiErrorResponse] envelope, so controllers state only
 * the status code and why it happens. springdoc resolves it as a merged `@ApiResponse` meta-annotation.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@ApiResponse(content = [Content(schema = Schema(implementation = ApiErrorResponse::class))])
annotation class ApiErrorResponseDoc(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "responseCode")
    val responseCode: String,
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String,
)
