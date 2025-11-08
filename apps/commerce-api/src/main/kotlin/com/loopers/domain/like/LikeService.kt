package com.loopers.domain.like

import com.loopers.domain.product.ProductRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Service

@Service
class LikeService(
    private val likeRepository: LikeRepository,
    private val productRepository: ProductRepository
) {
    fun addLike(userId: Long, productId: Long) {
        // 상품 존재 확인
        if (!productRepository.existsById(productId)) {
            throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다: $productId")
        }

        // 멱등성: 이미 존재하면 저장하지 않음
        if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
            return
        }

        val like = Like(userId = userId, productId = productId)
        likeRepository.save(like)
    }

    fun removeLike(userId: Long, productId: Long) {
        // 상품 존재 확인
        if (!productRepository.existsById(productId)) {
            throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다: $productId")
        }

        // 멱등성: 없어도 성공
        likeRepository.deleteByUserIdAndProductId(userId, productId)
    }
}
