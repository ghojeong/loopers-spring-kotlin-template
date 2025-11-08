package com.loopers.domain.order

import com.loopers.domain.point.PointRepository
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.StockRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Service

data class OrderItemRequest(
    val productId: Long,
    val quantity: Int
)

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val productRepository: ProductRepository,
    private val stockRepository: StockRepository,
    private val pointRepository: PointRepository
) {
    fun createOrder(userId: Long, orderItemRequests: List<OrderItemRequest>): Order {
        // 1. 상품 존재 및 재고 사전 검증
        val orderItems = orderItemRequests.map { request ->
            val product = productRepository.findById(request.productId)
                ?: throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다: ${request.productId}")

            val stock = stockRepository.findByProductId(request.productId)
                ?: throw CoreException(ErrorType.NOT_FOUND, "재고 정보를 찾을 수 없습니다: ${request.productId}")

            if (!stock.isAvailable(request.quantity)) {
                throw CoreException(
                    ErrorType.BAD_REQUEST,
                    "재고 부족: 상품 ${product.name}, 현재 재고 ${stock.quantity}, 요청 수량 ${request.quantity}"
                )
            }

            // OrderItem 생성 (스냅샷)
            OrderItem(
                productId = product.id,
                productName = product.name,
                brandId = product.brand.id,
                brandName = product.brand.name,
                brandDescription = product.brand.description,
                quantity = request.quantity,
                priceAtOrder = product.price
            )
        }

        // 2. Order 생성 (총액 자동 계산)
        val order = Order(userId = userId, items = orderItems)
        val totalAmount = order.calculateTotalAmount()

        // 3. 포인트 검증
        val point = pointRepository.findByUserId(userId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다: $userId")

        if (!point.canDeduct(totalAmount)) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "포인트 부족: 현재 잔액 ${point.balance.amount}, 필요 금액 ${totalAmount.amount}"
            )
        }

        // 4. Order 저장
        val savedOrder = orderRepository.save(order)

        // 5. 재고 차감 (비관적 락)
        orderItemRequests.forEach { request ->
            val stock = stockRepository.findByProductIdWithLock(request.productId)
                ?: throw CoreException(ErrorType.NOT_FOUND, "재고 정보를 찾을 수 없습니다: ${request.productId}")
            stock.decrease(request.quantity)
            stockRepository.save(stock)
        }

        // 6. 포인트 차감 (비관적 락)
        val lockedPoint = pointRepository.findByUserIdWithLock(userId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다: $userId")
        lockedPoint.deduct(totalAmount)
        pointRepository.save(lockedPoint)

        return savedOrder
    }
}
