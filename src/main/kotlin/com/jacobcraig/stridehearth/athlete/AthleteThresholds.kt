package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.api.RequestValidationException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * An athlete's dated **Thresholds**: recording, deleting and the as-of lookup that derived metrics use. Every write
 * publishes [ThresholdsChanged] in its transaction, so derived data that depends on the Threshold can be recomputed.
 * Nothing else writes entries: a **Threshold Proposal** becomes one only through [record], once the athlete accepts it.
 */
@Service
class AthleteThresholds(
    private val repository: AthleteThresholdRepository,
    private val events: ApplicationEventPublisher,
) {
    /** [userId]'s whole history, newest first. */
    @Transactional(readOnly = true)
    fun entries(userId: Long): List<ThresholdEntry> = repository.findAll(userId)

    /** [userId]'s history, for resolving many dates against one read. */
    @Transactional(readOnly = true)
    fun history(userId: Long): ThresholdHistory = ThresholdHistory(repository.findAll(userId))

    /** The Thresholds in force for [userId] on the athlete-local [date] (see [ThresholdHistory]). */
    @Transactional(readOnly = true)
    fun asOf(
        userId: Long,
        date: LocalDate,
    ): ThresholdsOn = history(userId).asOf(date)

    /** [asOf] for each of [dates], reading [userId]'s history once. */
    @Transactional(readOnly = true)
    fun asOfMany(
        userId: Long,
        dates: Collection<LocalDate>,
    ): Map<LocalDate, ThresholdsOn> = history(userId).asOfMany(dates)

    /**
     * Records [value] for [userId]'s [kind] from [effectiveDate], replacing a value already recorded for that date.
     * The value must be in the kind's range and the date no later than tomorrow (UTC), else [RequestValidationException].
     */
    @Transactional
    fun record(
        userId: Long,
        kind: ThresholdKind,
        effectiveDate: LocalDate,
        value: Double,
    ): ThresholdEntry {
        validate(kind, effectiveDate, value)
        val earliest = repository.earliestDate(userId, kind)
        val entry = repository.upsert(userId, kind, effectiveDate, value)
        val isEarliest = earliest == null || !effectiveDate.isAfter(earliest)
        events.publishEvent(ThresholdsChanged(userId, kind, if (isEarliest) null else effectiveDate))
        return entry
    }

    /** Deletes [userId]'s entry [id]; [NoSuchElementException] (404) when it is missing or another athlete's. */
    @Transactional
    fun delete(
        userId: Long,
        id: Long,
    ) {
        val entry = repository.find(userId, id) ?: throw NoSuchElementException("Threshold entry not found")
        val wasEarliest = repository.earliestDate(userId, entry.kind) == entry.effectiveDate
        repository.delete(userId, id)
        events.publishEvent(ThresholdsChanged(userId, entry.kind, if (wasEarliest) null else entry.effectiveDate))
    }

    private fun validate(
        kind: ThresholdKind,
        effectiveDate: LocalDate,
        value: Double,
    ) {
        val errors = mutableMapOf<String, String>()
        if (!isValidValue(kind, value)) {
            errors["value"] = "Must be between ${kind.validRange.start} and ${kind.validRange.endInclusive} ${kind.unit}"
        }
        if (!isValidDate(effectiveDate)) {
            errors["effectiveDate"] = "Must not be later than tomorrow"
        }
        if (errors.isNotEmpty()) throw RequestValidationException(errors)
    }

    companion object {
        /** Whether [record] would accept [value] for [kind] from [effectiveDate]. */
        fun isAcceptable(
            kind: ThresholdKind,
            effectiveDate: LocalDate,
            value: Double,
        ): Boolean = isValidValue(kind, value) && isValidDate(effectiveDate)

        private fun isValidValue(
            kind: ThresholdKind,
            value: Double,
        ) = value.isFinite() && value in kind.validRange

        private fun isValidDate(effectiveDate: LocalDate) = !effectiveDate.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))
    }
}
