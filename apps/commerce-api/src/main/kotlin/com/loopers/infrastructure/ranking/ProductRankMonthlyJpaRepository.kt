package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.ProductRankMonthly
import com.loopers.domain.ranking.ProductRankMonthlyRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface ProductRankMonthlyJpaRepository : JpaRepository<ProductRankMonthly, Long> {
    fun findByYearMonth(yearMonth: String): List<ProductRankMonthly>
}

@Repository
class ProductRankMonthlyRepositoryImpl(private val jpaRepository: ProductRankMonthlyJpaRepository) :
    ProductRankMonthlyRepository {

    override fun findByYearMonth(yearMonth: String) = jpaRepository.findByYearMonth(yearMonth)

    override fun findTopByYearMonthOrderByRank(
        yearMonth: String,
        limit: Int,
    ) = jpaRepository.findByYearMonth(yearMonth)
            .sortedBy { it.rank }
            .take(limit)
}
