package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.WorkoutType
import com.jacobcraig.stridehearth.system.ActivitySport

/**
 * Narrows an **Activity** listing; every criterion is optional. [titleContains] is a case-insensitive,
 * literal substring of the title (the `q` request parameter); blank matches everything. [workoutType] keeps only Runs
 * classified as that **Workout Type**.
 */
data class ActivityFilter(
    val titleContains: String? = null,
    val sport: ActivitySport? = null,
    val source: ActivitySource? = null,
    val workoutType: WorkoutType? = null,
)
