package com.jacobcraig.stridehearth.heatmap.performance

import com.jacobcraig.stridehearth.heatmap.LonLat
import com.jacobcraig.stridehearth.heatmap.METRES_PER_DEGREE_LAT
import com.jacobcraig.stridehearth.system.ActivitySport
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.random.Random

/** One synthetic Activity: its sport, start time and route, one point per recorded GPS sample. */
class SyntheticActivity(
    val sport: ActivitySport,
    val startedAt: Instant,
    val route: List<LonLat>,
)

/**
 * A synthetic athlete's training, clustered like real training for the Heatmap performance harness (#110):
 *
 * - **Dense home corridors.** Most Activities start at [HOME] and follow one of a set of favourite routes on a
 *   street lattice, out-and-back or round a loop, so the same streets are passed hundreds of times. Favourites
 *   are picked with a Zipf-like weighting, and each pass drifts a few metres sideways like real GPS.
 * - **A few far-flung trips** to other cities, each with a handful of routes of its own.
 * - **A mix of sports**, mostly runs, with rides, walks and hikes, sampled at each sport's speed and recording
 *   interval so an Activity averages about 2,000 route points.
 * - **Occasional GPS jumps** of over a kilometre, and **gaps** of a few hundred metres where the signal was
 *   lost: whether the heatmap route split leaves a gap out depends on the sport's GPS-jump threshold.
 *
 * The same seed always gives the same Activities. [activities] is lazy, so 10k Activities (about 20M points)
 * never sit in memory at once.
 */
