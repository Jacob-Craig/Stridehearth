package com.jacobcraig.stridehearth.heatmap

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** A pixel of a tile image: `x` to the east, `y` growing downwards. */
data class Pixel(
    val x: Int,
    val y: Int,
)

/** A decoded raster Heatmap tile, read the way a person looks at it: is a place lit, and how brightly. */
class RasterTile(
    private val image: BufferedImage,
) {
    val width get() = image.width
    val height get() = image.height

    private fun alpha(
        x: Int,
        y: Int,
    ) = image.getRGB(x, y) ushr 24

    /**
     * How bright the tile looks at [x], [y] over a black map: luminance scaled by opacity, from 0 to 255.
     */
    private fun brightness(
        x: Int,
        y: Int,
    ): Double {
        val argb = image.getRGB(x, y)
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) * alpha(x, y) / 255.0
    }

    /** The pixels within [radius] of [pixel], so a test is not thrown by a route landing a pixel either side. */
    private fun around(
        pixel: Pixel,
        radius: Int,
    ) = (pixel.y - radius..pixel.y + radius)
        .flatMap { y ->
            (pixel.x - radius..pixel.x + radius).map { x -> Pixel(x, y) }
        }.filter { it.x in 0 until width && it.y in 0 until height }

    /** The brightest the tile gets right next to [pixel]. */
    fun brightnessNear(pixel: Pixel): Double = around(pixel, NEAR).maxOf { brightness(it.x, it.y) }

    fun isLitAt(pixel: Pixel): Boolean = brightnessNear(pixel) > 0.0

    fun isTransparentAt(pixel: Pixel): Boolean = around(pixel, NEAR).all { alpha(it.x, it.y) == 0 }

    val isFullyTransparent: Boolean
        get() = (0 until height).all { y -> (0 until width).all { x -> alpha(x, y) == 0 } }

    companion object {
        private const val NEAR = 2

        fun decode(png: ByteArray): RasterTile = RasterTile(requireNotNull(ImageIO.read(ByteArrayInputStream(png))) { "Not an image" })
    }
}
