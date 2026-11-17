package com.jacobcraig.stridehearth.athlete

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DerivedDataRecomputeScheduleTest {
    private val worker: DerivedDataRecomputeWorker = mock()

    @Test
    fun `each run drains at most the configured number of pages`() {
        doReturn(RecomputeResult(3, 0)).whenever(worker).drain(any(), any(), anyOrNull())

        DerivedDataRecomputeSchedule(worker, pagesPerRun = 4).run()

        verify(worker).drain(DerivedDataRecomputeWorker.PAGE_SIZE, 4, null)
    }

    @Test
    fun `a failing run is logged, not thrown, so the next run retries`() {
        doThrow(IllegalStateException("database down")).whenever(worker).drain(any(), any(), anyOrNull())

        DerivedDataRecomputeSchedule(worker, pagesPerRun = 4).run()

        verify(worker).drain(DerivedDataRecomputeWorker.PAGE_SIZE, 4, null)
    }
}
