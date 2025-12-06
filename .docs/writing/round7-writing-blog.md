# "주문 하나 만들었는데 쿠폰 서버까지 느려진 이유"

**TL;DR**: "주문 생성할 때 쿠폰도 같이 사용 처리해주세요"라는 요구사항을 받고 하나의 트랜잭션에 다 넣었다. 그런데 **쿠폰 서비스가 느려지니 주문 생성까지 느려졌다**. "이게 맞나?" 싶어서 이벤트 기반으로 분리했더니, **쿠폰 서비스가 죽어도 주문은 정상적으로 생성**됐다. 트랜잭션을 나누는 것은 단순히 성능 문제가 아니라, **장애 격리와 시스템 안정성** 문제였다.

## "주문하면서 쿠폰도 써주세요"

### 처음 마주한 요구사항

Round 6에서 결제 시스템을 안정화하고 나니 자신감이 생겼다. "이제 외부 연동도 문제없다"고 생각했다.

그때 새로운 요구사항이 들어왔다:

> "주문 생성할 때 쿠폰 사용도 같이 처리해주세요. 할인 금액 계산까지 포함해서요."

"뭐 어렵겠어?" 이미 쿠폰 서비스가 있었고, 주문 생성 로직도 있었으니까.

**기존 흐름:**

```kotlin
fun createOrder(userId: Long, request: OrderCreateRequest): OrderCreateInfo {
    val totalAmount = calculateTotalAmount(orderItems)

    // 쿠폰 사용 (동기)
    val discountAmount = applyCoupon(userId, request.couponId, totalAmount)

    val finalAmount = totalAmount - discountAmount
    val order = orderService.createOrder(userId, orderItems)

    return OrderCreateInfo.from(order)
}

private fun applyCoupon(userId: Long, couponId: Long?, totalAmount: Money): Money {
    if (couponId == null) return Money.ZERO

    // 쿠폰 사용 처리 (비관적 락 사용)
    val userCoupon = couponService.useUserCoupon(userId, couponId)
    return userCoupon.coupon.calculateDiscount(totalAmount)
}
```

로컬에서 돌려보니 잘 작동했다. 주문도 생성되고, 쿠폰도 사용되고, 할인도 잘 적용됐다.

### 충격적인 결과

"실제 환경에서는 어떨까?" 부하 테스트를 해봤다.

```bash
# 동시 100명이 주문 생성
ab -n 100 -c 100 http://localhost:8080/api/v1/orders
```

**결과:**
- 첫 10초: 정상 동작 (응답 시간 ~200ms)
- 20초 후: 응답 속도 급격히 느려짐 (응답 시간 ~2000ms)
- 30초 후: **일부 주문이 타임아웃으로 실패**

"왜?! 쿠폰 서비스가 느려지니 주문까지 느려지잖아?"

### 로그를 보고 알게 된 것

로그를 확인하니 패턴이 보였다:

```
14:30:10 [http-nio-200] 쿠폰 사용 시작: userId=1, couponId=100
14:30:11 [http-nio-200] 비관적 락 대기 중... (1초)
14:30:12 [http-nio-200] 비관적 락 대기 중... (2초)
14:30:13 [http-nio-200] 쿠폰 사용 완료
14:30:13 [http-nio-200] 주문 생성 완료 (총 소요 시간: 3초)
```

**문제 분석:**

| 시점 | 상황 | 영향 |
|------|------|------|
| 동시 주문 100개 | 같은 쿠폰 사용 시도 | 비관적 락 경합 |
| 락 대기 시간 증가 | 쿠폰 처리 지연 | 주문 생성도 지연 |
| 트랜잭션 길어짐 | DB 커넥션 점유 시간 증가 | 전체 시스템 성능 저하 |

**"하나의 트랜잭션에 다 넣으면 안 되는구나..."**

처음 알았다. 트랜잭션을 나누는 것은 단순히 "성능 최적화"가 아니라 **"장애 격리"**의 문제라는 것을.

## "꼭 지금 해야 하는 걸까?"

### 내부의 고민

현재 흐름을 보니 모든 것이 동기적으로 처리되고 있었다:

**현재 주문 생성 흐름:**

```
createOrder()
 ├── 재고 차감 (필수)
 ├── 쿠폰 사용 (필수?)
 ├── 포인트 차감 (필수)
 └── 주문 저장 (필수)
```

"쿠폰 사용이 **지금 당장** 필요할까?"

다시 생각해보니:
- **할인 금액 계산**: 지금 필요 (주문 금액 결정)
- **쿠폰 실제 사용**: 나중에 해도 됨 (주문 생성 후)

| 항목 | 지금 필요한가? | 이유 |
|------|---------------|------|
| 재고 차감 | ✅ 필수 | 재고가 없으면 주문 불가 |
| 할인 계산 | ✅ 필수 | 최종 금액 결정 |
| **쿠폰 사용** | ⚠️ **나중에 가능** | 할인 계산만 하면 됨 |
| 포인트 차감 | ✅ 필수 | 결제 금액 확정 |

**"쿠폰은 검증만 하고, 실제 사용은 나중에 하면 되겠네!"**

결정했다. **"지금 꼭 해야 하는 것"**과 **"조금 나중에 해도 되는 것"**을 분리한다.

### Command vs Event

트랜잭션을 나누는 도구로 이벤트를 사용하기로 했다.

**Command (명령):**
- "쿠폰을 **사용해라**" → 직접 호출
- 실패하면 전체 롤백
- 강한 결합

