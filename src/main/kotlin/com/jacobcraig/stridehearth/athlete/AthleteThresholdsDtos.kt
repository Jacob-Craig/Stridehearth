package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.HeartRateZone
import com.jacobcraig.stridehearth.analytics.PaceZone
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/**
 * The athlete's **Thresholds** page: every entry, the values in force today, the **Zones** they give and the
 * **Threshold Proposals** awaiting a decision. Canonical units only (bpm, m/s, W); the client formats paces.
 */
data class AthleteThresholdsResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Every entry, newest effective date first")
    val entries: List<ThresholdEntryDTO>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The values in force today, in the athlete's time zone")
    val current: CurrentThresholdsDTO,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["array", "null"], description = "Null without an LTHR")
    val heartRateZones: List<HeartRateZoneDTO>?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["array", "null"], description = "Null without a threshold pace")
    val paceZones: List<PaceZoneDTO>?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val proposals: List<ThresholdProposalDTO>,
)

/** One dated **Threshold** entry; [value] is in the kind's unit (bpm, m/s or W). */
data class ThresholdEntryDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val kind: ThresholdKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val effectiveDate: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val value: Double,
)

/** The **Thresholds** in force on one date; each null when the athlete has never recorded that kind. */
data class CurrentThresholdsDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val maxHr: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val restingHr: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val lthr: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val thresholdSpeedMps: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val criticalPowerWatts: Double?,
)

/** A heart-rate **Zone**, `[lowerBpm, upperBpm)`; Z1 has no lower bound and Z5 no upper bound without MAX_HR. */
data class HeartRateZoneDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val zone: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val lowerBpm: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val upperBpm: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Seiler's three-zone grouping: 1, 1, 2, 2, 3")
    val seilerZone: Int,
)

/** A pace **Zone** as speeds in m/s, `[lowerSpeedMps, upperSpeedMps)`; Z1 has no lower and Z5 no upper bound. */
data class PaceZoneDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val zone: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val lowerSpeedMps: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val upperSpeedMps: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Seiler's three-zone grouping: 1, 1, 2, 2, 3")
    val seilerZone: Int,
)

/** A **Threshold Proposal** awaiting the athlete's Accept or Dismiss. */
data class ThresholdProposalDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val kind: ThresholdKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val value: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val effectiveDate: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The feature that proposed it")
    val source: String,
)

/**
 * A new **Threshold** entry. [kind] is read as text so an unknown kind is a field error rather than an unreadable
 * body; [value] is in the kind's unit (THRESHOLD_PACE as a speed in m/s).
 */
data class ThresholdEntryRequestDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = ThresholdKind::class)
    val kind: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val effectiveDate: LocalDate?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val value: Double?,
)

/** The athlete's IANA time zone, such as `Europe/London`. */
data class AthleteTimeZoneRequestDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Europe/London")
    val timeZone: String?,
)

fun ThresholdEntry.toDTO() = ThresholdEntryDTO(id, kind, effectiveDate, value)

fun ThresholdProposal.toDTO() = ThresholdProposalDTO(id, kind, value, effectiveDate, source)

fun ThresholdsOn.toDTO() = CurrentThresholdsDTO(maxHr, restingHr, lthr, thresholdSpeedMps, criticalPowerWatts)

fun HeartRateZone.toDTO() = HeartRateZoneDTO(zone, lowerBpm, upperBpm, seilerZone)

fun PaceZone.toDTO() = PaceZoneDTO(zone, lowerSpeedMps, upperSpeedMps, seilerZone)
