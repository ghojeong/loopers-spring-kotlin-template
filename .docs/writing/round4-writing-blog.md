# Round4 - 트랜잭션과 락, 그리고 동시성 문제를 마주하다

**TL;DR**: "@Transactional만 붙이면 안전하겠지"라고 생각했다가, 마지막 재고 1개를 10명이 동시에 가져가는 걸 보고 충격받았다. 비관적 락을 걸었더니 Gap Lock 때문에 데드락이 터지고, 낙관적 락이라고 생각했던 구현이 사실 낙관적 락이 아니었다는 걸 깨달았다. 쿠폰 1장으로 10번 할인받는 사용자를 막고, 좋아요 버튼 연타로 데이터가 꼬이는 걸 해결하며, "락은 정답이 없고 상황에 따라 선택해야 한다"는 걸 몸으로 배운 이야기.

## @Transactional이면 안전한 줄 알았다

### 처음 마주한 문제

Round 3까지는 순조로웠다. ERD 설계하고, 도메인 객체 만들고, API 만들고... "이제 트랜잭션만 걸면 되겠네"라고 생각했다.

```kotlin
@Transactional
fun createOrder(userId: Long, request: OrderCreateRequest): Order {
    // 재고 확인
    val stock = stockRepository.findByProductId(productId)
    if (stock.quantity < quantity) {
        throw InsufficientStockException()
    }

    // 재고 차감
    stock.decrease(quantity)
    stockRepository.save(stock)

    // 주문 생성
    return orderRepository.save(order)
}
```

"@Transactional 붙였으니까 안전하겠지?" 하고 테스트를 돌렸다.

### 충격적인 결과

동시성 테스트를 작성했다. 재고 100개인 상품을 10명이 동시에 15개씩 주문하면, 6명만 성공하고 4명은 실패해야 맞다. (100 / 15 = 6)

```kotlin
@Test
fun `재고 부족 상황에서 동시 주문`() {
    // given: 재고 100개
    val numberOfOrders = 10
    val quantityPerOrder = 15

    // when: 10명이 동시에 15개씩 주문
    repeat(numberOfOrders) {
        executor.submit {
            stockService.decreaseStock(productId, quantityPerOrder)
        }
    }

    // then: 6명만 성공해야 함
    assertThat(successCount.get()).isEqualTo(6)
    assertThat(stock.quantity).isEqualTo(10)  // 100 - 90 = 10
}
```

**결과: 테스트 실패. 재고가 음수가 됐다.**

"어? @Transactional 붙였는데 왜?"

### 문제의 원인: Lost Update

찾아보니 **Lost Update 문제**였다. 트랜잭션이 있어도 동시성 문제는 해결되지 않는다.

시나리오:
1. 사용자 A가 재고 100 읽음
2. 사용자 B도 재고 100 읽음
3. A가 15 차감해서 85로 업데이트 → 커밋
4. B도 15 차감해서 85로 업데이트 → 커밋
5. **결과: 재고 85 (30이 차감됐어야 하는데 15만 차감됨)**

또는 더 나쁜 경우:
1. 재고 10개 남음
2. A가 재고 10 읽음 → 15개 주문 → "재고 부족" 검증 통과 (아직 10개니까)
3. **B가 재고 10 읽음** → 15개 주문 → "재고 부족" 검증 통과
4. A가 15 차감 → 재고 -5
5. B가 15 차감 → **재고 -20**

@Transactional은 **원자성(All or Nothing)**은 보장하지만, **동시성 제어는 안 한다**는 걸 처음 알았다.

## 비관적 락을 걸었더니 데드락이 터졌다

### 비관적 락으로 해결?

"그럼 락을 걸면 되겠네!" 비관적 락을 적용했다.

```kotlin
// StockJpaRepository.kt
interface StockJpaRepository : JpaRepository<Stock, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.productId = :productId")
    fun findByProductIdWithLock(productId: Long): Stock?
}
```

```kotlin
// StockService.kt
@Transactional
fun decreaseStock(productId: Long, quantity: Int): Stock {
    val stock = stockRepository.findByProductIdWithLock(productId)  // SELECT ... FOR UPDATE
        ?: throw CoreException(ErrorType.NOT_FOUND, "재고 정보를 찾을 수 없습니다")
    stock.decrease(quantity)  // 내부에서 재고 부족 검증
    return stockRepository.save(stock)
}
```

이제 `SELECT ... FOR UPDATE`로 다른 트랜잭션이 대기하게 된다. 완벽해 보였다.

### 그런데 데드락이...

