package com.jacobcraig.stridehearth.activities

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface ActivityRepository :
    JpaRepository<Activity, Long>,
    JpaSpecificationExecutor<Activity> {
    @EntityGraph(attributePaths = ["user"])
    fun findByUserId(userId: Long): List<Activity>

    @EntityGraph(attributePaths = ["user"])
    fun findByIdAndUserId(
        id: Long,
        userId: Long,
    ): Activity?

    // mirrors uq_activity_user_source_external_id constraint
    fun existsByUserIdAndSourceAndExternalId(
        userId: Long,
        source: ActivitySource,
        externalId: String,
    ): Boolean

    fun findByUserIdAndSourceAndExternalId(
        userId: Long,
        source: ActivitySource,
        externalId: String,
    ): Activity?

    @EntityGraph(attributePaths = ["user"])
    override fun findAll(
        spec: Specification<Activity>,
        pageable: Pageable,
    ): Page<Activity>

    /** The athlete's Activities matching [filter]. */
    fun findFiltered(
        userId: Long,
        filter: ActivityFilter,
        pageable: Pageable,
    ): Page<Activity> {
        val (titleContains, sport, source, workoutType) = filter
        val spec =
            Specification<Activity> { root, _, cb ->
                val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
                predicates.add(cb.equal(root.get<com.jacobcraig.stridehearth.users.User>("user").get<Long>("id"), userId))
                if (!titleContains.isNullOrBlank()) {
                    val literal =
                        titleContains
                            .trim()
                            .lowercase()
                            .replace("\\", "\\\\")
                            .replace("%", "\\%")
                            .replace("_", "\\_")
                    predicates.add(cb.like(cb.lower(root.get("title")), "%$literal%", '\\'))
                }
                if (sport != null) {
                    predicates.add(cb.equal(root.get<com.jacobcraig.stridehearth.system.ActivitySport>("sport"), sport))
                }
                if (source != null) {
                    predicates.add(cb.equal(root.get<ActivitySource>("source"), source))
                }
                if (workoutType != null) {
                    predicates.add(cb.equal(root.get<String>("storedWorkoutType"), workoutType.name))
                }
                cb.and(*predicates.toTypedArray())
            }
        return findAll(spec, pageable)
    }

    @EntityGraph(attributePaths = ["user"])
    fun findByIdInAndUserId(
        ids: Collection<Long>,
        userId: Long,
    ): List<Activity>
}
