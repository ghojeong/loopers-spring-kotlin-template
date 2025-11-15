# Round3 - 도메인 객체에 생명을 불어넣다

## Suggestions

- 상품이 좋아요 수를 직접 관리해야 할까?
- 상품 상세에서 브랜드를 함께 제공하려면 누가 조합해야 할까?
- VO를 도입한 이유는 무엇이며, 어느 시점에서 유리하게 작용했는가?
- Order, Product, User 중 누가 어떤 책임을 갖는 것이 자연스러웠나?
- Repository Interface 를 Domain Layer에 두는 이유는?
- 처음엔 도메인에 두려 했지만, 결국 Application Layer로 옮긴 이유는?
- 테스트 가능한 구조를 만들기 위해 가장 먼저 고려한 건 무엇이었나?

**TL;DR**: "도메인 객체는 그냥 데이터를 담는 그릇일까?", "비즈니스 규칙은 누가 지켜야 할까?", "Value Object는 왜 필요할까?", "상태 전이는 어떻게 제어할까?"라는 질문들과 씨름했다. Entity와 VO에 책임을 주고, 도메인 규칙을 코드로 표현하며, 객체가 스스로 무결성을 지키게 만드는 과정을 기록한 글이다.

## 구현을 시작하며

3주차 과제는 도메인 모델링이었다. 지난주에 ERD와 다이어그램을 그렸으니 이제 코드로 옮기면 되는 거라 생각했다.

근데 첫 줄부터 막혔다.

```kotlin
class Product {
    val price: BigDecimal  // 이게 도메인 모델인가?
}
```

"Product는 가격을 가진다"는 건 알겠는데, **어떻게** 가져야 할까? 그냥 BigDecimal 필드 하나면 끝일까?

설계 문서는 "무엇"만 말해줬다. "어떻게"는 코드를 작성하는 순간 결정해야 했다.

## Value Object, 값 자체가 규칙을 지킨다

### 도메인 규칙을 어디에 둘까?

가장 먼저 고민한 건 금액 표현이었다. 상품 가격, 주문 총액, 포인트 잔액... 전부 금액인데 어떻게 다루지?

```kotlin
class Product(val price: BigDecimal)
class Order(val totalAmount: BigDecimal)
class Point(val balance: BigDecimal)
```

처음엔 이게 답인 줄 알았다. 근데 곧바로 문제가 보였다.

```kotlin
val price = BigDecimal("-1000")  // 음수 가격?
val product1 = BigDecimal("10000")  // 원화?
val product2 = BigDecimal("100")    // 달러?
val total = product1 + product2     // 이게 맞나?
```

**비즈니스 규칙이 코드에 없다.** 가격은 0 이상이어야 하고, 통화가 같아야 더할 수 있다는 규칙이 어디에도 없다. 이걸 매번 Service에서 검증해야 할까?

### 값 자체가 규칙이 되다

찾아보니 이럴 때 **Value Object(VO)**를 쓴다고 했다. 중요한 건 "누구"가 아니라 "값"이 무엇인지.

Price VO를 만들었다:

```kotlin
@Embeddable
data class Price private constructor(
    val amount: BigDecimal,
    @Enumerated(EnumType.STRING)
    val currency: Currency = Currency.KRW,
) : Comparable<Price> {
    init {
        if (amount < BigDecimal.ZERO) {
            throw CoreException(ErrorType.BAD_REQUEST, "금액은 0 이상이어야 합니다.")
        }
    }

    operator fun plus(other: Price): Price {
        if (this.currency != other.currency) {
            throw CoreException(ErrorType.BAD_REQUEST, "통화가 다른 가격은 더할 수 없습니다.")
        }
        return Price(this.amount + other.amount, this.currency)
    }

    operator fun times(multiplier: Int): Price {
        if (multiplier < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "수량은 0 이상이어야 합니다.")
        }
        return Price(this.amount * BigDecimal(multiplier), this.currency)
    }
}
```

이제 불가능한 가격은 **존재 자체가 불가능**하다:

