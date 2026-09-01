package com.stefan.payment_api_service.models.dto

import org.springframework.data.domain.Page

data class PageResponseDTO<T> (
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
) {
    companion object {
        fun <E : Any, T> from (page: Page<E>, mapper: (E) -> T) = PageResponseDTO(
            content = page.content.map(mapper),
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages
        )
    }
}