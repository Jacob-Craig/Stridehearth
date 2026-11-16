package com.jacobcraig.stridehearth.heatmap

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ZoomBandTest {
    @Test
    fun `every tile zoom falls in the band whose geometry it draws`() {
        val bands = (0..TileCoordinate.MAX_ZOOM).associateWith { ZoomBand.of(it) }

        assertEquals(setOf(0, 1, 2, 3, 4, 5), bands.filterValues { it == ZoomBand.Z0 }.keys)
        assertEquals(setOf(6, 7, 8, 9), bands.filterValues { it == ZoomBand.Z6 }.keys)
        assertEquals(setOf(10, 11, 12), bands.filterValues { it == ZoomBand.Z10 }.keys)
        assertEquals(setOf(13), bands.filterValues { it == ZoomBand.Z13 }.keys)
        assertEquals(setOf(14), bands.filterValues { it == ZoomBand.Z14 }.keys)
        assertEquals(setOf(15), bands.filterValues { it == ZoomBand.Z15 }.keys)
        assertEquals(setOf(16), bands.filterValues { it == ZoomBand.Z16 }.keys)
    }

    @Test
    fun `each band from z13 up draws its own column, and the deepest draws full detail`() {
        assertEquals(
            listOf("geom_z0", "geom_z6", "geom_z10", "geom_z13", "geom_z14", "geom_z15", "geom_z16"),
            ZoomBand.entries.map { it.column },
        )
    }
}