```kotlin
val price = Price(BigDecimal("-100"))  // ❌ 생성 시점에 예외
val krw = Price(BigDecimal("10000"), Currency.KRW)
val usd = Price(BigDecimal("100"), Currency.USD)
val sum = krw + usd  // ❌ 연산 시점에 예외
```

### 테스트로 규칙을 명확히 하다

VO를 만들고 나니 테스트가 명확해졌다:

```kotlin
class PriceTest {
    @Test
    fun `가격은 0 이상이어야 한다`() {
        assertThatThrownBy {
            Price(amount = BigDecimal("-1"), currency = Currency.KRW)
        }.isInstanceOf(CoreException::class.java)
            .hasMessageContaining("0 이상")
    }

    @Test
    fun `통화가 다른 가격끼리 더하면 예외가 발생한다`() {
        val krw = Price(amount = BigDecimal("10000"), currency = Currency.KRW)
        val usd = Price(amount = BigDecimal("100"), currency = Currency.USD)

        assertThatThrownBy {
            krw + usd
        }.isInstanceOf(CoreException::class.java)
            .hasMessageContaining("통화")
    }

    @Test
    fun `Value Object이므로 값이 같으면 동일하다`() {
        val price1 = Price(amount = BigDecimal("10000"), currency = Currency.KRW)
        val price2 = Price(amount = BigDecimal("10000"), currency = Currency.KRW)

        assertThat(price1).isEqualTo(price2)
        assertThat(price1.hashCode()).isEqualTo(price2.hashCode())
    }
}
```

테스트가 도메인 규칙을 말해준다. "가격은 음수일 수 없다", "다른 통화는 더할 수 없다", "값이 같으면 동일하다". **코드가 곧 규칙**이다.

### 배운 것

VO를 도입하기 전에는 검증 로직이 Service에 흩어져 있었다. "이 가격이 유효한가?"를 매번 체크해야 했다.

VO를 도입하고 나니 **불가능한 상태가 아예 존재하지 않는다**. Price 타입이 있다는 것 자체가 "유효한 가격"이라는 증명이다.

그리고 연산자 오버로딩 덕분에 코드가 의도를 드러낸다:

```kotlin
val itemTotal = price * quantity        // "가격 × 수량"
val orderTotal = item1Total + item2Total  // "항목들의 합"
```

BigDecimal로 했으면 `price.multiply(quantity)` 같은 코드가 됐을 거다. 도메인 언어로 말하는 코드가 더 읽기 쉽다.

## Entity, 상태를 가진 객체의 책임

### 누가 재고를 관리할까?

재고 차감 로직을 작성할 때, 처음엔 당연히 Service에 뒀다:

```kotlin
@Service
class StockService(
    private val stockRepository: StockRepository
) {
    fun decreaseStock(productId: Long, quantity: Int) {
        val stock = stockRepository.findByProductId(productId) ?: throw ...

        if (stock.quantity < quantity) {  // Service가 검증
            throw CoreException(...)
        }
        stock.quantity -= quantity  // Service가 직접 차감
        stockRepository.save(stock)
    }
}

class Stock(
    val productId: Long,
    var quantity: Int  // 그냥 public var
)
```

근데 뭔가 이상했다. Stock이 그냥 데이터 컨테이너다. 누구나 `stock.quantity = -100` 같은 걸 할 수 있다.

"재고를 관리하는 건 Stock의 책임 아닐까?"

### Tell, Don't Ask

Stock에게 책임을 줬다:

```kotlin
@Entity
class Stock(
    @Id val productId: Long,
    quantity: Int,
) {
    @Column(nullable = false)
    var quantity: Int = quantity
        protected set  // 외부에서 직접 수정 불가

    init {
        if (quantity < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "재고는 0 이상이어야 합니다.")
        }
    }

    fun decrease(amount: Int) {
        if (amount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "감소량은 0보다 커야 합니다.")
        }
        if (this.quantity < amount) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "재고 부족: 현재 재고 $quantity, 요청 수량 $amount"
            )
        }
        this.quantity -= amount
    }

    fun increase(amount: Int) {
        if (amount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "증가량은 0보다 커야 합니다.")
        }
        this.quantity += amount
    }

    fun isAvailable(amount: Int): Boolean = this.quantity >= amount
}
```

