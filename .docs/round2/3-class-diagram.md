# 클래스 다이어그램 & 도메인 모델

이커머스 시스템의 도메인 객체와 그들 간의 관계를 레이어드 아키텍처 관점에서 정의

## 도메인 모델링 개요

### Entity vs Value Object

- **Entity**: 고유 식별자를 가지며, 생명주기가 독립적인 객체 (동일성은 ID로 판단)
- **Value Object (VO)**: 식별자 없이 값으로만 구분되는 불변 객체 (동일성은 값으로 판단)

### Repository Interface 위치

- **Repository Interface는 Domain Layer에 위치**: 도메인이 필요로 하는 영속성 계약 정의
- **Repository 구현체는 Infrastructure Layer에 위치**: JPA 등 구체 기술로 구현

## 전체 도메인 모델

```mermaid
classDiagram
    class User {
        <<Entity>>
        +Long id
        +String name
        +String email
        +Gender gender
        +LocalDate birthDate
        +LocalDateTime createdAt
    }

    class Brand {
        <<Entity>>
        +Long id
        +String name
        +String description
        +LocalDateTime createdAt
    }

    class Product {
        <<Entity>>
        +Long id
        +String name
        +Price price
        +Brand brand
        +Long likeCount
        +LocalDateTime createdAt
        +checkStockAvailable(quantity) boolean
        +incrementLikeCount() void
        +decrementLikeCount() void
    }

    class Price {
        <<Value Object>>
        +BigDecimal amount
        +Currency currency
        +plus(Price) Price
        +minus(Price) Price
        +times(int) Price
        +compareTo(Price) int
    }

    class Stock {
        <<Entity>>
        +Long productId
        +int quantity
        +LocalDateTime createdAt
        +LocalDateTime updatedAt
        +decrease(int) void
        +increase(int) void
        +isAvailable(int) boolean
    }

    class Like {
        <<Entity>>
        +Long id
        +Long userId
        +Long productId
        +LocalDateTime createdAt
    }

    class Order {
        <<Entity, Aggregate Root>>
        +Long id
        +Long userId
        +List~OrderItem~ items
        +Money totalAmount
        +OrderStatus status
        +LocalDateTime orderedAt
        +calculateTotalAmount() Money
        +isOwnedBy(userId) boolean
        +confirm() void
        +cancel() void
    }

    class OrderItem {
        <<Entity>>
        +Long id
        +Long productId
        +String productName
        +Long brandId
        +String brandName
        +String brandDescription
        +int quantity
        +Price priceAtOrder
        +calculateItemAmount() Money
    }

    class Money {
        <<Value Object>>
        +BigDecimal amount
        +Currency currency
        +plus(Money) Money
        +minus(Money) Money
        +times(int) Money
        +unaryPlus() Money
        +unaryMinus() Money
        +isGreaterThanOrEqual(Money) Boolean
    }

    class Point {
        <<Entity>>
        +Long userId
        +Money balance
        +LocalDateTime createdAt
        +LocalDateTime updatedAt
        +charge(Money) void
        +deduct(Money) void
        +canDeduct(Money) boolean
    }

    class Coupon {
        <<Entity>>
        +Long id
        +String name
        +CouponType discountType
        +BigDecimal discountValue
        +LocalDateTime createdAt
        +LocalDateTime updatedAt
        +calculateDiscount(Money) Money
    }

    class UserCoupon {
        <<Entity>>
        +Long id
        +Long userId
        +Coupon coupon
        +boolean isUsed
        +LocalDateTime usedAt
        +LocalDateTime createdAt
        +LocalDateTime updatedAt
        +canUse() boolean
        +use() void
    }

    class CouponType {
        <<Enumeration>>
        FIXED_AMOUNT
        PERCENTAGE
    }

    Brand "1" --> "*" Product : has
    Product "1" --> "1" Price : contains (VO)
    Product "1" --> "1" Stock : has
    Order "1" *-- "*" OrderItem : aggregates
    OrderItem "1" --> "1" Price : contains (VO)
    OrderItem "1" --> "1" Money : uses (VO)
    Order "1" --> "1" Money : contains (VO)
    Point "1" --> "1" Money : contains (VO)
    UserCoupon "*" --> "1" Coupon : references
    Coupon "1" --> "1" CouponType : has

    note for Order "Aggregate Root: OrderItem은 Order를 통해서만 접근"
    note for Price "Value Object: 불변, 식별자 없음"
    note for Money "Value Object: 불변, 식별자 없음"
    note for UserCoupon "사용자가 소유한 쿠폰, 1회 사용 제한"
```

## BaseEntity (모든 엔티티의 기본 클래스)

### 소프트 삭제 (Soft Delete) 지원

모든 도메인 엔티티는 `BaseEntity`를 상속받아 공통 속성과 기능을 공유합니다.

**BaseEntity 속성:**
- `id`: 엔티티 고유 식별자 (Long)
- `createdAt`: 생성 일시 (LocalDateTime, @PrePersist로 자동 설정)
- `updatedAt`: 수정 일시 (LocalDateTime, @PreUpdate로 자동 갱신)
- `deletedAt`: 삭제 일시 (LocalDateTime?, null이면 삭제되지 않은 상태)