좋아요 기능에도 비관적 락을 적용하려다가 문제가 생겼다. 처음엔 이렇게 구현했었다:

```kotlin
// 초기 구현 (문제 있음)
@Entity
@Table(
    name = "likes",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_user_product", columnNames = ["user_id", "product_id"])
    ]
)
class Like(...)

// 비관적 락으로 조회
val like = likeRepository.findByUserIdAndProductIdWithLock(userId, productId)
if (like == null) {
    likeRepository.save(Like(userId, productId))
}
```

**데드락 발생!**

찾아보니 **Gap Lock** 때문이었다. MySQL의 InnoDB는 유니크 인덱스에서 `SELECT ... FOR UPDATE`를 하면 존재하지 않는 레코드의 "갭"에도 락을 건다.

시나리오:
1. 트랜잭션 A: `SELECT ... FOR UPDATE` (userId=1, productId=2) → Gap Lock 획득
2. 트랜잭션 B: `SELECT ... FOR UPDATE` (userId=1, productId=3) → Gap Lock 획득
3. 트랜잭션 A: `INSERT` (userId=1, productId=2) → B가 잡은 Gap Lock 때문에 대기
4. 트랜잭션 B: `INSERT` (userId=1, productId=3) → A가 잡은 Gap Lock 때문에 대기
5. **Deadlock!**

"유니크 인덱스 + 비관적 락 = 데드락 위험"이라는 걸 처음 알았다.

### 해결: 좋아요는 비관적 락이 아니다

좋아요는 비관적 락이 필요 없었다. **UniqueConstraint만으로 충분했다.**

```kotlin
// 최종 구현
@Transactional
fun addLike(userId: Long, productId: Long) {
    // 이미 존재하는지 확인
    if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
        return  // 멱등성 보장
    }

    // 저장 시도
    val like = Like(userId = userId, productId = productId)
    likeRepository.save(like)
}
```

동시에 같은 좋아요를 시도하면?
- 첫 번째 요청: 저장 성공
- 두 번째 요청: UniqueConstraint 위반 → `DataIntegrityViolationException`

Application Layer에서 이 예외를 catch하면 된다:

```kotlin
try {
    likeService.addLike(userId, productId)
} catch (e: DataIntegrityViolationException) {
    // 이미 존재함 = 멱등성 보장됨
}
```

테스트:
```kotlin
@Test
fun `동일한 사용자가 동시에 좋아요 요청`() {
    val numberOfThreads = 10

    repeat(numberOfThreads) {
        executor.submit {
            try {
                likeService.addLike(userId, productId)
                successCount.incrementAndGet()
            } catch (e: DataIntegrityViolationException) {
                duplicateCount.incrementAndGet()  // 중복도 성공으로 간주
            }
        }
    }

    // then: 성공 + 중복 = 전체 요청 수
    assertThat(successCount.get() + duplicateCount.get()).isEqualTo(numberOfThreads)
    assertThat(likeCount).isEqualTo(1)  // 실제로는 1개만 저장됨
}
```

결과: **통과!** 10개 요청 중 1개는 저장 성공, 9개는 중복 감지. 최종 좋아요 수는 정확히 1개.

## 낙관적 락이라고 착각했던 시행착오

### "이게 낙관적 락 아닌가?"

처음에는 이렇게 구현했었다:

```kotlin
// 과거 구현
@Transactional(propagation = Propagation.REQUIRES_NEW)
@Retryable(
    retryFor = [DataIntegrityViolationException::class],
    maxAttempts = 3,
    backoff = Backoff(delay = 100, multiplier = 2.0)
)
fun addLike(userId: Long, productId: Long) {
    if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
        return
    }

    val like = Like(userId = userId, productId = productId)
    likeRepository.save(like)
}
```

"UniqueConstraint 위반 시 재시도하니까 낙관적 락 아닌가?"라고 생각했다.

### 낙관적 락의 정의를 다시 보니...

**낙관적 락**은 읽기 시점에 락을 걸지 않고, **쓰기 시점에 버전을 확인**하는 동시성 제어 기술이다.

```kotlin
// 진짜 낙관적 락 예시
@Entity
class Product(
    var stock: Int,
    @Version  // 이게 핵심!
    var version: Long? = null
)
```

```kotlin
// JPA가 자동으로 버전 체크
// UPDATE products
// SET stock = stock - 5, version = version + 1
// WHERE id = 1 AND version = 현재버전
```

만약 다른 트랜잭션이 먼저 업데이트했다면 버전이 안 맞아서 `OptimisticLockingFailureException` 발생.

