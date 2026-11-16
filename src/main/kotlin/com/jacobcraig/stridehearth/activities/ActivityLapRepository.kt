package com.jacobcraig.stridehearth.activities

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ActivityLapRepository : JpaRepository<ActivityLap, Long> {
    fun findByActivityIdOrderByLapNumberAsc(activityId: Long): List<ActivityLap>

    fun deleteByActivityId(activityId: Long): Long
}