**BaseEntity 메서드:**
- `delete()`: 소프트 삭제 수행 (deletedAt 설정), 멱등적 (이미 삭제된 경우 무시)
- `restore()`: 삭제 복구 (deletedAt을 null로 설정), 멱등적
- `isDeleted()`: 삭제 여부 확인

**설계 포인트:**
- **물리적 삭제 대신 논리적 삭제**: 데이터 보존 및 이력 관리
- **멱등성**: `delete()`, `restore()` 메서드는 중복 호출 시에도 안전
- **조회 필터링**: 조회 시 `deletedAt IS NULL` 조건으로 삭제된 데이터 제외
- **삭제된 상품 필터링**: 좋아요 목록 조회 시 `findValidLikesByUserId()` 사용하여 삭제된 상품 제외

```kotlin
// modules/jpa/src/main/kotlin/com/loopers/domain/BaseEntity.kt
@MappedSuperclass
abstract class BaseEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open val id: Long = 0,

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    open val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(nullable = false)
    open val updatedAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = true)
    open var deletedAt: LocalDateTime? = null,
) {
    fun delete() {
        if (deletedAt == null) {
            deletedAt = LocalDateTime.now()
        }
    }

    fun restore() {
        deletedAt = null
    }

    fun isDeleted(): Boolean = deletedAt != null
}
```

## 도메인 객체 상세 설명

### 1. User (사용자)

- **책임**
  - 사용자 기본 정보 관리
  - 사용자 식별
- **속성**
  - `id`: 사용자 고유 식별자
  - `name`: 사용자 이름
  - `email`: 이메일 주소
  - `gender`: 성별 (enum)
  - `birthDate`: 생년월일
  - `createdAt`: 가입 일시
- **설계 포인트**
  - Entity (식별자 존재, 독립적 생명 주기)
  - 인증/인가는 구현하지 않음 (X-USER-ID 헤더 사용)

### 2. Brand (브랜드)

- **책임**
  - 브랜드 정보 관리
  - 소속 상품 관리
- **속성**
  - `id`: 브랜드 고유 식별자
  - `name`: 브랜드 이름
  - `description`: 브랜드 설명
  - `createdAt`: 등록 일시
- **메서드**
  - `getProducts()`: 해당 브랜드의 상품 목록 조회
- **설계 포인트**
  - Entity (식별자 존재)
  - Product와 1:N 관계

### 3. Product (상품)

- **책임**
  - 상품 기본 정보 관리
  - 재고 확인 로직
  - 브랜드 연관 관계
  - 좋아요 수 관리 (비정규화)
- **속성**
  - `id`: 상품 고유 식별자
  - `name`: 상품 이름
  - `price`: 상품 가격 (VO)
  - `brand`: 소속 브랜드
  - `likeCount`: 좋아요 수 (비정규화)
  - `stock`: 재고 정보
  - `createdAt`: 등록 일시
- **메서드**
  - `checkStockAvailable(quantity)`: 요청 수량만큼 재고가 있는지 확인
  - `incrementLikeCount()`: 좋아요 수 증가
  - `decrementLikeCount()`: 좋아요 수 감소
- **설계 포인트**
  - Entity (식별자 존재)
  - Brand와 N:1 관계 (단방향)
  - Price를 VO로 포함
  - Stock과 1:1 관계
  - **성능 최적화**: likeCount를 직접 보유하여 조회 성능 향상

### 4. Price (가격) - **Value Object**

- **타입**: Value Object (식별자 없음, 불변)
- **책임**
  - 금액 표현 및 계산
  - 통화 단위 관리
- **속성**
  - `amount`: 금액 (BigDecimal)
  - `currency`: 통화 (enum: KRW, USD 등)
- **메서드**
  - `plus(Price)`: 가격 더하기 (Kotlin 연산자 오버로드: `+`)
  - `minus(Price)`: 가격 빼기 (Kotlin 연산자 오버로드: `-`)
  - `times(int)`: 가격 곱하기 (Kotlin 연산자 오버로드: `*`)
  - `compareTo(Price)`: 가격 비교
- **설계 포인트**
  - **Value Object의 핵심**: 식별자가 없고, 값으로만 동일성을 판단
  - **불변성**: 모든 연산은 새로운 Price 객체 반환
  - **Kotlin 연산자 오버로드**: `operator fun plus()`, `operator fun times()` 등 사용
  - BigDecimal로 정확한 금액 계산
  - 통화 단위 포함하여 다국가 지원 가능
  - **도메인 무결성**: `init` 블록에서 음수 방지 검증

### 5. Stock (재고)

- **책임**
  - 상품 재고 관리
  - 재고 증감 로직
  - 재고 정합성 보장
- **속성**
  - `productId`: 상품 식별자
  - `quantity`: 재고 수량
- **메서드**
  - `decrease(int)`: 재고 감소 (재고 부족 시 예외 발생)
  - `increase(int)`: 재고 증가
  - `isAvailable(int)`: 재고 확인
- **설계 포인트**
  - Entity (productId로 식별)
  - Product와 1:1 관계
  - **동시성 제어**: 비관적 락(`SELECT FOR UPDATE`) 사용
    - 여러 사용자의 동시 주문 시 재고 정합성 확보
    - `decrease()` 메서드 호출 시 DB 행 잠금
    - 트랜잭션 내에서 재고 확인과 차감을 원자적으로 수행
  - **TOCTOU 방지**: 사전 검증(`isAvailable`)과 실제 차감(`decrease`)을 트랜잭션 내에서 처리

