package com.jacobcraig.stridehearth.api

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.data.domain.Page

/** One page of a paginated collection returned by the JSON API. [page] is zero-based. */
data class PagedResponseDTO<T>(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val items: List<T>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val page: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val size: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val totalElements: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val totalPages: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val hasNext: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val hasPrevious: Boolean,
)

fun <T : Any> Page<T>.toPagedResponse(): PagedResponseDTO<T> =
    PagedResponseDTO(
        items = content,
        page = number,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
        hasNext = hasNext(),
        hasPrevious = hasPrevious(),
    )
