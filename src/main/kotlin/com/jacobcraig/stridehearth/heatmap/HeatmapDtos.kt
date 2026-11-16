package com.jacobcraig.stridehearth.heatmap

import io.swagger.v3.oas.annotations.media.Schema

/** The athlete's heatmap revision, an opaque token that changes whenever their heatmap routes do. */
data class HeatmapRevisionDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val revision: String,
)

/** The active Heatmap tile renderer, which the SPA pairs with the `HeatmapLayer` adapter of the same id. */
data class HeatmapRendererDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = RasterDensityTileRenderer.ID)
    val id: String,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description =
            "Tile URL with {revision}, {z}, {x} and {y} placeholders, carrying the renderer and its version; " +
                "Heatmap Filter parameters are appended",
        example = "/api/heatmap/tiles/${RasterDensityTileRenderer.ID}-v${RasterDensityTileRenderer.VERSION}/{revision}/{z}/{x}/{y}",
    )
    val tileUrlTemplate: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The most detailed zoom tiles are generated at")
    val maxZoom: Int,
)

/** Bounds in degrees. */
data class GeoBoundsDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val west: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val south: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val east: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val north: Double,
) {
    constructor(bounds: GeoBounds) : this(bounds.west, bounds.south, bounds.east, bounds.north)
}

/** Where the Heatmap opens: its busiest area; [bounds] is `null` when the Heatmap Filter matches no routes. */
data class HeatmapStartingViewDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["object", "null"])
    val bounds: GeoBoundsDTO?,
)

/** The years with an Activity on the Heatmap, in the requested timezone, newest first: the year shortcuts. */
data class HeatmapYearsDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "[2026, 2025]")
    val years: List<Int>,
)