### 6. Like (좋아요)

- **책임**
  - 사용자의 상품 좋아요 관계 관리
- **속성**
  - `id`: 좋아요 고유 식별자
  - `user`: 좋아요한 사용자
  - `product`: 좋아요 대상 상품
  - `createdAt`: 좋아요 등록 일시
- **설계 포인트**
  - Entity (식별자 존재)
  - User와 N:1 관계
  - Product와 N:1 관계
  - 복합 유니크 제약: (userId, productId)

### 7. Order (주문)

- **책임**
  - 주문 정보 관리
  - 주문 총액 계산
  - 주문 상태 관리
- **속성**
  - `id`: 주문 고유 식별자
  - `user`: 주문한 사용자
  - `items`: 주문 항목 목록
  - `totalAmount`: 총 주문 금액
  - `status`: 주문 상태 (enum)
  - `orderedAt`: 주문 일시
- **메서드**
  - `calculateTotalAmount()`: 총 주문 금액 계산
  - `isOwnedBy(userId)`: 주문 소유자 확인
  - `confirm()`: 주문 상태를 CONFIRMED로 변경
  - `cancel()`: 주문 취소
- **설계 포인트**
  - Entity (식별자 존재)
  - Aggregate Root (OrderItem을 포함하는 집합체)
  - User와 N:1 관계
  - OrderItem과 1:N 관계 (Composition)

### 8. OrderItem (주문 항목)

- **책임**
  - 주문 내 개별 상품 정보 관리
  - 주문 시점의 상품 및 브랜드 정보 스냅샷 보존
- **속성**
  - `id`: 주문 항목 고유 식별자
  - `product`: 주문한 상품 (참조용)
  - `productName`: 주문 시점의 상품명 (스냅샷)
  - `brandId`: 주문 시점의 브랜드 ID (스냅샷)
  - `brandName`: 주문 시점의 브랜드명 (스냅샷)
  - `brandDescription`: 주문 시점의 브랜드 설명 (스냅샷)
  - `quantity`: 주문 수량
  - `priceAtOrder`: 주문 시점의 가격 (스냅샷)
- **메서드**
  - `calculateItemAmount()`: 항목별 금액 계산 (가격 × 수량)
- **설계 포인트**
  - Entity (식별자 존재)
  - Order의 일부 (독립적으로 존재 불가)
  - Product와 N:1 관계
  - 주문 시점의 상품명, 브랜드 정보(ID, 이름, 설명), 가격을 스냅샷으로 저장 (상품/브랜드 정보 변경에 영향받지 않음)

### 9. Money (금액) - **Value Object**

- **타입**: Value Object (식별자 없음, 불변)
- **책임**
  - 금액 표현 및 계산
  - 통화 단위 관리
- **속성**
  - `amount`: 금액 (BigDecimal)
  - `currency`: 통화 (enum: KRW, USD 등)
- **메서드**
  - `plus(Money)`: 금액 더하기 (Kotlin 연산자 오버로드: `+`)
  - `minus(Money)`: 금액 빼기 (Kotlin 연산자 오버로드: `-`)
  - `times(int)`: 금액 곱하기 (Kotlin 연산자 오버로드: `*`)
  - `unaryPlus()`: 단항 `+` 연산자
  - `unaryMinus()`: 단항 `-` 연산자 (부호 반전)
  - `isGreaterThanOrEqual(Money)`: 크거나 같은지 비교
- **설계 포인트**
  - **Value Object의 핵심**: 식별자가 없고, 값으로만 동일성을 판단
  - **불변성**: 모든 연산은 새로운 Money 객체 반환
  - **Kotlin 연산자 오버로드**: `operator fun plus()`, `operator fun times()`, `operator fun unaryMinus()` 등 사용
  - Price와 유사하지만 더 넓은 범위에서 사용 (주문 총액, 포인트 등)
  - **도메인 무결성**: `init` 블록에서 음수 방지 검증
  - **Kotlin의 inline value class** 사용 가능 (`@JvmInline value class`)

### 10. Point (포인트)

- **책임**
  - 사용자 포인트 관리
  - 포인트 충전 및 차감
- **속성**
  - `userId`: 사용자 식별자
  - `balance`: 보유 포인트 (Money)
  - `updatedAt`: 최근 갱신 일시
- **메서드**
  - `charge(Money)`: 포인트 충전
  - `deduct(Money)`: 포인트 차감
  - `canDeduct(Money)`: 차감 가능 여부 확인
- **설계 포인트**
  - Entity (userId로 식별)
  - User와 1:1 관계
  - 동시성 제어 필요 (비관적 락 사용)

### 11. Coupon (쿠폰)

- **책임**
  - 쿠폰 기본 정보 관리
  - 할인 금액 계산
- **속성**
  - `id`: 쿠폰 식별자
  - `name`: 쿠폰 이름
  - `discountType`: 할인 타입 (CouponType)
  - `discountValue`: 할인 값 (정액: 금액, 정률: 퍼센트)
  - `createdAt`: 생성 일시
  - `updatedAt`: 수정 일시
- **메서드**
  - `calculateDiscount(Money)`: 주문 금액에 대한 할인 금액 계산
