package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ProductJpaRepository : JpaRepository<Product, Long> {
    fun findByBrandId(brandId: Long, pageable: Pageable): Page<Product>

    @Query(
        value = """
            SELECT p.* FROM products p
            LEFT JOIN likes l ON l.product_id = p.id
            GROUP BY p.id
            ORDER BY COUNT(l.id) DESC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT p.id) FROM products p
        """,
        nativeQuery = true
    )
    fun findAllOrderByLikeCount(pageable: Pageable): Page<Product>

    @Query(
        value = """
            SELECT p.* FROM products p
            LEFT JOIN likes l ON l.product_id = p.id
            WHERE p.brand_id = :brandId
            GROUP BY p.id
            ORDER BY COUNT(l.id) DESC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT p.id) FROM products p
            WHERE p.brand_id = :brandId
        """,
        nativeQuery = true
    )
    fun findByBrandIdOrderByLikeCount(brandId: Long, pageable: Pageable): Page<Product>
}
