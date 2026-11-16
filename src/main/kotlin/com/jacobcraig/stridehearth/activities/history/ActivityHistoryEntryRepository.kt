package com.jacobcraig.stridehearth.activities.history

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** Injected only by [ActivityHistory]. */
@Repository
interface ActivityHistoryEntryRepository : JpaRepository<ActivityHistoryEntry, Long> {
    fun findByActivityIdOrderByCreatedAtAscIdAsc(activityId: Long): List<ActivityHistoryEntry>
}
