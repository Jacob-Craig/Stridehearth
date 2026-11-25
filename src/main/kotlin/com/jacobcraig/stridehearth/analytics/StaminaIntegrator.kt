package com.jacobcraig.stridehearth.analytics

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * **Stamina** (#40): Skiba's differential D′ balance (D′bal) over a Run, as a percentage of **D′**.
 *
 * The balance starts full (`bal = D′` at `t[0]`). For each later sample `i`, with `dt = t[i] - t[i-1]` and
 * `v` the Grade-Adjusted speed of sample `i` (m/s):
 * - above **Critical Speed**, `bal -= (v - CS)·dt`;
 * - at or below it, the balance recovers towards D′ with `τ = D′ / (CS - v)`, stepped exactly as
 *   `bal = D′ - (D′ - bal)·e^(-(CS - v)·dt / D′)`, so the result does not depend on the sample interval;
 * - the balance is clamped to `[0, D′]`.
 *
 * A missing speed counts as `v = 0`, and so does a step longer than [RECORDING_PAUSE_SECONDS] (a recording pause:
 * the athlete recovers as if standing still). A step whose time does not advance leaves the balance as it is.
 *
 * No Spring and no I/O.
 */
object StaminaIntegrator {
    /** The longest step integrated at the recorded speed; a longer one is a recording pause. */
    const val RECORDING_PAUSE_SECONDS = RunMetrics.RECORDING_PAUSE_SECONDS

    /**
     * Stamina at each sample, in percent to one decimal, parallel to [t]: `100·bal / D′`, with element 0 at 100.
     * [gapSpeed] is parallel to [t]; [criticalSpeed] is in m/s and [dPrime] in metres, both positive.
     */
    fun integrate(
        t: IntArray,
        gapSpeed: Array<Float?>,
        criticalSpeed: Double,
        dPrime: Double,
    ): FloatArray {
        require(t.size == gapSpeed.size) { "t and gapSpeed must be parallel" }
        require(criticalSpeed > 0 && dPrime > 0) { "Critical Speed and D′ must be positive" }
        val stamina = FloatArray(t.size)
        var balance = dPrime
        for (i in t.indices) {
            if (i > 0) {
                val dt = t[i] - t[i - 1]
                if (dt > 0) {
                    val speed = if (dt > RECORDING_PAUSE_SECONDS) 0.0 else gapSpeed[i]?.toDouble() ?: 0.0
                    balance =
                        if (speed > criticalSpeed) {
                            balance - (speed - criticalSpeed) * dt
                        } else {
                            dPrime - (dPrime - balance) * exp(-(criticalSpeed - speed) * dt / dPrime)
                        }
                    balance = balance.coerceIn(0.0, dPrime)
                }
            }
            stamina[i] = percent(balance, dPrime)
        }
        return stamina
    }

    /** The lowest Stamina of [stamina], or null when it is empty. */
    fun min(stamina: FloatArray): Float? = stamina.minOrNull()

    private fun percent(
        balance: Double,
        dPrime: Double,
    ): Float = ((balance / dPrime) * 1000).roundToInt() / 10.0f
}
