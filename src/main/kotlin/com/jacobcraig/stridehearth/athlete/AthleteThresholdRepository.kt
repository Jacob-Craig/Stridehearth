package com.jacobcraig.stridehearth.athlete

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate

/**
 * Data access for `athlete_threshold`, an athlete's dated **Threshold** history. Raw SQL through [JdbcClient]; every
 * query is scoped by the athlete, so one athlete's id never reaches another's rows.
 */
@Repository
class AthleteThresholdRepository(
    private val jdbcClient: JdbcClient,
) {
    /** Every entry of [userId], newest effective date first. */
    fun findAll(userId: Long): List<ThresholdEntry> =
        jdbcClient
            .sql(
                """
                SELECT id, kind, effective_date, value FROM athlete_threshold
                WHERE user_id = :userId
                ORDER BY effective_date DESC, kind, id
                """.trimIndent(),
            ).param("userId", userId)
            .query { rs, _ -> rs.toEntry() }
            .list()

    /** [userId]'s entry [id], or null when it does not exist or belongs to another athlete. */
    fun find(
        userId: Long,
        id: Long,
    ): ThresholdEntry? =
        jdbcClient
            .sql("SELECT id, kind, effective_date, value FROM athlete_threshold WHERE id = :id AND user_id = :userId")
            .param("id", id)
            .param("userId", userId)
            .query { rs, _ -> rs.toEntry() }
            .optional()
            .orElse(null)

    /** The earliest effective date of [userId]'s [kind], or null when they have none. */
    fun earliestDate(
        userId: Long,
        kind: ThresholdKind,
    ): LocalDate? =
        jdbcClient
            .sql("SELECT min(effective_date) FROM athlete_threshold WHERE user_id = :userId AND kind = :kind")
            .param("userId", userId)
            .param("kind", kind.name)
            .query(LocalDate::class.java)
            .optional()
            .orElse(null)

    /** Stores [value] for [userId]'s [kind] from [effectiveDate], replacing any value already recorded for that date. */
    fun upsert(
        userId: Long,
        kind: ThresholdKind,
        effectiveDate: LocalDate,
        value: Double,
    ): ThresholdEntry =
        jdbcClient
            .sql(
                """
                INSERT INTO athlete_threshold (user_id, kind, effective_date, value)
                VALUES (:userId, :kind, :effectiveDate, :value)
                ON CONFLICT (user_id, kind, effective_date) DO UPDATE SET value = EXCLUDED.value, created_at = now()
                RETURNING id, kind, effective_date, value
                """.trimIndent(),
            ).param("userId", userId)
            .param("kind", kind.name)
            .param("effectiveDate", effectiveDate)
            .param("value", value)
            .query { rs, _ -> rs.toEntry() }
            .single()

    /** Deletes [userId]'s entry [id]; false when it does not exist or belongs to another athlete. */
    fun delete(
        userId: Long,
        id: Long,
    ): Boolean =
        jdbcClient
            .sql("DELETE FROM athlete_threshold WHERE id = :id AND user_id = :userId")
            .param("id", id)
            .param("userId", userId)
            .update() > 0

    private fun ResultSet.toEntry() =
        ThresholdEntry(
            id = getLong("id"),
            kind = ThresholdKind.valueOf(getString("kind")),
            effectiveDate = getObject("effective_date", LocalDate::class.java),
            value = getDouble("value"),
        )
}