**Event (이벤트):**
- "주문이 **생성되었다**" → 이벤트 발행
- 리스너가 알아서 처리
- 느슨한 결합

| 항목 | Command | Event |
|------|---------|-------|
| 의미 | "~을 해라" (명령) | "~이 발생했다" (통지) |
| 흐름 제어 | 호출자가 제어 | 호출자는 모름 |
| 실패 영향 | 전체 롤백 | **격리됨** |

## ApplicationEvent로 분리하기

### Spring의 이벤트 메커니즘

Spring은 이벤트 기반 구조를 제공한다:

| 구성 요소 | 역할 |
|----------|------|
| `ApplicationEventPublisher` | 이벤트 발행 |
| `@EventListener` | 이벤트 수신 |
| `@TransactionalEventListener` | **트랜잭션 커밋 후** 실행 |
| `@Async` | 비동기 실행 |

**핵심: `@TransactionalEventListener(phase = AFTER_COMMIT)`**

이 어노테이션은 **트랜잭션이 성공적으로 커밋된 후에만** 이벤트를 처리한다.

```kotlin
// 주문 생성 (메인 트랜잭션)
@Transactional
fun createOrder(...) {
    val order = orderService.createOrder(...)
    eventPublisher.publishEvent(OrderCreatedEvent.from(order, couponId))
    // 여기서 커밋되면...
}

// 쿠폰 사용 (별도 트랜잭션)
@Async
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handleOrderCreatedForCoupon(event: OrderCreatedEvent) {
    event.couponId?.let { couponId ->
        couponService.useUserCoupon(event.userId, couponId)
    }
}
```

### 주문의 완결성을 지키는 AFTER_COMMIT

"쿠폰 사용은 주문이 커밋된 후에 처리한다"

이 문장이 얼마나 중요한지 처음엔 몰랐다.

#### 만약 AFTER_COMMIT을 안 쓰면?

**시나리오: 일반 @EventListener 사용**

```kotlin
// 주문 생성
@Transactional
fun createOrder(...) {
    val order = orderService.createOrder(...)
    eventPublisher.publishEvent(OrderCreatedEvent.from(order, couponId))
    // 여기서 이벤트가 즉시 발행됨

    // 만약 여기서 예외 발생?
    throw RuntimeException("재고 부족!")
}

// 쿠폰 사용 (일반 EventListener)
@EventListener
fun handleOrderCreatedForCoupon(event: OrderCreatedEvent) {
    couponService.useUserCoupon(event.userId, event.couponId)
    // 이미 쿠폰 사용됨!
}
```

**문제:**

```
1. 주문 생성 시작
   ↓
2. 이벤트 발행 (OrderCreatedEvent)
   ↓
3. 쿠폰 즉시 사용 (별도 트랜잭션에서)
   ↓
4. 주문 생성 실패 (재고 부족으로 롤백)
   ↓
❌ 결과: 주문은 없는데 쿠폰만 사용됨
```

**사용자 관점:**
- "주문이 실패했는데 쿠폰이 차감되었어요!"
- "환불해주세요!"

**"이건 심각한 데이터 정합성 문제다..."**

#### AFTER_COMMIT이 해결하는 방법

```kotlin
// 주문 생성
@Transactional
fun createOrder(...) {
    val order = orderService.createOrder(...)
    eventPublisher.publishEvent(OrderCreatedEvent.from(order, couponId))
    // 이벤트는 발행되지만, 핸들러는 아직 실행 안 됨

    // 만약 여기서 예외 발생?
    throw RuntimeException("재고 부족!")
    // → 롤백되면서 이벤트도 발행 취소!
}

// 쿠폰 사용 (AFTER_COMMIT)
@Async
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handleOrderCreatedForCoupon(event: OrderCreatedEvent) {
    // 주문이 성공적으로 커밋된 후에만 실행됨
    couponService.useUserCoupon(event.userId, event.couponId)
}
```

**정상 흐름:**

```
1. 주문 생성 시작
   ↓
2. 이벤트 발행 예약 (OrderCreatedEvent)
   ↓
3. 주문 생성 성공
   ↓
4. 트랜잭션 커밋 ✅
   ↓
5. 커밋 후 쿠폰 사용 실행
   ↓
✅ 결과: 주문도 있고, 쿠폰도 사용됨
```

**실패 흐름:**

```
1. 주문 생성 시작
   ↓
2. 이벤트 발행 예약
   ↓
3. 재고 부족 예외 발생
   ↓
4. 트랜잭션 롤백 ❌
   ↓
5. 이벤트 발행 취소 (쿠폰 사용 안 됨)
   ↓
✅ 결과: 주문도 없고, 쿠폰도 안 사용됨 (정합성 유지!)
```

#### 주문의 완결성 보장

AFTER_COMMIT이 보장하는 것들:

| 보장 항목 | 설명 |
|----------|------|
| **원자성** | 주문이 롤백되면 쿠폰 사용도 안 됨 |
| **데이터 정합성** | 실제로 존재하는 주문에 대해서만 쿠폰 사용 |
| **사용자 신뢰** | "주문 실패했는데 쿠폰만 빠졌어요" 문제 없음 |
| **재시도 안전성** | 커밋 전 실패는 이벤트 미발행 |

**"주문이 성공했을 때만 후속 처리한다"**

이게 AFTER_COMMIT의 핵심 가치다.

#### 실제 발생 가능한 시나리오

**시나리오 1: DB 커넥션 타임아웃**

