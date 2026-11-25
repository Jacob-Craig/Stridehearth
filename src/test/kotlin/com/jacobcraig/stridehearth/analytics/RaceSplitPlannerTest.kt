package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expected values are worked out by hand, never read back from the planner. Minetti's cost ratios `C(i) / 3.6`:
 * +5 % -> 1.3014, -5 % -> 0.7630 (below [RaceSplitPlanner.MIN_DOWNHILL_RATIO] = 0.85, so a -5 % stretch is planned
 * at 0.85), -25 % -> 0.5122 (also capped).
 */
class RaceSplitPlannerTest {
    /**
     * An outdoor run sampled once a second at [speed] m/s, heading north, with altitude a function of distance, after
     * Telemetry Cleaning, so its grades are read exactly as Grade-Adjusted Pace reads them.
     */
    private fun course(
        n: Int,
        speed: Float = 4.0f,
        altitudeAt: (distance: Double) -> Float? = { 30.0f },
    ): CleanedTelemetry {
        val details =
            FitTelemetry(
                t = IntArray(n) { it },
                lons = DoubleArray(n) { -1.5 },
                lats = DoubleArray(n) { 53.0 + it * speed / METRES_PER_DEGREE_LATITUDE },
                routeIndex = Array(n) { it },
                speed = Array(n) { speed },
                distance = Array(n) { it * speed },
                altitude = Array(n) { altitudeAt(it * speed.toDouble()) },
            )
        return TelemetryCleaner.clean(details, ActivitySport.RUN)
    }

    private fun plan(
        cleaned: CleanedTelemetry,
        goalSeconds: Double,
        unitMeters: Double = RaceSplitPlanner.KM_METERS,
        biasPct: Double = 0.0,
    ) = RaceSplitPlanner.plan(cleaned, GradeAdjustedPace.costRatios(cleaned), goalSeconds, unitMeters, biasPct)!!

    /** 10 km flat at 4 m/s, 1 Hz. */
    private val flat10k by lazy { course(2501) }

    /** 4 km at 4 m/s, 1 Hz: 2 km climbing at +5 % to 100 m, then 2 km descending at -5 %. */
    private val upAndDown by lazy {
        course(1001) { d -> (if (d <= 2000) 0.05 * d else 100 - 0.05 * (d - 2000)).toFloat() }
    }

    private fun assertNear(
        expected: Double,
        actual: Double,
        tolerance: Double,
        message: String? = null,
    ) = assertTrue(abs(expected - actual) <= tolerance, "${message ?: ""} expected $expected ± $tolerance, was $actual")