### 내 구현은 낙관적 락이 아니었다

내 구현은:
- `@Version` 없음
- 단순히 UniqueConstraint 위반을 재시도하는 것
- **낙관적 락이라기보다는 "멱등성 보장 + 재시도"**

더 큰 문제는 `REQUIRES_NEW`였다.

### REQUIRES_NEW의 함정

`REQUIRES_NEW`는 새로운 트랜잭션을 여는 전파 옵션이다. 문제가 뭘까?

**1. 외부 트랜잭션과 원자성이 깨진다**

```kotlin
@Transactional
fun placeOrder(...) {
    likeService.addLike(userId, productId)  // ← REQUIRES_NEW로 즉시 커밋됨
    orderService.createOrder(...)  // ← 여기서 예외 발생하면?
}
```

주문은 실패했는데 좋아요는 저장된다. **원자성 깨짐.**

**2. 롤백 전파 문제**

```kotlin
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun addLike(...) {
    // ...
    likeRepository.save(like)  // DataIntegrityViolationException 발생
}
```

재시도가 모두 실패하면 예외가 상위로 전파 → 외부 트랜잭션도 롤백 마킹됨.

**3. Facade 레벨에서 문제**

```kotlin
@Transactional
fun processOrder(...) {
    likeService.addLike(...)      // REQUIRES_NEW
    stockService.decreaseStock(...)  // 메인 트랜잭션
    // stockService에서 예외 → 롤백
    // 하지만 addLike는 이미 커밋됨!
}
```

이 우려사항들이 모두 타당했다. 그래서 `REQUIRES_NEW`를 제거하고 단순하게 바꿨다.

```kotlin
// 최종 구현
@Transactional
fun addLike(userId: Long, productId: Long) {
    if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
        return
    }

    val like = Like(userId = userId, productId = productId)
    likeRepository.save(like)
}
```

예외 처리는 호출하는 쪽(Facade)에서:

```kotlin
@Transactional
fun processLike(userId: Long, productId: Long) {
    try {
        likeService.addLike(userId, productId)
    } catch (e: DataIntegrityViolationException) {
        // 멱등성 보장: 중복은 무시
    }
    // 다른 비즈니스 로직과 원자성 보장됨
}
```

이제 원자성도 유지되고, 멱등성도 보장된다.

## 쿠폰 1장으로 10번 할인받는 사용자를 막다

### 쿠폰 중복 사용 문제

쿠폰 기능을 만들면서 고민이 생겼다. "동시에 여러 기기에서 같은 쿠폰으로 주문하면?"

```kotlin
@Entity
class UserCoupon(
    val userId: Long,
    @ManyToOne
    val coupon: Coupon,
    var isUsed: Boolean = false,
    var usedAt: LocalDateTime? = null
)
```

```kotlin
// 초기 구현 (문제 있음)
@Transactional
fun useUserCoupon(userId: Long, userCouponId: Long): UserCoupon {
    val userCoupon = userCouponRepository.findById(userCouponId)
        ?: throw CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다")

    if (!userCoupon.canUse()) {
        throw CoreException(ErrorType.BAD_REQUEST, "사용할 수 없는 쿠폰입니다")
    }

    userCoupon.use()  // isUsed = true
    return userCouponRepository.save(userCoupon)
}
```

동시 요청 시나리오:
1. 요청 A: `isUsed = false` 확인 → 통과
2. 요청 B: `isUsed = false` 확인 → 통과
3. A: `isUsed = true`로 업데이트 → 커밋
4. B: `isUsed = true`로 업데이트 → 커밋
5. **결과: 2번 사용됨**

### 해결: 비관적 락

쿠폰은 **절대 2번 사용되면 안 된다.** 재고처럼 정합성이 중요한 영역이다. 비관적 락을 선택했다.

```kotlin
// UserCouponJpaRepository.kt
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT uc FROM UserCoupon uc WHERE uc.id = :id AND uc.userId = :userId")
fun findByIdAndUserIdWithLock(id: Long, userId: Long): UserCoupon?
```

```kotlin
// CouponService.kt
@Transactional(timeout = 5)
@Retryable(
    retryFor = [PessimisticLockException::class, CannotAcquireLockException::class],
    maxAttempts = 3,
    backoff = Backoff(delay = 100, multiplier = 2.0)
)
fun useUserCoupon(userId: Long, userCouponId: Long): UserCoupon {
    val userCoupon = userCouponRepository.findByIdAndUserIdWithLock(userCouponId, userId)
        ?: throw CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다")

    if (!userCoupon.canUse()) {
        throw CoreException(ErrorType.BAD_REQUEST, "사용할 수 없는 쿠폰입니다")
    }

    userCoupon.use()
    return userCouponRepository.save(userCoupon)
}
```

