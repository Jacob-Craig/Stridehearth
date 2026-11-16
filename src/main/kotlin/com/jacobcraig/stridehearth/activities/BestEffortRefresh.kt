package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The Best Effort refresh module: after an **Activity** has changed, its **Best Efforts** and its athlete's
 * **Personal Records** are correct.
 *
 * Interface invariants, which every caller must know:
 * - Call it **after** the change has been applied, in the **same transaction**. That includes deletion:
 *   delete the Activity, then refresh it. It requires an existing transaction and never opens one.
 * - Do not flush between changing or deleting the Activity and calling it: leave the change pending, so
 *   the refresh can take its lock before the change reaches the database (ADR-0006).
 * - A caller that must lock the Activity's own row before applying the change (an import Upgrade storing
 *   Activity Details) first calls [lockBeforeChange] for it, then looks the Activity up again, since it may have
 *   been deleted while the caller waited.
 * - Call it whenever an Activity's sport or stored **Activity Details** changed, or the Activity was created
 *   or deleted. Title-only and other metadata edits do not need it, and neither does a newly created Activity
 *   with no Activity Details: it can hold no Best Efforts, so a refresh would change nothing.
 * - Details passed to [refresh] must be the details just stored for that Activity; otherwise the stored
 *   Activity Details are read.
 * - It is idempotent and holds no state between calls, so a rolled-back transaction followed by a retry gives
 *   the same result.
 * - It writes no **Activity History Entry**: Best Efforts and Personal Records are derived data.
 *
 * Everything else is derived from the database: it flushes pending changes once it holds its lock, treats a
 * deleted Activity as holding no Best Efforts, takes the affected sports to be those of the Activity's
 * previous Best Efforts plus its current sport (only sports with **Benchmark Distances**), and picks the
 * Personal Record strategy. An Activity that still exists and had no Best Efforts can only gain efforts, so
 * its Personal Records use the atomic incremental update that concurrent Import Batch workers rely on
 * (ADR-0003). Every other case runs one full recalibration per affected athlete and sport.
 *
 * Before flushing the caller's pending changes and before writing any Best Effort or Personal Record, it takes
 * a transaction-scoped advisory lock for each affected athlete and sport (ADR-0006), so a concurrent writer for
 * the same athlete and sport waits for this transaction to end and then sees its committed Best Efforts. Taking
 * it before the flush means a deleted Activity's cascade to its Best Efforts and Personal Records takes its row
 * locks only after the advisory lock, as every other writer does, so a delete and a recalibration cannot
 * deadlock. Only this module takes the lock, never its callers; it means a caller's transaction may wait on another writer
 * for the same athlete and sport. Callers that write other rows referencing the Activity (an edit's **Activity
 * History Entry**) do so after the refresh, and callers that lock the Activity's own row first ask for the lock
 * beforehand with [lockBeforeChange]. So every writer takes the advisory lock before any row lock.
 */