    @Nested
    inner class FlatCourse {
        @Test
        fun `a flat 10 km at a 3000 s goal is ten even 300 s kilometres whose cumulative time ends exactly at the goal`() {
            val plan = plan(flat10k, 3000.0)

            assertEquals(10000.0, plan.courseDistanceM, 1e-6)
            assertEquals(10, plan.splits.size)
            plan.splits.forEachIndexed { k, split ->
                assertEquals(k + 1, split.index)
                assertEquals(k * 1000.0, split.startM, 1e-6)
                assertEquals(1000.0, split.distanceM, 1e-6)
                assertNear(300.0, split.targetSeconds, 0.01, "split ${k + 1}")
                assertNear(300.0, split.paceSecondsPerUnit, 0.01, "pace ${k + 1}")
            }
            assertEquals(3000.0, plan.splits.last().cumulativeSeconds)
        }

        @Test
        fun `in miles a flat 10 km is six even full miles and a partial last mile`() {
            val plan = plan(flat10k, 3000.0, unitMeters = RaceSplitPlanner.MILE_METERS)

            // 10000 m = 6 x 1609.344 m + 343.936 m; at 0.3 s/m a full mile takes 482.8032 s.
            assertEquals(7, plan.splits.size)
            plan.splits.take(6).forEach {
                assertEquals(1609.344, it.distanceM, 1e-6)
                assertNear(482.8032, it.targetSeconds, 0.01)
                assertNear(482.8032, it.paceSecondsPerUnit, 0.01)
            }
            val last = plan.splits.last()
            assertEquals(343.936, last.distanceM, 1e-6)
            assertNear(103.1808, last.targetSeconds, 0.01)
            // The pace of a partial split is still per whole mile.
            assertNear(482.8032, last.paceSecondsPerUnit, 0.01)
            assertEquals(3000.0, last.cumulativeSeconds)
        }

        @Test
        fun `a +4 percent Split Bias makes the second half 4 percent slower than the first and keeps the goal`() {
            val plan = plan(flat10k, 3000.0, biasPct = 4.0)

            val firstHalf = plan.splits.take(5).sumOf { it.targetSeconds }
            val secondHalf = plan.splits.drop(5).sumOf { it.targetSeconds }
            assertNear(1.04, secondHalf / firstHalf, 1e-6)
            assertNear(3000.0, firstHalf + secondHalf, 1e-6)
            // Effort ramps evenly: every kilometre is slower than the one before.
            plan.splits.zipWithNext().forEach { (a, b) -> assertTrue(b.targetSeconds > a.targetSeconds) }
            assertEquals(3000.0, plan.splits.last().cumulativeSeconds)
        }

        @Test
        fun `a -4 percent Split Bias is a negative split`() {
            val plan = plan(flat10k, 3000.0, biasPct = -4.0)

            val firstHalf = plan.splits.take(5).sumOf { it.targetSeconds }
            val secondHalf = plan.splits.drop(5).sumOf { it.targetSeconds }
            assertNear(0.96, secondHalf / firstHalf, 1e-6)
        }

        @Test
        fun `a flat course has no elevation change and no grade in any split`() {
            plan(flat10k, 3000.0).splits.forEach {
                assertEquals(0.0, it.elevationGainM!!, 1e-6)
                assertEquals(0.0, it.elevationLossM!!, 1e-6)
                assertEquals(0.0, it.avgGradePct!!, 1e-6)
            }
        }
    }

    @Nested
    inner class HillyCourse {
        @Test
        fun `the splits sum to the goal and the climb is slower than the descent, which is capped at 0_85`() {
            val plan = plan(upAndDown, 1200.0)

            assertEquals(4, plan.splits.size)
            assertNear(1200.0, plan.splits.sumOf { it.targetSeconds }, 1e-6)
            assertEquals(1200.0, plan.splits.last().cumulativeSeconds)
            val (km1, km2, km3, km4) = plan.splits.map { it.targetSeconds }
            assertTrue(km1 > km3 && km1 > km4 && km2 > km3 && km2 > km4, "uphill $km1, $km2 vs downhill $km3, $km4")
            // km 1 is all at +5 % (ratio 1.3014) and km 4 all at -5 %, planned at the 0.85 cap: 1.3014 / 0.85 = 1.5311.
            assertNear(1.5311, km1 / km4, 1.5311 * 0.01)
        }

        @Test
        fun `a climbing kilometre takes 1_301 times as long as a kilometre of the same goal on the flat`() {
            // A 4 km course whose first 2 km climb at +5 % and whose last 2 km are flat: Σw = 2000·1.3014 + 2000
            // (with a short transition at the crest). A flat 4 km at the same goal takes T/4 per km, so compare km 1
            // with km 4 of the same plan instead: both share one normalisation.
            val climbThenFlat = course(1001) { d -> (if (d <= 2000) 0.05 * d else 100.0).toFloat() }
            val plan = plan(climbThenFlat, 1200.0)

            assertNear(1.3014, plan.splits[0].targetSeconds / plan.splits[3].targetSeconds, 1.3014 * 0.01)
        }

        @Test
        fun `a -25 percent descent is planned no faster than 0_85 of flat`() {
            // 1 km flat, 400 m at -25 % (100 m down), 1 km flat: the descent's ratio 0.512 is capped at 0.85.
            val steep =
                course(601) { d ->
                    when {
                        d <= 1000 -> 200.0
                        d <= 1400 -> 200 - 0.25 * (d - 1000)
                        else -> 100.0
                    }.toFloat()
                }
            val plan = plan(steep, 600.0, unitMeters = 200.0)

            // Splits 6 and 7 (1000-1400 m) lie on the descent, away from where the grade anchors cross into it.
            val flatSplit = plan.splits[0].targetSeconds
            assertNear(0.85, plan.splits[5].targetSeconds / flatSplit, 0.85 * 0.01)
            assertNear(0.85, plan.splits[6].targetSeconds / flatSplit, 0.85 * 0.01)
        }

        @Test
        fun `each split's elevation gain, loss and grade come from the cleaned altitude`() {
            val plan = plan(upAndDown, 1200.0)

            // Altitude smoothing rounds the crest a little, so allow a metre.
            assertNear(50.0, plan.splits[0].elevationGainM!!, 1.0)
            assertNear(0.0, plan.splits[0].elevationLossM!!, 1e-6)
            assertNear(5.0, plan.splits[0].avgGradePct!!, 0.1)
            assertNear(-5.0, plan.splits[3].avgGradePct!!, 0.1)
            assertNear(50.0, plan.splits[3].elevationLossM!!, 1.0)
            assertNear(100.0, plan.elevationGainM!!, 1.0)
        }
    }