- **설계 포인트**
  - Entity (id로 식별)
  - CouponType enum을 통해 정액/정률 할인 구분
  - 할인 금액은 주문 금액을 초과할 수 없음

### 12. UserCoupon (사용자 쿠폰)

- **책임**
  - 사용자의 쿠폰 소유 관계 관리
  - 쿠폰 사용 상태 관리
- **속성**
  - `id`: 사용자 쿠폰 식별자
  - `userId`: 사용자 식별자
  - `coupon`: 쿠폰 엔티티 (참조)
  - `isUsed`: 사용 여부
  - `usedAt`: 사용 일시
  - `createdAt`: 발급 일시
  - `updatedAt`: 수정 일시
- **메서드**
  - `canUse()`: 사용 가능 여부 확인
  - `use()`: 쿠폰 사용 처리
- **설계 포인트**
  - Entity (id로 식별)
  - User와 N:1 관계 (한 사용자가 여러 쿠폰 소유 가능)
  - Coupon과 N:1 관계 (동일한 쿠폰을 여러 사용자가 소유 가능)
  - 1회만 사용 가능 (isUsed 플래그)
  - 동시성 제어 필요 (비관적 락 사용)

## Enum 정의

### Gender (성별)

```kotlin
enum class Gender {
    MALE,
    FEMALE,
    OTHER
}
```

### Currency (통화)

```kotlin
enum class Currency {
    KRW,  // 한국 원
    USD   // 미국 달러
}
```

### OrderStatus (주문 상태)

```kotlin
enum class OrderStatus {
    PENDING,      // 대기 중
    CONFIRMED,    // 확인됨
    SHIPPED,      // 배송 중
    DELIVERED,    // 배송 완료
    CANCELLED     // 취소됨
}
```

### CouponType (쿠폰 할인 타입)

```kotlin
enum class CouponType {
    FIXED_AMOUNT,  // 정액 할인
    PERCENTAGE     // 정률 할인 (%)
}
```

## 연관 관계 정리

### 단방향 연관 관계

- Product → Brand: 상품은 자신이 속한 브랜드를 알아야 함
- Product → Stock: 상품은 자신의 재고를 알아야 함
- Like → User: 좋아요는 사용자를 알아야 함
- Like → Product: 좋아요는 상품을 알아야 함
- Order → User: 주문은 주문자를 알아야 함
- OrderItem → Product: 주문 항목은 상품을 알아야 함
- Point → User: 포인트는 소유자를 알아야 함
- UserCoupon → User: 사용자 쿠폰은 소유자를 알아야 함
- UserCoupon → Coupon: 사용자 쿠폰은 쿠폰 정보를 알아야 함

### 양방향 연관 관계 최소화

- Brand ↔ Product: 필요시 양방향으로 확장 가능하나, 기본은 단방향
- Order → OrderItem: 주문이 항목을 포함 (Composition)

## 설계 원칙

### 1. Entity vs Value Object 구분

- **Entity**: 식별자가 있고 생명 주기가 독립적 (User, Product, Order 등)
- **Value Object**: 식별자가 없고 불변 (Price, Money 등)

### 2. Aggregate 설계

- **Order가 OrderItem의 Aggregate Root**
- OrderItem은 Order를 통해서만 접근
- 트랜잭션 경계는 Aggregate 단위

### 3. 책임 주도 설계

- 도메인 로직은 도메인 객체가 직접 처리
- 예: Stock.decrease(), Order.calculateTotalAmount()
- Service는 도메인 객체를 조율하는 역할

### 4. 불변성

- Value Object는 불변으로 설계
- 수정이 필요하면 새 객체 생성

### 5. 연관 관계

- 단방향 기본, 양방향 최소화
- 필요한 방향으로만 참조

## 상태 다이어그램

### Order 상태 전이

주문(Order)의 생명주기 동안 상태가 어떻게 변화하는지 표현합니다.

```mermaid
stateDiagram-v2
    [*] --> PENDING: 주문 생성

    PENDING --> CONFIRMED: 결제 완료
    PENDING --> CANCELLED: 결제 실패/주문 취소

    CONFIRMED --> SHIPPED: 배송 시작
    CONFIRMED --> CANCELLED: 주문 취소

    SHIPPED --> DELIVERED: 배송 완료
    SHIPPED --> CANCELLED: 배송 취소 (예외적)

    DELIVERED --> [*]: 최종 상태
    CANCELLED --> [*]: 최종 상태

    note right of PENDING
        초기 상태
        - 주문 생성됨
        - 재고 임시 확보
        - 결제 대기 중
    end note

    note right of CONFIRMED
        결제 확인 상태
        - 결제 완료
        - 재고 확정 차감
        - 배송 준비
    end note

    note right of SHIPPED
        배송 중 상태
        - 배송 시작
        - 배송 중
    end note

    note right of DELIVERED
        완료 상태
        - 배송 완료
        - 주문 종료
    end note

    note right of CANCELLED
        취소 상태
        - 주문 취소
        - 재고 복구
        - 포인트 환불
    end note
```

### 상태 전이 조건 및 액션

