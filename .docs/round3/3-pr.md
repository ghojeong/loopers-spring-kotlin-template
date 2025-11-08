# Round 3: Domain Modeling & Layered Architecture

## 개요

Round 3 요구사항에 따라 **도메인 모델링**과 **레이어드 아키텍처 + DIP**를 적용하여 Product, Brand, Like, Order 기능을 구현했습니다.

핵심은 **비즈니스 로직을 도메인 계층에 집중**시키고, **각 계층의 책임을 명확히 분리**하여 테스트 가능하고 유연한 구조를 만드는 것이었습니다.

## 리뷰 포인트

### 1. 도메인 모델링: Entity, Value Object, Domain Service

#### Entity (식별 가능한 상태 중심 객체)

##### ✅ Product (상품) - `domain/product/Product.kt:11`

- **책임**: 상품명, 가격, 브랜드 정보를 가지며 가격/이름 변경 행위를 제공
- **핵심 로직**:
  - `updatePrice()`, `updateName()`: 상태 변경
  - init 블록에서 비즈니스 규칙 검증 (상품명 공백 불가)

##### ✅ Stock (재고) - `domain/product/Stock.kt:10`

- **책임**: 상품 재고 수량 관리 및 증감 처리
- **핵심 로직**:
  - `decrease(amount)`: 재고 차감 및 **음수 방지 (도메인 레벨 검증)**
  - `increase(amount)`: 재고 증가
  - `isAvailable(amount)`: 재고 가용성 확인

**리뷰 포인트**: Stock은 **감소만 가능하며 음수 방지는 도메인 레벨에서 처리**됩니다 (Quest 체크리스트 요구사항)

##### ✅ Like (좋아요) - `domain/like/Like.kt:16`

- **책임**: 유저와 상품 간의 관계를 표현
- **설계 의도**: 좋아요를 **별도 도메인으로 분리**하여 User ↔ Product 관계를 추적하고 확장성 확보
- **데이터 무결성**: UniqueConstraint로 중복 좋아요 방지

**리뷰 포인트**: 좋아요를 Product 내부의 `likedUserIds: Set<Long>`가 아닌 독립 Entity로 분리한 이유는?
→ 좋아요 자체가 시간 정보(`createdAt`), 이력 관리 등 **독자적인 비즈니스 의미를 확장**할 수 있기 때문

##### ✅ Order (주문) - `domain/order/Order.kt:12`

- **책임**: 주문 항목 집합, 총액 계산, 주문 상태 관리
- **핵심 로직**:
  - `calculateTotalAmount()`: 여러 상품의 총액 계산
  - `isOwnedBy(userId)`: **소유권 검증 로직을 도메인에 위치** (Application Layer에서 활용)
  - `cancel()`, `confirm()`: 주문 상태 전환

**리뷰 포인트**: Order는 **Aggregate Root**로 설계되어 OrderItem 컬렉션을 관리합니다

##### ✅ Point (포인트) - `domain/point/Point.kt:11`

- **책임**: 유저 포인트 잔액 관리
- **핵심 로직**:
  - `deduct(amount)`: 포인트 차감 및 **잔액 부족 검증**
  - `canDeduct(amount)`: 차감 가능 여부 확인
  - `charge(amount)`: 포인트 충전

**리뷰 포인트**: 주문 시 포인트 차감 로직이 `Point.deduct()`에 위치하여 **비즈니스 규칙이 도메인에 집중**됩니다

---

#### Value Object (불변 값 중심 객체)

##### ✅ Price (가격) - `domain/product/Price.kt:11`

- **책임**: 금액 + 통화 단위를 표현하고 연산 제공
- **불변성**: `data class`로 정의하여 불변 보장
- **핵심 로직**:
  - `add()`, `multiply()`: 가격 연산
  - `compareTo()`: 가격 비교 (Comparable 구현)
  - 통화가 다른 가격 간 연산 시 예외 발생

**리뷰 포인트**: Price는 단순 BigDecimal이 아니라 **도메인 규칙(음수 불가, 통화 일치 검증)을 가진 VO**입니다

##### ✅ Money (금액) - `domain/order/Money.kt:12`

- **책임**: 주문 총액, 포인트 잔액 등을 표현
- **Price와의 차이점**: Price는 상품 가격(읽기 중심), Money는 금액 계산(연산 중심)
- **핵심 로직**:
  - `add()`, `subtract()`, `multiply()`: 금액 연산
  - `isGreaterThanOrEqual()`: 잔액 비교