    @Nested
    inner class Profile {
        @Test
        fun `the elevation profile has at most 500 points spanning the course`() {
            val plan = plan(flat10k, 3000.0)

            assertEquals(RaceSplitPlanner.PROFILE_POINTS, plan.profile.size)
            assertEquals(0.0, plan.profile.first().distanceM, 1e-6)
            assertEquals(10000.0, plan.profile.last().distanceM, 1e-6)
            assertEquals(30.0, plan.profile.first().altitudeM, 1e-6)
        }

        @Test
        fun `a short course keeps every sample in its profile`() {
            val plan = plan(course(101), 100.0, unitMeters = 100.0)

            assertEquals(101, plan.profile.size)
        }

        @Test
        fun `a course without altitude has an empty profile and no elevation figures`() {
            val plan = plan(course(251) { null }, 300.0)

            assertTrue(plan.profile.isEmpty())
            assertNull(plan.elevationGainM)
            plan.splits.forEach {
                assertNull(it.elevationGainM)
                assertNull(it.avgGradePct)
            }
        }
    }

    @Nested
    inner class CostRatios {
        @Test
        fun `the cost ratio is Minetti's cost at each sample's grade over the flat cost`() {
            val ratios = GradeAdjustedPace.costRatios(upAndDown)

            // Sample 100 is at 400 m, well inside the +5 % climb; sample 900 at 3600 m on the -5 % descent.
            assertNear(1.3014, ratios[100], 1e-3)
            assertNear(0.7630, ratios[900], 1e-3)
        }

        @Test
        fun `Grade-Adjusted Pace is the cleaned speed times the cost ratio`() {
            val ratios = GradeAdjustedPace.costRatios(upAndDown)
            val gap = GradeAdjustedPace.gapSpeed(upAndDown)!!

            gap.indices.forEach { assertNear(upAndDown.speed[it]!! * ratios[it], gap[it]!!.toDouble(), 1e-5) }
        }
    }

    @Test
    fun `a course without GPS distance has no plan`() {
        val indoor =
            TelemetryCleaner.clean(
                FitTelemetry(
                    t = IntArray(10) { it },
                    lons = DoubleArray(0),
                    lats = DoubleArray(0),
                    routeIndex = arrayOfNulls(10),
                    speed = Array(10) { 3.0f },
                ),
                ActivitySport.RUN,
            )

        assertNull(RaceSplitPlanner.plan(indoor, GradeAdjustedPace.costRatios(indoor), 600.0, RaceSplitPlanner.KM_METERS, 0.0))
    }

    private companion object {
        const val METRES_PER_DEGREE_LATITUDE = 111_195.0
    }
}