| 현재 상태 | 이벤트 | 다음 상태 | 액션 |
|---------|--------|---------|------|
| - | 주문 생성 요청 | PENDING | • 재고 확인 및 임시 예약<br>• 주문 생성<br>• 결제 대기 |
| PENDING | 결제 완료 | CONFIRMED | • 포인트 차감<br>• 재고 확정 차감<br>• 배송 준비 시작 |
| PENDING | 결제 실패 | CANCELLED | • 임시 예약 재고 해제<br>• 주문 취소 처리 |
| PENDING | 주문 취소 요청 | CANCELLED | • 임시 예약 재고 해제<br>• 주문 취소 처리 |
| CONFIRMED | 배송 시작 | SHIPPED | • 배송 정보 등록<br>• 배송 상태 추적 시작 |
| CONFIRMED | 주문 취소 요청 | CANCELLED | • 재고 복구<br>• 포인트 환불<br>• 주문 취소 처리 |
| SHIPPED | 배송 완료 | DELIVERED | • 배송 완료 확인<br>• 주문 완료 처리 |
| SHIPPED | 배송 취소 (예외) | CANCELLED | • 재고 복구<br>• 포인트 환불<br>• 배송 취소 처리 |

### 상태별 허용 동작

| 상태 | 조회 | 취소 | 수정 | 배송 추적 |
|-----|-----|-----|-----|----------|
| PENDING | ✅ | ✅ | ✅ | ❌ |
| CONFIRMED | ✅ | ✅ | ❌ | ❌ |
| SHIPPED | ✅ | ⚠️ (제한적) | ❌ | ✅ |
| DELIVERED | ✅ | ❌ | ❌ | ✅ |
| CANCELLED | ✅ | ❌ | ❌ | ❌ |

### 비즈니스 규칙

1. **PENDING → CONFIRMED**
   - 포인트 잔액이 충분해야 함
   - 재고가 충분해야 함 (이중 체크)
   - 주문 생성 후 일정 시간 내에 결제해야 함 (타임아웃)

2. **CONFIRMED → CANCELLED**
   - 배송 시작 전까지만 취소 가능
   - 취소 시 재고 복구 및 포인트 환불 보장

3. **SHIPPED → CANCELLED**
   - 예외적인 경우만 허용 (배송 사고 등)
   - 관리자 승인 필요

4. **최종 상태**
   - DELIVERED, CANCELLED은 최종 상태로 더 이상 변경 불가

## Repository Interface 설계 (Domain Layer)

Repository Interface는 **Domain Layer에 위치**하여 도메인이 필요로 하는 영속성 계약을 정의합니다.
구체적인 구현은 **Infrastructure Layer**에서 JPA 등의 기술로 제공됩니다.

### UserRepository

```kotlin
// Domain Layer: domain/user/UserRepository.kt
interface UserRepository {
    fun findById(id: Long): User?
    fun save(user: User): User
    fun existsByEmail(email: String): Boolean
}

// Infrastructure Layer: infrastructure/user/UserRepositoryImpl.kt
@Repository
class UserRepositoryImpl(
    private val jpaRepository: UserJpaRepository
) : UserRepository {
    override fun findById(id: Long): User? =
        jpaRepository.findById(id).orElse(null)
    override fun save(user: User): User =
        jpaRepository.save(user)
    override fun existsByEmail(email: String): Boolean =
        jpaRepository.existsByEmail(email)
}
```

### ProductRepository

```kotlin
// Domain Layer: domain/product/ProductRepository.kt
interface ProductRepository {
    fun findById(id: Long): Product?
    fun findAllById(ids: List<Long>): List<Product>
    fun findByIdInAndDeletedAtIsNull(ids: List<Long>): List<Product>
    fun findAll(brandId: Long?, sort: String, pageable: Pageable): Page<Product>
    fun save(product: Product): Product
    fun existsById(id: Long): Boolean
}

// Infrastructure Layer: infrastructure/product/ProductRepositoryImpl.kt
@Repository
class ProductRepositoryImpl(
    private val jpaRepository: ProductJpaRepository
) : ProductRepository {
    override fun findById(id: Long): Product? =
        jpaRepository.findById(id).orElse(null)
    override fun findByIdInAndDeletedAtIsNull(ids: List<Long>): List<Product> =
        jpaRepository.findByIdInAndDeletedAtIsNull(ids)
    // ... JPA 기반 구현
}
```

### StockRepository

```kotlin
// Domain Layer: domain/product/StockRepository.kt
interface StockRepository {
    fun findByProductId(productId: Long): Stock?
    fun findByProductIdWithLock(productId: Long): Stock?  // 비관적 락
    fun save(stock: Stock): Stock
}

// Infrastructure Layer: infrastructure/product/StockRepositoryImpl.kt
@Repository
class StockRepositoryImpl(
    private val jpaRepository: StockJpaRepository
) : StockRepository {
    override fun findByProductIdWithLock(productId: Long): Stock? =
        jpaRepository.findByProductIdWithLock(productId)?.toDomain()
    // ... JPA + SELECT FOR UPDATE 구현
}
```

### LikeRepository

```kotlin
// Domain Layer: domain/like/LikeRepository.kt
interface LikeRepository {
    fun findByUserIdAndProductIdWithLock(userId: Long, productId: Long): Like?
    fun existsByUserIdAndProductId(userId: Long, productId: Long): Boolean
    fun countByProductId(productId: Long): Long
    fun countByProductIdIn(productIds: List<Long>): Map<Long, Long>
    fun save(like: Like): Like
    fun deleteByUserIdAndProductId(userId: Long, productId: Long)
    fun findByUserId(userId: Long, pageable: Pageable): Page<Like>
    fun findValidLikesByUserId(userId: Long, pageable: Pageable): Page<Like>
}
```

