package com.jacobcraig.stridehearth.heatmap

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One Activity's route within a tile: its lines, each a flat `x0, y0, x1, y1, ...` array in canvas pixels.
 * The canvas is the tile plus [DensityRaster.PAD] pixels on each side, so the glow of a route just outside the
 * tile still reaches into it and neighbouring tiles meet without a seam.
 */
class TileRoute(
    val lines: List<FloatArray>,
)

/**
 * Draws a raster **Heatmap** tile (ADR-0008): each Activity once, antialiased, into a mask; the masks added up so
 * every pixel holds the number of distinct Activities through it; a Gaussian blur of that density for the glow;
 * and a log scale against the **Full Heat** to colour it on a hot ramp, from dim red through orange and yellow to
 * white. The look (line width, glow, ramp, and how faint a single Activity is) was tuned in #107; line width, glow
 * and encoding come from [RasterSettings]. Any change to the look must bump [RasterDensityTileRenderer.VERSION],
 * since browsers keep tiles for good.
 */
object DensityRaster {
    /** MapLibre draws a 512-pixel raster tile at its own zoom. */
    const val SIZE = 512
    const val PAD = 16
    internal const val CANVAS = SIZE + 2 * PAD

    /**
     * The share of a pixel's heat taken from the sharp density; the rest comes from the glow, scaled by [glowGain] so
     * the centre line of a corridor of N Activities has a heat of about N whatever the zoom's line width and glow radius.
     */
    internal const val CORE_SHARE = 0.45f

    /** Heat below which a pixel stays transparent: the outer fringe of a single Activity's glow. */
    internal const val MIN_VISIBLE_HEAT = 0.04f

    /**
     * The opacity of a single Activity, at the bottom of the ramp, so a one-off route is dim but never lost. Busier
     * pixels grow towards opaque as they climb the ramp.
     */
    internal const val MIN_ALPHA = 0.62f

    private val HOT_RAMP =
        listOf(
            0.00f to 0xa3201c,
            0.30f to 0xd8391a,
            0.55f to 0xff7a1f,
            0.78f to 0xffc040,
            0.92f to 0xffeb9a,
            1.00f to 0xffffff,
        )

    /**
     * Scales the glow so its peak over a single line of [width] pixels is about 1: the blur spreads a line's
     * density over about sqrt(2 pi) sigma pixels across it.
     */
    private fun glowGain(
        width: Float,
        sigma: Float,
    ): Float = (1 - CORE_SHARE) * sqrt(2 * PI.toFloat()) * sigma / width