```kotlin
@Transactional
fun createOrder(...) {
    val order = orderService.createOrder(...) // 성공
    eventPublisher.publishEvent(OrderCreatedEvent.from(order, couponId))

    // DB 커넥션 타임아웃 발생
    throw QueryTimeoutException("Connection timeout")
}
```

- **일반 EventListener**: 쿠폰 이미 사용됨 → 데이터 불일치
- **AFTER_COMMIT**: 커밋 안 됨 → 쿠폰 사용 안 됨 → 정합성 유지

**시나리오 2: 동시성 문제로 롤백**

```kotlin
@Transactional
fun createOrder(...) {
    val order = orderService.createOrder(...)
    eventPublisher.publishEvent(OrderCreatedEvent.from(order, couponId))

    // 낙관적 락 예외
    throw OptimisticLockException("Version mismatch")
}
```

- **일반 EventListener**: 쿠폰 이미 사용됨
- **AFTER_COMMIT**: 롤백으로 쿠폰 사용 안 됨

**시나리오 3: 비즈니스 검증 실패**

```kotlin
@Transactional
fun createOrder(...) {
    val order = orderService.createOrder(...)
    eventPublisher.publishEvent(OrderCreatedEvent.from(order, couponId))

    // 주문 금액 검증
    if (order.totalAmount < 0) {
        throw IllegalArgumentException("Invalid amount")
    }
}
```

- **일반 EventListener**: 쿠폰만 사용되고 주문은 실패
- **AFTER_COMMIT**: 주문 실패 시 쿠폰도 안 사용됨

#### 코드로 확인하기

**현재 OrderEventHandler 구현:**

```kotlin
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
fun handleOrderCreatedForCoupon(event: OrderCreatedEvent) {
    event.couponId?.let { couponId ->
        try {
            couponService.useUserCoupon(event.userId, couponId)
            logger.info("쿠폰 사용 완료: orderId=${event.orderId}")
        } catch (e: Exception) {
            // 이 시점에는 주문은 이미 커밋된 상태
            // 쿠폰 사용 실패는 주문에 영향 없음
            logger.error("쿠폰 사용 실패: orderId=${event.orderId}", e)
        }
    }
}
```

**핵심 포인트:**

1. **`phase = TransactionPhase.AFTER_COMMIT`**
   - 주문 트랜잭션이 성공적으로 커밋된 후에만 실행
   - 주문이 롤백되면 이 메서드는 호출조차 안 됨

2. **`@Async`와의 조합**
   - 커밋 후 비동기 실행
   - 주문 응답은 빠르게, 쿠폰 처리는 백그라운드에서

3. **try-catch 위치의 의미**
   - 이 시점에는 주문은 이미 커밋됨
   - 쿠폰 실패해도 주문은 유지 (장애 격리)

**"주문의 완결성"이란:**

```
주문 트랜잭션의 성공/실패에 따라
후속 처리도 함께 결정되는 것
```

AFTER_COMMIT 없이는 이 완결성을 보장할 수 없다.

### 이벤트 리스너의 두 얼굴

처음 이벤트를 사용할 때 헷갈렸던 부분이 있다.

**"@EventListener와 @TransactionalEventListener, 뭐가 다른 거지?"**

둘 다 이벤트를 처리하는데, 왜 두 개나 있을까?

#### @EventListener - 즉시 반응

```kotlin
@EventListener
fun handleUserAction(event: UserActionEvent) {
    logger.info("[USER_ACTION] ${event.actionType}")
    // 바로 실행됨
}
```

**특징:**
- 이벤트 발행되는 **즉시** 실행
- 발행한 쪽의 트랜잭션과 **같은 트랜잭션**에서 실행
- 롤백되면 같이 롤백됨

**언제 사용?**
- 로깅, 모니터링 같은 **부가 기능**
- 트랜잭션 결과와 무관한 작업

#### @TransactionalEventListener - 커밋 후 반응

```kotlin
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handleOrderCreatedForCoupon(event: OrderCreatedEvent) {
    couponService.useUserCoupon(event.userId, event.couponId)
    // 주문 트랜잭션이 커밋된 후에만 실행됨
}
```

**특징:**
- 트랜잭션이 **성공적으로 커밋된 후**에만 실행
- **별도 트랜잭션**에서 실행 (특히 @Async와 함께 사용 시)
- 롤백되면 이벤트 자체가 발행 안 됨

**언제 사용?**
- 주문 생성 후 쿠폰 사용처럼 **후속 처리**
- 실패해도 메인 로직에 영향 없어야 하는 작업

#### 실전 예시: 결제 완료 처리

"결제가 완료되면 주문 상태를 업데이트한다"

이건 어떤 리스너를 써야 할까?

**첫 번째 시도: @Async + @TransactionalEventListener**

```kotlin
@Async
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handlePaymentCompleted(event: PaymentCompletedEvent) {
    val order = orderRepository.findById(event.orderId)
    order.confirm()
    orderRepository.save(order)
}
```

**문제:**
- @Async로 비동기 실행
- 하지만 주문 상태 업데이트는 **즉시** 되어야 함
- 비동기로 하면 사용자가 주문 조회 시 아직 PENDING 상태일 수 있음

**두 번째 시도: @EventListener + @Transactional(REQUIRES_NEW)**

```kotlin
@EventListener
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun handlePaymentCompleted(event: PaymentCompletedEvent) {
    val order = orderRepository.findById(event.orderId)
    order.confirm()
    orderRepository.save(order)
}
```

