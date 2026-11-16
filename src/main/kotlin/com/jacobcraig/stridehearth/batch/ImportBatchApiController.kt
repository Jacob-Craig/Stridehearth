package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.api.ApiErrorResponseDoc
import com.jacobcraig.stridehearth.users.AppUserDetails
import com.jacobcraig.stridehearth.users.UserRepository
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * JSON API backing the SPA's **Import Batch** uploader, live progress dashboard, and import history.
 * Validation failures surface through `GlobalApiExceptionHandler` as `ApiErrorResponse` envelopes.
 */
@RestController
@RequestMapping("/api/activities/import")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class ImportBatchApiController(
    private val importBatchService: ImportBatchService,
    private val lifecycle: ImportBatchLifecycle,
    private val userRepository: UserRepository,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponse(responseCode = "202", description = "The Import Batch was staged and queued for ingestion")
    @ApiErrorResponseDoc(
        responseCode = "400",
        description = "No files, no valid .fit entries, or an archive failed validation",
    )
    fun submitImportBatch(
        @RequestParam(name = "files", required = false) files: List<MultipartFile>?,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): ImportBatchReceiptDTO {
        val user = userRepository.getReferenceById(currentUser.id)
        return importBatchService.createAndEnqueueBatch(user, files.orEmpty())
    }

    @GetMapping("/batches/{id}")
    @ApiResponse(responseCode = "200", description = "Running counters for the Import Batch")
    @ApiErrorResponseDoc(responseCode = "404", description = NOT_FOUND_DESCRIPTION)
    fun getImportBatch(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): ImportBatchSummaryDTO = lifecycle.summary(id, currentUser.id) ?: throw importBatchNotFound(id)

    @PostMapping("/batches/{id}/cancel")
    @ApiResponse(
        responseCode = "200",
        description = "The Import Batch after cancellation; batches already in a terminal status are unchanged",
    )
    @ApiErrorResponseDoc(responseCode = "404", description = NOT_FOUND_DESCRIPTION)
    fun cancelImportBatch(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): ImportBatchSummaryDTO = importBatchService.cancelBatch(id, currentUser.id) ?: throw importBatchNotFound(id)

    @GetMapping("/history")
    @ApiResponse(responseCode = "200", description = "The athlete's Import Batches, newest first")
    fun getImportHistory(
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): List<ImportBatchSummaryDTO> = lifecycle.history(currentUser.id)

    private fun importBatchNotFound(id: Long) = NoSuchElementException("Import Batch $id was not found")
}

private const val NOT_FOUND_DESCRIPTION = "No Import Batch with this id belongs to the athlete"
