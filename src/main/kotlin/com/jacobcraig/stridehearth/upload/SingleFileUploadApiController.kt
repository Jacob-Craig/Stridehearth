package com.jacobcraig.stridehearth.upload

import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.api.ApiErrorResponse
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * JSON API for importing a single activity file synchronously from the React SPA. Bulk imports use
 * asynchronous **Import Batches** instead.
 */
@RestController
@RequestMapping("/api/activities")
class SingleFileUploadApiController(
    private val activityImportService: ActivityImportService,
) {
    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "The outcome of importing the file",
            content = [Content(schema = Schema(implementation = SingleFileUploadResponseDTO::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "The file could not be read as an activity (kind INVALID_ACTIVITY_FILE)",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun uploadActivityFile(
        @RequestPart("file") file: MultipartFile,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): SingleFileUploadResponseDTO = activityImportService.import(file, currentUser.id).toSingleFileUploadResponse()
}
