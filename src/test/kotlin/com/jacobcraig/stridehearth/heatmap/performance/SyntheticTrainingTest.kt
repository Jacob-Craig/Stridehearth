package com.jacobcraig.stridehearth.heatmap.performance

import com.jacobcraig.stridehearth.heatmap.LonLat
import com.jacobcraig.stridehearth.heatmap.metresBetween
import com.jacobcraig.stridehearth.system.ActivitySport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The synthetic athlete the Heatmap performance harness seeds: clustered like real training. */
class SyntheticTrainingTest {
    private val activities = SyntheticTraining(seed = 7).activities(1_000).toList()

    @Test
    fun `the same seed gives the same Activities`() {
        val again = SyntheticTraining(seed = 7).activities(1_000).toList()
        assertEquals(activities.map { it.route.size }, again.map { it.route.size })
        assertEquals(activities[123].route, again[123].route)
    }

    @Test
    fun `Activities average about 2,000 route points, so 10k Activities make about 20M points`() {
        val mean = activities.sumOf { it.route.size } / activities.size.toDouble()
        assertTrue(mean in 1_700.0..2_500.0, "mean $mean points per Activity")
    }

    @Test
    fun `most Activities start at home, a few are far-flung trips`() {
        val away = activities.count { metresBetween(SyntheticTraining.HOME, it.route.first()) > 100_000 }
        assertTrue(away in 10..80, "$away of ${activities.size} Activities far from home")
    }

    @Test
    fun `home Activities keep coming back to the same corridors`() {
        // Each home route touches many ~100 m cells, but between them they cover few: each is passed 10+ times on average, where routes that never repeated would pass each about once.
        val home = activities.filter { metresBetween(SyntheticTraining.HOME, it.route.first()) < 100_000 }
        val visits = home.flatMap { a -> a.route.map { cell(it) }.toSet() }
        val cells = visits.toSet()
        assertTrue(visits.size / cells.size.toDouble() > 10, "${visits.size} visits over ${cells.size} cells")
    }

    @Test
    fun `there is a mix of sports, mostly runs`() {
        val bySport = activities.groupingBy { it.sport }.eachCount()
        assertTrue(bySport.getValue(ActivitySport.RUN) > activities.size / 2, "$bySport")
        assertTrue(bySport.getOrDefault(ActivitySport.RIDE, 0) > activities.size / 10, "$bySport")
        assertTrue(bySport.keys.size >= 3, "$bySport")
    }

    @Test
    fun `a few Activities carry GPS jumps of over a kilometre`() {
        val jumpy = activities.count { a -> a.route.zipWithNext().any { (p, q) -> metresBetween(p, q) > 1_000 } }
        assertTrue(jumpy in 1..50, "$jumpy Activities with a GPS jump")
    }

    @Test
    fun `some Activities lose GPS for a few hundred metres, as in a tunnel`() {
        val gappy = activities.count { a -> a.route.zipWithNext().any { (p, q) -> metresBetween(p, q) in 150.0..900.0 } }
        assertTrue(gappy in 20..120, "$gappy Activities with a GPS gap")
    }

    private fun cell(p: LonLat) = Pair((p.lon * 1_000).toInt(), (p.lat * 1_000).toInt())
}