이제:
1. 요청 A: `SELECT ... FOR UPDATE` → 락 획득, `isUsed = false` 확인
2. 요청 B: `SELECT ... FOR UPDATE` → **대기**
3. A: `isUsed = true` 업데이트 → 커밋 → 락 해제
4. B: 락 획득 → `isUsed = true` → `canUse()` 실패 → 예외

**재시도 로직**도 추가했다. 락 획득 실패 시(`PessimisticLockException`, `CannotAcquireLockException`) 최대 3회 재시도한다.

테스트:

```kotlin
@Test
fun `동일한 쿠폰으로 10번 동시 사용 시도`() {
    val numberOfThreads = 10

    repeat(numberOfThreads) {
        executor.submit {
            try {
                couponService.useUserCoupon(userId, userCouponId)
                successCount.incrementAndGet()
            } catch (e: Exception) {
                failureCount.incrementAndGet()
            }
        }
    }

    latch.await()

    // then: 단 한 번만 성공
    assertThat(successCount.get()).isEqualTo(1)
    assertThat(failureCount.get()).isEqualTo(9)

    val userCoupon = userCouponRepository.findById(userCouponId)!!
    assertThat(userCoupon.isUsed).isTrue()
}
```

결과: **통과!** 10개 요청 중 정확히 1개만 성공, 9개는 "이미 사용된 쿠폰" 에러.

## 스투시 반팔티를 10명이 동시에 주문했을 때

### 재고 동시성 문제

인기 상품의 한정 수량 판매를 시뮬레이션했다. 재고 100개, 10명이 동시에 5개씩 주문.

```kotlin
@Test
fun `동일한 상품에 대해 여러 주문이 동시에 요청`() {
    val numberOfOrders = 10
    val quantityPerOrder = 5

    repeat(numberOfOrders) {
        executor.submit {
            stockService.decreaseStock(productId, quantityPerOrder)
        }
    }

    // then: 모두 성공, 최종 재고 50
    assertThat(successCount.get()).isEqualTo(10)
    assertThat(stock.quantity).isEqualTo(50)  // 100 - 50 = 50
}
```

비관적 락 적용:

```kotlin
@Transactional(timeout = 5)
@Retryable(
    retryFor = [PessimisticLockException::class, CannotAcquireLockException::class],
    maxAttempts = 3,
    backoff = Backoff(delay = 100, multiplier = 2.0)
)
fun decreaseStock(productId: Long, quantity: Int): Stock {
    val stock = stockRepository.findByProductIdWithLock(productId)  // FOR UPDATE
        ?: throw CoreException(ErrorType.NOT_FOUND, "재고 정보를 찾을 수 없습니다")
    stock.decrease(quantity)  // 내부에서 재고 부족 검증
    return stockRepository.save(stock)
}
```

Stock 도메인 객체:

```kotlin
class Stock(
    val productId: Long,
    private var quantity: Int
) {
    fun decrease(requestQuantity: Int) {
        if (quantity < requestQuantity) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "재고 부족: 현재 재고 $quantity, 요청 수량 $requestQuantity"
            )
        }
        quantity -= requestQuantity
    }

    fun increase(requestQuantity: Int) {
        quantity += requestQuantity
    }
}
```

결과: **통과!**

### 재고 부족 상황에서는?

더 흥미로운 테스트: 재고 100개, 10명이 15개씩 주문.

```kotlin
@Test
fun `재고 부족 상황에서 동시 주문`() {
    val numberOfOrders = 10
    val quantityPerOrder = 15  // 총 150개 필요하지만 100개만 있음

    repeat(numberOfOrders) {
        executor.submit {
            try {
                stockService.decreaseStock(productId, quantityPerOrder)
                successCount.incrementAndGet()
            } catch (e: CoreException) {
                if (e.customMessage?.contains("재고 부족") == true) {
                    failureCount.incrementAndGet()
                }
            }
        }
    }

    // then: 6번 성공 (100 / 15 = 6), 4번 실패
    assertThat(successCount.get()).isEqualTo(6)
    assertThat(failureCount.get()).isEqualTo(4)
    assertThat(stock.quantity).isEqualTo(10)  // 100 - 90 = 10
}
```

