package com.jacobcraig.stridehearth.activities

/** An existing **Activity** whose **Activity Details** or **Laps** were upgraded by re-ingestion, and what changed. */
data class ActivityUpgrade(
    val activity: Activity,
    val change: ActivityDetailsChange.Changed,
)

/**
 * The **Import Outcome** of ingesting one activity file: what it did to the athlete's **Activities**.
 *
 * Every variant lists the [duplicates] it skipped. A file that yields no Activities is invalid and has
 * no Import Outcome. Callers map the outcome onto their own vocabulary and never re-derive it.
 */
sealed interface ImportOutcome {
    val duplicates: List<Activity>

    /** Every Activity the file held was new, or already imported unchanged. */
    data class Created(
        val created: List<Activity>,
        override val duplicates: List<Activity> = emptyList(),
    ) : ImportOutcome

    /** The file only upgraded Activities that were already imported (apart from unchanged duplicates). */
    data class Upgraded(
        val upgrades: List<ActivityUpgrade>,
        override val duplicates: List<Activity> = emptyList(),
    ) : ImportOutcome

    /** The file both created new Activities and upgraded already-imported ones. */
    data class Mixed(
        val created: List<Activity>,
        val upgrades: List<ActivityUpgrade>,
        override val duplicates: List<Activity> = emptyList(),
    ) : ImportOutcome

    /** Every Activity in the file had already been imported and nothing changed. */
    data class Duplicate(
        override val duplicates: List<Activity>,
    ) : ImportOutcome

    companion object {
        /** Classifies what one file's ingestion did; null when it touched no Activities at all. */
        internal fun of(
            created: List<Activity>,
            upgrades: List<ActivityUpgrade>,
            duplicates: List<Activity>,
        ): ImportOutcome? =
            when {
                created.isNotEmpty() && upgrades.isNotEmpty() -> Mixed(created, upgrades, duplicates)
                created.isNotEmpty() -> Created(created, duplicates)
                upgrades.isNotEmpty() -> Upgraded(upgrades, duplicates)
                duplicates.isNotEmpty() -> Duplicate(duplicates)
                else -> null
            }
    }
}
