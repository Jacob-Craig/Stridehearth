package com.jacobcraig.stridehearth.analytics

/**
 * The derived data of one Activity that depends on the **Thresholds** in force on its date, and so is recomputed alone
 * when they or the **Athlete Time Zone** change (ADR-0014): its **Training Load** and, for a Run, its **VO2max
 * Estimate** ([vo2max], null for any other sport).
 */
data class ThresholdDependentMetrics(
    val trainingLoad: TrainingLoadResult,
    val vo2max: Vo2maxResult? = null,
)