**해결:**
- 결제 트랜잭션과 **별도 트랜잭션**에서 실행 (REQUIRES_NEW)
- 하지만 **동기적으로** 실행 → 즉시 반영
- 결제 실패 시 주문 업데이트 안 됨

| 항목 | @EventListener | @TransactionalEventListener |
|------|---------------|---------------------------|
| 실행 시점 | 즉시 | 커밋 후 |
| 트랜잭션 | 같은 트랜잭션 | 별도 가능 |
| 롤백 시 | 같이 롤백 | 이벤트 발행 안 됨 |
| 용도 | 로깅, 즉시 처리 | 후속 처리 |

**"언제 뭘 써야 하지?"**

| 시나리오 | 선택 | 이유 |
|---------|------|------|
| 사용자 행동 로깅 | @EventListener | 트랜잭션 성공/실패 무관 |
| 쿠폰 사용 | @TransactionalEventListener + @Async | 주문 커밋 후, 비동기 처리 |
| 주문 상태 업데이트 | @EventListener + REQUIRES_NEW | 즉시 반영, 별도 트랜잭션 |
| 외부 API 호출 | @TransactionalEventListener + @Async | 커밋 후, 비동기 처리 |

### 이벤트 정의

주요 이벤트들을 정의했다:

**주문-결제 관련:**
```kotlin
data class OrderCreatedEvent(
    val orderId: Long,
    val userId: Long,
    val amount: Long,
    val couponId: Long?,
    val createdAt: ZonedDateTime,
)

data class PaymentCompletedEvent(
    val paymentId: Long,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
    val transactionKey: String?,
)
```

**좋아요 관련:**
```kotlin
data class LikeAddedEvent(
    val userId: Long,
    val productId: Long,
    val createdAt: LocalDateTime,
)
```

### 이벤트 핸들러

**OrderEventHandler:**

```kotlin
@Component
class OrderEventHandler(
    private val couponService: CouponService,
) {
    @Async
    @TransactionalEventListener(phase = AFTER_COMMIT)
    fun handleOrderCreatedForCoupon(event: OrderCreatedEvent) {
        event.couponId?.let { couponId ->
            try {
                couponService.useUserCoupon(event.userId, couponId)
                logger.info("쿠폰 사용 완료: orderId=${event.orderId}")
            } catch (e: Exception) {
                // 쿠폰 사용 실패는 주문에 영향 없음
                logger.error("쿠폰 사용 실패: orderId=${event.orderId}", e)
            }
        }
    }
}
```

**핵심 포인트:**
- `@Async`: 별도 스레드에서 실행 → 주문 생성 응답 빠름
- `@TransactionalEventListener(AFTER_COMMIT)`: 주문 커밋 후 실행 → 안전
- `try-catch`: 쿠폰 실패해도 주문은 유지

### 서비스 레이어 수정

**OrderFacade 변경:**

**Before:**
```kotlin
val discountAmount = applyCoupon(userId, request.couponId, totalAmount)
// applyCoupon 내부에서 쿠폰 사용 처리 (동기)
```

**After:**
```kotlin
// 할인 금액만 계산 (쿠폰 정보 조회만)
val discountAmount = calculateCouponDiscount(userId, request.couponId, totalAmount)

val order = orderService.createOrder(userId, orderItems)

// 이벤트 발행 (실제 쿠폰 사용은 핸들러에서)
eventPublisher.publishEvent(OrderCreatedEvent.from(order, request.couponId))
```

**calculateCouponDiscount:**
```kotlin
private fun calculateCouponDiscount(userId: Long, couponId: Long?, totalAmount: Money): Money {
    if (couponId == null) return Money.ZERO

    // 쿠폰 정보 조회 (사용은 안 함)
    val userCoupon = couponService.getUserCoupon(userId, couponId)

    // 사용 가능 여부만 검증
    if (!userCoupon.canUse()) {
        throw CoreException(ErrorType.BAD_REQUEST, "사용할 수 없는 쿠폰입니다")
    }

    return userCoupon.coupon.calculateDiscount(totalAmount)
}
```

**변화:**
- 주문 생성 시: 쿠폰 검증 + 할인 계산
- 주문 커밋 후: 쿠폰 실제 사용 (이벤트)

## 결제 완료 후 주문 확정하기

### 또 다른 분리 포인트

쿠폰 사용은 분리했다. 그런데 또 다른 문제가 있었다.

**"결제가 완료되면 주문 상태를 어떻게 업데이트하지?"**

현재 흐름:

```
1. 주문 생성 (OrderFacade)
   └─> 주문 저장 (status: PENDING)

2. 결제 요청 (PaymentFacade)
   └─> PG 결제 시작

3. PG 콜백 (PaymentService)
   └─> 결제 상태 업데이트 (status: COMPLETED)

4. ??? → 주문 상태를 CONFIRMED로 어떻게?
```

### 첫 번째 시도: 직접 호출

**가장 간단한 방법:**

```kotlin
@Transactional
fun handlePaymentCallback(transactionKey: String, status: TransactionStatusDto) {
    val payment = paymentRepository.findByTransactionKey(transactionKey)

    when (status) {
        SUCCESS -> {
            payment.complete()

            // 주문 상태 직접 업데이트
            val order = orderRepository.findById(payment.orderId)
            order.confirm()
            orderRepository.save(order)
        }
    }
}
```

**문제:**
- PaymentService가 Order를 알아야 함 → 결합도 증가
- 결제 트랜잭션에 주문 업데이트까지 포함 → 트랜잭션 길어짐
- 주문 업데이트 실패하면 결제도 롤백? → 이상함

