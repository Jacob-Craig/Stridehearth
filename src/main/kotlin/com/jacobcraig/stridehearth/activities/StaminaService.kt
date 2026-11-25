package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.CriticalSpeedStatus
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import com.jacobcraig.stridehearth.analytics.StaminaIntegrator
import com.jacobcraig.stridehearth.athlete.AthleteTimeZone
import com.jacobcraig.stridehearth.athlete.CriticalSpeedService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** What a Run's **Stamina** is integrated from: its start, its sample times and its Grade-Adjusted speed, parallel. */
class StaminaInputs(
    val startedAt: Instant,
    val t: IntArray,
    val gapSpeed: Array<Float?>,
)

/** A Run's **Stamina** stream, in percent parallel to its Activity Details' `t`, and the lowest value it reaches. */
class Stamina(
    val stream: FloatArray,
    val min: Float,
)

/**
 * Reads a Run's **Stamina** (#40). It is computed on read, never stored: it depends on the athlete's **Critical
 * Speed** model, which the before-commit rollup refits whenever a Peak Effort changes, and storing it would mean
 * rewriting other Activities' rows inside that rollup (ADR-0014).
 *
 * The model is the one in effect on the Run's own athlete-local date, so a Run's own Peak Efforts count towards it:
 * a breakthrough Run is measured against the model it set.
 */
@Service
class StaminaService(
    private val derivedMetricsRepository: ActivityDerivedMetricsRepository,
    private val criticalSpeedService: CriticalSpeedService,
    private val athleteTimeZone: AthleteTimeZone,
) {
    /**
     * [activityId]'s Stamina, or null unless [userId] owns it, it is a Run with a Grade-Adjusted speed stream, and the
     * Critical Speed model in effect on its local date is a [CriticalSpeedStatus.MODEL].
     */
    @Transactional(readOnly = true)
    fun stamina(
        activityId: Long,
        userId: Long,
    ): Stamina? {
        val inputs = derivedMetricsRepository.findStaminaInputs(activityId, userId) ?: return null
        val date = athleteTimeZone.localDate(userId, inputs.startedAt)
        val model = criticalSpeedService.inEffect(userId, PeakEffortMetric.GAP_SPEED, date)
        if (model.status != CriticalSpeedStatus.MODEL) return null
        val criticalSpeed = model.criticalValue ?: return null
        val dPrime = model.reserveCapacity ?: return null
        val stream = StaminaIntegrator.integrate(inputs.t, inputs.gapSpeed, criticalSpeed, dPrime)
        val min = StaminaIntegrator.min(stream) ?: return null
        return Stamina(stream, min)
    }
}