    /** A fully transparent tile, encoded once: most tiles of a world map hold no routes. */
    val EMPTY_PNG: ByteArray by lazy { encodePng(BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)) }

    /** The PNG of [routes] at [zoom], coloured against [fullHeat] distinct Activities. */
    fun render(
        routes: List<TileRoute>,
        zoom: Int,
        fullHeat: Double,
    ): ByteArray = render(routes, zoom, fullHeat, RasterSettings.SHIPPED)

    /** The tile of [routes] at [zoom], coloured against [fullHeat] distinct Activities, drawn with [settings]. */
    internal fun render(
        routes: List<TileRoute>,
        zoom: Int,
        fullHeat: Double,
        settings: RasterSettings,
    ): ByteArray {
        // The log scale divides by ln(fullHeat): one Activity must sit below the top of the ramp.
        require(fullHeat > 1) { "Full Heat must exceed one Activity, was $fullHeat" }
        val width = settings.lineWidth(zoom)
        val sigma = settings.glowSigma(zoom)
        val density = density(routes, width, settings)
        val glow = blur(density, sigma)
        return settings.encode(TileHeat(density, glow, glowGain(width, sigma), fullHeat))
    }

    /** Distinct Activities per canvas pixel: each Activity's mask is drawn once, so it adds at most 1 anywhere. */
    private fun density(
        routes: List<TileRoute>,
        width: Float,
        settings: RasterSettings,
    ): FloatArray {
        val density = FloatArray(CANVAS * CANVAS)
        val mask = BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_BYTE_GRAY)
        val coverage = (mask.raster.dataBuffer as DataBufferByte).data
        val canvas = Rectangle(0, 0, CANVAS, CANVAS)
        val reach = ceil(width).toInt() + 2
        val graphics = mask.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            graphics.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.color = Color.WHITE
            for (route in routes) {
                val path = pathOf(route) ?: continue
                val drawn = path.bounds.apply { grow(reach, reach) }.intersection(canvas)
                if (drawn.isEmpty) continue
                graphics.draw(path)
                // Add this Activity's coverage, then clear the mask for the next one.
                for (sweep in settings.sweeps(route, drawn, reach)) {
                    for (y in sweep.y until sweep.y + sweep.height) {
                        for (i in y * CANVAS + sweep.x until y * CANVAS + sweep.x + sweep.width) {
                            val covered = coverage[i].toInt() and 0xff
                            if (covered != 0) {
                                density[i] += covered / 255f
                                coverage[i] = 0
                            }
                        }
                    }
                }
            }
        } finally {
            graphics.dispose()
        }
        return density
    }

    private fun pathOf(route: TileRoute): Path2D.Float? {
        val path = Path2D.Float()
        for (line in route.lines) {
            if (line.size < 4) continue
            path.moveTo(line[0], line[1])
            for (i in 2 until line.size step 2) path.lineTo(line[i], line[i + 1])
        }
        return path.takeIf { it.currentPoint != null }
    }

    /** A separable Gaussian blur; beyond the canvas edge counts as empty. */
    private fun blur(
        source: FloatArray,
        sigma: Float,
    ): FloatArray {
        val radius = max(1, (sigma * 3).toInt())
        val kernel = FloatArray(2 * radius + 1) { i -> exp(-((i - radius) * (i - radius)) / (2 * sigma * sigma)) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total

        val across = FloatArray(source.size)
        for (y in 0 until CANVAS) {
            for (x in 0 until CANVAS) {
                var sum = 0f
                for (k in max(-radius, -x)..min(radius, CANVAS - 1 - x)) sum += source[y * CANVAS + x + k] * kernel[k + radius]
                across[y * CANVAS + x] = sum
            }
        }
        val blurred = FloatArray(source.size)
        for (y in 0 until CANVAS) {
            for (x in 0 until CANVAS) {
                var sum = 0f
                for (k in max(-radius, -y)..min(radius, CANVAS - 1 - y)) sum += across[(y + k) * CANVAS + x] * kernel[k + radius]
                blurred[y * CANVAS + x] = sum
            }
        }
        return blurred
    }

    /** The hot ramp's RGB at [t], from 0 (one Activity, a dim ember) to 1 (Full Heat, white-hot). */
    internal fun hot(t: Float): Int {
        val upper = HOT_RAMP.indexOfFirst { it.first >= t }.coerceAtLeast(1)
        val (t0, c0) = HOT_RAMP[upper - 1]
        val (t1, c1) = HOT_RAMP[upper]
        val f = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)

        fun channel(shift: Int) = (((c0 shr shift) and 0xff) * (1 - f) + ((c1 shr shift) and 0xff) * f).toInt()
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    internal fun encodePng(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) { "No PNG writer" } }.toByteArray()

    /** Heat levels of the [PALETTE], from the bottom of the hot ramp (0) to its white-hot top, the Full Heat. */
    internal const val HEAT_LEVELS = 31

    /** Opacity levels of the [PALETTE] at each heat level, from transparent (0) to opaque. */
    internal const val ALPHA_LEVELS = 8

    /**
     * The colours a tile is encoded with, as an 8-bit palette PNG (#119): index 0 is transparent, and the rest are
     * [HEAT_LEVELS] steps up the hot ramp, each at [ALPHA_LEVELS] opacities. The busiest tiles are about a fifth the
     * size of a 32-bit ARGB PNG, and the steps are too fine to see on the map.
     */
    internal val PALETTE: IndexColorModel by lazy {
        val r = ByteArray(PALETTE_SIZE)
        val g = ByteArray(PALETTE_SIZE)
        val b = ByteArray(PALETTE_SIZE)
        val a = ByteArray(PALETTE_SIZE)
        for (level in 0 until HEAT_LEVELS) {
            for (opacity in 0 until ALPHA_LEVELS) {
                val i = paletteIndex(level, opacity)
                val rgb = hot(level / (HEAT_LEVELS - 1f))
                r[i] = (rgb shr 16).toByte()
                g[i] = (rgb shr 8).toByte()
                b[i] = rgb.toByte()
                a[i] = (opacity * 255 / (ALPHA_LEVELS - 1)).toByte()
            }
        }
        IndexColorModel(8, PALETTE_SIZE, r, g, b, a)
    }

    private const val PALETTE_SIZE = 1 + HEAT_LEVELS * ALPHA_LEVELS

    /** The [PALETTE] index of a heat [level] at an [opacity] level; index 0 stays transparent. */
    internal fun paletteIndex(
        level: Int,
        opacity: Int,
    ): Int = 1 + level * ALPHA_LEVELS + opacity
}