class SyntheticTraining(
    private val seed: Long,
) {
    /** [count] Activities, oldest first, spread over about six years. */
    fun activities(count: Int): Sequence<SyntheticActivity> =
        sequence {
            val random = Random(seed)
            val home = Area(HOME, seed)
            val trips = TRIP_CITIES.mapIndexed { i, city -> Area(city, seed + 1 + i) }
            for (i in 0 until count) {
                val sport = pickSport(random)
                val area = if (random.nextDouble() < TRIP_SHARE) trips[random.nextInt(trips.size)] else home
                val startedAt = EPOCH.plus(Duration.ofMinutes((i.toLong() * SIX_YEARS_MINUTES) / count))
                yield(SyntheticActivity(sport, startedAt, area.pass(sport, random)))
            }
        }

    private fun pickSport(random: Random): ActivitySport {
        val r = random.nextDouble()
        return when {
            r < 0.68 -> ActivitySport.RUN
            r < 0.88 -> ActivitySport.RIDE
            r < 0.96 -> ActivitySport.WALK
            else -> ActivitySport.HIKE
        }
    }

    /** How each sport moves and records: metres per second and seconds between GPS samples. */
    private class Pace(
        val speed: Double,
        val interval: Double,
        val metres: ClosedFloatingPointRange<Double>,
    )

    /** A place the athlete trains: a jittered street lattice around [centre] and favourite routes on it. */
    private class Area(
        private val centre: LonLat,
        seed: Long,
    ) {
        private val metresPerDegreeLon = METRES_PER_DEGREE_LAT * cos(Math.toRadians(centre.lat))
        private val favourites = mutableMapOf<ActivitySport, List<List<Point>>>()
        private val routeRandom = Random(seed * 7919)
        private val latticeSeed = seed * 1_000_003

        /** A street junction's position in metres east and north of [centre], jittered so streets aren't ruled. */
        private fun junction(
            i: Int,
            j: Int,
        ): Point {
            val jitter = Random(latticeSeed + i * 100_003L + j)
            return Point(i * BLOCK + jitter.nextDouble(-JITTER, JITTER), j * BLOCK + jitter.nextDouble(-JITTER, JITTER))
        }

        /** One pass of a favourite route for [sport], with this pass's GPS drift and the odd GPS jump. */
        fun pass(
            sport: ActivitySport,
            random: Random,
        ): List<LonLat> {
            val routes = favourites.getOrPut(sport) { List(FAVOURITES) { favourite(sport) } }
            // Zipf-like: the first favourites are run far more often than the rest.
            val rank = (routes.size * random.nextDouble().pow(2.2)).toInt().coerceAtMost(routes.size - 1)
            val pace = PACES.getValue(sport)
            val drift = random.nextDouble(-DRIFT, DRIFT)
            val step = pace.speed * random.nextDouble(0.9, 1.1) * pace.interval
            val points = sample(routes[rank], step, drift, random)
            if (random.nextDouble() < GAP_SHARE && points.size > 1_000) {
                // Lost signal, as in a tunnel: the route resumes a few hundred metres on, with no points between.
                val lost = (random.nextDouble(GAP_METRES.start, GAP_METRES.endInclusive) / step).toInt()
                val at = random.nextInt(10, points.size - lost - 10)
                points.subList(at, at + lost).clear()
            }
            if (random.nextDouble() < JUMP_SHARE && points.size > 100) {
                val at = random.nextInt(10, points.size - 60)
                for (k in at until at + 30) points[k] = Point(points[k].east + GLITCH, points[k].north + GLITCH)
            }
            return points.map { LonLat(centre.lon + it.east / metresPerDegreeLon, centre.lat + it.north / METRES_PER_DEGREE_LAT) }
        }

        /** A favourite route from the home junction: half out-and-back, half a loop home by another way. */
        private fun favourite(sport: ActivitySport): List<Point> {
            val pace = PACES.getValue(sport)
            val blocks = (routeRandom.nextDouble(pace.metres.start, pace.metres.endInclusive) / BLOCK).toInt()
            var i = 0
            var j = 0
            var heading = routeRandom.nextInt(4)
            val out = mutableListOf(0 to 0)
            repeat(blocks / 2) {
                heading = (heading + listOf(0, 0, 0, 1, 3)[routeRandom.nextInt(5)]) % 4
                val (di, dj) = HEADINGS[heading]
                if (abs(i + di) > LATTICE || abs(j + dj) > LATTICE) heading = (heading + 2) % 4
                i += HEADINGS[heading].first
                j += HEADINGS[heading].second
                out.add(i to j)
            }
            val back =
                if (routeRandom.nextBoolean()) {
                    out.reversed().drop(1)
                } else {
                    buildList {
                        while (i != 0 || j != 0) {
                            if (j == 0 || (i != 0 && routeRandom.nextBoolean())) i -= Integer.signum(i) else j -= Integer.signum(j)
                            add(i to j)
                        }
                    }
                }
            return (out + back).map { (a, b) -> junction(a, b) }
        }
    }

    private data class Point(
        val east: Double,
        val north: Double,
    )

    companion object {
        /** Home: Hyde Park, London, where the synthetic sample run and the Heatmap tests' routes run. */
        val HOME = LonLat(-0.165, 51.507)

        private val TRIP_CITIES =
            listOf(
                LonLat(2.3522, 48.8566), // Paris
                LonLat(-3.1883, 55.9533), // Edinburgh
                LonLat(6.8652, 45.9237), // Chamonix
                LonLat(-73.9712, 40.7831), // New York
                LonLat(151.2093, -33.8688), // Sydney
                LonLat(-16.6291, 28.2916), // Tenerife
            )
        private const val TRIP_SHARE = 0.03
        private const val JUMP_SHARE = 0.01
        private const val GAP_SHARE = 0.05
        private val GAP_METRES = 250.0..800.0

        private const val BLOCK = 350.0
        private const val JITTER = 60.0
        private const val LATTICE = 60
        private const val FAVOURITES = 30
        private const val DRIFT = 5.0
        private const val NOISE = 2.0
        private const val GLITCH = 1_500.0

        private val HEADINGS = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)

        private val PACES =
            mapOf(
                ActivitySport.RUN to Pace(speed = 3.0, interval = 1.0, metres = 5_000.0..11_000.0),
                ActivitySport.RIDE to Pace(speed = 7.5, interval = 3.0, metres = 30_000.0..60_000.0),
                ActivitySport.WALK to Pace(speed = 1.4, interval = 2.0, metres = 3_000.0..6_000.0),
                ActivitySport.HIKE to Pace(speed = 1.2, interval = 4.0, metres = 8_000.0..16_000.0),
            )

        private val EPOCH = Instant.parse("2020-01-01T07:00:00Z")
        private const val SIX_YEARS_MINUTES = 6L * 365 * 24 * 60

        /** Points every [step] metres along [polyline], shifted [drift] metres sideways plus a little noise. */
        private fun sample(
            polyline: List<Point>,
            step: Double,
            drift: Double,
            random: Random,
        ): MutableList<Point> {
            val points = mutableListOf<Point>()
            var carried = 0.0
            for ((a, b) in polyline.zipWithNext()) {
                val length = hypot(b.east - a.east, b.north - a.north)
                if (length == 0.0) continue
                // Sideways is a quarter turn from the street's heading, so a drifting pass runs alongside it.
                val side = Point(-(b.north - a.north) / length, (b.east - a.east) / length)
                var along = carried
                while (along < length) {
                    val f = along / length
                    points.add(
                        Point(
                            a.east + (b.east - a.east) * f + side.east * drift + random.nextDouble(-NOISE, NOISE),
                            a.north + (b.north - a.north) * f + side.north * drift + random.nextDouble(-NOISE, NOISE),
                        ),
                    )
                    along += step
                }
                carried = along - length
            }
            return points
        }
    }
}
