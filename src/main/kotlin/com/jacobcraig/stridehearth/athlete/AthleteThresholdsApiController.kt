package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.Zones
import com.jacobcraig.stridehearth.api.ApiErrorResponseDoc
import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * The signed-in athlete's **Thresholds**, **Zones** and **Threshold Proposals**. Every endpoint is scoped to the
 * signed-in athlete; another athlete's entry or proposal is reported as not found.
 */
@RestController
@RequestMapping("/api/athlete/thresholds")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class AthleteThresholdsApiController(
    private val athleteThresholds: AthleteThresholds,
    private val thresholdProposals: ThresholdProposals,
    private val athleteTimeZone: AthleteTimeZone,
    private val clock: Clock,
) {
    @GetMapping
    @Transactional(readOnly = true)
    @Operation(operationId = "getAthleteThresholds", summary = "The athlete's Threshold history, today's values, Zones and proposals")
    @ApiResponse(responseCode = "200", description = "The athlete's Thresholds")
    fun getThresholds(
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): AthleteThresholdsResponseDTO {
        val entries = athleteThresholds.entries(currentUser.id)
        val today = athleteTimeZone.localDate(currentUser.id, clock.instant())
        val current = ThresholdHistory(entries).asOf(today)
        return AthleteThresholdsResponseDTO(
            entries = entries.map { it.toDTO() },
            current = current.toDTO(),
            heartRateZones = current.lthr?.let { lthr -> Zones.heartRate(lthr, current.maxHr).map { it.toDTO() } },
            paceZones = current.thresholdSpeedMps?.let { speed -> Zones.pace(speed).map { it.toDTO() } },
            proposals = thresholdProposals.pending(currentUser.id).map { it.toDTO() },
        )
    }

    @PostMapping
    @Operation(
        operationId = "recordAthleteThreshold",
        summary = "Records a Threshold from a date, replacing a value already recorded for that kind and date",
    )
    @ApiResponse(responseCode = "200", description = "The stored entry")
    @ApiErrorResponseDoc(responseCode = "400", description = "Unknown kind, value out of range, or a date after tomorrow")
    fun recordThreshold(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @RequestBody request: ThresholdEntryRequestDTO,
    ): ThresholdEntryDTO {
        val errors = mutableMapOf<String, String>()
        val kind = ThresholdKind.entries.firstOrNull { it.name == request.kind }
        if (kind == null) errors["kind"] = "Unknown threshold kind"
        if (request.effectiveDate == null) errors["effectiveDate"] = "Effective date is required"
        if (request.value == null) errors["value"] = "Value is required"
        if (errors.isNotEmpty()) throw RequestValidationException(errors)
        return athleteThresholds.record(currentUser.id, kind!!, request.effectiveDate!!, request.value!!).toDTO()
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteAthleteThreshold", summary = "Deletes one of the athlete's Threshold entries")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiErrorResponseDoc(responseCode = "404", description = "No such entry of the athlete's")
    fun deleteThreshold(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        athleteThresholds.delete(currentUser.id, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/proposals/{id}/accept")
    @Operation(operationId = "acceptThresholdProposal", summary = "Applies a Threshold Proposal as a Threshold entry")
    @ApiResponse(responseCode = "200", description = "The entry the proposal became")
    @ApiErrorResponseDoc(responseCode = "404", description = "No such pending proposal of the athlete's")
    fun acceptProposal(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @PathVariable id: Long,
    ): ThresholdEntryDTO = thresholdProposals.accept(currentUser.id, id).toDTO()

    @PostMapping("/proposals/{id}/dismiss")
    @Operation(operationId = "dismissThresholdProposal", summary = "Dismisses a Threshold Proposal without applying it")
    @ApiResponse(responseCode = "204", description = "Dismissed")
    @ApiErrorResponseDoc(responseCode = "404", description = "No such pending proposal of the athlete's")
    fun dismissProposal(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        thresholdProposals.dismiss(currentUser.id, id)
        return ResponseEntity.noContent().build()
    }
}
