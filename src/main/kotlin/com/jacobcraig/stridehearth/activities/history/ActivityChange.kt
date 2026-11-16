package com.jacobcraig.stridehearth.activities.history

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.jacobcraig.stridehearth.activities.ActivityDetailsChange
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.DetailsChange
import com.jacobcraig.stridehearth.activities.LapsChange
import com.jacobcraig.stridehearth.system.ActivitySport
import java.time.Instant

/**
 * What one **Activity History Entry** records about a change to an **Activity**, as facts rather than wording
 * (the SPA renders them, ADR-0004).
 *
 * Stored as jsonb in `activity_history.change` and `import_batch_item.upgrades`, so every `kind` name below is a
 * persisted contract from the first row onward: never rename one. Parser Versions are stored as plain numbers.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ActivityChange.Created::class, name = "CREATED"),
    JsonSubTypes.Type(value = ActivityChange.Edited::class, name = "EDITED"),
    JsonSubTypes.Type(value = ActivityChange.BatchEdited::class, name = "BATCH_EDITED"),
    JsonSubTypes.Type(value = ActivityChange.Upgraded::class, name = "UPGRADED"),
)
sealed interface ActivityChange {
    /** The Activity was logged by hand, or imported with [parserVersion] (null when it had no Activity Details) and [lapCount] Laps. */
    data class Created(
        val source: ActivitySource,
        val parserVersion: Short?,
        val lapCount: Int,
    ) : ActivityChange {
        init {
            require(lapCount >= 0) { "A Lap count is never negative" }
        }

        companion object {
            /** A hand-logged Activity: no Activity Details and no Laps. */
            val MANUAL = Created(ActivitySource.MANUAL, parserVersion = null, lapCount = 0)

            /** A new Activity imported from [source], holding what the creating offer [change] stored. */
            fun of(
                source: ActivitySource,
                change: ActivityDetailsChange,
            ): Created = Created(source, change.details?.version?.value, change.laps?.count ?: 0)
        }
    }

    /** The athlete edited this one Activity. */
    data class Edited(
        val fields: List<FieldChange>,
    ) : ActivityChange {
        init {
            require(fields.isNotEmpty()) { "An edit changes at least one field" }
        }
    }

    /** The athlete edited this Activity as part of a batch edit. */
    data class BatchEdited(
        val fields: List<FieldChange>,
    ) : ActivityChange {
        init {
            require(fields.isNotEmpty()) { "A batch edit changes at least one field" }
        }
    }

    /** Re-ingesting the Activity's file upgraded or backfilled its Activity Details, its Laps, or both. */
    data class Upgraded(
        val details: DetailsUpgrade?,
        val laps: LapsUpgrade?,
    ) : ActivityChange {
        init {
            require(details != null || laps != null) { "An upgrade changes Activity Details, Laps or both" }
        }

        companion object {
            /** The one mapping from what an offer did to an already-imported Activity. */
            fun of(change: ActivityDetailsChange.Changed): Upgraded =
                Upgraded(
                    details =
                        when (val details = change.details) {
                            is DetailsChange.Backfilled -> DetailsUpgrade.Backfilled(details.version.value)
                            is DetailsChange.Upgraded -> DetailsUpgrade.ParserVersionUpgraded(details.from.value, details.to.value)
                            is DetailsChange.DistanceBackfilled -> DetailsUpgrade.DistanceBackfilled(details.version.value)
                            null -> null
                        },
                    laps =
                        when (val laps = change.laps) {
                            is LapsChange.Backfilled -> LapsUpgrade.Backfilled(laps.count)
                            is LapsChange.Replaced -> LapsUpgrade.Replaced(laps.count)
                            null -> null
                        },
                )
        }
    }
}

/** The stored `activity_history.change_type`, an index column derived from the variant. Callers never choose it. */
val ActivityChange.changeType: ActivityChangeType
    get() =
        when (this) {
            is ActivityChange.Created -> ActivityChangeType.CREATED
            is ActivityChange.Edited -> ActivityChangeType.EDITED
            is ActivityChange.BatchEdited -> ActivityChangeType.BATCH_EDITED
            is ActivityChange.Upgraded -> ActivityChangeType.UPGRADED
        }

