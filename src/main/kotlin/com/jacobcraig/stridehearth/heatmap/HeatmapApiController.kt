package com.jacobcraig.stridehearth.heatmap

import com.jacobcraig.stridehearth.api.ApiErrorResponseDoc
import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The signed-in athlete's **Heatmap** (ADR-0007, ADR-0008). Every endpoint is scoped to the signed-in athlete and carries
 * no athlete id. Tiles and the starting view take the **Heatmap Filter** as optional `from`, `to` and `sports`
 * parameters, defaulting to all time and all sports.
 */
@RestController
@RequestMapping("/api/heatmap")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class HeatmapApiController(
    private val heatmapService: HeatmapService,
) {
    @GetMapping("/revision")
    @Operation(
        operationId = "getHeatmapRevision",
        summary = "The athlete's heatmap revision, which changes whenever their heatmap routes do",
    )
    @ApiResponse(responseCode = "200", description = "The heatmap revision")
    fun revision(
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): HeatmapRevisionDTO = HeatmapRevisionDTO(heatmapService.revision(currentUser.id).token)

    @GetMapping("/renderer")
    @Operation(operationId = "getHeatmapRenderer", summary = "The active Heatmap tile renderer and its tile URL template")
    @ApiResponse(responseCode = "200", description = "The renderer descriptor")
    fun renderer(): HeatmapRendererDTO =
        HeatmapRendererDTO(
            id = heatmapService.renderer.id,
            tileUrlTemplate = "/api/heatmap/tiles/${heatmapService.rendering}/{revision}/{z}/{x}/{y}",
            maxZoom = TileCoordinate.MAX_ZOOM,
        )

    @GetMapping("/starting-view")
    @Operation(
        operationId = "getHeatmapStartingView",
        summary = "Where the Heatmap opens: the bounds of the busiest area of the routes the Heatmap Filter matches, or none",
        description =
            "The area holding the most Activities under the Heatmap Filter, so an athlete who has trained on two " +
                "continents opens on where they train most rather than the whole world. Bounds are null when the " +
                "filter matches no routes.",
    )
    @ApiResponse(responseCode = "200", description = "The starting view")
    fun startingView(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) sports: List<ActivitySport>?,
    ): HeatmapStartingViewDTO {
        val scope = HeatmapScope(currentUser.id, HeatmapFilter(from, to, sports?.toSet()))
        return HeatmapStartingViewDTO(heatmapService.bounds(scope)?.let(::GeoBoundsDTO))
    }

    @GetMapping("/years")
    @Operation(
        operationId = "getHeatmapYears",
        summary = "The years with an Activity on the Heatmap, in the browser's timezone, for the year shortcuts",
    )
    @ApiResponse(responseCode = "200", description = "The years, newest first")
    @ApiErrorResponseDoc(responseCode = "400", description = "Unknown timezone")
    fun years(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @Parameter(description = "IANA timezone the years are counted in, e.g. Europe/London", example = "Europe/London")
        @RequestParam
        timeZone: String,
    ): HeatmapYearsDTO {
        // Region ids only: PostgreSQL reads a bare offset such as "+02:00" with the POSIX sign, the other way round.
        if (timeZone !in ZoneId.getAvailableZoneIds()) {
            throw RequestValidationException(mapOf("timeZone" to "Unknown timezone"))
        }
        return HeatmapYearsDTO(heatmapService.years(currentUser.id, ZoneId.of(timeZone)))
    }

    /**
     * One tile of the athlete's Heatmap at [revision], from the active renderer at the look [rendering] names.
     *
     * There is deliberately **no server-side tile cache in v1**. The URL carries the rendering, the revision and the
     * filter, so the response is cached by the browser as `private, immutable`, which is correct by construction:
     * new data means a new revision, and a new look a new rendering, and so a new URL. A tile requested at a
     * revision or rendering that is no longer current is still drawn from current data with the active renderer,
     * but is not cacheable. Add a server-side cache only after measuring cold tiles.
     */
    @GetMapping("/tiles/{rendering}/{revision}/{z}/{x}/{y}")
    @Operation(operationId = "getHeatmapTile", summary = "One tile of the athlete's Heatmap at a revision")
    @ApiResponse(
        responseCode = "200",
        description = "The tile, in the active renderer's media type",
        content = [Content(mediaType = RasterDensityTileRenderer.MEDIA_TYPE, schema = Schema(type = "string", format = "binary"))],
    )
    @ApiErrorResponseDoc(responseCode = "400", description = "No such tile")
    fun tile(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @Parameter(description = "The renderer and its look, from the renderer descriptor's tile URL template")
        @PathVariable
        rendering: String,
        @PathVariable revision: String,
        @PathVariable z: Int,
        @PathVariable x: Int,
        @PathVariable y: Int,
        @Parameter(description = "Inclusive lower bound on the Activity start time")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        from: Instant?,
        @Parameter(description = "Exclusive upper bound on the Activity start time")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        to: Instant?,
        @Parameter(description = "Sports to include; all sports when absent")
        @RequestParam(required = false)
        sports: List<ActivitySport>?,
    ): ResponseEntity<ByteArray> {
        val coordinate =
            runCatching { TileCoordinate(z, x, y) }
                .getOrElse { throw RequestValidationException(mapOf("tile" to (it.message ?: "No such tile"))) }
        val scope = HeatmapScope(currentUser.id, HeatmapFilter(from, to, sports?.toSet()))
        val tile = heatmapService.tile(scope, rendering, revision, coordinate)
        val cacheControl =
            if (tile.current) CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable() else CacheControl.noStore()
        return ResponseEntity
            .ok()
            .cacheControl(cacheControl)
            .contentType(MediaType.parseMediaType(tile.tile.mediaType))
            .body(tile.tile.bytes)
    }
}
