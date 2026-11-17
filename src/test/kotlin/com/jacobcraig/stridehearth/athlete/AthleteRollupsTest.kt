package com.jacobcraig.stridehearth.athlete

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import kotlin.test.assertEquals

class AthleteRollupsTest {
    private val rollups = AthleteRollups()

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
}
