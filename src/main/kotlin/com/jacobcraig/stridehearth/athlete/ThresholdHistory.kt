package com.jacobcraig.stridehearth.athlete

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/**
 * The kinds of **Threshold** an athlete records, each with its canonical unit and the values accepted for it.
 * THRESHOLD_PACE is stored as a speed in m/s; the SPA shows and accepts it as a pace per km.
 */
@Schema(enumAsRef = true)
enum class ThresholdKind(
    val unit: String,
    val validRange: ClosedFloatingPointRange<Double>,
) {
    MAX_HR("bpm", 100.0..250.0),
    RESTING_HR("bpm", 25.0..120.0),
    LTHR("bpm", 60.0..240.0),
    THRESHOLD_PACE("m/s", 1.0..8.0),
    CRITICAL_POWER("W", 50.0..700.0),
}

/** One dated **Threshold** entry: [value] (in [ThresholdKind.unit]) is in force from [effectiveDate]. */
data class ThresholdEntry(
    val id: Long,
    val kind: ThresholdKind,
    val effectiveDate: LocalDate,
    val value: Double,
)

/** The **Thresholds** in force on one date, one per kind, each null when the athlete has never recorded that kind. */
data class ThresholdsOn(
    val maxHr: Double?,
    val restingHr: Double?,
    val lthr: Double?,
    val thresholdSpeedMps: Double?,
    val criticalPowerWatts: Double?,
) {
    companion object {
        val NONE = ThresholdsOn(null, null, null, null, null)
    }
}

/**
 * An athlete's whole **Threshold** history, resolved by date. Per kind, the value in force on a date is the latest
 * entry on or before it; before the first entry, the earliest entry applies backwards (ADR-0015), so Activities
 * from before an athlete first recorded a Threshold still use it. A kind with no entries is null.
 */
class ThresholdHistory(
    entries: List<ThresholdEntry>,
) {
    private val byKind: Map<ThresholdKind, List<ThresholdEntry>> =
        entries.groupBy { it.kind }.mapValues { (_, list) -> list.sortedBy { it.effectiveDate } }

    /** The value of [kind] in force on [date], or null when the athlete has no entry of that kind. */
    fun valueOf(
        kind: ThresholdKind,
        date: LocalDate,
    ): Double? {
        val ascending = byKind[kind] ?: return null
        return (ascending.lastOrNull { !it.effectiveDate.isAfter(date) } ?: ascending.first()).value
    }

    /** Every kind's value in force on [date]. */
    fun asOf(date: LocalDate): ThresholdsOn =
        ThresholdsOn(
            maxHr = valueOf(ThresholdKind.MAX_HR, date),
            restingHr = valueOf(ThresholdKind.RESTING_HR, date),
            lthr = valueOf(ThresholdKind.LTHR, date),
            thresholdSpeedMps = valueOf(ThresholdKind.THRESHOLD_PACE, date),
            criticalPowerWatts = valueOf(ThresholdKind.CRITICAL_POWER, date),
        )

    /** [asOf] for each of [dates], against this one history. */
    fun asOfMany(dates: Collection<LocalDate>): Map<LocalDate, ThresholdsOn> = dates.distinct().associateWith(::asOf)
}
