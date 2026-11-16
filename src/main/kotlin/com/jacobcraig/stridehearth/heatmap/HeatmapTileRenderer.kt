package com.jacobcraig.stridehearth.heatmap

import com.jacobcraig.stridehearth.system.ActivitySport
import java.time.Instant

/**
 * The **Heatmap Filter**: which of an athlete's Activities make up a **Heatmap**, by start time and sport.
 *
 * @property from inclusive lower bound on the Activity's start time; `null` leaves the range open.
 * @property to exclusive upper bound on the Activity's start time; `null` leaves the range open.
 * @property sports the sports to include; `null` means every sport, and an empty set matches nothing.
 */
data class HeatmapFilter(
    val from: Instant? = null,
    val to: Instant? = null,
    val sports: Set<ActivitySport>? = null,
) {
    companion object {
        /** All time and all sports. */
        val ALL = HeatmapFilter()
    }
}

/** One athlete's **Heatmap** as a **Heatmap Filter** chooses it: whose routes, and which of them. */
data class HeatmapScope(
    val athleteId: Long,
    val filter: HeatmapFilter = HeatmapFilter.ALL,
)

/** A slippy-map (XYZ, Web Mercator) tile address; tiles are generated up to [MAX_ZOOM]. */
data class TileCoordinate(
    val z: Int,
    val x: Int,
    val y: Int,
) {
    init {
        require(z in 0..MAX_ZOOM) { "Zoom must be between 0 and $MAX_ZOOM" }
        require(x in 0 until (1 shl z) && y in 0 until (1 shl z)) { "Tile $x/$y does not exist at zoom $z" }
    }

    companion object {
        /** MapLibre enlarges zoom-16 tiles beyond this (ADR-0007, ADR-0008). */
        const val MAX_ZOOM = 16
    }
}

/** A rendered tile's bytes and their media type. */
class RenderedTile(
    val bytes: ByteArray,
    val mediaType: String,
)

/**
 * The **Heatmap** tile rendering strategy (ADR-0007, ADR-0008): renders one tile of one athlete's Heatmap under a
 * **Heatmap Filter**, a [HeatmapScope]. The active renderer is chosen by `stridehearth.heatmap.renderer`, and the SPA pairs it
 * with the `HeatmapLayer` adapter of the same [id].
 *
 * Renderers keep no server-side tile cache in v1, deliberately. Tile URLs carry the athlete's heatmap revision
 * and are served `private, immutable`, so the browser cache is correct by construction; a server cache would
 * grow memory with every athlete x filter x tile. Add one only once measured cold-tile times call for it.
 */
interface HeatmapTileRenderer {
    /** The renderer id the SPA matches to its adapter, e.g. `raster-density`. */
    val id: String

    /**
     * The look of this renderer's tiles. Tile URLs carry it, so a change to the look (palette, line width, glow)
     * reaches browsers that have cached the old tiles for good.
     */
    val version: Int

    /**
     * Renders [tile] of the Heatmap [scope] names. [revision] is the athlete's heatmap revision in the snapshot the
     * tile is drawn from, so anything the renderer keeps per revision matches the routes it draws.
     */
    fun render(
        scope: HeatmapScope,
        revision: HeatmapRevision,
        tile: TileCoordinate,
    ): RenderedTile
}
