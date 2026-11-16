package com.jacobcraig.stridehearth.activities.history

import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.ActivitySport
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/*
 * The wire form of an ActivityChange: discriminated unions on `kind` that the SPA matches exhaustively and renders
 * (ADR-0004: no preformatted text). They mirror the domain types, which stay separate so either can change alone.
 */

/** The single source of the [ActivityChangeDTO.kind] values. */
object ActivityChangeKind {
    const val CREATED = "CREATED"
    const val EDITED = "EDITED"
    const val BATCH_EDITED = "BATCH_EDITED"
    const val UPGRADED = "UPGRADED"
    const val LEGACY = "LEGACY"
}

/** What an **Activity History Entry** (or an Import Batch Item upgrade) records, discriminated by [kind]. */
@Schema(
    oneOf = [
        CreatedChangeDTO::class,
        EditedChangeDTO::class,
        BatchEditedChangeDTO::class,
        UpgradedChangeDTO::class,
        LegacyChangeDTO::class,
    ],
    discriminatorProperty = "kind",
    discriminatorMapping = [
        DiscriminatorMapping(value = ActivityChangeKind.CREATED, schema = CreatedChangeDTO::class),
        DiscriminatorMapping(value = ActivityChangeKind.EDITED, schema = EditedChangeDTO::class),
        DiscriminatorMapping(value = ActivityChangeKind.BATCH_EDITED, schema = BatchEditedChangeDTO::class),
        DiscriminatorMapping(value = ActivityChangeKind.UPGRADED, schema = UpgradedChangeDTO::class),
        DiscriminatorMapping(value = ActivityChangeKind.LEGACY, schema = LegacyChangeDTO::class),
    ],
)
sealed interface ActivityChangeDTO {
    val kind: String
}

/** The Activity was logged by hand or imported; [parserVersion] is null when it had no Activity Details. */
data class CreatedChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val source: ActivitySource,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val parserVersion: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val lapCount: Int,
) : ActivityChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [ActivityChangeKind.CREATED])
    override val kind: String = ActivityChangeKind.CREATED
}

/** The athlete edited this one Activity. */
data class EditedChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val fields: List<FieldChangeDTO>,
) : ActivityChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [ActivityChangeKind.EDITED])
    override val kind: String = ActivityChangeKind.EDITED
}

/** The athlete edited this Activity in a batch edit. */
data class BatchEditedChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val fields: List<FieldChangeDTO>,
) : ActivityChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [ActivityChangeKind.BATCH_EDITED])
    override val kind: String = ActivityChangeKind.BATCH_EDITED
}

/** Re-ingestion upgraded or backfilled the Activity Details, the Laps, or both (at least one is set). */
data class UpgradedChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["object", "null"])
    val details: DetailsUpgradeDTO?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["object", "null"])
    val laps: LapsUpgradeDTO?,
) : ActivityChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [ActivityChangeKind.UPGRADED])
    override val kind: String = ActivityChangeKind.UPGRADED
}

/** A change recorded as free text before typed changes existed, with the [changeType] it was recorded under. */
data class LegacyChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val changeType: ActivityChangeType,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val description: String,
) : ActivityChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [ActivityChangeKind.LEGACY])
    override val kind: String = ActivityChangeKind.LEGACY
}

/** The single source of the [DetailsUpgradeDTO.kind] values. */
object DetailsUpgradeKind {
    const val BACKFILLED = "BACKFILLED"
    const val PARSER_VERSION_UPGRADED = "PARSER_VERSION_UPGRADED"
    const val DISTANCE_BACKFILLED = "DISTANCE_BACKFILLED"
}

/** How an upgrade wrote the **Activity Details**, discriminated by [kind]. */
@Schema(
    oneOf = [BackfilledDetailsDTO::class, ParserVersionUpgradedDetailsDTO::class, DistanceBackfilledDetailsDTO::class],
    discriminatorProperty = "kind",
    discriminatorMapping = [
        DiscriminatorMapping(value = DetailsUpgradeKind.BACKFILLED, schema = BackfilledDetailsDTO::class),
        DiscriminatorMapping(value = DetailsUpgradeKind.PARSER_VERSION_UPGRADED, schema = ParserVersionUpgradedDetailsDTO::class),
        DiscriminatorMapping(value = DetailsUpgradeKind.DISTANCE_BACKFILLED, schema = DistanceBackfilledDetailsDTO::class),
    ],
)
sealed interface DetailsUpgradeDTO {
    val kind: String
}

