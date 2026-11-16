package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import org.springframework.stereotype.Component

data class DetectedBestEffort(
    val benchmarkDistance: BenchmarkDistance,
    val elapsedTimeSeconds: Double,
    val window: EffortWindow,
) {
    constructor(
        benchmarkDistance: BenchmarkDistance,
        elapsedTimeSeconds: Double,
        startIndex: Int,
        endIndex: Int,
        startTimeSeconds: Double,
        endTimeSeconds: Double,
        startDistanceMeters: Double,
        endDistanceMeters: Double,
    ) : this(
        benchmarkDistance = benchmarkDistance,
        elapsedTimeSeconds = elapsedTimeSeconds,
        window =
            EffortWindow(
                startIndex = startIndex,
                endIndex = endIndex,
                startTimeSeconds = startTimeSeconds,
                endTimeSeconds = endTimeSeconds,
                startDistanceMeters = startDistanceMeters,
                endDistanceMeters = endDistanceMeters,
            ),
    )

    val startIndex: Int get() = window.startIndex
    val endIndex: Int get() = window.endIndex
    val startTimeSeconds: Double get() = window.startTimeSeconds
    val endTimeSeconds: Double get() = window.endTimeSeconds
    val startDistanceMeters: Double get() = window.startDistanceMeters
    val endDistanceMeters: Double get() = window.endDistanceMeters

    fun toEntity(
        activityId: Long,
        sport: ActivitySport,
    ): ActivityBestEffort =
        ActivityBestEffort(
            activityId = activityId,
            sport = sport,
            benchmarkDistance = benchmarkDistance,
            elapsedTimeSeconds = elapsedTimeSeconds,
            window = window,
        )
}

/**
 * Pure **Best Effort** detection: the fastest continuous window for each of the sport's **Benchmark Distances**
 * in the given **Activity Details** (ADR-0003's two-pointer sliding window with boundary interpolation). It
 * saves nothing; [BestEffortRefresh] persists what it detects.
 */
@Component
class BestEffortDetector {
    fun detectBestEfforts(
        telemetry: FitTelemetry,
        sport: ActivitySport,
    ): List<DetectedBestEffort> {
        val benchmarks = BenchmarkDistance.forSport(sport)
        if (benchmarks.isEmpty()) return emptyList()
        val t = telemetry.t
        val dist = telemetry.distance
        if (t.isEmpty() || dist.isEmpty() || t.size != dist.size) return emptyList()

        val sampleIndices = IntArray(t.size)
        val sampleTimes = IntArray(t.size)
        val sampleDistances = DoubleArray(t.size)
        var sampleCount = 0

        var lastDist = -1.0
        for (i in t.indices) {
            val d = dist[i]?.toDouble() ?: continue
            if (d >= lastDist) {
                sampleIndices[sampleCount] = i
                sampleTimes[sampleCount] = t[i]
                sampleDistances[sampleCount] = d
                lastDist = d
                sampleCount++
            }
        }

        if (sampleCount < 2) return emptyList()
        val totalDistance = sampleDistances[sampleCount - 1] - sampleDistances[0]
        val results = mutableListOf<DetectedBestEffort>()

        for (benchmark in benchmarks) {
            val benchmarkDistanceMeters = benchmark.distanceMeters
            if (totalDistance < benchmarkDistanceMeters) continue

            var best: DetectedBestEffort? = null

            // Pass A: Left-aligned candidate windows (start anchored at recorded samples)
            var right = 0
            for (left in 0 until sampleCount) {
                val startDist = sampleDistances[left]
                val windowEndDist = startDist + benchmarkDistanceMeters
                if (windowEndDist > sampleDistances[sampleCount - 1]) break

                while (right < sampleCount && sampleDistances[right] < windowEndDist) {
                    right++
                }
                if (right >= sampleCount) break

                val (endTime, endIndex) =
                    if (sampleDistances[right] == windowEndDist) {
                        sampleTimes[right].toDouble() to sampleIndices[right]
                    } else {
                        interpolateTimestamp(
                            sampleTimes[right - 1],
                            sampleTimes[right],
                            sampleDistances[right - 1],
                            sampleDistances[right],
                            windowEndDist,
                        ) to sampleIndices[right]
                    }

                best =
                    evaluateCandidate(
                        currentBest = best,
                        benchmark = benchmark,
                        window =
                            EffortWindow(
                                startIndex = sampleIndices[left],
                                endIndex = endIndex,
                                startTimeSeconds = sampleTimes[left].toDouble(),
                                endTimeSeconds = endTime,
                                startDistanceMeters = startDist,
                                endDistanceMeters = windowEndDist,
                            ),
                    )
            }

            // Pass B: Right-aligned candidate windows (end anchored at recorded samples)
            var left = 0
            for (right in 0 until sampleCount) {
                val endDist = sampleDistances[right]
                val windowStartDist = endDist - benchmarkDistanceMeters
                if (windowStartDist < sampleDistances[0]) continue

                while (left + 1 < sampleCount && sampleDistances[left + 1] <= windowStartDist) {
                    left++
                }

                val (startTime, startIndex) =
                    if (sampleDistances[left] == windowStartDist) {
                        sampleTimes[left].toDouble() to sampleIndices[left]
                    } else {
                        interpolateTimestamp(
                            sampleTimes[left],
                            sampleTimes[left + 1],
                            sampleDistances[left],
                            sampleDistances[left + 1],
                            windowStartDist,
                        ) to sampleIndices[left]
                    }

                best =
                    evaluateCandidate(
                        currentBest = best,
                        benchmark = benchmark,
                        window =
                            EffortWindow(
                                startIndex = startIndex,
                                endIndex = sampleIndices[right],
                                startTimeSeconds = startTime,
                                endTimeSeconds = sampleTimes[right].toDouble(),
                                startDistanceMeters = windowStartDist,
                                endDistanceMeters = endDist,
                            ),
                    )
            }

            best?.let { results.add(it) }
        }

        return results
    }

    private fun evaluateCandidate(
        currentBest: DetectedBestEffort?,
        benchmark: BenchmarkDistance,
        window: EffortWindow,
    ): DetectedBestEffort {
        val elapsed = window.durationSeconds
        return if (currentBest == null || elapsed < currentBest.elapsedTimeSeconds - 1e-9) {
            DetectedBestEffort(
                benchmarkDistance = benchmark,
                elapsedTimeSeconds = elapsed,
                window = window,
            )
        } else {
            currentBest
        }
    }

    private fun interpolateTimestamp(
        prevTime: Int,
        nextTime: Int,
        prevDistance: Double,
        nextDistance: Double,
        targetDistance: Double,
    ): Double {
        val distSpan = nextDistance - prevDistance
        val fraction = (targetDistance - prevDistance) / distSpan
        return prevTime + fraction * (nextTime - prevTime)
    }
}
