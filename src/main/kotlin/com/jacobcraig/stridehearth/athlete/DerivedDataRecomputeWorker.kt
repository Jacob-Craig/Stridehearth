package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityDerivedMetricsRepository
import com.jacobcraig.stridehearth.activities.ActivityDetailsRepository
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.DERIVED_DATA_VERSION
import com.jacobcraig.stridehearth.activities.DerivedDataRefresh
import com.jacobcraig.stridehearth.activities.WorkoutTypeReclassifier
import com.jacobcraig.stridehearth.activities.WorkoutTypeRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What one [DerivedDataRecomputeWorker.drain] did: Activities whose derived data it computed in full, Activities that
 * failed and were skipped, Activities whose **Training Load** alone it recomputed for a recompute request, and athletes
 * whose stale rollups it rebuilt or failed to rebuild (and skipped).
 */
data class RecomputeResult(
    val activitiesRecomputed: Int,
    val activitiesFailed: Int,
    val thresholdDependentRecomputed: Int = 0,
    val rollupsRebuilt: Int = 0,
    val rollupsFailed: Int = 0,
    val workoutTypesChanged: Int = 0,
)

/**
 * Brings every **Activity**'s derived data up to date in the background (ADR-0014), in three stages:
 *
 * 1. The durable **Training Load** recompute requests that a change to an athlete's Thresholds or time zone writes
 *    ([RecomputeRequestListener]): the Training Load of each of their Activities dated on or after the request's date
 *    is recomputed, and the request is cleared only once none of them was skipped and no newer request arrived
 *    meanwhile (compare-and-clear).
 * 2. The **Backfill** of Activities that have no `activity_derived_metrics` row yet (imported before derived data
 *    existed), and the **Recompute** of rows an older algorithm version wrote ([DERIVED_DATA_VERSION]).
 *    Between 2 and 3, the durable `workout_type_request`s that an Activity's change writes: the **Workout Type** of the
 *    athlete's Runs in the next 28 days is reclassified from their stored evidence ([WorkoutTypeReclassifier]), and the
 *    requests served are cleared (those written meanwhile stay pending).
 * 3. The full rebuild of athlete rollups that are stale ([ROLLUP_VERSION]: never built, built by an older version, or
 *    marked stale by a time-zone change), one athlete per transaction under their rollup lock (ADR-0016). It runs
 *    only once the first two stages have caught up within the run; stage 2's Activities that are backed off after
 *    failing are passed over without counting as pages, so they cannot keep it from being reached. An athlete whose
 *    rebuild fails is logged and backed off like a failed Activity.
 *
 * It is always present, so tests call [drain] directly; [DerivedDataRecomputeSchedule] runs it periodically.
 *
 * Each page is one transaction that first takes the same row lock an Upgrade takes (`FOR NO KEY UPDATE`, in id
 * order) on its Activities, then reloads them and calls [DerivedDataRefresh]. So a page never computes from a
 * sport, `startedAt` or Activity Details that a concurrent edit or Upgrade is changing: it waits for that change
 * to commit, and an Activity deleted meanwhile is skipped. A page that loses a lock race with another writer is
 * retried. A page the database fails (a timeout, a lost connection, no transaction) ends the run, which the next
 * run resumes from the same page. A page that fails any other way is retried one Activity per transaction, so one
 * Activity whose Activity Details cannot be analysed is logged and skipped rather than stopping the scan. It stays
 * a candidate, but later drains pass over it until [FAILED_RETRY_AFTER] has gone by, so a Server whose other rows
 * are all current does not re-analyse and re-log it on every run.
 */
