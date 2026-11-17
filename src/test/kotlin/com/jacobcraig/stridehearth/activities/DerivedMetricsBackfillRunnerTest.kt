package com.jacobcraig.stridehearth.activities

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DerivedMetricsBackfillRunnerTest {
    private val backfillService: DerivedMetricsBackfillService = mock()

    @Test
    fun `at startup it backfills every athlete's Activities on a background thread`() {
        whenever(backfillService.backfillAll()).thenReturn(DerivedMetricsBackfillResult(activitiesProcessed = 3))

        val thread = assertNotNull(DerivedMetricsBackfillRunner(backfillService, enabled = true).start())
        thread.join(10_000)

        assertTrue(thread.isDaemon)
        verify(backfillService).backfillAll()
    }

    @Test
    fun `a failing backfill is logged, not thrown, so startup is unaffected`() {
        whenever(backfillService.backfillAll()).thenThrow(IllegalStateException("database unavailable"))
        val uncaught = mutableListOf<Throwable>()

        val thread = assertNotNull(DerivedMetricsBackfillRunner(backfillService, enabled = true).start())
        thread.setUncaughtExceptionHandler { _, e -> uncaught += e }
        thread.join(10_000)

        verify(backfillService).backfillAll()
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `does nothing when switched off`() {
        assertNull(DerivedMetricsBackfillRunner(backfillService, enabled = false).start())

        verifyNoInteractions(backfillService)
    }
}