결과: **통과!** 정확히 6명만 주문 성공, 4명은 "재고 부족" 에러. 최종 재고 10개.

## 포인트가 마이너스가 되는 악몽

### 포인트 동시 차감 문제

한 사용자가 여러 기기에서 동시에 주문하면?

```kotlin
@Test
fun `동일한 유저가 동시에 여러 주문`() {
    // given: 포인트 100,000원
    val numberOfOrders = 10
    val deductAmount = Money(BigDecimal("5000"), Currency.KRW)

    // when: 10번 동시에 5,000원씩 차감
    repeat(numberOfOrders) {
        executor.submit {
            pointService.deductPoint(userId, deductAmount)
        }
    }

    // then: 모두 성공, 최종 50,000원
    assertThat(successCount.get()).isEqualTo(10)
    assertThat(point.balance.amount).isEqualByComparingTo(BigDecimal("50000"))
}
```

비관적 락 적용:

```kotlin
// PointJpaRepository.kt
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Point p WHERE p.userId = :userId")
fun findByUserIdWithLock(userId: Long): Point?
```

```kotlin
// PointService.kt
@Transactional
fun deductPoint(userId: Long, totalAmount: Money): Point {
    val lockedPoint = pointRepository.findByUserIdWithLock(userId)
        ?: throw CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다")
    lockedPoint.deduct(totalAmount)  // 내부에서 잔액 부족 검증
    return pointRepository.save(lockedPoint)
}
```

Point 도메인 객체:

```kotlin
class Point(
    val userId: Long,
    private var balance: Money
) {
    fun deduct(amount: Money) {
        if (!canDeduct(amount)) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "포인트 부족: 현재 잔액 ${balance.amount}, 필요 금액 ${amount.amount}"
            )
        }
        balance = balance.subtract(amount)
    }

    fun charge(amount: Money) {
        balance = balance.add(amount)
    }

    fun canDeduct(amount: Money): Boolean {
        return balance.amount >= amount.amount
    }
}
```

결과: **통과!**

포인트 부족 상황 테스트도:

```kotlin
@Test
fun `포인트 부족 상황에서 동시 주문`() {
    // given: 포인트 100,000원
    val numberOfOrders = 10
    val deductAmount = Money(BigDecimal("15000"), Currency.KRW)  // 총 150,000원 필요

    // when: 10번 동시에 15,000원씩 차감 시도

    // then: 6번 성공 (100,000 / 15,000 = 6), 4번 실패
    assertThat(successCount.get()).isEqualTo(6)
    assertThat(failureCount.get()).isEqualTo(4)
    assertThat(point.balance.amount).isEqualByComparingTo(BigDecimal("10000"))
}
```

결과: **통과!** 정확히 6명만 성공, 4명은 "포인트 부족" 에러.

## 주문 취소 시 재고 복구는 어떻게?

### 주문 취소와 동시성

주문 취소도 동시성 문제가 있다. 여러 주문을 동시에 취소하면 재고가 정확히 복구되는가?

```kotlin
@Test
fun `여러 주문을 동시에 취소해도 재고 정상 복구`() {
    // given: 10개 주문 생성 (각 5개씩, 총 50개 차감)
    val orderIds = createOrders(count = 10, quantity = 5)
    // 초기 재고 100 - 50 = 50

    // when: 10개 주문을 동시에 취소
    orderIds.forEach { orderId ->
        executor.submit {
            orderService.cancelOrder(orderId, userId)
        }
    }

    // then: 모두 성공, 재고 100으로 복구
    assertThat(successCount.get()).isEqualTo(10)
    assertThat(stock.quantity).isEqualTo(100)  // 50 + 50 = 100
}
```

주문 취소 로직:

```kotlin
@Transactional(timeout = 10)
@Retryable(
    retryFor = [PessimisticLockException::class, CannotAcquireLockException::class],
    maxAttempts = 3,
    backoff = Backoff(delay = 100, multiplier = 2.0)
)
fun cancelOrder(orderId: Long, userId: Long): Order {
    val order = orderRepository.findByIdWithLock(orderId)  // 주문에도 FOR UPDATE
        ?: throw CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다")

    if (!order.isOwnedBy(userId)) {
        throw CoreException(ErrorType.FORBIDDEN, "본인의 주문만 취소할 수 있습니다")
    }

    order.cancel()  // 상태 변경

    // 재고 복구 (각 아이템마다 비관적 락으로 증가)
    order.items.forEach { item ->
        stockService.increaseStock(item.productId, item.quantity)
    }

    return orderRepository.save(order)
}
```