**리뷰 포인트**: Money VO를 사용하여 **안전한 금액 연산**과 **도메인 의도 표현**을 동시에 확보했습니다

---

#### Domain Service (도메인 객체 협력 조율)

##### ✅ LikeService - `domain/like/LikeService.kt:9`

- **책임**: 좋아요 등록/취소 시 Product 존재 확인 및 멱등성 보장
- **핵심 로직**:
  - `addLike()`: 이미 좋아요가 존재하면 저장하지 않음 (**멱등성**)
  - `removeLike()`: 좋아요가 없어도 성공 처리 (**멱등성**)

**리뷰 포인트**: 중복 좋아요 방지를 **도메인 서비스에서 처리**하여 Application Layer는 단순히 호출만 합니다

##### ✅ ProductQueryService - `domain/product/ProductQueryService.kt:14`

- **책임**: 상품 조회 시 Product + 좋아요 수를 조합
- **핵심 로직**:
  - `findProducts()`: 상품 목록 조회 시 각 상품의 좋아요 수를 함께 제공

**리뷰 포인트**: Product와 Like의 협력 로직을 **Domain Service로 분리**하여 재사용성과 테스트 용이성 확보

##### ✅ OrderService - `domain/order/OrderService.kt:16`

- **책임**: 주문 생성 시 복잡한 협력 흐름 조율 (상품/재고/포인트 검증 및 차감)
- **핵심 로직**:

```kotlin
1. 상품 존재 및 재고 사전 검증
2. Order 생성 (총액 자동 계산)
3. 포인트 검증
4. Order 저장
5. 재고 차감 (비관적 락)
6. 포인트 차감 (비관적 락)
```

- **리뷰 포인트**: 여러 도메인 객체(Product, Stock, Point, Order)의 **협력 흐름을 Domain Service에서 조율**합니다
  - 재고 부족, 포인트 부족 등 **예외 흐름을 모두 고려**
  - 비관적 락을 사용하여 **동시성 제어**

---

### 2. 레이어드 아키텍처 + DIP

#### 전체 구조

```txt
[ Interfaces Layer ]
  └─ Controller: 요청/응답 변환
       └─ ProductV1Controller, OrderV1Controller, LikeV1Controller

[ Application Layer ]
  └─ Facade: 유스케이스 흐름 조율 (경량)
       └─ ProductFacade, OrderFacade, LikeFacade

[ Domain Layer ] ← 의존성 방향 (DIP)
  ├─ Entity: Product, Stock, Order, Like, Point
  ├─ Value Object: Price, Money
  ├─ Domain Service: OrderService, LikeService, ProductQueryService
  └─ Repository Interface: ProductRepository, OrderRepository, etc.

[ Infrastructure Layer ]
  └─ Repository Impl: JPA 구현체
       └─ ProductRepositoryImpl, OrderRepositoryImpl, etc.
```

#### DIP (Dependency Inversion Principle) 적용

##### Repository Interface는 Domain Layer에 정의

**예시**: `domain/product/ProductRepository.kt:6`

```kotlin
interface ProductRepository {
    fun findById(id: Long): Product?
    fun findAll(brandId: Long?, sort: String, pageable: Pageable): Page<Product>
    fun save(product: Product): Product
    fun existsById(id: Long): Boolean
}
```

- **구현체**: `infrastructure/product/ProductRepositoryImpl.kt`
- **리뷰 포인트**:
  - Domain Layer는 **외부 기술(JPA)에 의존하지 않습니다**
  - 테스트 시 `FakeProductRepository`나 `InMemoryProductRepository`로 교체 가능
  - **DIP 적용으로 Domain Layer가 Infrastructure Layer를 향하지 않음**

---

#### Application Layer: 경량 Facade

##### ProductFacade - `application/product/ProductFacade.kt:14`

- **책임**: Domain Service를 조합하여 유스케이스 제공
- **경량성**: 실제 비즈니스 로직은 Domain Layer에 위임
  ```kotlin
  fun getProductDetail(productId: Long): ProductDetailInfo {
      val product = productRepository.findById(productId) ?: throw ...
      val stock = stockRepository.findByProductId(productId) ?: throw ...
      val likeCount = likeRepository.countByProductId(productId)
      return ProductDetailInfo.from(product, stock, likeCount)
  }
  ```