@Component
class BestEffortRefresh(
    private val entityManager: EntityManager,
    private val jdbcClient: JdbcClient,
    private val activityBestEffortRepository: ActivityBestEffortRepository,
    private val activityDetailsService: ActivityDetailsService,
    private val bestEffortDetector: BestEffortDetector,
) {
    /**
     * Refreshes one [activity], detecting from [details] when given (they must be the ones just stored) or
     * else from its stored Activity Details. Returns the Best Efforts the Activity now holds.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun refresh(
        activity: Activity,
        details: FitTelemetry? = null,
    ): List<ActivityBestEffort> = refreshAll(mapOf(activity to details))

    /**
     * Refreshes several [activities] together, running Personal Record recalibration once per athlete and
     * sport. Returns the Best Efforts the Activities now hold.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun refresh(activities: Collection<Activity>): List<ActivityBestEffort> = refreshAll(activities.associateWith { null })

    /**
     * Takes the Personal Record lock that a later [refresh] of [activity] takes, now: for callers that must lock
     * the Activity's own row before applying the change (an import Upgrade storing Activity Details), so that the
     * lock still comes before any row lock (ADR-0006). The later refresh takes it again, which does not wait.
     * Callers then look the Activity up again, since a concurrent delete of it may have committed while they waited.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockBeforeChange(activity: Activity) {
        lockAffected(listOf(activity))
    }

    /** An athlete's Personal Records in one sport: the unit a full recalibration works on. */
    private data class AthleteSport(
        val userId: Long,
        val sport: ActivitySport,
    )

    /** What [lockAffected] read and locked: each Activity's previous Best Effort sports, and its affected athlete-sports. */
    private data class LockedActivities(
        val previousSports: Map<Long, Set<ActivitySport>>,
        val affected: Map<Activity, List<AthleteSport>>,
    )

    /**
     * Works out which athlete-sports a refresh of [activities] may change and takes their Personal Record locks,
     * in one fixed order across all of them. JdbcClient bypasses Hibernate, so the read still sees a queued
     * delete's Best Efforts.
     */
    private fun lockAffected(activities: List<Activity>): LockedActivities {
        val previousSports = findPreviousBestEffortSports(activities.map { it.id!! })
        val affected = activities.associateWith { affectedAthleteSports(it, previousSports[it.id!!].orEmpty()) }
        lockPersonalRecords(affected.values.flatten())
        return LockedActivities(previousSports, affected)
    }

    /** Refreshes each Activity, detecting from its supplied details or, when null, from its stored ones. */
    private fun refreshAll(detailsByActivity: Map<Activity, FitTelemetry?>): List<ActivityBestEffort> {
        val activities = detailsByActivity.keys.distinctBy { it.id }
        if (activities.isEmpty()) return emptyList()

        // ADR-0006: lock before flushing the caller's pending changes, so that its row locks (a deleted Activity's
        // cascades to its Best Efforts and Personal Records) come after the advisory lock, as for every other writer.
        val (previousSports, affected) = lockAffected(activities)

        entityManager.flush()
        val ids = activities.map { it.id!! }
        val existingIds = findExistingIds(ids)

        val nowHeld = mutableListOf<ActivityBestEffort>()
        val incremental = mutableListOf<ActivityBestEffort>()
        val recalibrations = mutableSetOf<AthleteSport>()

        for (activity in activities) {
            val activityId = activity.id!!
            val previous = previousSports[activityId].orEmpty()
            val exists = activityId in existingIds

            if (previous.isNotEmpty()) {
                activityBestEffortRepository.deleteByActivityId(activityId)
                activityBestEffortRepository.flush()
            }
            val saved = if (exists) detectAndSave(activity, detailsByActivity[activity]) else emptyList()
            nowHeld += saved

            if (exists && previous.isEmpty()) {
                incremental += saved
            } else {
                recalibrations += affected.getValue(activity)
            }
        }
        activityBestEffortRepository.flush()

        val userIdByActivityId = activities.associate { it.id!! to it.user.id!! }
        incremental
            .groupBy { userIdByActivityId.getValue(it.activityId) }
            .forEach { (userId, efforts) ->
                upsertPersonalRecordsIfFaster(userId, efforts.filterNot { AthleteSport(userId, it.sport) in recalibrations })
            }
        recalibrations.forEach { recalibratePersonalRecords(it.userId, it.sport) }
        return nowHeld
    }

    /**
     * The athlete-sports whose Personal Records a refresh of [activity] may change: the sports of its [previous]
     * Best Efforts plus its current sport, limited to sports with Benchmark Distances.
     */
    private fun affectedAthleteSports(
        activity: Activity,
        previous: Set<ActivitySport>,
    ): List<AthleteSport> =
        (previous + activity.sport)
            .filter { it in BenchmarkDistance.sportsWithBenchmarkDistances }
            .map { AthleteSport(activity.user.id!!, it) }

    /**
     * ADR-0006: serialises this transaction's Personal Record writes for each of [athleteSports] with every other
     * writer's, by taking a transaction-scoped advisory lock per athlete and sport. Taken before any Best Effort or
     * Personal Record write, in a fixed order (athlete, then sport) so that two refreshes covering several athletes
     * or sports cannot deadlock on each other. Held until the transaction ends, and re-entrant within it.
     */
    private fun lockPersonalRecords(athleteSports: Collection<AthleteSport>) {
        athleteSports
            .distinct()
            .sortedWith(compareBy<AthleteSport> { it.userId }.thenBy { it.sport })
            .forEach { (userId, sport) ->
                jdbcClient
                    .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", personalRecordLockKey(userId, sport))
                    // pg_advisory_xact_lock returns void: run the query for its effect and discard the row.
                    .query { _, _ -> }
                    .list()
            }
    }

    private fun detectAndSave(
        activity: Activity,
        suppliedDetails: FitTelemetry?,
    ): List<ActivityBestEffort> {
        if (activity.sport !in BenchmarkDistance.sportsWithBenchmarkDistances) return emptyList()
        val activityId = activity.id!!
        val details = suppliedDetails ?: activityDetailsService.findTimeAndDistanceSeries(activityId) ?: return emptyList()
        val detected =
            bestEffortDetector
                .detectBestEfforts(details, activity.sport)
                .map { it.toEntity(activityId, activity.sport) }
        return activityBestEffortRepository.saveAll(detected)
    }

    private fun findExistingIds(ids: List<Long>): Set<Long> =
        ids.chunkedForIdLookup().flatMapTo(mutableSetOf()) { chunk ->
            jdbcClient
                .sql("SELECT id FROM activity WHERE id IN (:ids)")
                .param("ids", chunk)
                .query { rs, _ -> rs.getLong("id") }
                .list()
        }

    private fun findPreviousBestEffortSports(ids: List<Long>): Map<Long, Set<ActivitySport>> =
        ids
            .chunkedForIdLookup()
            .flatMap { chunk ->
                jdbcClient
                    .sql("SELECT DISTINCT activity_id, sport FROM activity_best_effort WHERE activity_id IN (:ids)")
                    .param("ids", chunk)
                    .query { rs, _ -> rs.getLong("activity_id") to ActivitySport.valueOf(rs.getString("sport")) }
                    .list()
            }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    /**
     * ADR-0003's incremental update: each of [efforts] takes the athlete's Personal Record only if strictly
     * faster, or tied and from an earlier Activity. Atomic, so concurrent Import Batch workers converge.
     */
    private fun upsertPersonalRecordsIfFaster(
        userId: Long,
        efforts: List<ActivityBestEffort>,
    ) {
        if (efforts.isEmpty()) return
        val sql =
            """
            $INSERT_PERSONAL_RECORD_SQL
            VALUES (
                :userId, :sport, :benchmarkDistance, :bestEffortId, :activityId, :elapsedTimeSeconds, now(), now()
            )
            $REPLACE_PERSONAL_RECORD_ON_CONFLICT_SQL
            WHERE EXCLUDED.elapsed_time_seconds < user_personal_record.elapsed_time_seconds
               OR (
                   EXCLUDED.elapsed_time_seconds = user_personal_record.elapsed_time_seconds
                   AND (
                       SELECT (ca.started_at, ca.id) < (ea.started_at, ea.id)
                       FROM activity ca, activity ea
                       WHERE ca.id = EXCLUDED.activity_id
                         AND ea.id = user_personal_record.activity_id
                   )
               )
            """.trimIndent()

        for (effort in efforts.sortedBy { it.benchmarkDistance.ordinal }) {
            val effortId = checkNotNull(effort.id) { "A Best Effort must be saved before it can hold a Personal Record" }
            jdbcClient
                .sql(sql)
                .param("userId", userId)
                .param("sport", effort.sport.name)
                .param("benchmarkDistance", effort.benchmarkDistance.name)
                .param("bestEffortId", effortId)
                .param("activityId", effort.activityId)
                .param("elapsedTimeSeconds", effort.elapsedTimeSeconds)
                .update()
        }
    }

    /**
     * ADR-0003's full set-based recalibration: prunes the athlete's Personal Records in [sport] that no Best
     * Effort backs any more, then recomputes each from the remaining Best Efforts (earliest Activity wins ties).
     */
    private fun recalibratePersonalRecords(
        userId: Long,
        sport: ActivitySport,
    ) {
        val pruneSql =
            """
            DELETE FROM user_personal_record upr
            WHERE upr.user_id = :userId
              AND upr.sport = :sport
              AND NOT EXISTS (
                  SELECT 1
                  FROM activity_best_effort abe
                  JOIN activity a ON a.id = abe.activity_id
                  WHERE a.user_id = :userId
                    AND abe.sport = :sport
                    AND abe.benchmark_distance = upr.benchmark_distance
              )
            """.trimIndent()

        jdbcClient
            .sql(pruneSql)
            .param("userId", userId)
            .param("sport", sport.name)
            .update()

        val recomputeSql =
            """
            WITH winners AS (
                SELECT DISTINCT ON (abe.benchmark_distance)
                    a.user_id,
                    abe.sport,
                    abe.benchmark_distance,
                    abe.id AS best_effort_id,
                    abe.activity_id,
                    abe.elapsed_time_seconds
                FROM activity_best_effort abe
                JOIN activity a ON a.id = abe.activity_id
                WHERE a.user_id = :userId
                  AND abe.sport = :sport
                ORDER BY abe.benchmark_distance, abe.elapsed_time_seconds ASC, a.started_at ASC, a.id ASC
            )
            $INSERT_PERSONAL_RECORD_SQL
            SELECT
                w.user_id,
                w.sport,
                w.benchmark_distance,
                w.best_effort_id,
                w.activity_id,
                w.elapsed_time_seconds,
                now(),
                now()
            FROM winners w
            ORDER BY ${BenchmarkDistance.ordinalCaseSql("w.benchmark_distance")} ASC
            $REPLACE_PERSONAL_RECORD_ON_CONFLICT_SQL
            """.trimIndent()

        jdbcClient
            .sql(recomputeSql)
            .param("userId", userId)
            .param("sport", sport.name)
            .update()
    }

    private companion object {
        /** The head of both Personal Record writes; each supplies its own VALUES or SELECT. */
        const val INSERT_PERSONAL_RECORD_SQL =
            "INSERT INTO user_personal_record (" +
                "user_id, sport, benchmark_distance, best_effort_id, activity_id, elapsed_time_seconds, created_at, updated_at)"

        /**
         * Both writes replace the existing Personal Record on conflict. The incremental update appends its
         * "only if faster" WHERE clause; the full recalibration replaces unconditionally (ADR-0003).
         */
        const val REPLACE_PERSONAL_RECORD_ON_CONFLICT_SQL =
            "ON CONFLICT (user_id, sport, benchmark_distance) DO UPDATE SET " +
                "best_effort_id = EXCLUDED.best_effort_id, " +
                "activity_id = EXCLUDED.activity_id, " +
                "elapsed_time_seconds = EXCLUDED.elapsed_time_seconds, " +
                "updated_at = now()"
    }
}

/**
 * Prefixes every Personal Record advisory lock key, so it stays distinct from any other advisory lock that
 * hashes a name under its own prefix. A hash collision only costs extra serialisation, never correctness.
 */
private const val PERSONAL_RECORD_LOCK_NAMESPACE = "personal_record"

/** The name hashed into the advisory lock that serialises one athlete's Personal Record writes in [sport] (ADR-0006). */
internal fun personalRecordLockKey(
    userId: Long,
    sport: ActivitySport,
): String = "$PERSONAL_RECORD_LOCK_NAMESPACE:$userId:${sport.name}"
