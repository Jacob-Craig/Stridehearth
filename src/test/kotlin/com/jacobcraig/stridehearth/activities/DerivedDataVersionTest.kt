package com.jacobcraig.stridehearth.activities

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * [DERIVED_DATA_VERSION] must rise with every change that computes different derived data from the same Activity
 * Details, so the recompute worker replaces the stored rows (ADR-0014).
 */
class DerivedDataVersionTest {
    @Test
    fun `version 3 recomputes GAP and the Run metrics with the smoothed grade of #51`() {
        assertEquals(3, DERIVED_DATA_VERSION.toInt())
    }
}
