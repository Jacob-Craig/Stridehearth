package com.jacobcraig.stridehearth.athlete

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

/**
 * The version of the athlete rollups. Raise it whenever a rollup would compute different rows from the same inputs:
 * the recompute worker then rebuilds every athlete's rollups in full, and until it has, the before-commit pass leaves
 * an athlete's stale rollups alone (ADR-0016).
 *
 * - 1: Daily Load and the Performance Management Chart (#36).
 */
const val ROLLUP_VERSION = 1

/**
 * What one transaction marked for one athlete: the earliest moment from which their daily rollups must be rebuilt
 * ([dailyLoadFrom]), and every moment at which their peaks changed ([peaksChangedAt]). Peaks keep each moment rather
 * than the earliest one, because a model fitted over a window is affected only near each change.
 */
data class RollupMarks(
    val dailyLoadFrom: Instant?,
    val peaksChangedAt: Set<Instant>,
)

/**
 * Athlete-level rollups, recomputed once per transaction just before it commits (ADR-0014, ADR-0016).
 *
 * Per-Activity writers ([com.jacobcraig.stridehearth.activities.DerivedDataRefresh]) only *mark* what changed, by
 * the Activity's `startedAt` instant; the conversion to athlete-local days happens in the before-commit pass, where
 * the athlete's time zone is read under the rollup's own lock. Marks live in a transaction-bound resource with a
 * single [TransactionSynchronization], so many marks in one transaction cost one rollup per athlete.
 *
 * The before-commit pass flushes the caller's pending changes, then, for each athlete in ascending id order, takes
 * their rollup lock, always the last lock a transaction takes (ADR-0016), and rebuilds their **Daily Load** and
 * **Performance Management Chart** from the earliest marked day ([DailyLoadRollup]). An athlete without a state row
 * gets a full rebuild, and their row is created current; an athlete whose rollups are stale ([ROLLUP_VERSION]) is left
 * to the recompute worker's full rebuild.
 */
@Component
class AthleteRollups(
    private val entityManager: EntityManager,
    private val stateRepository: AthleteAnalyticsStateRepository,
    private val dailyLoadRollup: DailyLoadRollup,
) {
    /** Marks that [userId]'s daily rollups must be rebuilt from the day containing [startedAt] onwards. */
    fun markDailyLoad(
        userId: Long,
        startedAt: Instant,
    ) {
        val marks = pending().getOrPut(userId) { MutableMarks() }
        marks.dailyLoadFrom = marks.dailyLoadFrom?.let { minOf(it, startedAt) } ?: startedAt
    }

    /** Marks that [userId]'s peaks changed for an Activity started at [startedAt]. */
    fun markPeaksChanged(
        userId: Long,
        startedAt: Instant,
    ) {
        pending().getOrPut(userId) { MutableMarks() }.peaksChangedAt += startedAt
    }

    /** The marks recorded so far in the current transaction, by athlete; empty outside one or before any mark. */
    fun pendingMarks(): Map<Long, RollupMarks> {
        @Suppress("UNCHECKED_CAST")
        val bound = TransactionSynchronizationManager.getResource(RESOURCE_KEY) as? Map<Long, MutableMarks> ?: return emptyMap()
        return bound.mapValues { (_, marks) -> RollupMarks(marks.dailyLoadFrom, marks.peaksChangedAt.toSet()) }
    }

    /** The current transaction's marks, binding them and registering the before-commit pass on first use. */
    private fun pending(): MutableMap<Long, MutableMarks> {
        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "Athlete rollups are marked inside the caller's transaction"
        }
        @Suppress("UNCHECKED_CAST")
        (TransactionSynchronizationManager.getResource(RESOURCE_KEY) as? MutableMap<Long, MutableMarks>)?.let { return it }

        val marks = sortedMapOf<Long, MutableMarks>()
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, marks)
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    entityManager.flush()
                    // Ascending athlete id: the order the per-athlete rollup locks are taken in.
                    marks.forEach { (userId, marked) -> rollUp(userId, RollupMarks(marked.dailyLoadFrom, marked.peaksChangedAt.toSet())) }
                }

                override fun afterCompletion(status: Int) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY)
                }
            },
        )
        return marks
    }

    /** Recomputes [userId]'s rollups from [marks], under their rollup lock. */
    private fun rollUp(
        userId: Long,
        marks: RollupMarks,
    ) {
        stateRepository.lockRollups(userId)
        val version = stateRepository.findRollupVersion(userId)
        when {
            version == null -> {
                dailyLoadRollup.recomputeFrom(userId, from = null)
                stateRepository.insertRollupVersionIfAbsent(userId)
            }
            version < ROLLUP_VERSION -> return
            else -> marks.dailyLoadFrom?.let { dailyLoadRollup.recomputeFrom(userId, it) }
        }
    }

    /**
     * Rebuilds [userId]'s rollups in full and records them as current, unless they already are; for the recompute
     * worker, inside its own transaction. Returns whether it rebuilt them.
     *
     * An athlete with a state row has it locked from the read to commit, so a concurrent time-zone change waits and
     * marks the rebuild stale after it. One without a row has nothing to lock: a time-zone change can commit a stale
     * row during the rebuild, so the rebuild only inserts its row if absent, and a stale mark that got there first
     * stands for the next rebuild (ADR-0016).
     */
    fun rebuildStale(userId: Long): Boolean {
        stateRepository.lockRollups(userId)
        val version = stateRepository.findRollupVersion(userId, forUpdate = true)
        if (version != null && version >= ROLLUP_VERSION) return false
        dailyLoadRollup.recomputeFrom(userId, from = null)
        if (version == null) stateRepository.insertRollupVersionIfAbsent(userId) else stateRepository.setRollupVersion(userId)
        return true
    }

    private class MutableMarks(
        var dailyLoadFrom: Instant? = null,
        val peaksChangedAt: MutableSet<Instant> = linkedSetOf(),
    )

    private companion object {
        val RESOURCE_KEY = Any()
    }
}
