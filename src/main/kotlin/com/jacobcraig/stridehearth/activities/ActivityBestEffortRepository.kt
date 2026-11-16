package com.jacobcraig.stridehearth.activities

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ActivityBestEffortRepository : JpaRepository<ActivityBestEffort, Long> {
    fun findByActivityId(activityId: Long): List<ActivityBestEffort>

    /** Only [BestEffortRefresh] writes Best Efforts; everything else reads them. */
    fun deleteByActivityId(activityId: Long): Long
}
