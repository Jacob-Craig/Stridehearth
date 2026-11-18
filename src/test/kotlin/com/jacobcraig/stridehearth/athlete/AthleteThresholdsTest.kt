package com.jacobcraig.stridehearth.athlete

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The as-of lookup over a dated **Threshold** history: per kind, the latest entry on or before the date, else the
 * earliest entry (it applies backwards), else null. LTHR history: 2024-01-01 = 160, 2024-06-01 = 165,
 * 2025-01-01 = 170.
 */
class AthleteThresholdsTest {
    private fun entry(
        kind: ThresholdKind,
        date: String,
        value: Double,
    ) = ThresholdEntry(id = 0, kind = kind, effectiveDate = LocalDate.parse(date), value = value)

    private val history =
        ThresholdHistory(
            listOf(
                entry(ThresholdKind.LTHR, "2025-01-01", 170.0),
                entry(ThresholdKind.LTHR, "2024-01-01", 160.0),
                entry(ThresholdKind.LTHR, "2024-06-01", 165.0),
                entry(ThresholdKind.MAX_HR, "2024-09-01", 190.0),
            ),
        )

    private fun lthrOn(date: String) = history.asOf(LocalDate.parse(date)).lthr

    @Test
    fun `a date between two entries takes the earlier one`() {
        assertEquals(160.0, lthrOn("2024-03-15"))
    }

    @Test
    fun `an entry applies from its own date`() {
        assertEquals(165.0, lthrOn("2024-06-01"))
        assertEquals(160.0, lthrOn("2024-05-31"))
    }

    @Test
    fun `a date after the last entry takes the last one`() {
        assertEquals(170.0, lthrOn("2025-06-01"))
    }

    @Test
    fun `a date before every entry takes the earliest one`() {
        assertEquals(160.0, lthrOn("2023-05-01"))
    }

    @Test
    fun `a kind with no entries is null`() {
        val on = history.asOf(LocalDate.parse("2024-12-01"))

        assertNull(on.restingHr)
        assertNull(on.thresholdSpeedMps)
        assertNull(on.criticalPowerWatts)
    }

    @Test
    fun `kinds resolve independently`() {
        // MAX_HR starts later than LTHR; each kind falls back to its own earliest entry.
        val early = history.asOf(LocalDate.parse("2024-03-15"))
        val late = history.asOf(LocalDate.parse("2025-06-01"))

        assertEquals(160.0, early.lthr)
        assertEquals(190.0, early.maxHr)
        assertEquals(170.0, late.lthr)
        assertEquals(190.0, late.maxHr)
    }

    @Test
    fun `an empty history gives no thresholds on any date`() {
        assertEquals(ThresholdsOn.NONE, ThresholdHistory(emptyList()).asOf(LocalDate.parse("2024-01-01")))
    }

    @Test
    fun `many dates resolve against one history`() {
        val dates = listOf("2023-05-01", "2024-03-15", "2024-06-01", "2025-06-01").map(LocalDate::parse)

        val resolved = history.asOfMany(dates)

        assertEquals(listOf(160.0, 160.0, 165.0, 170.0), dates.map { resolved.getValue(it).lthr })
    }

    @Test
    fun `each kind knows its valid range`() {
        assertEquals(100.0..250.0, ThresholdKind.MAX_HR.validRange)
        assertEquals(25.0..120.0, ThresholdKind.RESTING_HR.validRange)
        assertEquals(60.0..240.0, ThresholdKind.LTHR.validRange)
        assertEquals(1.0..8.0, ThresholdKind.THRESHOLD_PACE.validRange)
        assertEquals(50.0..700.0, ThresholdKind.CRITICAL_POWER.validRange)
    }
}