중요한 점: **주문에도 비관적 락을 걸었다.** 같은 주문을 동시에 취소하려는 시도를 막기 위해.

```kotlin
@Test
fun `동일한 주문을 10번 동시 취소 시도`() {
    // given: 1개 주문 (10개 차감, 재고 90)
    val orderId = createOrder(quantity = 10)

    // when: 동일한 주문을 10번 동시 취소 시도
    repeat(10) {
        executor.submit {
            try {
                orderService.cancelOrder(orderId, userId)
                successCount.incrementAndGet()
            } catch (e: CoreException) {
                if (e.customMessage?.contains("이미 취소된 주문") == true) {
                    failureCount.incrementAndGet()
                }
            }
        }
    }

    // then: 정확히 1번만 성공
    assertThat(successCount.get()).isEqualTo(1)
    assertThat(failureCount.get()).isEqualTo(9)
    assertThat(stock.quantity).isEqualTo(100)  // 90 + 10 = 100 (1번만 복구)
}
```

결과: **통과!** 동일한 주문은 1번만 취소되고, 재고도 정확히 1번만 복구된다.

## 비관적 락 vs 낙관적 락, 선택 기준은?

### 내가 내린 결론

각 도메인마다 다른 전략을 선택했다:

| 도메인 | 락 전략 | 이유 |
|-------|--------|------|
| **재고(Stock)** | 비관적 락 | 재고 음수는 절대 안 됨. 정합성 최우선 |
| **쿠폰(UserCoupon)** | 비관적 락 | 쿠폰 중복 사용은 절대 안 됨 |
| **포인트(Point)** | 비관적 락 | 포인트 마이너스는 절대 안 됨 |
| **좋아요(Like)** | UniqueConstraint | 중복 감지만 하면 됨. 락 불필요 |
| **주문(Order)** | 비관적 락 (취소 시) | 중복 취소 방지 |

### 선택 기준

**비관적 락을 선택한 경우:**
- 데이터 정합성이 절대적으로 중요할 때 (재고, 포인트, 쿠폰)
- 값이 음수가 되거나 중복 사용되면 안 될 때
- 충돌 시 재시도보다는 대기가 낫을 때

**UniqueConstraint만 사용한 경우:**
- 멱등성만 보장하면 될 때 (좋아요)
- 중복만 막으면 되고, 충돌 시 실패해도 괜찮을 때
- Gap Lock 데드락 위험을 피하고 싶을 때

**낙관적 락을 고려했지만 선택하지 않은 이유:**
- 충돌 시 재시도 로직이 복잡해짐
- 재시도가 많아지면 성능 저하 (재고처럼 경합이 심한 경우)
- 재고/포인트는 "실패"보다 "대기"가 나음

### 재시도 전략

비관적 락을 사용하면서도 재시도를 추가한 이유:
- 락 획득 실패 시(`PessimisticLockException`, `CannotAcquireLockException`) 재시도
- 트랜잭션 타임아웃으로 빠르게 실패 (5초 또는 10초)
- 지수 백오프(100ms → 200ms → 400ms)로 재시도

```kotlin
@Retryable(
    retryFor = [PessimisticLockException::class, CannotAcquireLockException::class],
    maxAttempts = 3,
    backoff = Backoff(delay = 100, multiplier = 2.0)
)
```

이렇게 하면:
- 대부분 첫 시도에 성공
- 동시 요청이 많아도 재시도로 성공률 높임
- 타임아웃으로 무한 대기 방지

## "우리 상품 좋아요가 한번에 수십개씩 빠져요!"

### 처음엔 비관적 락을 시도했다

브랜드에서 전화가 왔다고 가정해보자. "좋아요 수가 갑자기 수십개씩 줄어들어요!"

처음엔 좋아요 삭제에도 비관적 락을 걸어야 하나 생각했다. 하지만 좋아요는 달랐다.

### 좋아요는 특별하다

좋아요 삭제는:
- 멱등해야 함 (이미 삭제된 좋아요를 다시 삭제해도 성공)
- 중복 방지만 하면 됨
- **정합성보다 응답 속도가 중요**

```kotlin
@Transactional
fun removeLike(userId: Long, productId: Long) {
    likeRepository.deleteByUserIdAndProductId(userId, productId)
}
```

`deleteBy...`는 멱등하다:
- 좋아요가 있으면 삭제
- 없으면 아무것도 안 함 (에러도 안 남)

여러 기기에서 동시에 삭제해도:
- 첫 번째 요청: 삭제 성공
- 두 번째 요청: 없으니까 아무것도 안 함 → 성공

