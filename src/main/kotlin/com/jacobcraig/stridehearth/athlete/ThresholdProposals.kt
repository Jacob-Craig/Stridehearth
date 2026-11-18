package com.jacobcraig.stridehearth.athlete

import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.LocalDate
import kotlin.math.abs

/** A **Threshold Proposal** awaiting the athlete: [source] suggests [value] for [kind] from [effectiveDate]. */
data class ThresholdProposal(
    val id: Long,
    val kind: ThresholdKind,
    val value: Double,
    val effectiveDate: LocalDate,
    val source: String,
)

/**
 * **Threshold Proposals**: values other features (a Critical Speed fit, a VO2max estimate) suggest for an athlete's
 * Thresholds. A proposal is never applied by itself: it becomes a Threshold entry only when the athlete accepts it,
 * through [AthleteThresholds.record], and nothing else writes entries. One proposal per source and kind; a newer one
 * replaces it. A dismissed proposal stays as a marker so the same source does not offer a value within
 * [SAME_VALUE_TOLERANCE] of it again.
 */
@Service
class ThresholdProposals(
    private val jdbcClient: JdbcClient,
    private val athleteThresholds: AthleteThresholds,
) {
    /**
     * Proposes [value] (in [ThresholdKind.unit]) for [userId]'s [kind] from [effectiveDate], replacing [source]'s
     * earlier proposal of that kind. Returns null, storing nothing, when the athlete could not accept it (the value is
     * not finite or outside the kind's range, or the date is after tomorrow, UTC: what [AthleteThresholds.record]
     * refuses) or when the athlete dismissed a proposal from [source] within [SAME_VALUE_TOLERANCE] of [value]. It never
     * throws for such a value, so a degenerate fit does not roll back the proposer's transaction.
     */
    @Transactional
    fun propose(
        userId: Long,
        kind: ThresholdKind,
        value: Double,
        effectiveDate: LocalDate,
        source: String,
    ): ThresholdProposal? {
        if (!athleteThresholds.isAcceptable(kind, effectiveDate, value)) return null
        val dismissed =
            jdbcClient
                .sql(
                    """
                    SELECT value FROM athlete_threshold_proposal
                    WHERE user_id = :userId AND kind = :kind AND source = :source AND status = 'DISMISSED'
                    """.trimIndent(),
                ).param("userId", userId)
                .param("kind", kind.name)
                .param("source", source)
                .query(Double::class.java)
                .optional()
                .orElse(null)
        if (dismissed != null && sameValue(value, dismissed)) return null
        return jdbcClient
            .sql(
                """
                INSERT INTO athlete_threshold_proposal (user_id, kind, value, effective_date, source, status)
                VALUES (:userId, :kind, :value, :effectiveDate, :source, 'PENDING')
                ON CONFLICT (user_id, kind, source) DO UPDATE
                SET value = EXCLUDED.value, effective_date = EXCLUDED.effective_date, status = 'PENDING', created_at = now()
                RETURNING id, kind, value, effective_date, source
                """.trimIndent(),
            ).param("userId", userId)
            .param("kind", kind.name)
            .param("value", value)
            .param("effectiveDate", effectiveDate)
            .param("source", source)
            .query { rs, _ -> rs.toProposal() }
            .single()
    }

    /** [userId]'s proposals awaiting a decision, oldest first. */
    @Transactional(readOnly = true)
    fun pending(userId: Long): List<ThresholdProposal> =
        jdbcClient
            .sql(
                """
                SELECT id, kind, value, effective_date, source FROM athlete_threshold_proposal
                WHERE user_id = :userId AND status = 'PENDING'
                ORDER BY created_at, id
                """.trimIndent(),
            ).param("userId", userId)
            .query { rs, _ -> rs.toProposal() }
            .list()

    /**
     * Applies [userId]'s pending proposal [id] as a Threshold entry and removes it; [NoSuchElementException] (404) when
     * it is missing, already decided or another athlete's.
     */
    @Transactional
    fun accept(
        userId: Long,
        id: Long,
    ): ThresholdEntry {
        val proposal = findPending(userId, id) ?: throw NoSuchElementException("Threshold proposal not found")
        val entry = athleteThresholds.record(userId, proposal.kind, proposal.effectiveDate, proposal.value)
        jdbcClient
            .sql("DELETE FROM athlete_threshold_proposal WHERE id = :id AND user_id = :userId")
            .param("id", id)
            .param("userId", userId)
            .update()
        return entry
    }

    /** Dismisses [userId]'s pending proposal [id]; [NoSuchElementException] (404) as for [accept]. */
    @Transactional
    fun dismiss(
        userId: Long,
        id: Long,
    ) {
        val updated =
            jdbcClient
                .sql(
                    """
                    UPDATE athlete_threshold_proposal SET status = 'DISMISSED'
                    WHERE id = :id AND user_id = :userId AND status = 'PENDING'
                    """.trimIndent(),
                ).param("id", id)
                .param("userId", userId)
                .update()
        if (updated == 0) throw NoSuchElementException("Threshold proposal not found")
    }

    /**
     * When an athlete's Threshold changes, withdraws their pending proposals of that kind that the value now in force
     * on the proposal's date already matches, within [SAME_VALUE_TOLERANCE]: there is nothing left to suggest.
     */
    @EventListener
    fun onThresholdsChanged(event: ThresholdsChanged) {
        val history = athleteThresholds.history(event.userId)
        pending(event.userId)
            .filter { it.kind == event.kind }
            .filter { proposal ->
                history.valueOf(proposal.kind, proposal.effectiveDate)?.let { sameValue(proposal.value, it) } == true
            }.forEach { proposal ->
                jdbcClient
                    .sql("DELETE FROM athlete_threshold_proposal WHERE id = :id")
                    .param("id", proposal.id)
                    .update()
            }
    }

    private fun findPending(
        userId: Long,
        id: Long,
    ): ThresholdProposal? =
        jdbcClient
            .sql(
                """
                SELECT id, kind, value, effective_date, source FROM athlete_threshold_proposal
                WHERE id = :id AND user_id = :userId AND status = 'PENDING'
                """.trimIndent(),
            ).param("id", id)
            .param("userId", userId)
            .query { rs, _ -> rs.toProposal() }
            .optional()
            .orElse(null)

    private fun ResultSet.toProposal() =
        ThresholdProposal(
            id = getLong("id"),
            kind = ThresholdKind.valueOf(getString("kind")),
            value = getDouble("value"),
            effectiveDate = getObject("effective_date", LocalDate::class.java),
            source = getString("source"),
        )

    companion object {
        /** Two values within this fraction of the reference value count as the same suggestion. */
        const val SAME_VALUE_TOLERANCE = 0.03

        /** Whether [value] is within [SAME_VALUE_TOLERANCE] of [reference], relative to [reference]. */
        fun sameValue(
            value: Double,
            reference: Double,
        ): Boolean = abs(value - reference) <= SAME_VALUE_TOLERANCE * abs(reference)
    }
}