/**
 * A drawn tile before it is coloured: the sharp [density] and its blurred [glow] over the canvas, to be coloured on a
 * log scale against [fullHeat] distinct Activities.
 */
internal class TileHeat(
    val density: FloatArray,
    val glow: FloatArray,
    /** How much of the blurred density makes up a pixel's heat, for the zoom's line width and glow radius. */
    val glowGain: Float,
    val fullHeat: Double,
) {
    /**
     * Calls [paint] with the heat `t` and opacity of each of the tile's own pixels, dropping the padding, by its index
     * in a [DensityRaster.SIZE]-wide image. Pixels are on a log scale against [fullHeat]: a single Activity sits at the
     * bottom of the ramp (`t` 0), at its faintest, and [fullHeat] Activities or more at the white-hot top (`t` 1).
     * Transparent pixels are skipped.
     */
    inline fun eachPixel(paint: (pixel: Int, t: Float, alpha: Float) -> Unit) {
        val logFullHeat = ln(fullHeat).toFloat()
        for (y in 0 until DensityRaster.SIZE) {
            for (x in 0 until DensityRaster.SIZE) {
                val i = (y + DensityRaster.PAD) * DensityRaster.CANVAS + (x + DensityRaster.PAD)
                // The glow makes a corridor hottest along its centre line and fade towards its edges; a share of
                // the sharp density keeps thin, single routes crisp.
                val heat = glow[i] * glowGain + density[i] * DensityRaster.CORE_SHARE
                if (heat < DensityRaster.MIN_VISIBLE_HEAT) continue
                val t = (ln(max(1f, heat)) / logFullHeat).coerceIn(0f, 1f)
                // Below one Activity's worth of heat, at a route's edges and in its glow, the pixel fades out.
                val alpha = min(1f, heat) * (DensityRaster.MIN_ALPHA + (1 - DensityRaster.MIN_ALPHA) * sqrt(t))
                paint(y * DensityRaster.SIZE + x, t, alpha)
            }
        }
    }

    /** The tile coloured on the hot ramp, as 8-bit [DensityRaster.PALETTE] indices: what ships. */
    fun paletteImage(): BufferedImage {
        val image = BufferedImage(DensityRaster.SIZE, DensityRaster.SIZE, BufferedImage.TYPE_BYTE_INDEXED, DensityRaster.PALETTE)
        val pixels = (image.raster.dataBuffer as DataBufferByte).data
        eachPixel { pixel, t, alpha ->
            val level = (t * (DensityRaster.HEAT_LEVELS - 1)).roundToInt()
            val opacity = (alpha * (DensityRaster.ALPHA_LEVELS - 1)).roundToInt()
            pixels[pixel] = DensityRaster.paletteIndex(level, opacity).toByte()
        }
        return image
    }
}