**락이 필요 없었다.**

### 좋아요 수 집계는?

"그럼 좋아요 수가 꼬이지 않나요?"

좋아요 수는 실시간으로 정확할 필요가 없다. 캐싱이나 비동기 집계로 해결할 수 있다.

```kotlin
// 간단한 조회
fun countByProductId(productId: Long): Long {
    return likeRepository.countByProductId(productId)
}
```

향후 개선안:
- Redis 캐싱 (1분마다 갱신)
- 이벤트 기반 집계
- 상품 테이블에 `like_count` 컬럼 비정규화

지금은 단순하게 가고, 성능 문제가 생기면 개선하기로 했다.

## 배운 것들

### 1. @Transactional은 만능이 아니다

트랜잭션은 원자성만 보장한다. 동시성 제어는 별개다. Lost Update 문제는 락으로 해결해야 한다.

### 2. 락은 정답이 없다

- 비관적 락: 안전하지만 느릴 수 있음. 데드락 위험.
- 낙관적 락: 빠르지만 충돌 시 재시도 필요. 경합이 심하면 비효율.
- UniqueConstraint: 중복만 막으면 될 때 가장 단순.

**"이 도메인에서 무엇이 가장 중요한가?"**를 먼저 고민해야 한다.

### 3. Gap Lock은 조심해야 한다

유니크 인덱스 + 비관적 락 = 데드락 위험. 존재하지 않는 레코드에 `SELECT ... FOR UPDATE`를 하면 Gap Lock이 걸린다.

### 4. 도메인 객체가 검증을 해야 한다

```kotlin
class Stock {
    fun decrease(quantity: Int) {
        if (this.quantity < quantity) {  // 여기서 검증
            throw InsufficientStockException()
        }
        this.quantity -= quantity
    }
}
```

Service가 아닌 도메인 객체에서 검증하면:
- 중복 검증 코드 방지
- 도메인 규칙이 객체에 캡슐화됨
- 테스트하기 쉬움

### 5. 멱등성은 중요하다

좋아요, 주문 취소처럼 사용자가 실수로 중복 요청할 수 있는 기능은 멱등하게 설계해야 한다.

### 6. 재시도는 신중하게

비관적 락 + 재시도는 좋은 조합이다. 하지만:
- 타임아웃을 짧게 (5-10초)
- 재시도 횟수를 제한 (3회)
- 지수 백오프 사용

### 7. REQUIRES_NEW는 신중하게

독립적인 트랜잭션이 필요한 경우에만 사용. 대부분은 기본 `REQUIRED`가 맞다.

## 아직 해결하지 못한 것들

### 1. N+1 문제

좋아요 수를 조회할 때 N+1 문제가 발생할 수 있다. 지금은 단순하게 구현했지만, 성능 문제가 생기면:
- JOIN FETCH
- Batch Size 설정
- Redis 캐싱

### 2. 분산 환경에서는?

지금은 단일 DB, 단일 서버다. 여러 DB 인스턴스나 여러 서버에서는?
- 분산 락 (Redis, Zookeeper)
- Saga 패턴
- 이벤트 소싱

### 3. 데드락 모니터링

비관적 락을 쓰면 데드락이 발생할 수 있다. 실제 운영에서는:
- 데드락 로그 수집
- 알림 설정
- 쿼리 타임아웃 최적화

## 설계와 구현, 그 사이의 간극

### 설계문서는 완벽했는데

Round 2에서 작성한 설계문서에는 이렇게 적혀 있었다:

> "좋아요 등록 시 비관적 락(SELECT FOR UPDATE)을 사용하여 동시성을 제어한다."

당시에는 당연해 보였다. "동시성 문제니까 락을 걸면 되지"라고 생각했다.

### 하지만 구현하면서...

실제로 구현하려다가 Gap Lock 데드락을 만났다. UniqueConstraint가 있는 테이블에 `SELECT ... FOR UPDATE`를 하면, 존재하지 않는 레코드의 "갭"에도 락이 걸린다.

결국 설계를 변경했다:

**설계 (Round 2):**
```
좋아요: 비관적 락으로 동시성 제어
```

**실제 구현 (Round 4):**
```
좋아요: UniqueConstraint만으로 중복 방지
Facade에서 DataIntegrityViolationException catch
```

설계문서를 업데이트했다. "비관적 락 미사용 이유: Gap Lock 데드락 위험"이라고 명시했다.

### 쿠폰은 설계에 없었다

Round 2 설계에는 쿠폰이 없었다. Round 4 quest를 보고 급하게 추가했다:

