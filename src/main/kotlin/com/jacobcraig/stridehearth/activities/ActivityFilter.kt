package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport

/**
 * Narrows an **Activity** listing; every criterion is optional. [titleContains] is a case-insensitive,
 * literal substring of the title (the `q` request parameter); blank matches everything.
 */
data class ActivityFilter(
    val titleContains: String? = null,
    val sport: ActivitySport? = null,
    val source: ActivitySource? = null,
)