**"결제와 주문은 별개의 도메인인데, 왜 같은 트랜잭션에?"**

### 두 번째 시도: 이벤트로 분리

**PaymentService:**

```kotlin
@Transactional
fun handlePaymentCallback(transactionKey: String, status: TransactionStatusDto, reason: String?) {
    val payment = paymentRepository.findByTransactionKey(transactionKey)

    when (status) {
        SUCCESS -> {
            payment.complete(reason)

            // 이벤트 발행
            eventPublisher.publishEvent(PaymentCompletedEvent.from(payment))
        }
        FAILED -> {
            payment.fail(reason ?: "결제 실패")

            // 실패 이벤트도 발행
            eventPublisher.publishEvent(PaymentFailedEvent.from(payment))
        }
    }

    paymentRepository.save(payment)
}
```

**OrderEventHandler:**

```kotlin
@Component
class OrderEventHandler(
    private val orderRepository: OrderRepository,
) {
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handlePaymentCompleted(event: PaymentCompletedEvent) {
        try {
            logger.info("주문 상태 업데이트 시작: orderId=${event.orderId}")

            val order = orderRepository.findById(event.orderId)
                ?: throw CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다")

            order.confirm()
            orderRepository.save(order)

            logger.info("주문 상태 업데이트 완료: orderId=${event.orderId}, status=${order.status}")
        } catch (e: Exception) {
            logger.error("주문 상태 업데이트 실패: orderId=${event.orderId}", e)
            throw e
        }
    }
}
```

**핵심 포인트:**

1. **@EventListener (not @TransactionalEventListener)**
   - 결제 콜백 처리와 **동시에** 실행
   - 사용자가 주문 조회 시 즉시 CONFIRMED 상태 확인 가능

2. **@Transactional(propagation = REQUIRES_NEW)**
   - 결제 트랜잭션과 **완전히 분리**
   - 주문 업데이트 실패해도 결제는 완료된 상태 유지

3. **예외를 throw**
   - 주문 업데이트 실패는 심각한 문제
   - 로그만 남기지 않고 예외를 전파하여 모니터링

### 흐름 비교

**Before (직접 호출):**

```
[결제 콜백 트랜잭션]
  ├── 결제 상태 업데이트
  ├── 주문 상태 업데이트 (같은 트랜잭션)
  └── 커밋
```

**문제:**
- 하나의 트랜잭션에 두 도메인 포함
- 주문 업데이트 실패 시 결제도 롤백

**After (이벤트 기반):**

```
[결제 콜백 트랜잭션]
  ├── 결제 상태 업데이트
  ├── 이벤트 발행
  └── 커밋
      ↓
[주문 업데이트 트랜잭션] (REQUIRES_NEW)
  ├── 주문 조회
  ├── 주문 확정
  └── 커밋
```

**개선:**
- 별도 트랜잭션으로 분리
- 결제 실패해도 주문은 영향 없음
- 하지만 **동기적으로** 실행되어 즉시 반영

### 왜 @Async를 안 썼을까?

"주문 상태 업데이트도 비동기로 하면 안 되나?"

처음엔 @Async를 붙일까 고민했다. 하지만:

| 시나리오 | 문제 |
|---------|------|
| 결제 완료 | 즉시 주문 상태 확인 가능해야 함 |
| 비동기 처리 시 | 수십 ms 지연 → 사용자가 "결제 완료"인데 주문은 "대기 중"? |

**결론: 주문 상태는 즉시 반영되어야 한다**

쿠폰 사용은 나중에 처리해도 되지만, 주문 상태는 결제와 거의 동시에 업데이트되어야 한다.

### 결제 실패 처리

결제가 실패했을 때는?

```kotlin
@EventListener
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun handlePaymentFailed(event: PaymentFailedEvent) {
    try {
        logger.warn("결제 실패로 인한 주문 처리: orderId=${event.orderId}, reason=${event.reason}")

        val order = orderRepository.findById(event.orderId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다")

        // 주문 상태를 PENDING으로 유지하여 재시도 가능하도록
        // 또는 완전히 취소 처리 (비즈니스 정책에 따라)
        logger.info("결제 실패한 주문 유지: orderId=${event.orderId}, status=${order.status}")
    } catch (e: Exception) {
        logger.error("결제 실패 처리 중 오류: orderId=${event.orderId}", e)
    }
}
```

**정책 결정:**
- 결제 실패 시 주문을 바로 취소할 것인가?
- 아니면 PENDING 상태로 유지하여 재시도 기회를 줄 것인가?

현재 구현은 후자를 선택했다.

### 데이터 플랫폼 전송도 분리

결제와 주문 정보를 외부 데이터 플랫폼에 전송하는 것도 이벤트로 분리했다.

```kotlin
@Async
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handleOrderCreatedForDataPlatform(event: OrderCreatedEvent) {
    try {
        logger.info("데이터 플랫폼 전송 시작: orderId=${event.orderId}")
        sendToDataPlatform(event)
        logger.info("데이터 플랫폼 전송 완료: orderId=${event.orderId}")
    } catch (e: Exception) {
        // 전송 실패해도 주문은 정상 생성
        logger.error("데이터 플랫폼 전송 실패: orderId=${event.orderId}", e)
    }
}

@Async
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handlePaymentCompletedForDataPlatform(event: PaymentCompletedEvent) {
    try {
        sendPaymentCompletedToDataPlatform(event)
    } catch (e: Exception) {
        logger.error("결제 완료 데이터 플랫폼 전송 실패", e)
    }
}
```

