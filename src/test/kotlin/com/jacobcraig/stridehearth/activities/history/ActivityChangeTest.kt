package com.jacobcraig.stridehearth.activities.history

import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityDetailsChange
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.DetailsChange
import com.jacobcraig.stridehearth.activities.LapsChange
import com.jacobcraig.stridehearth.activities.ProtectedMetricValues
import com.jacobcraig.stridehearth.activities.ProtectedMetrics
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.users.User
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals

/** The [ActivityChange] types as pure values: their invariants, factories, and the field changes an edit yields. */
class ActivityChangeTest {
    private val v1 = ParserVersion(1)
    private val v2 = ParserVersion(2)

    @Test
    fun `invalid changes are rejected at construction`() {
        assertThrows<IllegalArgumentException> { ActivityChange.Edited(emptyList()) }
        assertThrows<IllegalArgumentException> { ActivityChange.BatchEdited(emptyList()) }
        assertThrows<IllegalArgumentException> { ActivityChange.Upgraded(details = null, laps = null) }
        assertThrows<IllegalArgumentException> { DetailsUpgrade.ParserVersionUpgraded(from = 2, to = 2) }
        assertThrows<IllegalArgumentException> { DetailsUpgrade.ParserVersionUpgraded(from = 3, to = 2) }
        assertThrows<IllegalArgumentException> { LapsUpgrade.Backfilled(0) }
        assertThrows<IllegalArgumentException> { LapsUpgrade.Replaced(0) }
        assertThrows<IllegalArgumentException> { ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = 1, lapCount = -1) }
    }

    @Test
    fun `Upgraded of maps every Activity Details and Laps change`() {
        val cases =
            listOf(
                ActivityDetailsChange.Changed(DetailsChange.Backfilled(v1), null) to
                    ActivityChange.Upgraded(DetailsUpgrade.Backfilled(1), null),
                ActivityDetailsChange.Changed(DetailsChange.Upgraded(v1, v2), LapsChange.Replaced(3)) to
                    ActivityChange.Upgraded(DetailsUpgrade.ParserVersionUpgraded(1, 2), LapsUpgrade.Replaced(3)),
                ActivityDetailsChange.Changed(DetailsChange.DistanceBackfilled(v1), null) to
                    ActivityChange.Upgraded(DetailsUpgrade.DistanceBackfilled(1), null),
                ActivityDetailsChange.Changed(null, LapsChange.Backfilled(1)) to
                    ActivityChange.Upgraded(null, LapsUpgrade.Backfilled(1)),
            )

        cases.forEach { (change, expected) -> assertEquals(expected, ActivityChange.Upgraded.of(change)) }
    }

    @Test
    fun `Created of records the Parser Version and Lap count the creating offer stored`() {
        assertEquals(
            ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = 2, lapCount = 3),
            ActivityChange.Created.of(
                ActivitySource.FIT_UPLOAD,
                ActivityDetailsChange.Changed(DetailsChange.Backfilled(v2), LapsChange.Replaced(3)),
            ),
        )
        assertEquals(
            ActivityChange.Created(ActivitySource.GARMIN, parserVersion = null, lapCount = 2),
            ActivityChange.Created.of(ActivitySource.GARMIN, ActivityDetailsChange.Changed(null, LapsChange.Backfilled(2))),
        )
        assertEquals(
            ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = null, lapCount = 0),
            ActivityChange.Created.of(ActivitySource.FIT_UPLOAD, ActivityDetailsChange.Unchanged),
        )
        assertEquals(ActivityChange.Created(ActivitySource.MANUAL, parserVersion = null, lapCount = 0), ActivityChange.Created.MANUAL)
    }

    @Test
    fun `each variant derives its stored change type`() {
        val title = FieldChange.Title("A", "B")
        assertEquals(ActivityChangeType.CREATED, ActivityChange.Created.MANUAL.changeType)
        assertEquals(ActivityChangeType.EDITED, ActivityChange.Edited(listOf(title)).changeType)
        assertEquals(ActivityChangeType.BATCH_EDITED, ActivityChange.BatchEdited(listOf(title)).changeType)
        assertEquals(ActivityChangeType.UPGRADED, ActivityChange.Upgraded(null, LapsUpgrade.Backfilled(1)).changeType)
    }

    private fun activity() =
        Activity(
            title = "Morning Run",
            sport = ActivitySport.RUN,
            startedAt = Instant.parse("2026-01-01T08:00:00Z"),
            distance = 5.0,
            timeTakenSeconds = 1500,
            user = User(id = 1, email = "u@example.com", passwordHash = "x", username = "u"),
            averagePaceKmh = 12.0,
            averageHeartRate = 150,
            calories = null,
            source = ActivitySource.MANUAL,
        )

    @Test
    fun `applyMetadataChanges returns the title and sport changes it applied`() {
        val activity = activity()

        assertEquals(
            listOf(FieldChange.Title("Morning Run", "Evening Ride"), FieldChange.Sport(ActivitySport.RUN, ActivitySport.RIDE)),
            activity.applyMetadataChanges("  Evening Ride ", ActivitySport.RIDE),
        )
        assertEquals(emptyList(), activity.applyMetadataChanges("Evening Ride", ActivitySport.RIDE))
        assertEquals(emptyList(), activity.applyMetadataChanges("   ", null))
    }

    @Test
    fun `applyProtectedMetricChanges returns one typed change per changed Protected Metric, in display order`() {
        val activity = activity()
        val replaced =
            ProtectedMetrics(
                startedAt = Instant.parse("2026-01-02T08:00:00Z"),
                distance = 10.0,
                timeTakenSeconds = 3000,
                averagePaceKmh = null,
                averageHeartRate = 155,
                calories = 600,
            )

        val changes = activity.applyProtectedMetricChanges(replaced)

        assertEquals(
            listOf(
                FieldChange.StartedAt(Instant.parse("2026-01-01T08:00:00Z"), Instant.parse("2026-01-02T08:00:00Z")),
                FieldChange.Distance(5.0, 10.0),
                FieldChange.TimeTaken(1500, 3000),
                FieldChange.AveragePace(12.0, null),
                FieldChange.AverageHeartRate(150, 155),
                FieldChange.Calories(null, 600),
            ),
            changes,
        )
        assertEquals(ProtectedMetricValues.ALL.size, changes.size, "one variant per Protected Metric")
        assertEquals(emptyList(), activity.applyProtectedMetricChanges(replaced))
    }
}
