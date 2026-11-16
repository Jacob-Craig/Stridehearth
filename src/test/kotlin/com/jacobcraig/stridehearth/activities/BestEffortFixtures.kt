package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** Linear Activity Details covering [meters] in [seconds], so every Benchmark Distance takes `seconds * d / meters`. */
fun steadyDetails(
    meters: Float,
    seconds: Int,
) = FitTelemetry(
    t = intArrayOf(0, seconds),
    lons = doubleArrayOf(),
    lats = doubleArrayOf(),
    routeIndex = arrayOfNulls(2),
    distance = arrayOf(0.0f, meters),
)

/**
 * Seeds **Activities** for tests of the Best Effort refresh module and reads back the athlete's **Personal
 * Records**. Activities belong to [defaultOwner] unless a test names another athlete.
 */
class BestEffortFixtures(
    private val activityRepository: ActivityRepository,
    private val activityDetailsRepository: ActivityDetailsRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val bestEffortRefresh: BestEffortRefresh,
    private val transactionTemplate: TransactionTemplate,
    private val defaultOwner: User,
) {
    /** Runs [block] in a transaction, joining the caller's transaction if there is one. */
    fun <T> inTransaction(block: () -> T): T = transactionTemplate.execute { block() }!!

    /** Saves an Activity and, when given, its Activity Details at [parserVersion], without refreshing it. */
    fun storeActivity(
        title: String,
        startedAt: String,
        details: FitTelemetry?,
        sport: ActivitySport = ActivitySport.RUN,
        owner: User = defaultOwner,
        parserVersion: ParserVersion = ParserVersion(1),
    ): Activity =
        inTransaction {
            val saved =
                activityRepository.save(
                    Activity(
                        title = title,
                        sport = sport,
                        startedAt = Instant.parse(startedAt),
                        distance = 5.0,
                        timeTakenSeconds = 1500,
                        user = owner,
                        source = ActivitySource.FIT_UPLOAD,
                    ),
                )
            details?.let { activityDetailsRepository.upsert(saved.id!!, it, parserVersion) }
            saved
        }

    /** Stores a new Activity and refreshes it with the details just stored, as the import does. */
    fun importActivity(
        title: String,
        startedAt: String,
        details: FitTelemetry,
        sport: ActivitySport = ActivitySport.RUN,
        owner: User = defaultOwner,
    ): Activity =
        inTransaction {
            val saved = storeActivity(title, startedAt, details, sport, owner)
            bestEffortRefresh.refresh(saved, details)
            saved
        }

    fun reload(activity: Activity): Activity = activityRepository.findById(activity.id!!).orElseThrow()

    /** The athlete's running Personal Records, keyed by Benchmark Distance. */
    fun runningRecordRows(owner: User = defaultOwner): Map<BenchmarkDistance, PersonalRecordRow> =
        personalRecordRepository
            .findByUserIdAndSport(owner.id!!, ActivitySport.RUN)
            .associateBy { it.benchmarkDistance }

    /** The athlete's running Personal Records as (Benchmark Distance -> holding Activity id, elapsed seconds). */
    fun runningRecords(owner: User = defaultOwner): Map<BenchmarkDistance, Pair<Long, Double>> =
        runningRecordRows(owner).mapValues { (_, record) -> record.activityId to record.elapsedTimeSeconds }

    /** [runningRecords] with elapsed seconds rounded to 4 decimal places, so they compare exactly. */
    fun roundedRunningRecords(owner: User = defaultOwner): Map<BenchmarkDistance, Pair<Long, Double>> =
        runningRecords(owner).mapValues { (_, record) -> record.first to Math.round(record.second * 10_000) / 10_000.0 }
}
