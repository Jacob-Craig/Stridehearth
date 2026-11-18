package com.jacobcraig.stridehearth.analytics

/**
 * A **Benchmark Duration**: a standard window length over which an Activity's **Peak Efforts** are measured,
 * shortest first.
 */
enum class BenchmarkDuration(
    val seconds: Int,
    val displayName: String,
) {
    FIVE_SECONDS(5, "5 s"),
    FIFTEEN_SECONDS(15, "15 s"),
    THIRTY_SECONDS(30, "30 s"),
    ONE_MINUTE(60, "1 min"),
    TWO_MINUTES(120, "2 min"),
    THREE_MINUTES(180, "3 min"),
    FIVE_MINUTES(300, "5 min"),
    TEN_MINUTES(600, "10 min"),
    TWENTY_MINUTES(1200, "20 min"),
    THIRTY_MINUTES(1800, "30 min"),
    SIXTY_MINUTES(3600, "60 min"),
}

/** What a **Peak Effort** averages: Grade-Adjusted speed in m/s, or power in W. */
enum class PeakEffortMetric {
    GAP_SPEED,
    POWER,
}
