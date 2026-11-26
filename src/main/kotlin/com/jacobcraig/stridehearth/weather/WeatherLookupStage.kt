package com.jacobcraig.stridehearth.weather

import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.DERIVED_DATA_VERSION
import com.jacobcraig.stridehearth.activities.DerivedDataRefresh
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/**
 * The background stage that looks weather up after an import has committed (ADR-0017), present only for
 * `stridehearth.weather.provider=open-meteo`. It never runs inside an import or [DerivedDataRefresh]'s transaction:
 * the provider call is made with no transaction and no lock open, and only caching the result and refreshing the
 * Activity's derived data share one short transaction.
 *
 * A failed lookup (provider down, slow, rate-limited) ends the run and leaves the Activity without a cache row, so
 * the next run tries it again. Activities newer than [archiveLag] are not asked for, as ERA5 has no data for them yet.
 */
@Component
@ConditionalOnProperty(name = ["stridehearth.weather.provider"], havingValue = "open-meteo")
class WeatherLookupStage(
    private val client: OpenMeteoClient,
    private val repository: ActivityWeatherRepository,
    private val activityRepository: ActivityRepository,
    private val derivedDataRefresh: DerivedDataRefresh,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    @param:Value("\${stridehearth.weather.archive-lag:P6D}") private val archiveLag: Duration,
    @param:Value("\${stridehearth.weather.lookups-per-run:100}") private val lookupsPerRun: Int,
) {
    private val logger = LoggerFactory.getLogger(WeatherLookupStage::class.java)

    @Scheduled(
        fixedDelayString = "\${stridehearth.weather.interval-ms:60000}",
        initialDelayString = "\${stridehearth.weather.initial-delay-ms:60000}",
    )
    fun run() {
        try {
            val cached = runOnce()
            if (cached > 0) logger.info("Weather: cached the weather of {} Activities", cached)
        } catch (e: Exception) {
            logger.warn("Weather lookup failed; it resumes at the next run: {}", e.message)
        }
    }

    /** Looks up and caches up to one run's worth of Activities, stopping (by throwing) at the first failure. Returns how many it cached. */
    fun runOnce(): Int {
        val candidates = repository.findLookupCandidates(clock.instant().minus(archiveLag), DERIVED_DATA_VERSION, lookupsPerRun)
        var cached = 0
        for (candidate in candidates) {
            val observation = client.lookup(candidate.latitude, candidate.longitude, candidate.startedAt)
            val stored =
                transactions.execute {
                    val activity = activityRepository.findById(candidate.activityId).orElse(null)
                    if (activity != null && repository.insert(candidate.activityId, observation)) {
                        derivedDataRefresh.refresh(activity)
                        true
                    } else {
                        false
                    }
                }
            if (stored == true) cached++
        }
        return cached
    }
}
