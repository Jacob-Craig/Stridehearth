package com.jacobcraig.stridehearth.heatmap

/**
 * The zoom bands of a heatmap route (ADR-0007): each band's tiles draw the route's geometry simplified for that
 * band, stored in [column]. A band covers the zooms above the previous band's [maxZoom] up to its own.
 *
 * This is the one definition of the bands: tiles draw the geometry of the band [of] their zoom, and the
 * **Full Heat** is measured per band, so the two can never disagree about which band a zoom belongs to.
 *
 * The bands are declared from the coarsest to the most detailed, and code compares them in that order.
 *
 * From z13 up each zoom is its own band, simplified to about one pixel at that zoom; [Z16] is the full-detail band,
 * whose column also finds a tile's routes by bounding box.
 *
 * The migrations that store each band's simplified geometry (V13, then V16 for the bands from z13 up) and its Full
 * Heat segments (V15, in `heat_segments_z0_sampled` and so on, for the z0–12 bands only) are written for these
 * [maxZoom]s, so changing a band needs a migration too.
 */
enum class ZoomBand(
    val column: String,
    val maxZoom: Int,
) {
    Z0("geom_z0", 5),
    Z6("geom_z6", 9),
    Z10("geom_z10", 12),
    Z13("geom_z13", 13),
    Z14("geom_z14", 14),
    Z15("geom_z15", 15),
    Z16("geom_z16", TileCoordinate.MAX_ZOOM),
    ;

    companion object {
        /** The band a tile at [zoom] belongs to; zooms above the last band's [maxZoom] are not tiles. */
        fun of(zoom: Int): ZoomBand = entries.first { zoom <= it.maxZoom }
    }
}
