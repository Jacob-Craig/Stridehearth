package com.jacobcraig.stridehearth.heatmap.performance

import com.jacobcraig.stridehearth.heatmap.DensityRaster
import com.jacobcraig.stridehearth.heatmap.FullHeat
import com.jacobcraig.stridehearth.heatmap.HeatmapTileRenderer
import com.jacobcraig.stridehearth.heatmap.RasterBand
import com.jacobcraig.stridehearth.heatmap.RasterDensityTileRenderer
import com.jacobcraig.stridehearth.heatmap.RasterSettings
import com.jacobcraig.stridehearth.heatmap.TileHeat
import com.jacobcraig.stridehearth.heatmap.TileRoute
import org.springframework.jdbc.core.simple.JdbcClient
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How a variant encodes its tiles. */
enum class Encoding {
    /** The shipped 8-bit palette PNG, through ImageIO's PNG writer with its defaults. */
    PNG,

    /** The shipped palette PNG at deflate level 1. */
    PNG_FAST_DEFLATE,

    /** A 32-bit ARGB PNG, as renderer v2 shipped before #119. */
    PNG_ARGB,
}

/**
 * One way to build raster density tiles, for the harness to compare with what ships. A variant is a set of overrides
 * on the shipped [RasterSettings], drawn by the shipped `RasterDensityTileRenderer` and `DensityRaster`: with every
 * property at its default it is [SHIPPED], and draws exactly what ships.
 *
 * @property bands the zoom bands, by default the shipped ones.
 * @property onTheFlyTolerance when set, each band simplifies the full-detail geometry on the fly to this many
 *   times its tolerance, instead of reading its pre-simplified column.
 * @property coordinatePrecision the precision of tile coordinates from PostgreSQL, in fractions of a pixel. This is
 *   not supersampled drawing: the tile is always drawn at 1x.
 * @property tilePixelTolerance when set, the band's geometry is simplified again on the fly to this many pixels
 *   at the tile's own zoom, so a tile never draws more points than its pixels can show.
 * @property chunkedSweep when true, each Activity's mask is added to the density and cleared only around each run
 *   of [SWEEP_CHUNK] segments, instead of over the bounding box of the whole route. The output is the same: a
 *   pixel cleared by one run reads 0 to the next. A long route crossing a tile has a bounding box of nearly the
 *   whole tile, so the shipped sweep reads about 300k pixels per Activity however thin the route is.
 */
internal data class RasterVariant(
    val name: String,
    val changesLook: Boolean,
    val bands: List<RasterBand> = RasterSettings.ZOOM_BANDS,
    val onTheFlyTolerance: Double? = null,
    val coordinatePrecision: Int = RasterSettings.SHIPPED.coordinatePrecision,
    val lineWidthScale: Float = 1f,
    val glowScale: Float = 1f,
    val encoding: Encoding = Encoding.PNG,
    val chunkedSweep: Boolean = false,
    val tilePixelTolerance: Double? = null,
) {
    /** The shipped renderer, drawing with this variant's settings, with its own Full Heat cache. */
    fun renderer(jdbcClient: JdbcClient): HeatmapTileRenderer =
        RasterDensityTileRenderer(jdbcClient, FullHeat(jdbcClient), VariantSettings(this))

    companion object {
        val SHIPPED = RasterVariant("shipped", changesLook = false)

        /** Segments per mask sweep of a [chunkedSweep]. */
        const val SWEEP_CHUNK = 8

        /** Each shipped band column's simplification tolerance in metres, as `heatmap_route_geometries` (V16) applies it. */
        val TOLERANCES =
            mapOf(
                "geom_z0" to 2446.0,
                "geom_z6" to 153.0,
                "geom_z10" to 19.0,
                "geom_z13" to 9.55,
                "geom_z14" to 4.78,
                "geom_z15" to 2.39,
                "geom_z16" to 1.2,
            )
    }
}