### OrderRepository

```kotlin
// Domain Layer: domain/order/OrderRepository.kt
interface OrderRepository {
    fun findById(orderId: Long): Order?
    fun findByUserId(userId: Long, pageable: Pageable): Page<Order>
    fun save(order: Order): Order
}
```

### PointRepository

```kotlin
// Domain Layer: domain/point/PointRepository.kt
interface PointRepository {
    fun findByUserId(userId: Long): Point?
    fun findByUserIdWithLock(userId: Long): Point?  // 비관적 락
    fun save(point: Point): Point
}
```

## Domain Service 설계

Domain Service는 여러 도메인 객체의 협력이 필요한 로직을 처리합니다.

### OrderService

```kotlin
// Domain Layer: domain/order/OrderService.kt
@Service
class OrderService(
    private val orderRepository: OrderRepository,
) {
    fun createOrder(userId: Long, orderItems: List<OrderItem>): Order {
        // Order 생성 및 저장
        val order = Order(userId = userId, items = orderItems)
        return orderRepository.save(order)
    }
}
```

### StockService

```kotlin
// Domain Layer: domain/product/StockService.kt
@Service
class StockService(
    private val stockRepository: StockRepository,
) {
    fun getStockByProductId(productId: Long): Stock {
        return stockRepository.findByProductId(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "재고 정보를 찾을 수 없습니다: $productId")
    }

    fun validateStockAvailability(stock: Stock, productName: String, requestedQuantity: Int) {
        if (!stock.isAvailable(requestedQuantity)) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "재고 부족: $productName (요청: $requestedQuantity, 현재: ${stock.quantity})"
            )
        }
    }

    fun decreaseStock(productId: Long, quantity: Int): Stock {
        // 비관적 락으로 재고 조회
        val stock = stockRepository.findByProductIdWithLock(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "재고 정보를 찾을 수 없습니다: $productId")
        stock.decrease(quantity)
        return stockRepository.save(stock)
    }
}
```

### PointService

```kotlin
// Domain Layer: domain/point/PointService.kt
@Service
class PointService(
    private val pointRepository: PointRepository,
) {
    fun validateUserPoint(userId: Long, totalAmount: Money) {
        val point = pointRepository.findByUserIdWithLock(userId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다: $userId")

        if (!point.canDeduct(totalAmount)) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "포인트 부족: 현재 잔액 ${point.balance.amount}, 필요 금액 ${totalAmount.amount}"
            )
        }
    }

    fun deductPoint(userId: Long, totalAmount: Money): Point {
        // 비관적 락으로 포인트 조회
        val lockedPoint = pointRepository.findByUserIdWithLock(userId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다: $userId")
        lockedPoint.deduct(totalAmount)
        return pointRepository.save(lockedPoint)
    }

    fun getPoint(userId: Long): Point {
        return pointRepository.findByUserId(userId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다: $userId")
    }

    fun chargePoint(userId: Long, amount: Money): Point {
        val point = pointRepository.findByUserId(userId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다: $userId")
        point.charge(amount)
        return pointRepository.save(point)
    }
}
```

### ProductQueryService

```kotlin
// Domain Layer: domain/product/ProductQueryService.kt
@Service
class ProductQueryService(
    private val productRepository: ProductRepository,
    private val stockRepository: StockRepository,
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val PRODUCT_DETAIL_CACHE_PREFIX = "product:detail:"
        private const val PRODUCT_LIST_CACHE_PREFIX = "product:list:"
        private val PRODUCT_DETAIL_TTL = Duration.ofMinutes(10)
        private val PRODUCT_LIST_TTL = Duration.ofMinutes(5)
    }

    data class ProductDetailData(
        val product: Product,
        val stock: Stock,
    )

    fun findProducts(brandId: Long?, sort: String, pageable: Pageable): Page<Product> {
        val cacheKey = buildProductListCacheKey(brandId, sort, pageable)

        // 1. Redis에서 먼저 조회
        val cached = redisTemplate.opsForValue().get(cacheKey)
        if (cached != null) {
            return objectMapper.readValue(cached)
        }

        // 2. DB 조회
        val products = productRepository.findAll(brandId, sort, pageable)

        // 3. Redis에 캐시 저장 (5분 TTL)
        val cacheValue = objectMapper.writeValueAsString(products)
        redisTemplate.opsForValue().set(cacheKey, cacheValue, PRODUCT_LIST_TTL)

        return products
    }

    fun getProductDetail(productId: Long): ProductDetailData {
        val cacheKey = "$PRODUCT_DETAIL_CACHE_PREFIX$productId"

        // 1. Redis에서 먼저 조회
        val cached = redisTemplate.opsForValue().get(cacheKey)
        if (cached != null) {
            return objectMapper.readValue(cached)
        }

        // 2. DB 조회
        val product = productRepository.findById(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다: $productId")
        val stock = stockRepository.findByProductId(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "재고 정보를 찾을 수 없습니다: $productId")
        val productDetailData = ProductDetailData(product, stock)

        // 3. Redis에 캐시 저장 (10분 TTL)
        val cacheValue = objectMapper.writeValueAsString(productDetailData)
        redisTemplate.opsForValue().set(cacheKey, cacheValue, PRODUCT_DETAIL_TTL)

        return productDetailData
    }

    fun getProductsByIds(productIds: List<Long>): List<Product> {
        // 삭제되지 않은 상품만 조회 (deletedAt IS NULL)
        return productRepository.findByIdInAndDeletedAtIsNull(productIds)
    }

    private fun buildProductListCacheKey(brandId: Long?, sort: String, pageable: Pageable): String {
        val brand = brandId ?: "all"
        return "${PRODUCT_LIST_CACHE_PREFIX}brand:$brand:sort:$sort:page:${pageable.pageNumber}:size:${pageable.pageSize}"
    }
}
```

