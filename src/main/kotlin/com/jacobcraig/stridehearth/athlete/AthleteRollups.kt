package com.jacobcraig.stridehearth.athlete

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

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
 * Athlete-level rollups, recomputed once per transaction just before it commits (ADR-0014).
 *
 * Per-Activity writers ([com.jacobcraig.stridehearth.activities.DerivedDataRefresh]) only *mark* what changed, by
 * the Activity's `startedAt` instant; the conversion to athlete-local days happens in the before-commit pass, where
 * the athlete's time zone is read under the rollup's own lock. Marks live in a transaction-bound resource with a
 * single [TransactionSynchronization], so many marks in one transaction cost one rollup per athlete.
 *
 * This is the skeleton: no rollup exists yet, so the before-commit pass takes no lock and writes nothing. Later
 * rollups (daily load, fitted models) hook into [rollUp].
 */
@Component
class AthleteRollups {
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
                    // Ascending athlete id: the order every future per-athlete rollup lock is taken in.
                    marks.forEach { (userId, marked) -> rollUp(userId, RollupMarks(marked.dailyLoadFrom, marked.peaksChangedAt.toSet())) }
                }

                override fun afterCompletion(status: Int) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY)
                }
            },
        )
        return marks
    }

    /** Recomputes [userId]'s rollups from [marks]. No rollup exists yet, so there is nothing to do. */
    @Suppress("UNUSED_PARAMETER")
    private fun rollUp(
        userId: Long,
        marks: RollupMarks,
    ) = Unit

    private class MutableMarks(
        var dailyLoadFrom: Instant? = null,
        val peaksChangedAt: MutableSet<Instant> = linkedSetOf(),
    )

    private companion object {
        val RESOURCE_KEY = Any()
    }
}