이제 Stock에게 "해줘"라고 말한다:

```kotlin
stock.decrease(orderQuantity)  // ✅ Stock이 알아서 검증하고 차감
stock.increase(cancelledQuantity)  // ✅ Stock이 알아서 검증하고 증가
```

묻지 않는다. "너 재고 몇 개야?" "부족하지 않아?" 같은 질문을 하지 않고, 그냥 "10개 차감해줘"라고 말한다. Stock이 스스로 판단한다.

### 테스트로 책임을 검증하다

```kotlin
class StockTest {
    @Test
    fun `재고를 감소시킬 수 있다`() {
        val stock = Stock(productId = 1L, quantity = 100)

        stock.decrease(30)

        assertThat(stock.quantity).isEqualTo(70)
    }

    @Test
    fun `재고보다 많이 감소시키면 예외가 발생한다`() {
        val stock = Stock(productId = 1L, quantity = 100)

        assertThatThrownBy {
            stock.decrease(101)
        }.isInstanceOf(CoreException::class.java)
            .hasMessageContaining("재고 부족")
    }

    @Test
    fun `재고는 생성 시점에 0 이상이어야 한다`() {
        assertThatThrownBy {
            Stock(productId = 1L, quantity = -1)
        }.isInstanceOf(CoreException::class.java)
            .hasMessageContaining("재고")
    }
}
```

Service를 띄우지 않아도, DB 없이도 Stock의 규칙을 검증할 수 있다. **도메인 로직이 도메인 객체에 있으니까.**

### 배운 것

처음엔 "Service에 로직을 두는 게 당연한 거 아냐?"라고 생각했다. 근데 그러면 Stock을 쓰는 모든 곳에서 동일한 검증을 반복해야 한다.

도메인 객체에 책임을 주니:
- 중복 검증이 사라졌다
- 도메인 규칙이 한곳에 모였다
- 테스트하기 쉬워졌다
- 의도가 명확해졌다 (`stock.decrease(5)` vs `stock.quantity -= 5`)

## 상태 전이, 객체가 자신의 흐름을 제어한다

### 주문의 라이프사이클

주문을 구현하면서 또 다른 고민이 생겼다. 주문의 상태를 어떻게 관리할까?

```kotlin
enum class OrderStatus {
    PENDING,     // 생성됨
    CONFIRMED,   // 확정됨
    CANCELLED    // 취소됨
}
```

처음엔 그냥 상태만 있으면 되는 줄 알았다. 근데 "언제 어떤 상태로 전이할 수 있는가?"가 비즈니스 규칙이었다.

- PENDING → CONFIRMED: OK
- PENDING → CANCELLED: OK
- CONFIRMED → CANCELLED: **NO** (확정된 주문은 취소 불가)
- CANCELLED → CONFIRMED: **NO** (취소된 주문은 확정 불가)

이 규칙을 어디에 둘까?

### 상태 전이를 캡슐화하다

Order Entity가 스스로 상태 전이를 제어하게 했다:

```kotlin
@Entity
class Order(
    val userId: Long,
    items: List<OrderItem>,
) : BaseEntity() {
    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.PENDING
        protected set

    fun cancel() {
        if (status == OrderStatus.CONFIRMED) {
            throw CoreException(ErrorType.BAD_REQUEST, "이미 확정된 주문은 취소할 수 없습니다.")
        }
        if (status == OrderStatus.CANCELLED) {
            throw CoreException(ErrorType.BAD_REQUEST, "이미 취소된 주문입니다.")
        }
        this.status = OrderStatus.CANCELLED
    }

    fun confirm() {
        if (status == OrderStatus.CANCELLED) {
            throw CoreException(ErrorType.BAD_REQUEST, "취소된 주문은 확정할 수 없습니다.")
        }
        if (status == OrderStatus.CONFIRMED) {
            throw CoreException(ErrorType.BAD_REQUEST, "이미 확정된 주문입니다.")
        }
        this.status = OrderStatus.CONFIRMED
    }
}
```