/** The shipped [RasterSettings] with a [RasterVariant]'s overrides; at the variant's defaults each override returns the shipped value. */
private class VariantSettings(
    private val variant: RasterVariant,
) : RasterSettings() {
    override val coordinatePrecision get() = variant.coordinatePrecision

    override fun geometrySql(): String {
        val band = bandGeometrySql(variant.bands, ::source)
        val pixels = variant.tilePixelTolerance ?: return band
        // One pixel of a tile at the tile's own zoom, in Web Mercator metres.
        return "ST_Simplify($band, $pixels * $EQUATOR_METRES / (2 ^ :z) / ${DensityRaster.SIZE}, true)"
    }

    private fun source(band: RasterBand): String {
        val factor = variant.onTheFlyTolerance ?: return band.column
        if (band.column == FULL_DETAIL && factor == 1.0) return FULL_DETAIL
        return "ST_Simplify($FULL_DETAIL, ${RasterVariant.TOLERANCES.getValue(band.column) * factor}, true)"
    }

    override fun lineWidth(zoom: Int) = variant.lineWidthScale * super.lineWidth(zoom)

    // The glow's radius (3 sigma) must stay within the canvas padding.
    override fun glowSigma(zoom: Int) = min(DensityRaster.PAD / 3f, variant.glowScale * super.glowSigma(zoom))

    override fun sweeps(
        route: TileRoute,
        drawn: Rectangle,
        reach: Int,
    ): List<Rectangle> = if (variant.chunkedSweep) chunkBounds(route, reach) else super.sweeps(route, drawn, reach)

    /** The bounds, grown by [reach], of each run of [RasterVariant.SWEEP_CHUNK] segments of [route], within the canvas. */
    private fun chunkBounds(
        route: TileRoute,
        reach: Int,
    ): List<Rectangle> =
        route.lines.filter { it.size >= 4 }.flatMap { line ->
            (0 until line.size / 2 - 1 step RasterVariant.SWEEP_CHUNK).mapNotNull { first ->
                val last = min(line.size / 2 - 1, first + RasterVariant.SWEEP_CHUNK)
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (p in first..last) {
                    minX = min(minX, line[2 * p])
                    maxX = max(maxX, line[2 * p])
                    minY = min(minY, line[2 * p + 1])
                    maxY = max(maxY, line[2 * p + 1])
                }
                val x = floor(minX).toInt() - reach
                val y = floor(minY).toInt() - reach
                Rectangle(x, y, ceil(maxX).toInt() + reach - x + 1, ceil(maxY).toInt() + reach - y + 1)
                    .intersection(CANVAS)
                    .takeUnless { it.isEmpty }
            }
        }

    override fun encode(heat: TileHeat): ByteArray =
        when (variant.encoding) {
            Encoding.PNG -> super.encode(heat)
            Encoding.PNG_FAST_DEFLATE -> writePng(heat.paletteImage(), FAST_DEFLATE_QUALITY)
            Encoding.PNG_ARGB -> DensityRaster.encodePng(argbImage(heat))
        }

    /** [heat] coloured on the hot ramp as 32-bit ARGB, as renderer v2 encoded it. */
    private fun argbImage(heat: TileHeat): BufferedImage {
        val image = BufferedImage(DensityRaster.SIZE, DensityRaster.SIZE, BufferedImage.TYPE_INT_ARGB)
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        heat.eachPixel { pixel, t, alpha -> pixels[pixel] = ((alpha * 255).toInt() shl 24) or DensityRaster.hot(t) }
        return image
    }

    private fun writePng(
        image: BufferedImage,
        quality: Float,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param =
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality
                    }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    companion object {
        private const val FULL_DETAIL = "geom_z16"
        private const val EQUATOR_METRES = 40_075_016.686
        private val CANVAS = Rectangle(0, 0, DensityRaster.CANVAS, DensityRaster.CANVAS)

        /** ImageIO maps quality q to deflate level (int) 9 * (1 - q): this is level 1. */
        private const val FAST_DEFLATE_QUALITY = 0.85f
    }
}
