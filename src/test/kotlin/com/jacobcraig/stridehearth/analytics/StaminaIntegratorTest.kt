package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Stamina** (#40): Skiba's differential D′bal integrator on Grade-Adjusted speed, as a percentage of D′. Expected
 * values are worked by hand: above CS the balance falls by `(v - CS)·dt` metres; at or below it the balance recovers
 * towards D′ with `τ = D′ / (CS - v)`, so after `dt` seconds `bal = D′ - (D′ - bal)·e^(-dt/τ)`.
 */
class StaminaIntegratorTest {
    private val cs = 4.0
    private val dPrime = 200.0

    private fun constant(
        seconds: Int,
        speed: Float?,
    ): Pair<IntArray, Array<Float?>> = IntArray(seconds + 1) { it } to Array(seconds + 1) { speed }

    private fun integrate(
        t: IntArray,
        gap: Array<Float?>,
    ) = StaminaIntegrator.integrate(t, gap, cs, dPrime)

    @Test
    fun `holding 1 m per s above CS empties D' 200 m at exactly 200 s, half of it at 100 s, and it stays empty`() {
        val (t, gap) = constant(300, 5.0f)

        val stamina = integrate(t, gap)

        assertEquals(100.0f, stamina[0])
        assertEquals(50.0f, stamina[100])
        assertEquals(0.5f, stamina[199])
        assertEquals(0.0f, stamina[200])
        assertTrue((200..300).all { stamina[it] == 0.0f }, "Stamina never goes below 0")
    }

    @Test
    fun `depletion time is D' over the speed above CS at another speed`() {
        // 2 m/s above CS empties 200 m in 100 s.
        val (t, gap) = constant(150, 6.0f)

        val stamina = integrate(t, gap)

        assertEquals(1.0f, stamina[99])
        assertEquals(0.0f, stamina[100])
    }

    /** 100 s at 5 m/s leaves 100 m; then 100 s at 3 m/s gives τ = 200 s, so 200 - 100·e^(-0.5) = 139.347 m. */
    private fun depleteThenRecover(steps: List<Int>): Float {
        val times = mutableListOf(0)
        val speeds = mutableListOf<Float?>(null)
        repeat(100) {
            times += times.last() + 1
            speeds += 5.0f
        }
        for (step in steps) {
            times += times.last() + step
            speeds += 3.0f
        }
        assertEquals(200, times.last())
        return integrate(times.toIntArray(), speeds.toTypedArray()).last()
    }

    @Test
    fun `recovery below CS matches the closed-form reference whatever the sample interval`() {
        val reference = 100 * (dPrime - 100 * exp(-100.0 / 200.0)) / dPrime
        assertEquals(69.67, reference, 0.005)

        assertEquals(69.7f, depleteThenRecover(List(100) { 1 }))
        assertEquals(69.7f, depleteThenRecover(List(20) { 5 }))
        assertEquals(69.7f, depleteThenRecover(listOf(3, 7, 1, 9, 10, 2, 8, 4, 6, 10, 10, 5, 5, 10, 10)))
    }

    @Test
    fun `running exactly at CS holds the balance`() {
        val t = IntArray(201) { it }
        val gap = Array<Float?>(201) { if (it <= 100) 5.0f else 4.0f }

        val stamina = integrate(t, gap)

        assertEquals(50.0f, stamina[100])
        assertEquals(50.0f, stamina[200])
    }

    @Test
    fun `a recording pause longer than 10 s recovers as standing still, whatever speed follows it`() {
        // 100 s at 5 m/s leaves 100 m; a 60 s gap at v = 0 has τ = 50 s: 200 - 100·e^(-1.2) = 169.881 m = 84.9 %.
        val t = IntArray(102) { if (it <= 100) it else 160 }
        val gap = Array<Float?>(102) { 5.0f }

        val stamina = integrate(t, gap)

        assertEquals(84.9f, stamina[101])
    }

    @Test
    fun `a missing Grade-Adjusted speed counts as standing still`() {
        // 100 s at 5 m/s, then 10 s with no speed: τ = 50 s, 200 - 100·e^(-0.2) = 118.127 m = 59.1 %.
        val t = IntArray(111) { it }
        val gap = Array<Float?>(111) { if (it <= 100) 5.0f else null }

        assertEquals(59.1f, integrate(t, gap)[110])
    }

    @Test
    fun `Stamina never rises above 100 percent`() {
        val (t, gap) = constant(600, 0.0f)

        assertTrue(integrate(t, gap).all { it == 100.0f })
    }

    @Test
    fun `the minimum Stamina of a stream is its lowest value, and an empty stream has none`() {
        val t = IntArray(201) { it }
        val gap = Array<Float?>(201) { if (it <= 150) 5.0f else 3.0f }

        val stamina = integrate(t, gap)

        assertEquals(25.0f, StaminaIntegrator.min(stamina))
        assertNull(StaminaIntegrator.min(FloatArray(0)))
    }
}
