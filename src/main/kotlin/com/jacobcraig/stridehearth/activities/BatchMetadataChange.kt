package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport

/**
 * A new [title] and/or [sport] to apply to several **Activities** at once. Never empty: at least one of the
 * two is set, and a set [title] is trimmed and non-blank. Build it with [of].
 */
class BatchMetadataChange private constructor(
    val title: String?,
    val sport: ActivitySport?,
) {
    companion object {
        const val NOTHING_TO_APPLY = "Please specify a new title or sport to apply"

        /** The change described by a requested [title] (blank keeps each title) and [sport], or null when it changes nothing. */
        fun of(
            title: String?,
            sport: ActivitySport?,
        ): BatchMetadataChange? {
            val cleanTitle = title?.trim()?.ifEmpty { null }
            return if (cleanTitle == null && sport == null) null else BatchMetadataChange(cleanTitle, sport)
        }
    }
}
