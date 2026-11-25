package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Daniels' published VDOT tables, reproduced by the Daniels-Gilbert equations within 0.2 %. */
class DanielsVdotTest {
    @ParameterizedTest(name = "VDOT {0} runs {1} m in {2} s")
    @CsvSource(
        // VDOT 50: 5K 19:57, 10K 41:21, Half 1:31:35, Marathon 3:10:49.
        "50, 5000, 1197",
        "50, 10000, 2481",
        "50, 21097.5, 5495",
        "50, 42195, 11449",
        // VDOT 40: 5K 24:08, 10K 50:03, Half 1:50:59, Marathon 3:49:45.
        "40, 5000, 1448",
        "40, 10000, 3003",
        "40, 21097.5, 6659",
        "40, 42195, 13785",
    )
    fun `timeFor matches Daniels' table within 0,2 percent`(
        vdot: Double,
        meters: Double,
        tableSeconds: Double,
    ) {
        assertEquals(tableSeconds, DanielsVdot.timeFor(vdot, meters), tableSeconds * 0.002)
    }

    @Test
    fun `a 19 57 5K is VDOT 50`() {
        assertEquals(49.95, DanielsVdot.vdot(5000.0, 1197.0), 0.1)
    }

    @Test
    fun `timeFor inverts vdot to 0,01 s`() {
        val seconds = DanielsVdot.timeFor(47.3, 10000.0)
        assertEquals(47.3, DanielsVdot.vdot(10000.0, seconds), 1e-4)
    }

    @Test
    fun `the oxygen cost of 240 m per min is 45,132`() {
        // -4.60 + 0.182258 x 240 + 0.000104 x 240^2
        assertEquals(45.132, DanielsVdot.vo2Cost(240.0), 1e-3)
    }
}
