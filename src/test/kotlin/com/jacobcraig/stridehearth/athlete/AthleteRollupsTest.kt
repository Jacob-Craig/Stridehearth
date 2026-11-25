package com.jacobcraig.stridehearth.athlete

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import kotlin.test.assertEquals

class AthleteRollupsTest {
    private val entityManager = mock<EntityManager>()
    private val stateRepository = mock<AthleteAnalyticsStateRepository>()
    private val dailyLoadRollup = mock<DailyLoadRollup>()
    private val criticalSpeedRollup = mock<CriticalSpeedRollup>()
    private val acclimationRollup = mock<AcclimationRollup>()
    private val fitnessRollup = mock<FitnessRollup>()
    private val rollups =
        AthleteRollups(entityManager, stateRepository, dailyLoadRollup, criticalSpeedRollup, acclimationRollup, fitnessRollup)

    private fun at(text: String) = Instant.parse(text)

    @AfterEach
    fun tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `keeps the earliest daily-load instant and every peak instant per athlete, with one synchronization`() {
        TransactionSynchronizationManager.initSynchronization()

        rollups.markDailyLoad(1, at("2026-03-02T08:00:00Z"))
        rollups.markDailyLoad(1, at("2015-03-01T08:00:00Z"))
        rollups.markPeaksChanged(1, at("2015-03-01T08:00:00Z"))
        rollups.markPeaksChanged(1, at("2020-06-01T08:00:00Z"))
        rollups.markDailyLoad(2, at("2026-01-01T08:00:00Z"))

        assertEquals(
            mapOf(
                1L to
                    RollupMarks(
                        dailyLoadFrom = at("2015-03-01T08:00:00Z"),
                        peaksChangedAt = setOf(at("2015-03-01T08:00:00Z"), at("2020-06-01T08:00:00Z")),
                    ),
                2L to RollupMarks(dailyLoadFrom = at("2026-01-01T08:00:00Z"), peaksChangedAt = emptySet()),
            ),
            rollups.pendingMarks(),
        )
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size)
    }

    @Test
    fun `the before-commit pass runs, and completion forgets the transaction's marks`() {
        TransactionSynchronizationManager.initSynchronization()
        rollups.markDailyLoad(1, at("2026-03-02T08:00:00Z"))
        val synchronization = TransactionSynchronizationManager.getSynchronizations().single()

        synchronization.beforeCommit(false)
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)

        assertEquals(emptyMap(), rollups.pendingMarks())
    }

    @Test
    fun `marking requires the caller's transaction`() {
        assertThrows<IllegalStateException> { rollups.markDailyLoad(1, at("2026-03-02T08:00:00Z")) }
        assertThrows<IllegalStateException> { rollups.markPeaksChanged(1, at("2026-03-02T08:00:00Z")) }
    }

    private fun commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach { it.beforeCommit(false) }
    }

    @Test
    fun `before commit, a current athlete's daily load is recomputed from the earliest mark under their locks, after a flush`() {
        whenever(stateRepository.findRollupVersion(1)).thenReturn(ROLLUP_VERSION)
        TransactionSynchronizationManager.initSynchronization()
        rollups.markDailyLoad(1, at("2026-03-02T08:00:00Z"))
        rollups.markDailyLoad(1, at("2026-02-01T08:00:00Z"))

        commit()

        inOrder(entityManager, stateRepository, dailyLoadRollup, acclimationRollup, fitnessRollup) {
            verify(entityManager).flush()
            verify(stateRepository).lockScoringInputs(1)
            verify(stateRepository).lockRollups(1)
            verify(dailyLoadRollup).recomputeFrom(1, at("2026-02-01T08:00:00Z"))
            verify(acclimationRollup).recomputeFrom(1, at("2026-02-01T08:00:00Z"))
            verify(fitnessRollup).recomputeFrom(1, at("2026-02-01T08:00:00Z"))
        }
    }

    @Test
    fun `athletes are rolled up in ascending id order`() {
        whenever(stateRepository.findRollupVersion(any(), any())).thenReturn(ROLLUP_VERSION)
        TransactionSynchronizationManager.initSynchronization()
        rollups.markDailyLoad(7, at("2026-03-02T08:00:00Z"))
        rollups.markDailyLoad(3, at("2026-03-02T08:00:00Z"))

        commit()

        inOrder(stateRepository) {
            verify(stateRepository).lockRollups(3)
            verify(stateRepository).lockRollups(7)
        }
    }

    @Test
    fun `an athlete without a state row gets a full rebuild and is recorded current`() {
        whenever(stateRepository.findRollupVersion(1)).thenReturn(null)
        TransactionSynchronizationManager.initSynchronization()
        rollups.markDailyLoad(1, at("2026-03-02T08:00:00Z"))

        commit()

        inOrder(criticalSpeedRollup, dailyLoadRollup, acclimationRollup, fitnessRollup, stateRepository) {
            verify(criticalSpeedRollup).refit(1, null)
            verify(dailyLoadRollup).recomputeFrom(1, null)
            verify(acclimationRollup).recomputeFrom(1, null)
            verify(fitnessRollup).recomputeFrom(1, null)
            verify(stateRepository).insertRollupVersionIfAbsent(1)
        }
    }

    @Test
    fun `before commit, a current athlete's Critical Speed models are refitted at every peak mark, before the daily load`() {
        whenever(stateRepository.findRollupVersion(1)).thenReturn(ROLLUP_VERSION)
        TransactionSynchronizationManager.initSynchronization()
        rollups.markDailyLoad(1, at("2015-03-01T08:00:00Z"))
        rollups.markPeaksChanged(1, at("2015-03-01T08:00:00Z"))
        rollups.markPeaksChanged(1, at("2020-06-01T08:00:00Z"))

        commit()

        inOrder(stateRepository, criticalSpeedRollup, dailyLoadRollup) {
            verify(stateRepository).lockRollups(1)
            verify(criticalSpeedRollup).refit(1, setOf(at("2015-03-01T08:00:00Z"), at("2020-06-01T08:00:00Z")))
            verify(dailyLoadRollup).recomputeFrom(1, at("2015-03-01T08:00:00Z"))
        }
    }

    @Test
    fun `without a peak mark the Critical Speed models are left alone`() {
        whenever(stateRepository.findRollupVersion(1)).thenReturn(ROLLUP_VERSION)
        TransactionSynchronizationManager.initSynchronization()
        rollups.markDailyLoad(1, at("2026-03-02T08:00:00Z"))

        commit()

        verify(criticalSpeedRollup, never()).refit(any(), anyOrNull())
    }

    @Test
    fun `the rollup version is 4 since Effective VO2max joined the rollups`() {
        assertEquals(4, ROLLUP_VERSION)
    }

    @Test
    fun `stale rollups are left to the worker's full rebuild`() {
        whenever(stateRepository.findRollupVersion(1)).thenReturn(0)
        TransactionSynchronizationManager.initSynchronization()
        rollups.markDailyLoad(1, at("2026-03-02T08:00:00Z"))

        commit()

        verify(stateRepository).lockRollups(1)
        verify(dailyLoadRollup, never()).recomputeFrom(any(), anyOrNull())
        verify(acclimationRollup, never()).recomputeFrom(any(), anyOrNull())
        verify(fitnessRollup, never()).recomputeFrom(any(), anyOrNull())
        verify(criticalSpeedRollup, never()).refit(any(), anyOrNull())
    }

    @Test
    fun `a stale rebuild runs once and records the current version`() {
        whenever(stateRepository.findRollupVersion(1, forUpdate = true)).thenReturn(0)
        whenever(stateRepository.findRollupVersion(2, forUpdate = true)).thenReturn(ROLLUP_VERSION)

        assertEquals(true, rollups.rebuildStale(1))
        assertEquals(false, rollups.rebuildStale(2))

        verify(criticalSpeedRollup).refit(1, null)
        verify(dailyLoadRollup).recomputeFrom(1, null)
        verify(acclimationRollup).recomputeFrom(1, null)
        verify(fitnessRollup).recomputeFrom(1, null)
        verify(stateRepository).setRollupVersion(1)
        verify(dailyLoadRollup, never()).recomputeFrom(2, null)
        verify(acclimationRollup, never()).recomputeFrom(2, null)
        verify(fitnessRollup, never()).recomputeFrom(2, null)
        verify(criticalSpeedRollup, never()).refit(2, null)
    }

    @Test
    fun `a rebuild of an athlete without a state row records them current only if no row appeared meanwhile`() {
        whenever(stateRepository.findRollupVersion(1, forUpdate = true)).thenReturn(null)

        assertEquals(true, rollups.rebuildStale(1))

        inOrder(dailyLoadRollup, stateRepository) {
            verify(dailyLoadRollup).recomputeFrom(1, null)
            verify(stateRepository).insertRollupVersionIfAbsent(1)
        }
        verify(stateRepository, never()).setRollupVersion(any(), any())
    }
}