/** There were no stored Activity Details; [parserVersion] decoded the ones now stored. */
data class BackfilledDetailsDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val parserVersion: Int,
) : DetailsUpgradeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [DetailsUpgradeKind.BACKFILLED])
    override val kind: String = DetailsUpgradeKind.BACKFILLED
}

/** The stored Activity Details came from Parser Version [from] and now come from the newer [to]. */
data class ParserVersionUpgradedDetailsDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val from: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val to: Int,
) : DetailsUpgradeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [DetailsUpgradeKind.PARSER_VERSION_UPGRADED])
    override val kind: String = DetailsUpgradeKind.PARSER_VERSION_UPGRADED
}

/** The stored Activity Details lacked distance; [parserVersion] supplied it. */
data class DistanceBackfilledDetailsDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val parserVersion: Int,
) : DetailsUpgradeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [DetailsUpgradeKind.DISTANCE_BACKFILLED])
    override val kind: String = DetailsUpgradeKind.DISTANCE_BACKFILLED
}

/** The single source of the [LapsUpgradeDTO.kind] values. */
object LapsUpgradeKind {
    const val BACKFILLED = "BACKFILLED"
    const val REPLACED = "REPLACED"
}

/** How an upgrade wrote the **Laps**, discriminated by [kind]; [count] Laps are stored now. */
@Schema(
    oneOf = [BackfilledLapsDTO::class, ReplacedLapsDTO::class],
    discriminatorProperty = "kind",
    discriminatorMapping = [
        DiscriminatorMapping(value = LapsUpgradeKind.BACKFILLED, schema = BackfilledLapsDTO::class),
        DiscriminatorMapping(value = LapsUpgradeKind.REPLACED, schema = ReplacedLapsDTO::class),
    ],
)
sealed interface LapsUpgradeDTO {
    val kind: String
    val count: Int
}

/** There were no stored Laps and nothing else changed. */
data class BackfilledLapsDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    override val count: Int,
) : LapsUpgradeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [LapsUpgradeKind.BACKFILLED])
    override val kind: String = LapsUpgradeKind.BACKFILLED
}

/** The Laps were written together with the Activity Details, replacing any stored ones. */
data class ReplacedLapsDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    override val count: Int,
) : LapsUpgradeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [LapsUpgradeKind.REPLACED])
    override val kind: String = LapsUpgradeKind.REPLACED
}

/** The single source of the [FieldChangeDTO.kind] values. */
object FieldChangeKind {
    const val TITLE = "TITLE"
    const val SPORT = "SPORT"
    const val STARTED_AT = "STARTED_AT"
    const val DISTANCE = "DISTANCE"
    const val TIME_TAKEN = "TIME_TAKEN"
    const val AVERAGE_PACE = "AVERAGE_PACE"
    const val AVERAGE_HEART_RATE = "AVERAGE_HEART_RATE"
    const val CALORIES = "CALORIES"
}

/** One field an edit changed, discriminated by [kind], in the units of the Activity's own fields. */
@Schema(
    oneOf = [
        TitleChangeDTO::class,
        SportChangeDTO::class,
        StartedAtChangeDTO::class,
        DistanceChangeDTO::class,
        TimeTakenChangeDTO::class,
        AveragePaceChangeDTO::class,
        AverageHeartRateChangeDTO::class,
        CaloriesChangeDTO::class,
    ],
    discriminatorProperty = "kind",
    discriminatorMapping = [
        DiscriminatorMapping(value = FieldChangeKind.TITLE, schema = TitleChangeDTO::class),
        DiscriminatorMapping(value = FieldChangeKind.SPORT, schema = SportChangeDTO::class),
        DiscriminatorMapping(value = FieldChangeKind.STARTED_AT, schema = StartedAtChangeDTO::class),
        DiscriminatorMapping(value = FieldChangeKind.DISTANCE, schema = DistanceChangeDTO::class),
        DiscriminatorMapping(value = FieldChangeKind.TIME_TAKEN, schema = TimeTakenChangeDTO::class),
        DiscriminatorMapping(value = FieldChangeKind.AVERAGE_PACE, schema = AveragePaceChangeDTO::class),
        DiscriminatorMapping(value = FieldChangeKind.AVERAGE_HEART_RATE, schema = AverageHeartRateChangeDTO::class),
        DiscriminatorMapping(value = FieldChangeKind.CALORIES, schema = CaloriesChangeDTO::class),
    ],
)
sealed interface FieldChangeDTO {
    val kind: String
}

data class TitleChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val from: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val to: String,
) : FieldChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [FieldChangeKind.TITLE])
    override val kind: String = FieldChangeKind.TITLE
}

