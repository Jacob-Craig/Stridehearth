package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.activities.ActivityDerivedMetricsRepository
import com.jacobcraig.stridehearth.activities.ActivityDetailsRepository
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.DERIVED_DATA_VERSION
import com.jacobcraig.stridehearth.activities.DerivedDataRefresh
import org.slf4j.LoggerFactory
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/** What one [DerivedDataRecomputeWorker.drain] did: Activities recomputed, and Activities that failed and were skipped. */
data class RecomputeResult(
    val activitiesRecomputed: Int,
    val activitiesFailed: Int,
)

/**
 * Brings every **Activity**'s derived data up to [DERIVED_DATA_VERSION] in the background (ADR-0014): the
 * **Backfill** of Activities that have no `activity_derived_metrics` row yet (imported before derived data
 * existed), and the **Recompute** of rows an older algorithm version wrote.
 *
 * It is always present, so tests call [drain] directly; [DerivedDataRecomputeSchedule] runs it periodically.
 *
 * Each page is one transaction that first takes the same row lock an Upgrade takes (`FOR NO KEY UPDATE`, in id
 * order) on its Activities, then reloads them and calls [DerivedDataRefresh]. So a page never computes from a
 * sport, `startedAt` or Activity Details that a concurrent edit or Upgrade is changing: it waits for that change
 * to commit, and an Activity deleted meanwhile is skipped. A page that loses a lock race (a deadlock with a batch
 * edit that locks the same Activities in another order) is retried. A page that fails any other way is retried
 * one Activity per transaction, so one Activity whose Activity Details cannot be analysed is logged and skipped
 * rather than stopping the scan; it stays a candidate and is tried again on the next pass.
 */
@Component
class DerivedDataRecomputeWorker(
    private val activityRepository: ActivityRepository,
    private val activityDetailsRepository: ActivityDetailsRepository,
    private val derivedMetricsRepository: ActivityDerivedMetricsRepository,
    private val derivedDataRefresh: DerivedDataRefresh,
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
     * Recomputes candidate Activities, [pageSize] per page, for at most [maxPages] pages: every athlete's, or only
     * those of [onlyUserIds]. It opens one transaction per page itself, so it must not be called inside one.
     *
     * It resumes after the last page the previous drain finished, in id order, and wraps around to the lowest id
     * once, stopping where it started. So Activities that keep failing are retried once per pass over all the
     * candidates, rather than at the start of every run, where enough of them would use up every run's pages and
     * keep the Activities after them from ever being reached.
     *
     * @throws PessimisticLockingFailureException when a page still loses its lock race after [LOCK_ATTEMPTS]
     *   tries; the pages before it stay committed, and the next run resumes.
     */
    @Transactional(propagation = Propagation.NEVER)
    fun drain(
        pageSize: Int = PAGE_SIZE,
        maxPages: Int = Int.MAX_VALUE,
        onlyUserIds: Collection<Long>? = null,
    ): RecomputeResult {
        val limit = pageSize.coerceAtLeast(1)
        val startedAfter = resumeAfterId
        var afterId = startedAfter
        // Activities above startedAfter were scanned before wrapping around; after it, the scan ends at startedAfter.
        var wrapped = startedAfter == 0L
        var recomputed = 0
        var failed = 0
        var pages = 0
        while (pages < maxPages) {
            val page = derivedMetricsRepository.findRecomputePage(afterId, limit, DERIVED_DATA_VERSION, onlyUserIds)
            val candidates = if (wrapped && startedAfter > 0) page.filter { it <= startedAfter } else page
            if (candidates.isEmpty()) {
                if (wrapped) {
                    resumeAfterId = 0L
                    return RecomputeResult(recomputed, failed)
                }
                wrapped = true
                afterId = 0L
                continue
            }
            try {
                recomputed += recomputeInTransaction(candidates)
            } catch (e: PessimisticLockingFailureException) {
                throw e
            } catch (e: RuntimeException) {
                logger.warn("Derived data page after Activity {} failed; recomputing its Activities one by one", afterId, e)
                for (activityId in candidates) {
                    try {
                        recomputed += recomputeInTransaction(listOf(activityId))
                    } catch (lockFailure: PessimisticLockingFailureException) {
                        throw lockFailure
                    } catch (activityFailure: RuntimeException) {
                        logger.error("Skipping Activity {}: its derived data could not be computed", activityId, activityFailure)
                        failed++
                    }
                }
            }
            afterId = candidates.last()
            resumeAfterId = afterId
            pages++
        }
        return RecomputeResult(recomputed, failed)
    }

    /** Recomputes [activityIds] in one transaction, retrying a lost lock race; returns how many still existed. */
    private fun recomputeInTransaction(activityIds: List<Long>): Int {
        var attempt = 1
        while (true) {
            try {
                return transactionTemplate.execute { recomputePage(activityIds) }!!
            } catch (e: PessimisticLockingFailureException) {
                if (attempt >= LOCK_ATTEMPTS) throw e
                logger.info("Derived data page lost a lock race (attempt {} of {}); retrying", attempt, LOCK_ATTEMPTS)
                attempt++
            }
        }
    }

    private fun recomputePage(activityIds: List<Long>): Int {
        // Lock first, then read: under READ COMMITTED the reads below see whatever a concurrent writer committed
        // while this page waited for its lock.
        val locked = activityDetailsRepository.lockActivities(activityIds)
        if (locked.isEmpty()) return 0
        val activities = activityRepository.findAllById(locked)
        derivedDataRefresh.refresh(activities)
        return activities.size
    }

    companion object {
        const val PAGE_SIZE = 50

        /** How many times a page is tried before a lost lock race ends the run. */
        const val LOCK_ATTEMPTS = 3
    }
}
