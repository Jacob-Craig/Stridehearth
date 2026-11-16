package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ParserVersion

/**
 * What an offer of newly parsed **Activity Details** and **Laps** did to one **Activity**
 * (see [ActivityDetailsService.offer]). Callers branch on it and never re-derive it.
 */
sealed interface ActivityDetailsChange {
    /** How the Activity Details were written, or `null` when they were left alone. */
    val details: DetailsChange?

    /** How the Laps were written, or `null` when they were left alone. */
    val laps: LapsChange?

    /** Whether the offered Activity Details were written (and so are what Best Efforts are detected from). */
    val detailsWritten: Boolean get() = details != null

    /** Nothing was written. */
    data object Unchanged : ActivityDetailsChange {
        override val details: DetailsChange? get() = null
        override val laps: LapsChange? get() = null
    }

    /** At least one of [details] and [laps] was written; an absent part was left alone. */
    data class Changed(
        override val details: DetailsChange?,
        override val laps: LapsChange?,
    ) : ActivityDetailsChange {
        init {
            require(details != null || laps != null) { "A change writes Activity Details, Laps or both" }
        }
    }
}

/** How an offer wrote an Activity's **Activity Details**: an **Upgrade** or a **Backfill**. */
sealed interface DetailsChange {
    /** The Parser Version that decoded the details now stored. */
    val version: ParserVersion

    /** There were no stored Activity Details (a **Backfill**). */
    data class Backfilled(
        override val version: ParserVersion,
    ) : DetailsChange

    /** The stored details came from the older Parser Version [from] (an **Upgrade**). */
    data class Upgraded(
        val from: ParserVersion,
        val to: ParserVersion,
    ) : DetailsChange {
        override val version: ParserVersion get() = to
    }

    /** The stored details lacked distance and the offered ones, from a version that is not older, have it. */
    data class DistanceBackfilled(
        override val version: ParserVersion,
    ) : DetailsChange
}

/** How an offer wrote an Activity's **Laps**: a **Backfill**, or a replacement alongside the Activity Details. */
sealed interface LapsChange {
    /** How many Laps are now stored. */
    val count: Int

    /** No Laps were stored and nothing else changed. */
    data class Backfilled(
        override val count: Int,
    ) : LapsChange

    /** The Laps were written together with the Activity Details, replacing any stored ones. */
    data class Replaced(
        override val count: Int,
    ) : LapsChange
}