@Component
class DerivedDataRecomputeWorker(
    private val activityRepository: ActivityRepository,
    private val activityDetailsRepository: ActivityDetailsRepository,
    private val derivedMetricsRepository: ActivityDerivedMetricsRepository,
    private val derivedDataRefresh: DerivedDataRefresh,
    private val stateRepository: AthleteAnalyticsStateRepository,
    private val workoutTypeRepository: WorkoutTypeRepository,
    private val workoutTypeReclassifier: WorkoutTypeReclassifier,
    private val athleteRollups: AthleteRollups,
    private val transactionTemplate: TransactionTemplate,
) {
    private val logger = LoggerFactory.getLogger(DerivedDataRecomputeWorker::class.java)

    /**
     * Where the next [drain] starts: after the last page a drain finished, or 0 once a drain has scanned every
     * candidate. Kept in memory only (a restart starts again from 0), and written by one drain at a time: the
     * schedule's single thread, or a test.
     */
    @Volatile
    private var resumeAfterId = 0L

    /**
     * When each Activity whose derived data could not be computed last failed. Later drains pass over it until
     * [FAILED_RETRY_AFTER] has gone by. Kept in memory only: a restart (which is also the only way
     * [DERIVED_DATA_VERSION] changes) tries every Activity again.
     */
    private val failedAt = ConcurrentHashMap<Long, Instant>()

    /** When each athlete whose stale rollups could not be rebuilt last failed, by athlete id; as [failedAt]. */
    private val rollupFailedAt = ConcurrentHashMap<Long, Instant>()

    /**
     * How far a drain got through each athlete's recompute request: the last Activity id done, for the request stamped
     * [RequestCursor.requestedAt], whether every page has been scanned, and the Activities skipped because they failed
     * or were backed off. The next drain resumes there, so a request larger than one run's pages still finishes; a
     * scanned request stays pending, retrying only its skipped Activities once their back-off has passed, until none is
     * left; a newer request starts again from the beginning. Kept in memory only: a restart rescans the request.
     */
    private val requestCursors = ConcurrentHashMap<Long, RequestCursor>()

    /** How far a drain got through an athlete's merged workout type requests (up to request id [lastId]). */
    private data class WorkoutCursor(
        val lastId: Long,
        val afterId: Long,
    )

    private val workoutCursors = ConcurrentHashMap<Long, WorkoutCursor>()

    private data class RequestCursor(
        val requestedAt: Instant,
        val afterId: Long,
        val scanned: Boolean = false,
        val skipped: Set<Long> = emptySet(),
    )

    /**
     * Serves the pending Training Load recompute requests, then recomputes candidate Activities, [pageSize] per page,
     * for at most [maxPages] pages in all: every athlete's, or only those of [onlyUserIds]. It opens one transaction
     * per page itself, so it must not be called inside one.
     *
     * It resumes after the last page the previous drain finished, in id order, and wraps around to the lowest id
     * once, stopping where it started. So Activities that keep failing are retried once per pass over all the
     * candidates, rather than at the start of every run, where enough of them would use up every run's pages and
     * keep the Activities after them from ever being reached. An Activity that failed less than [FAILED_RETRY_AFTER]
     * before [now] is passed over without being analysed or logged again, and a page holding only such Activities
     * does not count against [maxPages].
     *
     * @throws PessimisticLockingFailureException when a page still loses its lock race after [LOCK_ATTEMPTS]
     *   tries; the pages before it stay committed, and the next run resumes.
     * @throws RuntimeException any other failure of the database itself (see [isDatabaseFailure]), the same way:
     *   no Activity is backed off for it.
     */
    @Transactional(propagation = Propagation.NEVER)
    fun drain(
        pageSize: Int = PAGE_SIZE,
        maxPages: Int = Int.MAX_VALUE,
        onlyUserIds: Collection<Long>? = null,
        now: Instant = Instant.now(),
    ): RecomputeResult {
        val limit = pageSize.coerceAtLeast(1)
        failedAt.values.removeIf { !it.isAfter(now - FAILED_RETRY_AFTER) }
        rollupFailedAt.values.removeIf { !it.isAfter(now - FAILED_RETRY_AFTER) }
        val tally = Tally()
        if (!serveRecomputeRequests(limit, maxPages, onlyUserIds, now, tally)) return tally.result()
        val startedAfter = resumeAfterId
        var afterId = startedAfter
        // Activities above startedAfter were scanned before wrapping around; after it, the scan ends at startedAfter.
        var wrapped = startedAfter == 0L
        while (tally.pages < maxPages) {
            val page = derivedMetricsRepository.findRecomputePage(afterId, limit, DERIVED_DATA_VERSION, onlyUserIds)
            val candidates = if (wrapped && startedAfter > 0) page.filter { it <= startedAfter } else page
            if (candidates.isEmpty()) {
                if (wrapped) {
                    resumeAfterId = 0L
                    if (serveWorkoutTypeRequests(limit, maxPages, onlyUserIds, now, tally)) {
                        rebuildStaleRollups(limit, maxPages, onlyUserIds, now, tally)
                    }
                    return tally.result()
                }
                wrapped = true
                afterId = 0L
                continue
            }
            // A page of Activities that are all backed off does no work, so it costs no page: otherwise enough
            // failures would use up every run's pages and the scan would never finish, nor reach stage 3.
            val anyDue = candidates.any { !failedAt.containsKey(it) }
            tally.recomputed += runPage(candidates, afterId, now, tally, ::recomputePage)
            afterId = candidates.last()
            resumeAfterId = afterId
            if (anyDue) tally.pages++
        }
        return tally.result()
    }

    /**
     * Stage 1: serves each pending recompute request in athlete id order, a page at a time from where the last drain
     * left it, clearing each request it finishes unless a newer one replaced it meanwhile. A request with Activities
     * it skipped (failed, or backed off) is not finished: it stays pending, and later drains retry only those
     * Activities once their back-off has passed. Returns false when it used up [maxPages] first.
     */
    private fun serveRecomputeRequests(
        limit: Int,
        maxPages: Int,
        onlyUserIds: Collection<Long>?,
        now: Instant,
        tally: Tally,
    ): Boolean {
        for (request in stateRepository.findPendingRequests(onlyUserIds)) {
            val cursor = requestCursors[request.userId]?.takeIf { it.requestedAt == request.requestedAt }
            var afterId = cursor?.afterId ?: 0L
            var scanned = cursor?.scanned ?: false
            val skipped = cursor?.skipped.orEmpty().toSortedSet()
            // Whether this drain ran any page of the request: a drain that only passes over backed-off Activities
            // changes nothing, so it does not log again (the schedule drains every few seconds).
            var attempted = false

            fun saveCursor() {
                requestCursors[request.userId] = RequestCursor(request.requestedAt, afterId, scanned, skipped.toSet())
            }
            while (!scanned) {
                if (tally.pages >= maxPages) {
                    saveCursor()
                    return false
                }
                val page =
                    derivedMetricsRepository.findTrainingLoadRecomputePage(request.userId, request.from, request.zone, afterId, limit)
                if (page.isEmpty()) {
                    scanned = true
                    break
                }
                tally.trainingLoads += runPage(page, afterId, now, tally, ::recomputeThresholdDependentPage, skipped)
                attempted = true
                afterId = page.last()
                tally.pages++
            }
            for (retry in skipped.filterNot { failedAt.containsKey(it) }.chunked(limit)) {
                if (tally.pages >= maxPages) {
                    saveCursor()
                    return false
                }
                skipped.removeAll(retry.toSet())
                tally.trainingLoads += runPage(retry, retry.first() - 1, now, tally, ::recomputeThresholdDependentPage, skipped)
                attempted = true
                tally.pages++
            }
            if (skipped.isNotEmpty()) {
                saveCursor()
                if (!attempted) continue
                logger.info(
                    "Training Load recompute request for athlete {} kept pending: {} Activities are retried after their back-off",
                    request.userId,
                    skipped.size,
                )
                continue
            }
            requestCursors.remove(request.userId)
            if (!stateRepository.clearRequest(request)) {
                logger.info("A newer Training Load recompute request for athlete {} arrived; it is served next run", request.userId)
            }
        }
        return true
    }

    /**
     * Serves each pending `workout_type_request` in athlete id order, a page at a time from where the last drain left
     * it, clearing the requests it merged unless an Activity failed: then they stay pending and the next drain scans
     * again, passing over the Activities still backed off. Returns false when it used up [maxPages] first.
     */
    private fun serveWorkoutTypeRequests(
        limit: Int,
        maxPages: Int,
        onlyUserIds: Collection<Long>?,
        now: Instant,
        tally: Tally,
    ): Boolean {
        for (request in workoutTypeRepository.findPending(onlyUserIds)) {
            var afterId = workoutCursors[request.userId]?.takeIf { it.lastId == request.lastId }?.afterId ?: 0L
            val skipped = mutableSetOf<Long>()
            while (true) {
                val page = workoutTypeRepository.findRunIdsInRange(request.userId, request.from, request.to, afterId, limit)
                if (page.isEmpty()) break
                if (tally.pages >= maxPages) {
                    workoutCursors[request.userId] = WorkoutCursor(request.lastId, afterId)
                    return false
                }
                tally.workoutTypes += runPage(page, afterId, now, tally, ::reclassifyPage, skipped)
                afterId = page.last()
                tally.pages++
            }
            workoutCursors.remove(request.userId)
            if (skipped.isEmpty()) workoutTypeRepository.clear(request)
        }
        return true
    }

    /**
     * Stage 3: rebuilds each athlete's stale rollups in full, one athlete per transaction, in id order, counting each
     * [limit] athletes as one page against [maxPages]. A lost lock race is retried like a page's. An athlete whose
     * rebuild fails other than in the database is logged and passed over until [FAILED_RETRY_AFTER] has gone by, like
     * an Activity, so one athlete's failure never keeps the athletes after them stale.
     */
    private fun rebuildStaleRollups(
        limit: Int,
        maxPages: Int,
        onlyUserIds: Collection<Long>?,
        now: Instant,
        tally: Tally,
    ) {
        var afterId = 0L
        while (tally.pages < maxPages) {
            val stale = stateRepository.findStaleRollups(afterId, limit, onlyUserIds)
            if (stale.isEmpty()) return
            stale.filterNot { rollupFailedAt.containsKey(it) }.forEach { userId -> rebuildRollups(userId, now, tally) }
            afterId = stale.last()
            tally.pages++
        }
    }

    private fun rebuildRollups(
        userId: Long,
        now: Instant,
        tally: Tally,
    ) {
        try {
            if (inTransaction(listOf(userId)) { if (athleteRollups.rebuildStale(userId)) 1 else 0 } > 0) tally.rollups++
        } catch (e: RuntimeException) {
            if (e.isDatabaseFailure()) throw e
            logger.error("Skipping athlete {}'s rollups for {} min: they could not be rebuilt", userId, FAILED_RETRY_AFTER.toMinutes(), e)
            rollupFailedAt[userId] = now
            tally.rollupsFailed++
        }
    }

    /**
     * Runs [work] over the [candidates] not backed off, in one transaction; if that fails other than in the database,
     * once per Activity, logging and backing off each that still fails. Returns what [work] counted. Adds to [skipped],
     * if given, every candidate it passed over or that failed.
     */
    private fun runPage(
        candidates: List<Long>,
        afterId: Long,
        now: Instant,
        tally: Tally,
        work: (List<Long>) -> Int,
        skipped: MutableSet<Long>? = null,
    ): Int {
        val (due, backedOff) = candidates.partition { !failedAt.containsKey(it) }
        skipped?.addAll(backedOff)
        if (due.isEmpty()) return 0
        return try {
            inTransaction(due, work)
        } catch (e: RuntimeException) {
            if (e.isDatabaseFailure()) throw e
            logger.warn("Derived data page after Activity {} failed; recomputing its Activities one by one", afterId, e)
            due.sumOf { activityId ->
                try {
                    inTransaction(listOf(activityId), work)
                } catch (activityFailure: RuntimeException) {
                    if (activityFailure.isDatabaseFailure()) throw activityFailure
                    logger.error(
                        "Skipping Activity {} for {} min: its derived data could not be computed",
                        activityId,
                        FAILED_RETRY_AFTER.toMinutes(),
                        activityFailure,
                    )
                    failedAt[activityId] = now
                    skipped?.add(activityId)
                    tally.failed++
                    0
                }
            }
        }
    }

    /** Runs [work] over [activityIds] in one transaction, retrying a lost lock race. */
    private fun inTransaction(
        activityIds: List<Long>,
        work: (List<Long>) -> Int,
    ): Int {
        var attempt = 1
        while (true) {
            try {
                return transactionTemplate.execute { work(activityIds) }!!
            } catch (e: PessimisticLockingFailureException) {
                if (attempt >= LOCK_ATTEMPTS) throw e
                logger.info("Derived data page lost a lock race (attempt {} of {}); retrying", attempt, LOCK_ATTEMPTS)
                attempt++
            }
        }
    }

    /**
     * Whether this failure came from the database rather than from the Activities being recomputed: a lost lock race
     * or any other transient failure (a timeout), a lost or unreachable database, or a transaction that could not be
     * opened or committed. Retrying the page's Activities one by one would only wait on the database again.
     */
    private fun RuntimeException.isDatabaseFailure(): Boolean =
        this is TransientDataAccessException || this is DataAccessResourceFailureException || this is TransactionException

    /** Recomputes all the derived data of [activityIds]; returns how many still existed. */
    private fun recomputePage(activityIds: List<Long>): Int {
        val activities = lockAndLoad(activityIds)
        if (activities.isNotEmpty()) derivedDataRefresh.refresh(activities)
        return activities.size
    }

    /** Recomputes only the threshold-dependent derived data of [activityIds]; returns how many rows it replaced. */
    private fun recomputeThresholdDependentPage(activityIds: List<Long>): Int =
        derivedDataRefresh.refreshThresholdDependent(lockAndLoad(activityIds))

    /** Reclassifies the **Workout Type** of [activityIds] from their stored evidence; returns how many changed. */
    private fun reclassifyPage(activityIds: List<Long>): Int =
        workoutTypeReclassifier.reclassify(activityDetailsRepository.lockActivities(activityIds))

    private fun lockAndLoad(activityIds: List<Long>): List<Activity> {
        // Lock first, then read: under READ COMMITTED the reads below see whatever a concurrent writer committed
        // while this page waited for its lock.
        val locked = activityDetailsRepository.lockActivities(activityIds)
        if (locked.isEmpty()) return emptyList()
        return activityRepository.findAllById(locked)
    }

    /** What one drain has done so far. */
    private class Tally {
        var recomputed = 0
        var failed = 0
        var trainingLoads = 0
        var rollups = 0
        var rollupsFailed = 0
        var workoutTypes = 0
        var pages = 0

        fun result() = RecomputeResult(recomputed, failed, trainingLoads, rollups, rollupsFailed, workoutTypes)
    }

    companion object {
        const val PAGE_SIZE = 50

        /** How many times a page is tried before a lost lock race ends the run. */
        const val LOCK_ATTEMPTS = 3

        /** How long drains pass over an Activity whose derived data could not be computed before trying it again. */
        val FAILED_RETRY_AFTER: Duration = Duration.ofHours(1)
    }
}
