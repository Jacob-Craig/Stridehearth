package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport

/**
 * Standard metric benchmark distances against which athletic peak continuous performance is tracked.
 */
enum class BenchmarkDistance(
    val distanceMeters: Double,
    val displayName: String,
    val sport: ActivitySport = ActivitySport.RUN,
) {
    FOUR_HUNDRED_METERS(400.0, "400m"),
    ONE_KILOMETER(1000.0, "1k"),
    ONE_MILE(1609.344, "1 mile"),
    FIVE_KILOMETERS(5000.0, "5k"),
    TEN_KILOMETERS(10000.0, "10k"),
    HALF_MARATHON(21097.5, "Half-Marathon"),
    ;

    companion object {
        /** The sports that have Benchmark Distances; only these can hold Best Efforts or Personal Records. */
        val sportsWithBenchmarkDistances: Set<ActivitySport> = entries.mapTo(mutableSetOf()) { it.sport }

        /**
         * A SQL `VALUES` table `b(sport, min_meters)` of the sports with Benchmark Distances, each joined to its
         * shortest one: an Activity can only hold a Best Effort in such a sport, and only if it covers at least
         * that distance.
         */
        val shortestBenchmarkPerSportSql: String =
            entries
                .groupBy { it.sport }
                .map { (sport, benchmarks) -> "('${sport.name}', ${benchmarks.minOf { it.distanceMeters }})" }
                .joinToString(separator = ", ", prefix = "(VALUES ", postfix = ") AS b(sport, min_meters)")

        /**
         * A SQL `CASE` expression mapping the Benchmark Distance name in [column] to its ordinal, so rows can be
         * ordered by Benchmark Distance as ADR-0003's lock ordering requires.
         */
        fun ordinalCaseSql(column: String): String =
            entries.joinToString(
                separator = " ",
                prefix = "CASE $column ",
                postfix = " ELSE ${entries.size} END",
            ) { "WHEN '${it.name}' THEN ${it.ordinal}" }

        fun forSport(sport: ActivitySport): List<BenchmarkDistance> = entries.filter { it.sport == sport }
    }
}
