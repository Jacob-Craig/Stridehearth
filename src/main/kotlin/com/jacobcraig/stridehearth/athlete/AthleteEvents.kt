package com.jacobcraig.stridehearth.athlete

import java.time.LocalDate

/**
 * An athlete's [kind] of **Threshold** changed: an entry was recorded, replaced or deleted. Derived data that depends
 * on it must be recomputed from [affectedFrom] onwards, an athlete-local date, or from the beginning when it is null
 * (the changed entry is or was the earliest of its kind, which applies backwards).
 *
 * Published inside the writing transaction; subscribers are plain listeners that write only durable markers.
 */
data class ThresholdsChanged(
    val userId: Long,
    val kind: ThresholdKind,
    val affectedFrom: LocalDate?,
)

/** An athlete's time zone changed, so every athlete-local date of theirs may have moved. Published like [ThresholdsChanged]. */
data class AthleteTimeZoneChanged(
    val userId: Long,
)
