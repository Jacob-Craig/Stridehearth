package com.jacobcraig.stridehearth.heatmap

import java.awt.Rectangle

/**
 * A zoom band as a tile draws it: tiles up to [maxZoom] draw [column], the route pre-simplified for that band by
 * `heatmap_route_geometries`. The shipped bands are the [ZoomBand]s; the performance harness moves their boundaries.
 */
internal data class RasterBand(
    val maxZoom: Int,
    val column: String,
)

/**
 * The settings that decide how a raster density tile is built and what it looks like: which geometry PostgreSQL reads,
 * how precisely it returns it, and how [DensityRaster] draws, blurs and encodes it.
 *
 * The shipped renderer uses [SHIPPED] as it is. The Heatmap performance harness (#110) subclasses this to time
 * variants through the same code, so a variant differs from what ships only in the settings it overrides. Any change
 * here that alters tiles must bump [RasterDensityTileRenderer.VERSION], since browsers keep tiles for good.
 */
internal open class RasterSettings {
    /**
     * The precision of the tile pixel coordinates PostgreSQL returns, in fractions of a pixel, so lines keep their
     * sub-pixel position for antialiasing. It is coordinate precision only: the tile is still drawn at 1x.
     */
    open val coordinatePrecision: Int get() = COORDINATE_PRECISION

    /** The SQL expression for the route geometry a tile at zoom `:z` draws: its zoom band's pre-simplified column. */
    open fun geometrySql(): String = bandGeometrySql(ZOOM_BANDS) { it.column }

    /** Line width in pixels: routes thicken a little as the map zooms in. */
    open fun lineWidth(zoom: Int): Float =
        when {
            zoom <= 6 -> 1.0f
            zoom <= 10 -> 1.3f
            zoom <= 13 -> 1.8f
            else -> 2.4f
        }

    /** The glow's Gaussian sigma in pixels; its radius (3 sigma) must stay within [DensityRaster.PAD]. */
    open fun glowSigma(zoom: Int): Float =
        when {
            zoom <= 6 -> 1.2f
            zoom <= 10 -> 1.8f
            zoom <= 13 -> 2.4f
            else -> 3.0f
        }

    /**
     * The canvas areas whose coverage is added to the density, and cleared, after [route] is drawn into the mask.
     * [drawn] is the route's bounds, grown by [reach] pixels and clipped to the canvas: everything it can have covered.
     */
    open fun sweeps(
        route: TileRoute,
        drawn: Rectangle,
        reach: Int,
    ): List<Rectangle> = listOf(drawn)

    /** Encodes the tile's coloured pixels as an 8-bit palette PNG ([DensityRaster.PALETTE]). */
    open fun encode(heat: TileHeat): ByteArray = DensityRaster.encodePng(heat.paletteImage())

    companion object {
        val SHIPPED = RasterSettings()

        private const val COORDINATE_PRECISION = 4

        /** The shipped zoom bands, from the one [ZoomBand] definition; the last runs to the deepest zoom. */
        val ZOOM_BANDS = ZoomBand.entries.map { RasterBand(it.maxZoom, it.column) }

        /** A `CASE` over `:z` that picks each of [bands]' geometry, as [source] gives it in SQL. */
        fun bandGeometrySql(
            bands: List<RasterBand>,
            source: (RasterBand) -> String,
        ): String {
            val cases = bands.dropLast(1).joinToString(" ") { "WHEN :z <= ${it.maxZoom} THEN ${source(it)}" }
            return "CASE $cases ELSE ${source(bands.last())} END"
        }
    }
}