- `Coupon (Entity)`: 정액/정률 할인
- `UserCoupon (Entity)`: 사용자 소유, 1회 사용 제한
- `CouponService`: 비관적 락으로 중복 사용 방지

설계 → 구현이 아니라, **요구사항 → 설계 + 구현 동시 진행**이었다.

주문 흐름도 변경됐다:

**기존 설계:**
```
1. 재고 확인
2. 포인트 확인
3. 주문 생성
```

**실제 구현:**
```
1. 재고 확인
2. 쿠폰 사용 (비관적 락)
3. 할인 금액 계산
4. 포인트 확인 및 차감 (비관적 락)
5. 재고 차감 (비관적 락)
6. 주문 생성
```

쿠폰이 추가되면서 흐름이 복잡해졌다. 특히 **쿠폰 사용 → 할인 계산 → 포인트 차감** 순서가 중요했다.

### 주문 취소도 없었다

설계에는 주문 조회만 있었다. 하지만 구현하다 보니 "주문 취소하면 재고는 어떻게 되지?"라는 질문이 생겼다.

추가한 기능:
- 주문 취소 API (POST `/api/v1/orders/{orderId}/cancel`)
- 재고 복구 로직 (`stockService.increaseStock()`)
- 주문 상태 관리 (`OrderStatus.CANCELLED`)

이것도 설계문서에 추가했다. 동시 취소 시 재고가 정확히 복구되는지 테스트도 작성했다.

### 설계는 계속 변한다

처음에는 "완벽한 설계를 먼저 하고, 그대로 구현하면 된다"고 생각했다.

하지만:
- 설계할 때는 몰랐던 문제(Gap Lock)를 구현 중에 발견했다.
- 요구사항(쿠폰)이 추가되면서 설계도 바뀌었다.
- 구현하다가 필요한 기능(주문 취소)을 추가했다.

**설계는 구현의 청사진이 아니라, 구현과 함께 진화하는 문서**였다.

지금은 설계문서를 최신 상태로 유지했다. 다음 사람이 보면 "아, 좋아요는 UniqueConstraint를 쓰는구나. Gap Lock 문제가 있었구나"를 바로 알 수 있도록.

### 배운 것

1. **설계는 완벽할 수 없다**: 구현하면서 발견하는 문제가 있다.
2. **설계는 고정되지 않는다**: 요구사항이 변하면 설계도 변한다.
3. **설계문서는 살아있어야 한다**: 구현과 설계가 다르면 둘 다 쓸모없다.

## 다음에 시도해보고 싶은 것

1. **이벤트 기반 아키텍처**: 주문 생성 후 이벤트를 발행하고, 재고 차감을 이벤트 리스너에서 처리하면 어떨까?

2. **낙관적 락 실제 적용**: `@Version`을 사용한 진짜 낙관적 락을 적용해보고, 비관적 락과 성능 비교해보기.

3. **Redis 분산 락**: 단일 DB를 넘어서, 분산 환경에서의 동시성 제어.

4. **성능 테스트**: JMeter나 Gatling으로 실제 부하를 주고, 락 전략별 성능 측정.

5. **데드락 재현**: 의도적으로 데드락을 발생시키고, 감지/해결하는 방법 연구.

## 마치며

이번 라운드는 "코드가 돌아간다"와 "코드가 안전하다"의 차이를 배운 시간이었다.

@Transactional만 붙이면 안전할 줄 알았다. 하지만 재고가 음수가 되고, 쿠폰이 중복 사용되고, 포인트가 마이너스가 되는 걸 직접 목격했다.

비관적 락을 걸었더니 Gap Lock 때문에 데드락이 터졌고, 낙관적 락이라고 생각했던 구현이 사실 낙관적 락이 아니라는 것도 깨달았다.

**완벽한 락 전략은 없다.** 다만 "이 도메인에서는 이게 더 적합하다"는 선택만 있을 뿐이다.

재고는 비관적 락. 좋아요는 UniqueConstraint. 각자의 이유가 있었다.

다음 라운드에서는 이 기반 위에 더 복잡한 비즈니스 로직을 쌓아가고, 분산 환경에서의 동시성 제어도 고민해보고 싶다.

동시성 문제는 로컬 개발 환경에서는 절대 발견할 수 없다. 테스트 코드로 시뮬레이션하고, 실제 운영에서 검증하는 수밖에 없다는 것도 배웠다.

트랜잭션과 락, 그리고 동시성. 이제 시작일 뿐이다.
