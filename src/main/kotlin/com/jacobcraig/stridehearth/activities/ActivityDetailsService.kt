package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ParsedActivityLap
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.system.fit.FitTelemetryMapper
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The Activity Details module: the only writer of an **Activity**'s **Activity Details** and **Laps** for
 * imports. It owns the decision whether newly parsed ones **Upgrade** or **Backfill** what is stored, and the
 * read of stored details for **Best Effort** scanning ([findTimeAndDistanceSeries]).
 *
 * The decision is a pure Kotlin rule ([decide]) applied under a lock, not an SQL guard: Postgres 16 cannot
 * return a row's previous values from an upsert, so the outcome (the version upgraded from, and why) would
 * still need a Kotlin twin, and the rule would only be testable against a database.
 *
 * Interface invariants of [offer]:
 * - It requires the caller's transaction and never opens one, so its write commits or rolls back with the
 *   caller's Activity and history. It is safe to roll back and run again (the import's duplicate-race retry).
 * - Concurrent offers for the same Activity serialize: the second decides against what the first committed.
 * - It records no **Activity History Entry** and triggers no **Best Effort** refresh; the caller does both,
 *   based on the returned [ActivityDetailsChange].
 */
@Service
class ActivityDetailsService(
    private val entityManager: EntityManager,
    private val activityDetailsRepository: ActivityDetailsRepository,
    private val activityLapRepository: ActivityLapRepository,
) {
    /**
     * Offers newly parsed [details] (if the file had any) and [laps] for [activity], decoded by
     * [parserVersion]. Writes only what the rule decides and returns what changed.
     *
     * Activity Details are written when [details] are offered and nothing is stored, the stored version is
     * older, or the stored details lack distance while the offered ones (from a version that is not older)
     * have it. Details from a newer Parser Version are never overwritten. Laps are replaced when the details
     * were written; they are backfilled when nothing else changed and none are stored.
     *
     * It locks the Activity's row, so an import offering details for an existing Activity takes the
     * **Personal Record** lock first, through [BestEffortRefresh.lockBeforeChange] (ADR-0006).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun offer(
        activity: Activity,
        details: FitTelemetry?,
        laps: List<ParsedActivityLap>,
        parserVersion: ParserVersion,
    ): ActivityDetailsChange {
        val activityId = checkNotNull(activity.id) { "Only a saved Activity can be offered Activity Details" }
        // Pending JPA writes (the new Activity row, earlier Laps) must reach the database before the SQL below.
        entityManager.flush()
        activityDetailsRepository.lockActivity(activityId)
        // A separate statement: under READ COMMITTED it sees what a concurrent offer committed while this one
        // waited for the lock, whereas a read joined into the locking statement would return stale details.
        val stored = activityDetailsRepository.findStoredDetailsState(activityId)
        val change = decide(stored, details, laps.size, parserVersion)
        if (change is ActivityDetailsChange.Changed) write(activityId, change, details, laps, parserVersion)
        return change
    }

    /**
     * Reads an Activity's stored details for **Best Effort** scanning: `t`, `distance` and `routeIndex`, or
     * `null` when none are stored. A pure read: it never writes.
     *
     * When the stored details lack distance (see [FitTelemetry.hasDistance]), the route is read too and distance
     * is synthesized in memory from it and speed, exactly as the parser would; the stored row is left unchanged.
     */
    fun findTimeAndDistanceSeries(activityId: Long): FitTelemetry? {
        val stored = activityDetailsRepository.findStoredScanningSeries(activityId) ?: return null
        if (stored.hasDistance) return stored
        // Only synthesis needs the route, so it is read only now, not on every scan.
        val (lons, lats) = activityDetailsRepository.findStoredRoute(activityId)
        val distance = stored.distance.copyOf()
        FitTelemetryMapper.synthesizeDistanceIfAbsent(
            t = stored.t,
            lons = lons,
            lats = lats,
            routeIndex = stored.routeIndex,
            enhancedSpeed = stored.enhancedSpeed,
            speed = stored.speed,
            distance = distance,
        )
        return stored.copy(lons = lons, lats = lats, distance = distance)
    }

    private fun write(
        activityId: Long,
        change: ActivityDetailsChange.Changed,
        details: FitTelemetry?,
        laps: List<ParsedActivityLap>,
        parserVersion: ParserVersion,
    ) {
        if (change.details != null) {
            activityDetailsRepository.upsert(activityId, checkNotNull(details), parserVersion)
        }
        if (change.laps != null) {
            activityLapRepository.deleteByActivityId(activityId)
            activityLapRepository.flush()
            activityLapRepository.saveAll(laps.map { it.toActivityLap(activityId) })
        }
    }

    private fun ParsedActivityLap.toActivityLap(activityId: Long) =
        ActivityLap(
            activityId = activityId,
            lapNumber = lapNumber,
            startedAt = startedAt,
            distance = distance,
            timeTakenSeconds = timeTakenSeconds,
            averagePaceKmh = averagePaceKmh,
            averageHeartRate = averageHeartRate,
            elevationGainMeters = elevationGainMeters,
        )

    private companion object {
        /** The rule: a pure function of what is stored and what is offered. */
        fun decide(
            stored: StoredDetailsState,
            details: FitTelemetry?,
            lapCount: Int,
            offered: ParserVersion,
        ): ActivityDetailsChange {
            val detailsChange =
                when {
                    details == null -> null
                    stored.version == null -> DetailsChange.Backfilled(offered)
                    stored.version < offered -> DetailsChange.Upgraded(from = stored.version, to = offered)
                    !stored.hasDistance && details.hasDistance && offered >= stored.version ->
                        DetailsChange.DistanceBackfilled(offered)
                    else -> null
                }
            val lapsChange =
                when {
                    // Even when the details were Upgraded, an offer without Laps leaves stored Laps alone.
                    lapCount == 0 -> null
                    detailsChange != null -> LapsChange.Replaced(lapCount)
                    stored.lapCount == 0 -> LapsChange.Backfilled(lapCount)
                    else -> null
                }
            return if (detailsChange == null && lapsChange == null) {
                ActivityDetailsChange.Unchanged
            } else {
                ActivityDetailsChange.Changed(detailsChange, lapsChange)
            }
        }
    }
}
