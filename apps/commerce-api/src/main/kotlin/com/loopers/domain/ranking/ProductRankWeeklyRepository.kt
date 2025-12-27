package com.loopers.domain.ranking

interface ProductRankWeeklyRepository {
    fun findByYearWeek(yearWeek: String): List<ProductRankWeekly>

    fun findTopByYearWeekOrderByRank(
        yearWeek: String,
        limit: Int,
    ): List<ProductRankWeekly>
}
