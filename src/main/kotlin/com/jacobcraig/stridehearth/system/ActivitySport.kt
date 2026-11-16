package com.jacobcraig.stridehearth.system

/**
 * The kinds of sport this app understands, independent of any file format.
 *
 * Each parser converts its own format's sport value into one of these. For example,
 * [com.jacobcraig.stridehearth.system.fit.FitActivityParser] converts Garmin's `Sport`
 * enum. Shared domain code then never depends on a particular SDK.
 *
 * An `enum class` is a fixed set of named values. Each value here also carries a [label].
 *
 * @property label the human-readable name used in titles, e.g. `"Run"` in "Morning Run".
 */
enum class ActivitySport(
    val label: String,
) {
    RUN("Run"),
    WALK("Walk"),
    HIKE("Hike"),
    RIDE("Ride"),
    SWIM("Swim"),

    /** Fallback for any sport that isn't recognised or isn't listed above. */
    WORKOUT("Workout"),
}