이제 불가능한 상태 전이는 **컴파일 타임에는 막을 수 없지만, 런타임에 확실히 막힌다**:

```kotlin
val order = Order(userId = 1L, items = items)
order.confirm()
order.cancel()  // ❌ "이미 확정된 주문은 취소할 수 없습니다"
```

### 테스트로 상태 전이를 검증하다

```kotlin
class OrderTest {
    @Test
    fun `확정된 주문은 취소할 수 없다`() {
        val order = createOrder()
        order.confirm()

        assertThatThrownBy {
            order.cancel()
        }.isInstanceOf(CoreException::class.java)
            .hasMessageContaining("확정된 주문은 취소할 수 없습니다")
    }

    @Test
    fun `취소된 주문은 확정할 수 없다`() {
        val order = createOrder()
        order.cancel()

        assertThatThrownBy {
            order.confirm()
        }.isInstanceOf(CoreException::class.java)
            .hasMessageContaining("취소된 주문은 확정할 수 없습니다")
    }

    @Test
    fun `이미 취소된 주문은 다시 취소할 수 없다`() {
        val order = createOrder()
        order.cancel()

        assertThatThrownBy {
            order.cancel()
        }.isInstanceOf(CoreException::class.java)
            .hasMessageContaining("이미 취소된 주문입니다")
    }
}
```

테스트가 상태 전이 규칙을 문서화한다. 주석이나 문서 없이도 테스트만 봐도 "어떤 전이가 가능한지"를 알 수 있다.

### 배운 것

처음엔 Service에서 상태를 검증하려고 했다:

```kotlin
fun cancelOrder(orderId: Long) {
    val order = orderRepository.findById(orderId)
    if (order.status == OrderStatus.CONFIRMED) {  // Service가 검증
        throw ...
    }
    order.status = OrderStatus.CANCELLED
}
```

근데 이러면:
- 주문을 취소하는 모든 곳에서 동일한 검증을 반복해야 한다
- 누군가 검증을 깜빡하면 버그가 된다
- 상태 전이 규칙이 여기저기 흩어진다

Order에 `cancel()` 메서드를 주니:
- 규칙이 한곳에 모인다
- 불가능한 전이를 원천 차단한다
- 테스트로 명확히 검증할 수 있다

## 도메인 규칙은 코드가 된다

### Point Entity의 고민

포인트 차감 로직을 작성할 때도 비슷한 고민이 있었다.

```kotlin
@Entity
class Point(
    @Id val userId: Long,
    balance: Money,
) {
    var balance: Money = balance
        protected set

    fun deduct(amount: Money) {
        if (amount.amount <= BigDecimal.ZERO) {
            throw CoreException(ErrorType.BAD_REQUEST, "차감 금액은 0보다 커야 합니다.")
        }
        if (!canDeduct(amount)) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "포인트 부족: 현재 잔액 ${balance.amount}, 차감 요청 ${amount.amount}"
            )
        }
        this.balance = this.balance - amount
    }

    fun charge(amount: Money) {
        if (amount.amount <= BigDecimal.ZERO) {
            throw CoreException(ErrorType.BAD_REQUEST, "충전 금액은 0보다 커야 합니다.")
        }
        this.balance = this.balance + amount
    }

    fun canDeduct(amount: Money): Boolean = this.balance.isGreaterThanOrEqual(amount)
}
```

Point Entity가:
- 잔액 부족을 스스로 체크한다
- 음수 차감/충전을 막는다
- 상태를 protected로 보호한다

Service는 그냥 명령만 내린다:

```kotlin
@Service
class PointService(
    private val pointRepository: PointRepository,
) {
    fun deductPoint(userId: Long, totalAmount: Money): Point {
        val lockedPoint = pointRepository.findByUserIdWithLock(userId) ?: throw ...
        lockedPoint.deduct(totalAmount)  // Point에게 위임
        return pointRepository.save(lockedPoint)
    }
}
```

### 테스트로 규칙을 명확히