### OrderQueryService

```kotlin
// Domain Layer: domain/order/OrderQueryService.kt
@Service
class OrderQueryService(
    private val orderRepository: OrderRepository,
) {
    fun getOrders(userId: Long, pageable: Pageable): Page<Order> {
        return orderRepository.findByUserId(userId, pageable)
    }

    fun getOrderDetail(userId: Long, orderId: Long): Order {
        val order = orderRepository.findById(orderId)
            ?: throw OrderNotFoundException(orderId)

        if (!order.isOwnedBy(userId)) {
            throw ForbiddenException("주문 조회 권한이 없습니다.")
        }

        return order
    }
}
```

### LikeQueryService

```kotlin
// Domain Layer: domain/like/LikeQueryService.kt
@Service
class LikeQueryService(
    private val likeRepository: LikeRepository,
) {
    fun getLikesByUserId(userId: Long, pageable: Pageable): Page<Like> {
        return likeRepository.findByUserId(userId, pageable)
    }

    fun getValidLikesByUserId(userId: Long, pageable: Pageable): Page<Like> {
        // 삭제된 상품은 제외 (deletedAt IS NULL)
        return likeRepository.findValidLikesByUserId(userId, pageable)
    }

    fun countByProductId(productId: Long): Long {
        return likeRepository.countByProductId(productId)
    }

    fun countByProductIdIn(productIds: List<Long>): Map<Long, Long> {
        return likeRepository.countByProductIdIn(productIds)
    }
}
```

### BrandQueryService

```kotlin
// Domain Layer: domain/brand/BrandQueryService.kt
@Service
class BrandQueryService(
    private val brandRepository: BrandRepository,
) {
    fun getBrand(brandId: Long): Brand {
        return brandRepository.findById(brandId)
            ?: throw BrandNotFoundException(brandId)
    }
}
```

### UserService

```kotlin
// Domain Layer: domain/user/UserService.kt
@Service
class UserService(
    private val userRepository: UserRepository,
) {
    fun registerUser(
        name: String,
        email: String,
        gender: Gender,
        birthDate: LocalDate,
    ): User {
        // 이메일 중복 확인
        if (userRepository.existsByEmail(email)) {
            throw CoreException(
                errorType = ErrorType.BAD_REQUEST,
                customMessage = "이미 사용 중인 이메일입니다: $email",
            )
        }

        val user = User(
            name = name,
            email = email,
            gender = gender,
            birthDate = birthDate,
        )

        return userRepository.save(user)
    }

    fun getUser(userId: Long): User {
        return userRepository.findById(userId)
            ?: throw CoreException(
                errorType = ErrorType.NOT_FOUND,
                customMessage = "사용자를 찾을 수 없습니다: $userId",
            )
    }
}
```

### LikeService

```kotlin
// Domain Layer: domain/like/LikeService.kt
@Service
class LikeService(
    private val likeRepository: LikeRepository,
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, String>,
) {
    fun addLike(userId: Long, productId: Long) {
        // 멱등성: 이미 존재하면 저장하지 않음
        if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
            return
        }

        // 상품 조회 및 좋아요 수 증가
        val product = productRepository.findById(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다")
        product.incrementLikeCount()

        val like = Like(userId = userId, productId = productId)
        likeRepository.save(like)

        // 캐시 무효화
        evictProductCache(productId)
    }

    fun removeLike(userId: Long, productId: Long) {
        // 멱등성: 없어도 성공
        if (!likeRepository.existsByUserIdAndProductId(userId, productId)) {
            return
        }

        // 상품 조회 및 좋아요 수 감소
        val product = productRepository.findById(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다")
        product.decrementLikeCount()

        likeRepository.deleteByUserIdAndProductId(userId, productId)

        // 캐시 무효화
        evictProductCache(productId)
    }

    private fun evictProductCache(productId: Long) {
        // 상품 상세 캐시 삭제
        redisTemplate.delete("product:detail:$productId")

        // 상품 목록 캐시 전체 삭제 (좋아요 순위 변경)
        val keys = redisTemplate.keys("product:list:*")
        if (keys.isNotEmpty()) {
            redisTemplate.delete(keys)
        }
    }
}
```

## 패키지 구조 (레이어드 아키텍처)