/** How an upgrade wrote an Activity's **Activity Details**: a **Backfill** or an **Upgrade**. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = DetailsUpgrade.Backfilled::class, name = "BACKFILLED"),
    JsonSubTypes.Type(value = DetailsUpgrade.ParserVersionUpgraded::class, name = "PARSER_VERSION_UPGRADED"),
    JsonSubTypes.Type(value = DetailsUpgrade.DistanceBackfilled::class, name = "DISTANCE_BACKFILLED"),
)
sealed interface DetailsUpgrade {
    /** There were no stored Activity Details; [parserVersion] decoded the ones now stored. */
    data class Backfilled(
        val parserVersion: Short,
    ) : DetailsUpgrade

    /** The stored Activity Details came from the older Parser Version [from] and now come from [to]. */
    data class ParserVersionUpgraded(
        val from: Short,
        val to: Short,
    ) : DetailsUpgrade {
        init {
            require(from < to) { "An upgrade moves to a newer Parser Version, not from $from to $to" }
        }
    }

    /** The stored Activity Details lacked distance; [parserVersion] (not older than the stored one) supplied it. */
    data class DistanceBackfilled(
        val parserVersion: Short,
    ) : DetailsUpgrade
}

/** How an upgrade wrote an Activity's **Laps**. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = LapsUpgrade.Backfilled::class, name = "BACKFILLED"),
    JsonSubTypes.Type(value = LapsUpgrade.Replaced::class, name = "REPLACED"),
)
sealed interface LapsUpgrade {
    /** There were no stored Laps and nothing else changed; [count] are stored now. */
    data class Backfilled(
        val count: Int,
    ) : LapsUpgrade {
        init {
            require(count > 0) { "A Laps upgrade stores at least one Lap" }
        }
    }

    /** The Laps were written together with the Activity Details, replacing any stored ones; [count] are stored now. */
    data class Replaced(
        val count: Int,
    ) : LapsUpgrade {
        init {
            require(count > 0) { "A Laps upgrade stores at least one Lap" }
        }
    }
}

/**
 * One field an edit changed, from [from] to [to], in the same units as the `Activity` columns: one variant per
 * editable field, so each value keeps its type through the jsonb round trip.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = FieldChange.Title::class, name = "TITLE"),
    JsonSubTypes.Type(value = FieldChange.Sport::class, name = "SPORT"),
    JsonSubTypes.Type(value = FieldChange.StartedAt::class, name = "STARTED_AT"),
    JsonSubTypes.Type(value = FieldChange.Distance::class, name = "DISTANCE"),
    JsonSubTypes.Type(value = FieldChange.TimeTaken::class, name = "TIME_TAKEN"),
    JsonSubTypes.Type(value = FieldChange.AveragePace::class, name = "AVERAGE_PACE"),
    JsonSubTypes.Type(value = FieldChange.AverageHeartRate::class, name = "AVERAGE_HEART_RATE"),
    JsonSubTypes.Type(value = FieldChange.Calories::class, name = "CALORIES"),
)
sealed interface FieldChange {
    data class Title(
        val from: String,
        val to: String,
    ) : FieldChange

    data class Sport(
        val from: ActivitySport,
        val to: ActivitySport,
    ) : FieldChange

    data class StartedAt(
        val from: Instant?,
        val to: Instant?,
    ) : FieldChange

    /** Kilometers. */
    data class Distance(
        val from: Double?,
        val to: Double?,
    ) : FieldChange

    /** Seconds. */
    data class TimeTaken(
        val from: Int?,
        val to: Int?,
    ) : FieldChange

    /** Average speed in km/h. */
    data class AveragePace(
        val from: Double?,
        val to: Double?,
    ) : FieldChange

    /** Beats per minute. */
    data class AverageHeartRate(
        val from: Int?,
        val to: Int?,
    ) : FieldChange

    data class Calories(
        val from: Int?,
        val to: Int?,
    ) : FieldChange
}