```kotlin
class PointTest {
    @Test
    fun `잔액보다 많이 차감하면 예외가 발생한다`() {
        val point = Point(userId = 1L, balance = Money(BigDecimal("10000"), Currency.KRW))
        val deductAmount = Money(BigDecimal("11000"), Currency.KRW)

        assertThatThrownBy {
            point.deduct(deductAmount)
        }.isInstanceOf(CoreException::class.java)
            .hasMessageContaining("포인트 부족")
    }

    @Test
    fun `차감 가능 여부를 확인할 수 있다`() {
        val point = Point(userId = 1L, balance = Money(BigDecimal("10000"), Currency.KRW))

        assertThat(point.canDeduct(Money(BigDecimal("5000"), Currency.KRW))).isTrue()
        assertThat(point.canDeduct(Money(BigDecimal("10000"), Currency.KRW))).isTrue()
        assertThat(point.canDeduct(Money(BigDecimal("10001"), Currency.KRW))).isFalse()
    }
}
```

## Entity vs VO, 언제 무엇을 쓸까?

### 고민의 기준

코드를 작성하면서 계속 고민했다. "이건 Entity? VO?"

**Entity**는:
- ID로 식별한다
- 상태가 변한다
- 시간이 지나도 "그 객체"다

```kotlin
@Entity
class Product(
    name: String,
    price: Price,
    brand: Brand,
) : BaseEntity() {  // ID를 상속받음
    var name: String = name
        protected set

    var price: Price = price
        protected set

    fun updatePrice(newPrice: Price) {  // 상태 변화
        this.price = newPrice
    }
}
```

Product #123은 가격이 바뀌어도 Product #123이다.

**Value Object**는:
- ID가 없다
- 불변이다
- 값이 같으면 동일하다

```kotlin
@Embeddable
data class Price(
    val amount: BigDecimal,
    val currency: Currency = Currency.KRW,
) {
    // 연산은 새 객체를 반환
    operator fun plus(other: Price): Price =
        Price(this.amount + other.amount, this.currency)
}
```

10,000원은 언제 어디서나 10,000원이다.

### 실전 적용

헷갈렸던 건 Like였다. 이건 Entity? VO?

```kotlin
@Entity
class Like(
    @Column val userId: Long,
    @Column val productId: Long,
) : BaseEntity()  // ID를 가짐
```

Like를 Entity로 만든 이유:
- "언제 누가 좋아요를 눌렀는지" 추적이 필요하다
- `(userId, productId)` 조합이 고유하다
- 나중에 "좋아요 취소 시각" 같은 상태를 추가할 수 있다

VO로 만들 수도 있었지만, 도메인 요구사항을 고려하면 Entity가 맞다고 판단했다.

## 테스트 가능한 설계란?

### 도메인 객체를 단독으로 테스트하다

도메인 로직을 도메인 객체에 두니 테스트가 쉬워졌다.

**VO 테스트**는 의존성이 전혀 없다:

```kotlin
@Test
fun `가격을 더할 수 있다`() {
    val price1 = Price(amount = BigDecimal("10000"), currency = Currency.KRW)
    val price2 = Price(amount = BigDecimal("5000"), currency = Currency.KRW)

    val result = price1 + price2

    assertThat(result.amount).isEqualTo(BigDecimal("15000"))
}
```

**Entity 테스트**도 DB 없이 가능하다:

```kotlin
@Test
fun `주문 총 금액을 계산할 수 있다`() {
    val items = listOf(
        createOrderItem(price = BigDecimal("100000"), quantity = 2),
        createOrderItem(price = BigDecimal("50000"), quantity = 1),
    )
    val order = Order(userId = 1L, items = items)

    val totalAmount = order.calculateTotalAmount()

    assertThat(totalAmount.amount).isEqualTo(BigDecimal("250000"))
}
```

Service 테스트는 Repository만 Mock으로:

```kotlin
class LikeServiceTest {
    private val likeRepository: LikeRepository = mockk(relaxed = true)
    private val likeService = LikeService(likeRepository)

    @Test
    fun `이미 좋아요한 상품에 다시 좋아요를 시도하면 멱등하게 동작한다`() {
        every { likeRepository.existsByUserIdAndProductId(1L, 100L) } returns true

        likeService.addLike(1L, 100L)

        verify(exactly = 0) { likeRepository.save(any()) }
    }
}
```