```txt
src/main/kotlin/
├── interfaces/               # Interfaces Layer
│   └── api/
│       ├── product/
│       │   ├── ProductV1Controller.kt
│       │   ├── ProductV1Dto.kt
│       │   └── ProductV1ApiSpec.kt
│       ├── like/
│       │   ├── LikeV1Controller.kt
│       │   ├── LikeV1Dto.kt
│       │   └── LikeV1ApiSpec.kt
│       ├── order/
│       │   ├── OrderV1Controller.kt
│       │   ├── OrderV1Dto.kt
│       │   └── OrderV1ApiSpec.kt
│       ├── brand/
│       │   ├── BrandV1Controller.kt
│       │   ├── BrandV1Dto.kt
│       │   └── BrandV1ApiSpec.kt
│       ├── ApiResponse.kt
│       └── ApiControllerAdvice.kt
│
├── application/              # Application Layer (Facade 패턴)
│   ├── product/
│   │   ├── ProductFacade.kt
│   │   ├── ProductListInfo.kt
│   │   ├── ProductDetailInfo.kt
│   │   └── LikedProductInfo.kt
│   ├── like/
│   │   └── LikeFacade.kt
│   ├── order/
│   │   ├── OrderFacade.kt
│   │   ├── OrderCreateRequest.kt
│   │   ├── OrderCreateInfo.kt
│   │   ├── OrderListInfo.kt
│   │   └── OrderDetailInfo.kt
│   ├── brand/
│   │   ├── BrandFacade.kt
│   │   └── BrandInfo.kt
│   ├── user/
│   │   ├── UserFacade.kt
│   │   ├── UserRegisterRequest.kt
│   │   └── UserInfo.kt
│   └── point/
│       ├── PointFacade.kt
│       ├── PointChargeRequest.kt
│       └── PointInfo.kt
│
├── domain/                   # Domain Layer (핵심)
│   ├── user/
│   │   ├── User.kt                    # Entity
│   │   ├── Gender.kt                  # Enum
│   │   ├── UserRepository.kt          # Interface
│   │   └── UserService.kt             # Domain Service
│   ├── brand/
│   │   ├── Brand.kt                   # Entity
│   │   ├── BrandRepository.kt         # Interface
│   │   └── BrandQueryService.kt       # Query Service
│   ├── product/
│   │   ├── Product.kt                 # Entity
│   │   ├── Price.kt                   # Value Object
│   │   ├── Stock.kt                   # Entity
│   │   ├── Currency.kt                # Enum
│   │   ├── ProductRepository.kt       # Interface
│   │   ├── StockRepository.kt         # Interface
│   │   ├── ProductQueryService.kt     # Query Service
│   │   └── StockService.kt            # Domain Service
│   ├── like/
│   │   ├── Like.kt                    # Entity
│   │   ├── LikeRepository.kt          # Interface
│   │   ├── LikeService.kt             # Domain Service
│   │   └── LikeQueryService.kt        # Query Service
│   ├── order/
│   │   ├── Order.kt                   # Entity (Aggregate Root)
│   │   ├── OrderItem.kt               # Entity
│   │   ├── OrderStatus.kt             # Enum
│   │   ├── Money.kt                   # Value Object
│   │   ├── CreateOrderItemCommand.kt  # Command
│   │   ├── OrderRepository.kt         # Interface
│   │   ├── OrderService.kt            # Domain Service
│   │   └── OrderQueryService.kt       # Query Service
│   └── point/
│       ├── Point.kt                   # Entity
│       ├── PointRepository.kt         # Interface
│       └── PointService.kt            # Domain Service
│
└── infrastructure/           # Infrastructure Layer
    ├── product/
    │   ├── ProductRepositoryImpl.kt
    │   ├── ProductJpaRepository.kt
    │   ├── StockRepositoryImpl.kt
    │   └── StockJpaRepository.kt
    ├── like/
    │   ├── LikeRepositoryImpl.kt
    │   └── LikeJpaRepository.kt
    ├── order/
    │   ├── OrderRepositoryImpl.kt
    │   └── OrderJpaRepository.kt
    ├── brand/
    │   ├── BrandRepositoryImpl.kt
    │   └── BrandJpaRepository.kt
    ├── point/
    │   ├── PointRepositoryImpl.kt
    │   └── PointJpaRepository.kt
    └── user/
        ├── UserRepositoryImpl.kt
        └── UserJpaRepository.kt
```

### 레이어 간 의존성

```mermaid
graph TD
    Interfaces[Interfaces Layer<br/>Controller, Request/Response DTO]
    Application[Application Layer<br/>ApplicationService, 트랜잭션 경계]
    Domain[Domain Layer<br/>Entity, VO, Domain Service, Repository Interface]
    Infrastructure[Infrastructure Layer<br/>Repository 구현체, JPA Entity]

    Interfaces -->|의존| Application
    Application -->|의존| Domain
    Infrastructure -.->|구현| Domain

    style Domain fill:#e8f5e9,stroke:#4caf50,stroke-width:3px
    style Infrastructure fill:#fff4e1,stroke:#ff9800,stroke-width:2px
    style Application fill:#e1f5ff,stroke:#2196f3,stroke-width:2px
    style Interfaces fill:#f3e5f5,stroke:#9c27b0,stroke-width:2px
```

**핵심 원칙**:
- **Domain Layer는 독립적**: 다른 계층에 의존하지 않음
- **Repository Interface는 Domain에 위치**: 도메인이 필요로 하는 계약 정의
- **Infrastructure는 Domain을 구현**: 점선 화살표 (구현 관계)
- **의존성 방향**: `Interfaces → Application → Domain ← Infrastructure` (DIP)
