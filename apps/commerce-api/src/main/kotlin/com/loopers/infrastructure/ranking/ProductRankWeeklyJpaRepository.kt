package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.ProductRankWeekly
import com.loopers.domain.ranking.ProductRankWeeklyRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface ProductRankWeeklyJpaRepository : JpaRepository<ProductRankWeekly, Long> {
    fun findByYearWeek(yearWeek: String): List<ProductRankWeekly>
}

@Repository
class ProductRankWeeklyRepositoryImpl(private val jpaRepository: ProductRankWeeklyJpaRepository) : ProductRankWeeklyRepository {

    override fun findByYearWeek(yearWeek: String) = jpaRepository.findByYearWeek(yearWeek)

    override fun findTopByYearWeekOrderByRank(
        yearWeek: String,
        limit: Int,
    ) = jpaRepository.findByYearWeek(yearWeek)
            .sortedBy { it.rank }
            .take(limit)
}