**리뷰 포인트**: Facade는 **도메인 객체를 조합(orchestration)**하는 역할만 수행

##### OrderFacade - `application/order/OrderFacade.kt:12`

- **책임**: 주문 생성/조회 유스케이스 제공
- **핵심 흐름**:

```kotlin
fun createOrder(userId: Long, request: OrderCreateRequest): OrderCreateInfo {
    val orderItemRequests = request.items.map { ... }
    val order = orderService.createOrder(userId, orderItemRequests) // Domain Service 위임
    return OrderCreateInfo.from(order)
}
```

- **리뷰 포인트**:
  - Application Layer는 **DTO 변환 + Domain Service 호출**만 수행
  - 핵심 비즈니스 로직(재고 차감, 포인트 차감)은 OrderService(Domain Service)에 위임

---

### 3. 패키지 구조 (계층 + 도메인 기준)

```txt
com.loopers
├── domain
│   ├── product
│   │   ├── Product.kt (Entity)
│   │   ├── Stock.kt (Entity)
│   │   ├── Price.kt (VO)
│   │   ├── ProductRepository.kt (Interface)
│   │   └── ProductQueryService.kt (Domain Service)
│   ├── order
│   │   ├── Order.kt (Entity)
│   │   ├── OrderItem.kt (Entity)
│   │   ├── Money.kt (VO)
│   │   ├── OrderRepository.kt (Interface)
│   │   └── OrderService.kt (Domain Service)
│   ├── like
│   │   ├── Like.kt (Entity)
│   │   ├── LikeRepository.kt (Interface)
│   │   └── LikeService.kt (Domain Service)
│   └── point, brand, user...
│
├── application
│   ├── product
│   │   ├── ProductFacade.kt
│   │   └── ProductDetailInfo.kt, ProductListInfo.kt
│   ├── order
│   │   ├── OrderFacade.kt
│   │   └── OrderCreateRequest.kt, OrderCreateInfo.kt
│   └── like, brand...
│
├── infrastructure
│   ├── product
│   │   ├── ProductJpaRepository.kt
│   │   └── ProductRepositoryImpl.kt
│   └── order, like, point...
│
└── interfaces/api
    ├── product/ProductV1Controller.kt
    ├── order/OrderV1Controller.kt
    └── like/LikeV1Controller.kt
```

- **리뷰 포인트**:
  - 계층(domain, application, infrastructure, interfaces) + 도메인(product, order, like) 기준으로 패키지 구성
  - **응집도 높은 구조**: 각 도메인의 Entity, VO, Service, Repository가 한 패키지에 모임

---

### 4. 주요 유스케이스별 책임 분배

#### 상품 목록 조회 (정렬 + 좋아요 수)

- **흐름**: Controller → ProductFacade → ProductQueryService → ProductRepository + LikeRepository
- **책임 분배**:
  - `ProductQueryService.findProducts()`: Product + 좋아요 수 조합 (Domain Service)
  - `ProductRepository.findAll()`: 정렬 조건(`latest`, `price_asc`, `likes_desc`) 고려한 조회 (Infrastructure)
- **리뷰 포인트**: 상품 조회 시 **정렬 조건을 고려한 조회 기능**이 설계되었습니다 (Quest 체크리스트)

---

#### 좋아요 등록/취소

- **흐름**: Controller → LikeFacade → LikeService
- **책임 분배**:
  - `LikeService.addLike()`: 중복 좋아요 방지 (멱등성)
  - `LikeService.removeLike()`: 없어도 성공 (멱등성)
- **리뷰 포인트**:
  - **중복 좋아요 방지를 위한 멱등성 처리**가 구현되었습니다 (Quest 체크리스트)
  - 좋아요 수는 **상품 상세/목록 조회에서 함께 제공**됩니다 (Quest 체크리스트)

---

#### 주문 생성 (재고 차감 + 포인트 차감)

- **흐름**: Controller → OrderFacade → OrderService

##### **책임 분배**:

1. **OrderService (Domain Service)**:
   - 상품 존재 확인
   - 재고 가용성 사전 검증
   - 포인트 잔액 검증
   - 재고 차감 (`Stock.decrease()`)
   - 포인트 차감 (`Point.deduct()`)
2. **Stock (Entity)**:
   - `decrease(amount)`: 재고 차감 및 **음수 방지**