data class SportChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val from: ActivitySport,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val to: ActivitySport,
) : FieldChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [FieldChangeKind.SPORT])
    override val kind: String = FieldChangeKind.SPORT
}

data class StartedAtChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["string", "null"], format = "date-time")
    val from: Instant?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["string", "null"], format = "date-time")
    val to: Instant?,
) : FieldChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [FieldChangeKind.STARTED_AT])
    override val kind: String = FieldChangeKind.STARTED_AT
}

/** Kilometers. */
data class DistanceChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"], description = "Distance in kilometers")
    val from: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"], description = "Distance in kilometers")
    val to: Double?,
) : FieldChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [FieldChangeKind.DISTANCE])
    override val kind: String = FieldChangeKind.DISTANCE
}

/** Seconds. */
data class TimeTakenChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"], description = "Elapsed seconds")
    val from: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"], description = "Elapsed seconds")
    val to: Int?,
) : FieldChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [FieldChangeKind.TIME_TAKEN])
    override val kind: String = FieldChangeKind.TIME_TAKEN
}

/** Average speed in km/h. */
data class AveragePaceChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"], description = "Average speed in km/h")
    val from: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"], description = "Average speed in km/h")
    val to: Double?,
) : FieldChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [FieldChangeKind.AVERAGE_PACE])
    override val kind: String = FieldChangeKind.AVERAGE_PACE
}

/** Beats per minute. */
data class AverageHeartRateChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val from: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val to: Int?,
) : FieldChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [FieldChangeKind.AVERAGE_HEART_RATE])
    override val kind: String = FieldChangeKind.AVERAGE_HEART_RATE
}

data class CaloriesChangeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val from: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val to: Int?,
) : FieldChangeDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [FieldChangeKind.CALORIES])
    override val kind: String = FieldChangeKind.CALORIES
}

/** One **Activity History Entry**: when it was recorded and what [change] it records. */
data class ActivityHistoryEntryDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val createdAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val change: ActivityChangeDTO,
)

fun HistoryEntry.toDto(): ActivityHistoryEntryDTO =
    ActivityHistoryEntryDTO(
        id = id,
        createdAt = createdAt,
        change = change?.toDto() ?: legacyChangeDto(changeType, checkNotNull(legacyDescription)),
    )

/** A change written as free text before typed changes existed: served as it was stored, never parsed. */
internal fun legacyChangeDto(
    changeType: ActivityChangeType,
    description: String,
): ActivityChangeDTO = LegacyChangeDTO(changeType, description)

fun ActivityChange.toDto(): ActivityChangeDTO =
    when (this) {
        is ActivityChange.Created -> CreatedChangeDTO(source, parserVersion?.toInt(), lapCount)
        is ActivityChange.Edited -> EditedChangeDTO(fields.map { it.toDto() })
        is ActivityChange.BatchEdited -> BatchEditedChangeDTO(fields.map { it.toDto() })
        is ActivityChange.Upgraded -> UpgradedChangeDTO(details?.toDto(), laps?.toDto())
    }

private fun DetailsUpgrade.toDto(): DetailsUpgradeDTO =
    when (this) {
        is DetailsUpgrade.Backfilled -> BackfilledDetailsDTO(parserVersion.toInt())
        is DetailsUpgrade.ParserVersionUpgraded -> ParserVersionUpgradedDetailsDTO(from.toInt(), to.toInt())
        is DetailsUpgrade.DistanceBackfilled -> DistanceBackfilledDetailsDTO(parserVersion.toInt())
    }

private fun LapsUpgrade.toDto(): LapsUpgradeDTO =
    when (this) {
        is LapsUpgrade.Backfilled -> BackfilledLapsDTO(count)
        is LapsUpgrade.Replaced -> ReplacedLapsDTO(count)
    }

private fun FieldChange.toDto(): FieldChangeDTO =
    when (this) {
        is FieldChange.Title -> TitleChangeDTO(from, to)
        is FieldChange.Sport -> SportChangeDTO(from, to)
        is FieldChange.StartedAt -> StartedAtChangeDTO(from, to)
        is FieldChange.Distance -> DistanceChangeDTO(from, to)
        is FieldChange.TimeTaken -> TimeTakenChangeDTO(from, to)
        is FieldChange.AveragePace -> AveragePaceChangeDTO(from, to)
        is FieldChange.AverageHeartRate -> AverageHeartRateChangeDTO(from, to)
        is FieldChange.Calories -> CaloriesChangeDTO(from, to)
    }
