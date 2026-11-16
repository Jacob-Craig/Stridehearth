package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.api.RequestValidationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

/** How many **Activities** one listing returns: a fixed page, or every matching Activity (`all`). */
sealed interface ActivityPageSize {
    fun toPageable(
        page: Int,
        sort: Sort,
    ): Pageable

    data class Fixed(
        val size: Int,
    ) : ActivityPageSize {
        override fun toPageable(
            page: Int,
            sort: Sort,
        ): Pageable = PageRequest.of(page, size, sort)
    }

    /** Every matching Activity in a single page; the requested page index is ignored. */
    data object All : ActivityPageSize {
        override fun toPageable(
            page: Int,
            sort: Sort,
        ): Pageable = Pageable.unpaged(sort)
    }

    companion object {
        const val DEFAULT = "20"
        const val ALL = "all"
        const val MAX_FIXED_SIZE = 100
        const val INVALID_MESSAGE = "Page size must be a whole number from 1 to $MAX_FIXED_SIZE, or 'all'"

        /** @throws RequestValidationException when [raw] is neither `all` nor a size from 1 to [MAX_FIXED_SIZE]. */
        fun parse(raw: String): ActivityPageSize {
            val value = raw.trim()
            if (value.equals(ALL, ignoreCase = true)) return All
            val size = value.toIntOrNull()
            if (size == null || size !in 1..MAX_FIXED_SIZE) {
                throw RequestValidationException(mapOf("size" to INVALID_MESSAGE))
            }
            return Fixed(size)
        }
    }
}
