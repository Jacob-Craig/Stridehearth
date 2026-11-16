package com.jacobcraig.stridehearth.heatmap

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The raster density renderer (ADR-0008): 512-pixel PNG tiles, drawn on request by [DensityRaster].
 *
 * PostgreSQL reads only the tile's zoom-band geometry of the athlete's routes that the **Heatmap Filter** matches,
 * clipped to the tile and returned in tile pixel coordinates at a fraction of a pixel (see [RasterSettings]), so lines
 * keep their sub-pixel position for antialiasing. Tiles carry no Activity ids or counts.
 *
 * Its [RasterSettings] are always [RasterSettings.SHIPPED] in the application; the performance harness (#110) builds it
 * with variant settings to time alternatives through this same code.
 *
 * There is deliberately **no server-side tile cache in v1**: see [HeatmapTileRenderer].
 */
@Component
class RasterDensityTileRenderer internal constructor(
    private val jdbcClient: JdbcClient,
    private val fullHeat: FullHeat,
    private val settings: RasterSettings,
) : HeatmapTileRenderer {
    @Autowired
    constructor(jdbcClient: JdbcClient, fullHeat: FullHeat) : this(jdbcClient, fullHeat, RasterSettings.SHIPPED)

    override val id = ID
    override val version = VERSION

    override fun render(
        scope: HeatmapScope,
        revision: HeatmapRevision,
        tile: TileCoordinate,
    ): RenderedTile {
        val routes = routesIn(scope, tile)
        // Most tiles of a world map hold no routes: skip the Full Heat and the drawing for them.
        val png =
            if (routes.isEmpty()) {
                DensityRaster.EMPTY_PNG
            } else {
                DensityRaster.render(routes, tile.z, fullHeat.of(scope, revision, tile.z), settings)
            }
        return RenderedTile(png, MEDIA_TYPE)
    }

    private fun routesIn(
        scope: HeatmapScope,
        tile: TileCoordinate,
    ): List<TileRoute> {
        val precision = settings.coordinatePrecision
        return jdbcClient
            .sql(
                """
                WITH tile AS (
                    SELECT ST_TileEnvelope(:z, :x, :y) AS envelope,
                           ST_TileEnvelope(:z, :x, :y, margin => :margin) AS clip
                )
                SELECT ST_AsBinary(ST_AsMVTGeom(
                           ${settings.geometrySql()},
                           tile.envelope, ${DensityRaster.SIZE * precision}, ${DensityRaster.PAD * precision}, true
                       )) AS wkb
                FROM heatmap_route, tile
                WHERE $MATCHES_SCOPE
                  AND geom_z16 && tile.clip
                """.trimIndent(),
            ).param("z", tile.z)
            .param("x", tile.x)
            .param("y", tile.y)
            .param("margin", DensityRaster.PAD.toDouble() / DensityRaster.SIZE)
            .bindScope(scope)
            .query { rs, _ -> rs.getBytes("wkb") }
            .list()
            .mapNotNull { wkb -> wkb?.let { TileRoute(canvasLines(it, precision)) } }
    }

    companion object {
        const val ID = "raster-density"

        /** The look of the tiles: bump it whenever [DensityRaster]'s output changes, as browsers keep tiles for good. */
        const val VERSION = 3
        const val MEDIA_TYPE = "image/png"

        private const val WKB_POINT = 1
        private const val WKB_LINE_STRING = 2
        private const val WKB_MULTI_POINT = 4
        private const val WKB_MULTI_LINE_STRING = 5
        private const val WKB_GEOMETRY_COLLECTION = 7

        /**
         * The lines of a WKB (Multi)LineString, or a collection of them, from `ST_AsMVTGeom` tile units, [precision]
         * to a pixel, to [DensityRaster] canvas pixels. Points, left when a line collapses at low zoom, are dropped.
         */
        private fun canvasLines(
            wkb: ByteArray,
            precision: Int,
        ): List<FloatArray> {
            val buffer = ByteBuffer.wrap(wkb)
            val lines = mutableListOf<FloatArray>()

            fun point() = buffer.double.toFloat() / precision + DensityRaster.PAD

            fun read() {
                buffer.order(if (buffer.get().toInt() == 0) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
                when (val type = buffer.int % 1000) {
                    WKB_POINT -> buffer.position(buffer.position() + 2 * Double.SIZE_BYTES)
                    WKB_LINE_STRING -> lines.add(FloatArray(buffer.int * 2) { point() })
                    WKB_MULTI_POINT, WKB_MULTI_LINE_STRING, WKB_GEOMETRY_COLLECTION -> repeat(buffer.int) { read() }
                    else -> error("Unexpected WKB geometry type $type in a heatmap tile")
                }
            }
            read()
            return lines
        }
    }
}
