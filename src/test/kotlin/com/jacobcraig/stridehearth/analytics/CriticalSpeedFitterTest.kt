package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2-parameter **Critical Speed** fit `D(t) = CS·t + D′` (#39): ordinary least squares of distance (or work) on
 * time over an athlete's best Peak Efforts between 2 and 20 minutes. Expected values are worked by hand from the
 * closed form `v = CS + D′ / t`.
 */
class CriticalSpeedFitterTest {
    private val durations = listOf(120, 180, 300, 600, 1200)

    /** The speed (or power) a 2-parameter athlete holds for each fit duration: `CS + D′ / t`. */
    private fun hyperbolic(
        critical: Double,
        reserve: Double,
    ) = durations.map { it to critical + reserve / it }

    @Test
    fun `the fit durations are the Benchmark Durations from 2 to 20 minutes`() {
        assertEquals(
            listOf(
                BenchmarkDuration.TWO_MINUTES,
                BenchmarkDuration.THREE_MINUTES,
                BenchmarkDuration.FIVE_MINUTES,
                BenchmarkDuration.TEN_MINUTES,
                BenchmarkDuration.TWENTY_MINUTES,
            ),
            CriticalSpeedFitter.FIT_DURATIONS,
        )
        assertEquals(90, CriticalSpeedFitter.WINDOW_DAYS)
    }

    @Test
    fun `peaks from CS 4 m per s and D' 200 m recover both within 1 percent`() {
        val fit = CriticalSpeedFitter.fit(hyperbolic(4.0, 200.0), PeakEffortMetric.GAP_SPEED)

        assertEquals(CriticalSpeedStatus.MODEL, fit.status)
        assertEquals(4.0, assertNotNull(fit.criticalValue), 0.04)
        assertEquals(200.0, assertNotNull(fit.reserveCapacity), 2.0)
        assertTrue(assertNotNull(fit.rSquared) > 0.999)
        assertEquals(5, fit.pointCount)
    }

    @Test
    fun `power peaks from CP 300 W and W' 20000 J recover both within 1 percent`() {
        val fit = CriticalSpeedFitter.fit(hyperbolic(300.0, 20_000.0), PeakEffortMetric.POWER)

        assertEquals(CriticalSpeedStatus.MODEL, fit.status)
        assertEquals(300.0, assertNotNull(fit.criticalValue), 3.0)
        assertEquals(20_000.0, assertNotNull(fit.reserveCapacity), 200.0)
        assertTrue(assertNotNull(fit.rSquared) > 0.999)
    }

    @Test
    fun `2 percent noise on the peaks still gives a model`() {
        // Speeds alternately 2 % above and below the curve: OLS by hand gives CS 4.0782 m/s, D' 176.72 m, R^2 0.99937.
        val noise = listOf(1.02, 0.98, 1.02, 0.98, 1.02)
        val fit = CriticalSpeedFitter.fit(hyperbolic(4.0, 200.0).zip(noise) { (t, v), k -> t to v * k }, PeakEffortMetric.GAP_SPEED)

        assertEquals(CriticalSpeedStatus.MODEL, fit.status)
        assertEquals(4.078165, assertNotNull(fit.criticalValue), 1e-5)
        assertEquals(176.720734, assertNotNull(fit.reserveCapacity), 1e-5)
        assertEquals(0.999372, assertNotNull(fit.rSquared), 1e-6)
    }

    @Test
    fun `fewer than 3 points publish no model`() {
        val fit = CriticalSpeedFitter.fit(listOf(120 to 5.0, 600 to 4.0), PeakEffortMetric.GAP_SPEED)

        assertEquals(CriticalSpeedStatus.INSUFFICIENT_POINTS, fit.status)
        assertNull(fit.criticalValue)
        assertNull(fit.reserveCapacity)
        assertNull(fit.rSquared)
        assertEquals(2, fit.pointCount)
        assertEquals(CriticalSpeedStatus.INSUFFICIENT_POINTS, CriticalSpeedFitter.fit(emptyList(), PeakEffortMetric.GAP_SPEED).status)
    }

    @Test
    fun `scattered points with R squared below 0_9 publish no model but report the fit quality`() {
        // Distances 1200, 1080, 600, 1200, 1200 m: R^2 = 0.0976 by hand.
        val fit = CriticalSpeedFitter.fit(listOf(120 to 10.0, 180 to 6.0, 300 to 2.0, 600 to 2.0, 1200 to 1.0), PeakEffortMetric.GAP_SPEED)

        assertEquals(CriticalSpeedStatus.POOR_FIT, fit.status)
        assertNull(fit.criticalValue)
        assertNull(fit.reserveCapacity)
        assertEquals(0.097599, assertNotNull(fit.rSquared), 1e-6)
        assertEquals(5, fit.pointCount)
    }

    @Test
    fun `a negative D' publishes no model even though the line fits well`() {
        // Equal speeds up to 10 min and a faster 20 min: OLS gives D' = -57.69 m with R^2 = 0.99943.
        val fit = CriticalSpeedFitter.fit(listOf(120 to 4.0, 180 to 4.0, 300 to 4.0, 600 to 4.0, 1200 to 4.2), PeakEffortMetric.GAP_SPEED)

        assertEquals(CriticalSpeedStatus.NON_PHYSICAL, fit.status)
        assertNull(fit.criticalValue)
        assertNull(fit.reserveCapacity)
        assertEquals(0.999426, assertNotNull(fit.rSquared), 1e-6)
    }

    @Test
    fun `a non-positive CS publishes no model`() {
        // Distance falling with time: slope -0.5 m/s, intercept 1100 m, a perfect but impossible line.
        val fit = CriticalSpeedFitter.fit(listOf(200 to 5.0, 400 to 2.25, 600 to 4.0 / 3.0), PeakEffortMetric.GAP_SPEED)

        assertEquals(CriticalSpeedStatus.NON_PHYSICAL, fit.status)
        assertNull(fit.criticalValue)
    }

    @Test
    fun `one steady easy run's peaks publish no model, however well the line fits`() {
        // Peaks of a single steady run at about 2.95 m/s: OLS gives CS 2.9390 m/s and D' 16.80 m with R^2 above 0.999,
        // a near-straight D = v t whose tiny D' is no athlete's anaerobic reserve, just sub-maximal running.
        val fit =
            CriticalSpeedFitter.fit(
                listOf(120 to 3.05, 180 to 3.02, 300 to 3.0, 600 to 2.98, 1200 to 2.95),
                PeakEffortMetric.GAP_SPEED,
            )

        assertEquals(CriticalSpeedStatus.NON_PHYSICAL, fit.status)
        assertNull(fit.criticalValue)
        assertNull(fit.reserveCapacity)
        assertTrue(assertNotNull(fit.rSquared) > 0.999)
    }

    @Test
    fun `a D' just under the 100 m floor publishes no model, and one at it does`() {
        assertEquals(100.0, CriticalSpeedFitter.MIN_D_PRIME)
        assertEquals(CriticalSpeedStatus.NON_PHYSICAL, CriticalSpeedFitter.fit(hyperbolic(4.0, 99.0), PeakEffortMetric.GAP_SPEED).status)
        assertEquals(CriticalSpeedStatus.MODEL, CriticalSpeedFitter.fit(hyperbolic(4.0, 100.5), PeakEffortMetric.GAP_SPEED).status)
    }

    @Test
    fun `a W' under the 5 kJ floor publishes no Critical Power model`() {
        assertEquals(5_000.0, CriticalSpeedFitter.MIN_W_PRIME)
        assertEquals(CriticalSpeedStatus.NON_PHYSICAL, CriticalSpeedFitter.fit(hyperbolic(250.0, 4_000.0), PeakEffortMetric.POWER).status)
        assertEquals(CriticalSpeedStatus.MODEL, CriticalSpeedFitter.fit(hyperbolic(250.0, 6_000.0), PeakEffortMetric.POWER).status)
    }
}