**특징:**
- @Async + @TransactionalEventListener
- 커밋 후 비동기 실행
- 실패해도 주문/결제는 정상 처리

**결과:**
- 외부 시스템 장애가 우리 서비스에 영향 없음
- 데이터 플랫폼이 느려도 주문 응답 빠름

## 좋아요 집계도 분리하기

### 같은 문제, 다른 영역

좋아요 추가할 때도 비슷한 문제가 있었다:

**기존:**
```kotlin
fun addLike(userId: Long, productId: Long) {
    val like = Like(userId, productId)
    likeRepository.save(like)

    // 동기 처리
    productLikeCountService.increment(productId) // Redis 업데이트
    evictProductCache(productId) // 캐시 무효화
}
```

**문제:**
- Redis 장애 → 좋아요 추가 실패
- 캐시 무효화 실패 → 좋아요 추가 실패

**"좋아요는 추가됐는데, 집계가 실패하면 어떻게 하지?"**

### 이벤트로 분리

**LikeService 변경:**

**After:**
```kotlin
fun addLike(userId: Long, productId: Long) {
    val like = Like(userId, productId)
    likeRepository.save(like)

    // 이벤트 발행 (집계는 핸들러에서)
    eventPublisher.publishEvent(LikeAddedEvent(userId, productId))
}
```

**LikeEventHandler:**

```kotlin
@Component
class LikeEventHandler(
    private val productLikeCountService: ProductLikeCountService,
    private val productCacheRepository: ProductCacheRepository,
) {
    @Async
    @TransactionalEventListener(phase = AFTER_COMMIT)
    fun handleLikeAdded(event: LikeAddedEvent) {
        try {
            // Redis 카운트 증가
            productLikeCountService.increment(event.productId)

            // 캐시 무효화
            evictProductCache(event.productId)

            logger.info("좋아요 집계 완료: productId=${event.productId}")
        } catch (e: Exception) {
            // 집계 실패해도 좋아요는 추가됨
            logger.error("좋아요 집계 실패: productId=${event.productId}", e)
        }
    }
}
```

**결과:**
- 좋아요 추가 트랜잭션: Like 저장만
- 집계 트랜잭션: Redis 업데이트, 캐시 무효화 (비동기)
- **집계 실패해도 좋아요는 정상 추가**

### Eventual Consistency (최종적 일관성)

"좋아요 누르자마자 바로 반영 안 되는 거 아니야?"

맞다. 하지만:

| 시점 | 상태 | 사용자 경험 |
|------|------|-----------|
| t0 | 좋아요 클릭 | - |
| t1 | Like 저장 완료 | "좋아요 추가됨" 응답 ✅ |
| t2 | 이벤트 발행 | - |
| t3 | Redis 카운트 증가 | 수십 ms 지연 |
| t4 | 다른 사용자 조회 | 최신 카운트 확인 ✅ |

**지연: 수십 밀리초**

사용자는 느끼지 못한다. 하지만 시스템은 훨씬 안정적이다.

## 유저 행동 로깅까지

### 모든 행동을 추적하고 싶었다

"사용자가 뭘 하는지 알 수 있으면 좋겠는데..."

**추적하고 싶은 행동:**
- 상품 조회
- 좋아요 추가/제거
- 주문 생성
- 결제 완료/실패
- 쿠폰 사용

### UserActionEvent

**이벤트 정의:**

```kotlin
data class UserActionEvent(
    val userId: Long,
    val actionType: UserActionType,
    val targetType: String,
    val targetId: Long?,
    val metadata: Map<String, Any>? = null,
)

enum class UserActionType {
    PRODUCT_VIEW,
    PRODUCT_LIKE,
    ORDER_CREATE,
    PAYMENT_COMPLETE,
    COUPON_USE,
    // ...
}
```

**핸들러:**

```kotlin
@Component
class UserActionEventHandler {
    @Async
    @EventListener
    fun handleUserAction(event: UserActionEvent) {
        logger.info("[USER_ACTION] ${event.actionType} - userId=${event.userId}")
        sendToAnalyticsSystem(event)
    }
}
```

**사용:**

```kotlin
// 주문 생성 후
eventPublisher.publishEvent(
    UserActionEvent(
        userId = userId,
        actionType = UserActionType.ORDER_CREATE,
        targetType = "ORDER",
        targetId = order.id,
        metadata = mapOf("amount" to finalAmount.amount),
    )
)
```

**로그 예시:**

```
[USER_ACTION] PRODUCT_VIEW - userId=1, productId=100
[USER_ACTION] PRODUCT_LIKE - userId=1, productId=100
[USER_ACTION] ORDER_CREATE - userId=1, orderId=1, amount=50000
[USER_ACTION] COUPON_USE - userId=1, couponId=10, orderId=1
[USER_ACTION] PAYMENT_COMPLETE - userId=1, orderId=1, paymentId=1
```

## 극적인 효과

### 성능 테스트 결과

동일한 부하 테스트를 다시 실행했다:

```bash
ab -n 100 -c 100 http://localhost:8080/api/v1/orders
```

#### 📊 응답 시간 비교

**AS-IS (동기 처리):**

```
평균 응답 시간: ~2000ms
최대 응답 시간: ~5000ms
실패율: 10% (타임아웃)
```

**TO-BE (이벤트 기반):**

```
평균 응답 시간: ~200ms
최대 응답 시간: ~500ms
실패율: 0%
```