### 배운 것

**좋은 도메인 모델은 테스트하기 쉽다.**

- 도메인 로직이 도메인 객체에 있으면 → 단독 테스트 가능
- 외부 의존성이 분리되어 있으면 → Mock으로 대체 가능
- 책임이 명확하면 → 테스트 케이스가 명확함

반대로 테스트하기 어려운 코드는:
- 로직이 Service에 몰려있다
- 의존성이 복잡하게 얽혀있다
- 책임이 분산되어 있다

테스트를 작성하면서 설계를 개선하는 과정 자체가 큰 배움이었다.

## 아직 고민 중인 것들

### OrderItem의 스냅샷, 정규화 vs 도메인 무결성

주문 항목에 주문 시점의 정보를 저장했다:

```kotlin
private fun createOrderItemSnapshot(
    product: Product,
    quantity: Int,
): OrderItem = OrderItem(
    productId = product.id,
    productName = product.name,
    brandId = product.brand.id,
    brandName = product.brand.name,
    priceAtOrder = product.price,  // 주문 시점 가격
)
```

데이터 중복이지만 도메인 관점에서는 맞다. 주문은 "그 시점의 기록"이니까. 상품 가격이 바뀌어도 주문 이력은 불변이어야 한다.

정규화와 도메인 무결성 사이에서 도메인을 선택했다.

### VO의 경계, Price vs Money

Price와 Money를 따로 만든 이유:
- Price: 상품의 **가격**
- Money: 계산된 **금액**

구조는 비슷하지만 도메인 의미가 다르다. 근데 이게 오버 엔지니어링일 수도 있다. 통합해도 되지 않을까?

아직도 확신은 없지만, 타입으로 도메인 의미를 표현하는 게 더 명확한 것 같다.

### 도메인 규칙의 위치

Order에 `calculateTotalAmount()`를 둘까, 별도의 OrderCalculator를 만들까?

지금은 Entity에 뒀다. Order가 자신의 총액을 계산하는 게 자연스럽다고 판단했다. 하지만 계산 로직이 복잡해지면 분리가 필요할 수도 있다.

## 도메인 모델링은 끊임없는 질문이었다

이번 구현 과정에서 가장 많이 한 질문:

- 이 규칙은 누가 지켜야 할까?
- 이 로직은 어느 객체의 책임일까?
- 이 개념은 Entity? VO?
- 불변이어야 할까, 가변이어야 할까?
- 상태 전이를 어떻게 제어할까?

정답은 없었다. 대신 **"이 선택이 도메인을 더 잘 표현하는가?"**를 계속 물었다.

완벽한 설계는 없다. 다만 **코드로 도메인을 표현하고, 테스트로 규칙을 검증**할 수 있다면, 그게 좋은 시작이 아닐까?

## 다음에 해보고 싶은 것

1. **도메인 이벤트**를 적용해보고 싶다. 주문 생성, 재고 차감, 포인트 차감을 이벤트로 연결하면 어떨까?

2. **Aggregate Root** 개념을 더 명확히 하고 싶다. Order와 OrderItem의 관계, Product와 Stock의 관계를 더 깊이 고민해보고 싶다.

3. **불변 객체**를 더 많이 써보고 싶다. Entity의 상태 변경도 새 객체를 반환하는 방식으로 바꾸면 어떨까?

4. **상태 전이를 타입으로** 표현할 수 있을까? Kotlin의 sealed class로 불가능한 상태를 컴파일 타임에 막을 수 있지 않을까?

이번 라운드는 "도메인 객체에 생명을 불어넣는" 과정이었다. 단순한 데이터 컨테이너가 아니라, 스스로 규칙을 지키고, 상태를 제어하고, 의미를 드러내는 객체를 만드는 법을 배웠다.

코드는 도메인을 표현한다. 그리고 테스트는 그 표현이 올바른지 검증한다. 설계와 구현과 테스트가 하나의 흐름이 되는 경험이었다.

다음 라운드에서는 이 객체들이 실제로 어떻게 협력하는지, 어떤 문제를 마주할지 궁금하다.
