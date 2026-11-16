package com.jacobcraig.stridehearth.heatmap

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.ZoneId

@Component
@ConfigurationProperties(prefix = "stridehearth.heatmap")
data class HeatmapProperties(
    /** The id of the active [HeatmapTileRenderer]. */
    var renderer: String = RasterDensityTileRenderer.ID,
)

/**
 * A rendered tile, and whether it shows what its URL names, the athlete's revision and the renderer's look, and so
 * may be cached for good.
 */
class HeatmapTile(
    val tile: RenderedTile,
    val current: Boolean,
)

/** The athlete's **Heatmap**: its revision, its active tile renderer, and its tiles. */
@Service
class HeatmapService(
    renderers: List<HeatmapTileRenderer>,
    properties: HeatmapProperties,
    private val heatmapRepository: HeatmapRepository,
    transactionManager: PlatformTransactionManager,
) {
    /** The active renderer, chosen by `stridehearth.heatmap.renderer`; an unknown id fails startup. */
    val renderer: HeatmapTileRenderer =
        renderers.singleOrNull { it.id == properties.renderer }
            ?: error("No Heatmap renderer '${properties.renderer}'; known: ${renderers.map { it.id }}")

    /** The active renderer and its look, as tile URLs carry it, e.g. `raster-density-v3`. */
    val rendering: String = "${renderer.id}-v${renderer.version}"

    // One snapshot for the revision check and the tile, so a tile is never cached under a revision it doesn't show.
    private val snapshot =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    fun revision(athleteId: Long): HeatmapRevision = heatmapRepository.revision(athleteId)

    fun years(
        athleteId: Long,
        timeZone: ZoneId,
    ): List<Int> = heatmapRepository.years(athleteId, timeZone)

    fun bounds(scope: HeatmapScope): GeoBounds? = heatmapRepository.bounds(scope)

    /**
     * Renders [tile] of the Heatmap [scope] names with the active renderer. The tile is
     * [HeatmapTile.current] only when [requestedRendering] is the active [rendering] and [requestedRevision] is the
     * athlete's revision in the same snapshot the tile was rendered from.
     */
    fun tile(
        scope: HeatmapScope,
        requestedRendering: String,
        requestedRevision: String,
        tile: TileCoordinate,
    ): HeatmapTile =
        snapshot.execute {
            val revision = heatmapRepository.revision(scope.athleteId)
            val current = requestedRendering == rendering && revision.token == requestedRevision
            HeatmapTile(renderer.render(scope, revision, tile), current)
        }!!
}
