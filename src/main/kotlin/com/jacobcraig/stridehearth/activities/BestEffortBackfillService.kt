package com.jacobcraig.stridehearth.activities

import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

data class BackfillResult(
    val activitiesProcessed: Int,
    val bestEffortsCreated: Int,
)

/**
 * Computes **Best Efforts** for historical **Activities** that lack them: it picks one athlete's candidate
 * Activities in pages and refreshes each page through [BestEffortRefresh]. Candidates hold no Best Efforts, so
 * their Personal Records only ever gain efforts and the refresh's incremental update keeps them correct.
 *
 * Each page runs and commits in its own transaction, so the backfill holds one page's Personal Record locks at a
 * time (ADR-0006) and each page reaches the **Dashboard** as soon as it commits. A failing page rolls back on its
 * own and the failure is rethrown, leaving earlier pages committed; a rerun resumes, because the candidates are
 * exactly the Activities that still hold no Best Efforts.
 *
 * An Activity is a candidate when its sport has Benchmark Distances, its details hold at least two samples, it
 * holds no Best Efforts, and its recorded distance span or its summary distance reaches the shortest Benchmark
 * Distance for its sport. Whether the details have distance is not checked here: the scanning read synthesizes
 * it in memory when they lack it.
 */
@Service
class BestEffortBackfillService(
    private val jdbcClient: JdbcClient,
    private val entityManager: EntityManager,
    private val activityRepository: ActivityRepository,
    private val bestEffortRefresh: BestEffortRefresh,
    private val transactionTemplate: TransactionTemplate,
) {
    /**
     * Backfills [userId]'s candidate Activities, [pageSize] per page. It opens one transaction per page itself,
     * so it must not be called inside a transaction: that would hold every page's locks until the caller commits.
     */
    @Transactional(propagation = Propagation.NEVER)
    fun backfillForUser(
        userId: Long,
        pageSize: Int = 50,
    ): BackfillResult = runBackfill(userId, pageSize.coerceAtLeast(1))

    private fun runBackfill(
        userId: Long,
        pageSize: Int,
    ): BackfillResult {
        var lastActivityId = 0L
        var activitiesProcessed = 0
        var bestEffortsCreated = 0

        while (true) {
            // Only a committed page advances the scan: a failing page has rolled back and thrown.
            val page = transactionTemplate.execute { backfillPage(userId, lastActivityId, pageSize) } ?: break
            lastActivityId = page.lastActivityId
            activitiesProcessed += page.activitiesProcessed
            bestEffortsCreated += page.bestEffortsCreated
        }

        return BackfillResult(
            activitiesProcessed = activitiesProcessed,
            bestEffortsCreated = bestEffortsCreated,
        )
    }

    /** One committed page of the backfill: the last candidate id it covered and its counts. */
    private data class BackfillPage(
        val lastActivityId: Long,
        val activitiesProcessed: Int,
        val bestEffortsCreated: Int,
    )

    /**
     * Refreshes the next page of [userId]'s candidates after [afterActivityId], in the current transaction. Null when
     * no candidate is left.
     */
    private fun backfillPage(
        userId: Long,
        afterActivityId: Long,
        pageSize: Int,
    ): BackfillPage? {
        val candidateIds = fetchNextPage(userId, afterActivityId, pageSize)
        if (candidateIds.isEmpty()) return null

        val candidates = activityRepository.findAllById(candidateIds)
        val bestEffortsCreated = bestEffortRefresh.refresh(candidates).size
        // The refresh has flushed this page, so detach it to keep the persistence context from growing with every
        // page of a long backfill.
        entityManager.clear()
        return BackfillPage(
            lastActivityId = candidateIds.last(),
            activitiesProcessed = candidates.size,
            bestEffortsCreated = bestEffortsCreated,
        )
    }

    private fun fetchNextPage(
        userId: Long,
        afterActivityId: Long,
        limit: Int,
    ): List<Long> {
        val sql =
            """
            SELECT a.id AS activity_id
            FROM activity a
            JOIN ${BenchmarkDistance.shortestBenchmarkPerSportSql} ON b.sport = a.sport
            JOIN activity_details d ON d.activity_id = a.id
            WHERE a.id > :afterId
              AND a.user_id = :userId
              AND cardinality(d.t) >= 2
              AND (
                  (SELECT max(v) - min(v) FROM unnest(d.distance) AS v WHERE v IS NOT NULL) >= b.min_meters
                  OR a.distance * 1000.0 >= b.min_meters
              )
              AND NOT EXISTS (
                  SELECT 1 FROM activity_best_effort abe WHERE abe.activity_id = a.id
              )
            ORDER BY a.id ASC
            LIMIT :limit
            """.trimIndent()

        return jdbcClient
            .sql(sql)
            .param("afterId", afterActivityId)
            .param("userId", userId)
            .param("limit", limit)
            .query { rs, _ -> rs.getLong("activity_id") }
            .list()
    }
}