3. **Point (Entity)**:
   - `deduct(amount)`: 포인트 차감 및 **잔액 부족 검증**
4. **Order (Entity)**:
   - `calculateTotalAmount()`: 총액 계산

- **리뷰 포인트**:
  - 주문은 **여러 상품을 포함**할 수 있으며, 각 상품의 수량을 명시합니다 (Quest 체크리스트)
  - **재고 부족, 포인트 부족 등 예외 흐름**을 고려해 설계되었습니다 (Quest 체크리스트)
  - **도메인 간 협력 로직은 Domain Service에 위치**시켰습니다 (Quest 체크리스트)

---

### 5. 테스트 가능한 구조

#### DIP를 통한 테스트 용이성

- **Repository Interface가 Domain Layer에 위치**하므로:
  - 테스트 시 `FakeOrderRepository`, `InMemoryProductRepository` 등으로 교체 가능
  - 외부 DB 의존 없이 **도메인 로직만 단위 테스트** 가능

**예시**:

```kotlin
// 테스트 코드
val fakeOrderRepository = FakeOrderRepository()
val orderService = OrderService(
    orderRepository = fakeOrderRepository,
    productRepository = fakeProductRepository,
    stockRepository = fakeStockRepository,
    pointRepository = fakePointRepository
)
```

- **리뷰 포인트**:
  - Repository Interface와 구현체는 분리되었습니다 (Quest 체크리스트)
  - 테스트 가능성을 고려한 구조입니다 (Quest 체크리스트)

---

### 6. 체크리스트 대응

#### Product / Brand 도메인

- [x] 상품 정보 객체는 브랜드 정보, 좋아요 수를 포함한다
  - `ProductDetailInfo.from(product, stock, likeCount)` 참고
- [x] 상품의 정렬 조건(`latest`, `price_asc`, `likes_desc`)을 고려한 조회 기능을 설계했다
  - `ProductRepository.findAll(brandId, sort, pageable)` 참고
- [x] 상품은 재고를 가지고 있고, 주문 시 차감할 수 있어야 한다
  - `Stock` Entity 분리, `OrderService.createOrder()` 참고
- [x] 재고는 감소만 가능하며 음수 방지는 도메인 레벨에서 처리된다
  - `Stock.decrease()` 참고

#### Like 도메인

- [x] 좋아요는 유저와 상품 간의 관계로 별도 도메인으로 분리했다
  - `Like` Entity 참고
- [x] 중복 좋아요 방지를 위한 멱등성 처리가 구현되었다
  - `LikeService.addLike()` 참고
- [x] 상품의 좋아요 수는 상품 상세/목록 조회에서 함께 제공된다
  - `ProductQueryService.findProducts()` 참고

#### Order 도메인

- [x] 주문은 여러 상품을 포함할 수 있으며, 각 상품의 수량을 명시한다
  - `Order.items: List<OrderItem>` 참고
- [x] 주문 시 상품의 재고 차감, 유저 포인트 차감 등을 수행한다
  - `OrderService.createOrder()` 참고
- [x] 재고 부족, 포인트 부족 등 예외 흐름을 고려해 설계되었다
  - `Stock.decrease()`, `Point.deduct()` 예외 처리 참고

#### 도메인 서비스

- [x] 도메인 간 협력 로직은 Domain Service에 위치시켰다
  - `OrderService`, `LikeService`, `ProductQueryService` 참고
- [x] 상품 상세 조회 시 Product + Brand 정보 조합은 도메인 서비스에서 처리했다
  - `ProductQueryService` 참고
- [x] 복합 유스케이스는 Application Layer에 존재하고, 도메인 로직은 위임되었다
  - `ProductFacade`, `OrderFacade` 참고
- [x] 도메인 서비스는 상태 없이, 도메인 객체의 협력 중심으로 설계되었다

#### 소프트웨어 아키텍처 & 설계

- [x] 전체 프로젝트의 구성은 아래 아키텍처를 기반으로 구성되었다
  - Application → **Domain** ← Infrastructure
- [x] Application Layer는 도메인 객체를 조합해 흐름을 orchestration 했다
  - Facade 참고