| 항목 | AS-IS | TO-BE | 개선율 |
|------|-------|-------|--------|
| 평균 응답 시간 | ~2000ms | **~200ms** | **90% ↑** |
| 최대 응답 시간 | ~5000ms | **~500ms** | **90% ↑** |
| 실패율 | 10% | **0%** | **100% ↑** |

**90% 성능 향상!**

### 장애 격리 테스트

더 중요한 건 장애 상황이었다.

**시나리오: 쿠폰 서비스 장애**

```kotlin
// 쿠폰 서비스를 강제로 느리게 만듦
fun useUserCoupon(...) {
    Thread.sleep(10000) // 10초 지연
    throw Exception("쿠폰 서비스 장애")
}
```

#### 📊 장애 시 동작 비교

**AS-IS (동기 처리):**

```
주문 생성 요청
  → 쿠폰 사용 시도 (10초 대기)
  → 쿠폰 사용 실패
  → 전체 롤백
  → 주문 생성 실패 ❌
```

**TO-BE (이벤트 기반):**

```
주문 생성 요청
  → 쿠폰 검증만 (즉시)
  → 주문 저장 (성공)
  → 이벤트 발행
  → 주문 생성 성공 ✅

[별도 스레드]
  → 쿠폰 사용 시도 (10초 대기)
  → 쿠폰 사용 실패 (로그만)
  → 주문은 그대로 유지 ✅
```

| 상황 | AS-IS | TO-BE |
|------|-------|-------|
| 쿠폰 서비스 느림 | 주문 생성 느림 🔴 | **주문 생성 빠름** ✅ |
| 쿠폰 서비스 장애 | 주문 생성 실패 🔴 | **주문 생성 성공** ✅ |
| 사용자 경험 | 나쁨 | **좋음** |

**"쿠폰 서비스가 죽어도 주문은 생성된다!"**

처음 알았다. 이벤트 기반은 단순히 성능 문제가 아니라 **"장애 격리와 시스템 안정성"** 문제라는 것을.

## 하지만 완벽하지 않다

### 발생할 수 있는 문제들

이벤트 기반은 강력하지만, 새로운 문제도 생긴다:

| 리스크 | 설명 | 대응 |
|-------|------|------|
| ❌ 예외 은닉 | 이벤트 핸들러 실패는 사용자에게 안 보임 | 로그 적재, 모니터링 |
| ❌ 순서 보장 어려움 | 이벤트는 병렬 실행될 수 있음 | 순서 의존 없는 흐름만 분리 |
| ❌ 중복 실행 | 트랜잭션 재시도 시 이벤트 중복 발행 | Idempotency 처리 |
| ❌ 데이터 불일치 | 최종적으로는 일관성 보장, 즉시는 아님 | 비즈니스 요구사항 확인 |

### 쿠폰 중복 사용 문제

"주문은 생성됐는데, 쿠폰 사용이 실패하면?"

**시나리오:**

```
[사용자 A] 쿠폰 100번으로 주문 생성
  → 주문 생성 성공 (orderId=1)
  → 쿠폰 사용 이벤트 발행

[사용자 B] 같은 쿠폰 100번으로 주문 생성 (동시)
  → 주문 생성 성공 (orderId=2) ← 문제!
  → 쿠폰 사용 이벤트 발행

[이벤트 핸들러]
  → 쿠폰 100번 사용 (orderId=1) 성공
  → 쿠폰 100번 사용 (orderId=2) 실패 (이미 사용됨)
```

**결과:**
- 주문 1: 정상 (쿠폰 사용됨)
- 주문 2: 쿠폰 없이 생성됨 (할인 적용 안 됨)

**이게 맞나?**

사실 이건 **비즈니스 정책**의 문제다:

| 정책 | 선택 |
|------|------|
| 쿠폰 사용 필수 | 주문 생성 전 쿠폰 사용 처리 (동기) |
| 쿠폰 사용 선택 | 주문 생성 후 쿠폰 사용 처리 (비동기) |

현재 구현은 후자를 선택했다. 쿠폰 사용이 실패해도 주문은 유지되고, 사용자는 나중에 다른 쿠폰으로 재시도할 수 있다.

## 배운 것들

### 1. 트랜잭션은 최소화해야 한다

처음엔 "하나의 트랜잭션에 다 넣으면 간단하겠지"라고 생각했다.

하지만:

**트랜잭션이 길어질수록:**
- DB 락 유지 시간 증가 → 성능 저하
- 실패 포인트 증가 → 롤백 가능성 증가
- 외부 시스템 의존 → 장애 전파

**"꼭 지금 해야 하는가?"**를 항상 묻자.

### 2. 이벤트는 통지, 명령이 아니다

Command (명령):
- "쿠폰을 **사용해라**"
- 호출자가 제어
- 실패하면 전체 영향

Event (통지):
- "주문이 **생성되었다**"
- 리스너가 알아서 처리
- **실패해도 격리됨**

**이벤트는 "무엇을 하라"가 아니라 "무슨 일이 일어났다"를 알리는 것이다.**

### 3. @TransactionalEventListener의 마법

```kotlin
@TransactionalEventListener(phase = AFTER_COMMIT)
```

이 한 줄이 주는 가치:
- 트랜잭션이 **성공적으로 커밋된 후에만** 실행
- 롤백되면 이벤트 발행 안 됨
- **데이터 정합성 보장**

**"커밋된 후에 처리하면 안전하다"**

### 4. Eventual Consistency는 타협이 아니다

"즉시 반영 안 되면 문제 아니야?"