- [x] 핵심 비즈니스 로직은 Entity, VO, Domain Service에 위치한다
- [x] Repository Interface는 Domain Layer에 정의되고, 구현체는 Infra에 위치한다
- [x] 패키지는 계층 + 도메인 기준으로 구성되었다 (`/domain/order`, `/application/like` 등)
- [x] 테스트는 외부 의존성을 분리하고, Fake/Stub 등을 사용해 단위 테스트가 가능하게 구성되었다

---

## 핵심 설계 결정 사항

### 1. 왜 Like를 독립 Entity로 분리했나?

**초기 고려사항**: Product 내부에 `likeCount: Int` 필드만 두는 방안
**최종 결정**: Like를 독립 Entity로 분리

- **이유**:
  - 누가 언제 좋아요를 눌렀는지 **추적 가능**
  - 좋아요 이력 관리, 통계 분석 등 **확장성 확보**
  - User ↔ Product 관계를 명시적으로 표현
- **트레이드오프**:
  - 조회 시 JOIN이나 추가 쿼리 필요 (성능 vs 유연성)
  - 현재는 유연성을 우선하여 Like를 독립 도메인으로 설계

---

### 2. 왜 Price와 Money를 분리했나?

- **Price**: 상품 가격 (읽기 중심, Comparable 구현으로 가격 비교)
- **Money**: 금액 계산 (연산 중심, 포인트 잔액, 주문 총액 등)

#### **이유**:

- 각 VO의 **사용 목적과 연산이 다름**
- Price는 상품 가격 비교에 특화 (`compareTo()`)
- Money는 금액 가감에 특화 (`add()`, `subtract()`, `isGreaterThanOrEqual()`)

#### **트레이드오프**:

- VO가 2개로 늘어나지만, **각 VO의 책임이 명확**해짐
- 도메인 의도가 코드에 드러남 (가격 vs 금액)

---

### 3. 왜 OrderService를 Domain Service로 두었나?

- **초기 고려사항**: Application Layer에 주문 생성 로직 위치
- **최종 결정**: Domain Service로 분리

- **이유**:
  - 주문 생성 로직은 **순수한 비즈니스 로직** (재고 검증, 포인트 검증, 차감)
  - Application Layer는 **DTO 변환 + 흐름 조율**만 담당
  - Domain Service로 분리하여 **테스트 용이성 확보**
- **트레이드오프**:
  - Domain Service가 여러 Repository를 의존하게 됨
  - 하지만 **도메인 로직이 Application Layer에 섞이지 않음**

---

### 4. Repository Interface를 Domain Layer에 둔 이유?

- **DIP (Dependency Inversion Principle) 적용**:
  - Domain Layer는 **외부 기술(JPA, DB)에 의존하지 않음**
  - Infrastructure Layer가 Domain Layer의 Interface를 구현
  - 테스트 시 Fake Repository로 교체 가능
- **효과**:
  - **도메인 로직만 독립적으로 테스트** 가능
  - DB 기술 교체 시 Infrastructure Layer만 수정
  - 도메인 중심 설계 유지

---

## 다음 개선 방향

### 1. 테스트 작성

- 도메인 로직 단위 테스트 (Stock.decrease(), Point.deduct() 등)
- 예외 케이스 테스트 (재고 부족, 포인트 부족, 중복 좋아요)
- Domain Service 테스트 (Fake Repository 활용)

### 2. 동시성 제어 개선

- 현재: 비관적 락 사용
- 개선 방향: 낙관적 락 도입 검토 (성능 vs 일관성)

### 3. 이벤트 기반 아키텍처 고려

- 주문 생성 시 재고 차감, 포인트 차감을 이벤트로 분리
- 도메인 이벤트 발행 → 이벤트 핸들러에서 처리

---

## 리뷰 시 확인해주세요

1. **도메인 모델링**:
   - Entity/VO/Domain Service 구분이 적절한가?
   - 각 객체의 책임이 명확한가?

2. **레이어드 아키텍처**:
   - 계층 간 의존성 방향이 올바른가? (DIP 준수)
   - Application Layer가 경량인가?

3. **비즈니스 로직 위치**:
   - 핵심 로직이 도메인에 위치하는가?
   - Application Layer에 비즈니스 로직이 섞이지 않았는가?

4. **테스트 가능성**:
   - Repository Interface 분리로 테스트 가능한 구조인가?
   - Fake/Stub 활용 가능한 설계인가?

5. **코드 가독성**:
   - 패키지 구조가 직관적인가?
   - 메서드명이 도메인 의도를 드러내는가?