아니다. 이건 **전략적 선택**이다:

**즉시 일관성 (Immediate Consistency):**
- 장점: 항상 최신 데이터
- 단점: 느림, 장애에 약함

**최종적 일관성 (Eventual Consistency):**
- 장점: 빠름, 장애에 강함
- 단점: 수십 ms 지연

**"사용자가 느끼지 못하는 지연은 문제가 아니다"**

좋아요 수가 1-2개 차이 나는 건 사용자가 신경 쓰지 않는다. 하지만 페이지가 1초 느린 건 바로 느낀다.

### 5. 모니터링이 더 중요해진다

이벤트 기반은 **흐름이 눈에 보이지 않는다**.

Before:
```kotlin
orderService.createOrder()
  → couponService.useCoupon() // 여기서 실패하면 바로 알 수 있음
```

After:
```kotlin
orderService.createOrder()
  → eventPublisher.publishEvent() // 이벤트 핸들러가 어디서 실패했는지 모름
```

**필요한 것:**
- 로그 적재 (모든 이벤트 처리 로그)
- 실패 이벤트 저장 (DLQ - Dead Letter Queue)
- 모니터링 대시보드 (이벤트 처리 현황)

## 한계와 개선 방향

### 쿠폰 사용 실패 시 알림

현재는 쿠폰 사용이 실패해도 로그만 남긴다.

```kotlin
catch (e: Exception) {
    logger.error("쿠폰 사용 실패: orderId=${event.orderId}", e)
}
```

**개선 방향:**
- 실패한 이벤트를 별도 테이블에 저장
- 재시도 큐에 추가
- 관리자에게 알림

### 이벤트 순서 보장

현재는 이벤트가 **병렬로 실행**된다.

```kotlin
@Async // 별도 스레드
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handleOrderCreatedForCoupon(...) { ... }

@Async // 별도 스레드
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handleOrderCreatedForDataPlatform(...) { ... }
```

만약 순서가 중요하다면?
- `@Async` 제거 (동기 실행)
- 또는 메시지 큐 사용 (Kafka 등)

### 이벤트 저장소 (Outbox Pattern)

중요한 이벤트는 **이벤트 저장소**에 적재 후 처리:

```
[주문 생성]
  ↓
[이벤트 저장소에 저장]
  ├─ OrderCreatedEvent
  ├─ status: PENDING
  └─ timestamp
  ↓
[별도 스케줄러가 처리]
  ├─ PENDING 이벤트 조회
  ├─ 이벤트 핸들러 실행
  └─ status: PROCESSED
```

**장점:**
- 이벤트 유실 방지
- 재처리 가능
- 추적 가능

## 다음에 시도해보고 싶은 것

### 1. 메시지 브로커 (Kafka)

ApplicationEvent는 **단일 JVM 내부**에서만 동작한다.

만약 서비스가 분리되면?
- 주문 서비스 (별도 서버)
- 쿠폰 서비스 (별도 서버)

→ Kafka 같은 메시지 브로커 필요

### 2. Saga 패턴

복잡한 분산 트랜잭션을 관리하는 패턴:

```
[주문 생성]
  ↓ 성공
[재고 차감]
  ↓ 성공
[쿠폰 사용]
  ↓ 실패!
[보상 트랜잭션]
  ├─ 재고 복구
  └─ 주문 취소
```

### 3. CQRS (Command Query Responsibility Segregation)

명령과 조회를 분리:

**Command (쓰기):**
- 주문 생성, 좋아요 추가
- 이벤트 발행

**Query (읽기):**
- 주문 목록, 좋아요 수
- 별도 Read Model

**장점:**
- 읽기 최적화
- 쓰기와 독립적

## 마치며

### "하나의 트랜잭션에 다 넣으면 간단하지 않나?"

처음엔 그렇게 생각했다. 모든 것을 하나의 트랜잭션에 넣으면 간단하고 안전할 것 같았다.

하지만 현실은 달랐다:
- 쿠폰 서비스가 느려지니 주문도 느려졌다
- 외부 시스템 장애가 우리 서비스까지 멈췄다
- 트랜잭션이 길어질수록 DB 락 경합이 심해졌다

**"꼭 지금 해야 하는가?"**

이 질문 하나가 시스템을 바꿨다.

### 가장 중요한 깨달음

**"트랜잭션 분리는 성능 최적화가 아니라, 장애 격리다"**

이벤트 기반 아키텍처의 진짜 가치는:
- 응답 속도 향상 (부수적 효과)
- **장애 전파 방지** (핵심 가치)
- **시스템 안정성** (최종 목표)

Round 5에서 "빠르게 돌아간다"를 배웠다면,
Round 6에서 "장애에도 멈추지 않는다"를 배웠고,
Round 7에서는 **"느슨하게 연결하되, 안전하게 동작한다"**를 배웠다.

### 다음은

이제 기본적인 이벤트 기반 아키텍처는 구축했다.

하지만 여전히 궁금한 게 많다:

**다음 단계:**
- **메시지 브로커**: Kafka를 통한 서비스 간 이벤트 전달
- **Saga 패턴**: 분산 트랜잭션 관리
- **CQRS**: 읽기와 쓰기의 완전한 분리
- **Event Sourcing**: 모든 변경을 이벤트로 저장

"주문 하나 만들었는데 쿠폰 서버까지 느려진" 경험에서 시작해서,
트랜잭션 분리, 이벤트 기반 아키텍처, 장애 격리까지 배웠다.

느슨한 결합은 이제 시작일 뿐이다. 🚀
