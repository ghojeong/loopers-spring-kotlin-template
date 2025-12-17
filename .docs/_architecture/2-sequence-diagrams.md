# 시퀀스 다이어그램

주요 기능의 객체 간 협력 구조를 레이어드 아키텍처 관점에서 시각화

## 레이어드 아키텍처 구조

```txt
[Interfaces Layer]     → ProductV1Controller (요청/응답 처리)
         ↓
[Application Layer]    → ProductFacade (유스케이스 조율)
         ↓
[Domain Layer]         → ProductQueryService (Domain Service)
                       → Product, Price (Entity, VO)
                       → ProductRepository (Interface)
                       → LikeRepository (Interface)
         ↓
[Infrastructure Layer] → ProductRepositoryImpl (JPA 구현체)
                       → LikeRepositoryImpl (JPA 구현체)
```

## 0. 사용자 회원가입

- 사용자가 회원가입하는 흐름
- 이메일 중복 확인 및 도메인 검증

```mermaid
sequenceDiagram
    participant User
    participant UserV1Controller
    participant UserFacade
    participant UserService
    participant UserRepository

    User->>UserV1Controller: POST /api/v1/users {name, email, gender, birthDate}
    Note over UserV1Controller: [Interfaces Layer]<br/>요청 검증

    UserV1Controller->>UserFacade: registerUser(request)
    Note over UserFacade: [Application Layer]<br/>@Transactional

    UserFacade->>UserService: registerUser(name, email, gender, birthDate)
    Note over UserService: [Domain Service]<br/>비즈니스 로직

    UserService->>UserRepository: existsByEmail(email)
    Note over UserRepository: [Domain Interface]
    alt 이메일 중복
        UserRepository-->>UserService: true
        UserService-->>UserFacade: CoreException (이미 사용 중인 이메일)
        UserFacade-->>UserV1Controller: 400 Bad Request
        UserV1Controller-->>User: 400 Bad Request
    end
    UserRepository-->>UserService: false

    Note over UserService: User Entity 생성<br/>도메인 검증 수행
    UserService->>UserRepository: save(user)
    UserRepository-->>UserService: User

    UserService-->>UserFacade: User
    UserFacade-->>UserV1Controller: UserInfo
    UserV1Controller-->>User: 200 OK (사용자 정보)
```

- **레이어별 책임**
  - `UserV1Controller (Interfaces)`: HTTP 요청 검증 및 응답 DTO 변환
  - `UserFacade (Application)`: 유스케이스 흐름 조율, 트랜잭션 경계
  - `UserService (Domain Service)`: 이메일 중복 확인, 회원가입 로직
  - `User (Domain Entity)`: 도메인 규칙 검증 (이름, 이메일 형식, 생년월일)
  - `UserRepository (Domain Interface)`: Repository 인터페이스
  - `UserRepositoryImpl (Infrastructure)`: JPA 기반 구현체
- **설계 포인트**
  - **도메인 검증**: User Entity의 init 블록에서 도메인 규칙 검증
  - **이메일 중복 확인**: Domain Service에서 처리
  - **의존성 방향**: Application → Domain ← Infrastructure (DIP 적용)

## 0.1 사용자 정보 조회

- 사용자가 자신의 정보를 조회하는 흐름

```mermaid
sequenceDiagram
    participant User
    participant UserV1Controller
    participant UserFacade
    participant UserService
    participant UserRepository

    User->>UserV1Controller: GET /api/v1/users/me
    Note over UserV1Controller: [Interfaces Layer]<br/>X-USER-ID 헤더 검증

    UserV1Controller->>UserFacade: getUserInfo(userId)
    Note over UserFacade: [Application Layer]<br/>@Transactional(readOnly)

    UserFacade->>UserService: getUser(userId)
    Note over UserService: [Domain Service]

    UserService->>UserRepository: findById(userId)
    Note over UserRepository: [Domain Interface]
    alt 사용자 미존재
        UserRepository-->>UserService: null
        UserService-->>UserFacade: CoreException (사용자를 찾을 수 없음)
        UserFacade-->>UserV1Controller: 404 Not Found
        UserV1Controller-->>User: 404 Not Found
    end
    UserRepository-->>UserService: User

    UserService-->>UserFacade: User
    UserFacade-->>UserV1Controller: UserInfo
    UserV1Controller-->>User: 200 OK (사용자 정보)
```

- **레이어별 책임**
  - `UserV1Controller (Interfaces)`: X-USER-ID 헤더 처리, HTTP 응답 변환
  - `UserFacade (Application)`: 유스케이스 흐름 조율
  - `UserService (Domain Service)`: 사용자 조회 로직
  - `UserRepository (Domain Interface)`: Repository 인터페이스
- **설계 포인트**
  - **읽기 전용 트랜잭션**: @Transactional(readOnly = true)

## 0.2 포인트 충전

- 사용자가 포인트를 충전하는 흐름

```mermaid
sequenceDiagram
    participant User
    participant PointV1Controller
    participant PointFacade
    participant PointService
    participant PointRepository

    User->>PointV1Controller: POST /api/v1/points/charge {amount}
    Note over PointV1Controller: [Interfaces Layer]<br/>X-USER-ID 헤더 검증

    PointV1Controller->>PointFacade: chargePoint(userId, request)
    Note over PointFacade: [Application Layer]<br/>@Transactional

    PointFacade->>PointService: chargePoint(userId, amount)
    Note over PointService: [Domain Service]

    PointService->>PointRepository: findByUserId(userId)
    Note over PointRepository: [Domain Interface]
    alt 포인트 정보 미존재
        PointRepository-->>PointService: null
        PointService-->>PointFacade: CoreException (포인트 정보를 찾을 수 없음)
        PointFacade-->>PointV1Controller: 404 Not Found
        PointV1Controller-->>User: 404 Not Found
    end
    PointRepository-->>PointService: Point

    Note over PointService: Point.charge(amount)<br/>도메인 로직 실행
    PointService->>PointRepository: save(point)
    PointRepository-->>PointService: Point (updated)

    PointService-->>PointFacade: Point
    PointFacade-->>PointV1Controller: PointInfo
    PointV1Controller-->>User: 200 OK (포인트 정보)
```

- **레이어별 책임**
  - `PointV1Controller (Interfaces)`: HTTP 요청 검증 및 응답 변환
  - `PointFacade (Application)`: 유스케이스 흐름 조율, 트랜잭션 경계
  - `PointService (Domain Service)`: 포인트 충전 로직
  - `Point (Domain Entity)`: `charge()` 메서드, 도메인 규칙 검증 (양수 확인)
  - `Money (VO)`: 금액 표현 및 연산
  - `PointRepository (Domain Interface)`: Repository 인터페이스
- **설계 포인트**
  - **도메인 로직**: Point Entity의 charge() 메서드에서 검증 및 처리
  - **Money VO 활용**: 금액 연산의 안정성 확보

## 0.3 포인트 조회

- 사용자가 자신의 포인트를 조회하는 흐름

```mermaid
sequenceDiagram
    participant User
    participant PointV1Controller
    participant PointFacade
    participant PointService
    participant PointRepository

    User->>PointV1Controller: GET /api/v1/points
    Note over PointV1Controller: [Interfaces Layer]<br/>X-USER-ID 헤더 검증

    PointV1Controller->>PointFacade: getPoint(userId)
    Note over PointFacade: [Application Layer]<br/>@Transactional(readOnly)

    PointFacade->>PointService: getPoint(userId)
    Note over PointService: [Domain Service]

    PointService->>PointRepository: findByUserId(userId)
    Note over PointRepository: [Domain Interface]
    alt 포인트 정보 미존재
        PointRepository-->>PointService: null
        PointService-->>PointFacade: CoreException (포인트 정보를 찾을 수 없음)
        PointFacade-->>PointV1Controller: 404 Not Found
        PointV1Controller-->>User: 404 Not Found
    end
    PointRepository-->>PointService: Point

    PointService-->>PointFacade: Point
    PointFacade-->>PointV1Controller: PointInfo
    PointV1Controller-->>User: 200 OK (포인트 정보)
```

- **레이어별 책임**
  - `PointV1Controller (Interfaces)`: X-USER-ID 헤더 처리, HTTP 응답 변환
  - `PointFacade (Application)`: 유스케이스 흐름 조율
  - `PointService (Domain Service)`: 포인트 조회 로직
  - `PointRepository (Domain Interface)`: Repository 인터페이스
- **설계 포인트**
  - **읽기 전용 트랜잭션**: @Transactional(readOnly = true)

## 1. 상품 목록 조회 (필터링 & 정렬)

- 사용자가 상품 목록을 조회하는 흐름
- 브랜드 필터링, 정렬 기준, 페이징을 지원

```mermaid
sequenceDiagram
    participant User
    participant ProductV1Controller
    participant ProductFacade
    participant ProductQueryService
    participant LikeQueryService
    participant ProductRepository
    participant LikeRepository

    User->>ProductV1Controller: GET /products?brandId=1&sort=likes_desc&page=0&size=20
    Note over ProductV1Controller: [Interfaces Layer]<br/>요청 검증

    ProductV1Controller->>ProductFacade: getProducts(brandId, sort, page, size)
    Note over ProductFacade: [Application Layer]<br/>유스케이스 조율

    ProductFacade->>ProductQueryService: findProducts(brandId, sort, pageable)
    Note over ProductQueryService: [Domain Service]<br/>도메인 로직 처리

    ProductQueryService->>ProductRepository: findAll(brandId, sort, pageable)
    Note over ProductRepository: [Domain Interface]
    ProductRepository-->>ProductQueryService: Page<Product>
    ProductQueryService-->>ProductFacade: Page<Product>

    ProductFacade->>LikeQueryService: countByProductIdIn(productIds)
    Note over LikeQueryService: [Domain Service]<br/>좋아요 수 배치 조회
    LikeQueryService->>LikeRepository: countByProductIdIn(productIds)
    Note over LikeRepository: [Domain Interface]<br/>배치 집계
    LikeRepository-->>LikeQueryService: Map<productId, likeCount>
    LikeQueryService-->>ProductFacade: Map<productId, likeCount>

    ProductFacade-->>ProductV1Controller: Page<ProductListInfo>
    ProductV1Controller-->>User: 200 OK (상품 목록 + 좋아요 수)
```

- **레이어별 책임**
  - `ProductV1Controller (Interfaces)`: HTTP 요청 검증 및 응답 DTO 변환
  - `ProductFacade (Application)`: 유스케이스 흐름 조율 (경량)
  - `ProductQueryService (Domain Service)`: 상품 조회 로직
  - `LikeQueryService (Domain Service)`: 좋아요 수 집계 로직 (배치 처리)
  - `Product, Price (Domain Entity/VO)`: 도메인 객체
  - `ProductRepository, LikeRepository (Domain Interface)`: Repository 인터페이스
  - `ProductRepositoryImpl, LikeRepositoryImpl (Infrastructure)`: JPA 기반 구현체
- **설계 포인트**
  - **의존성 방향**: Application → Domain ← Infrastructure (DIP 적용)
  - **Repository Interface는 Domain Layer에 위치**: 도메인이 필요로 하는 계약 정의
  - **Infrastructure는 Domain을 구현**: JPA 구현체가 Domain의 Repository를 구현
  - **N+1 문제 해결**: 좋아요 수는 `countByProductIdIn`으로 배치 집계하여 성능 최적화

## 2. 상품 좋아요 등록

- 사용자가 상품에 좋아요를 등록하는 흐름
- 멱등성을 보장

```mermaid
sequenceDiagram
    participant User
    participant LikeV1Controller
    participant LikeFacade
    participant LikeService
    participant ProductRepository
    participant LikeRepository

    User->>LikeV1Controller: POST /products/{productId}/likes
    Note over LikeV1Controller: [Interfaces Layer]<br/>X-USER-ID 헤더로 사용자 식별

    LikeV1Controller->>LikeFacade: addLike(userId, productId)
    Note over LikeFacade: [Application Layer]<br/>유스케이스 조율

    LikeFacade->>ProductRepository: existsById(productId)
    Note over ProductRepository: [Domain Interface]
    alt 상품이 존재하지 않음
        ProductRepository-->>LikeFacade: false
        LikeFacade-->>LikeV1Controller: ProductNotFoundException
        LikeV1Controller-->>User: 404 Not Found
    end
    ProductRepository-->>LikeFacade: true

    LikeFacade->>LikeService: addLike(userId, productId)
    Note over LikeService: [Domain Service]<br/>멱등성 보장

    LikeService->>LikeRepository: findByUserIdAndProductIdWithLock(userId, productId)
    Note over LikeRepository: [Domain Interface]<br/>비관적 락으로 중복 확인<br/>(SELECT FOR UPDATE)
    alt 이미 좋아요 존재
        LikeRepository-->>LikeService: Like 객체
        Note over LikeService: 멱등성: 중복 저장 없음<br/>바로 반환
    else 좋아요 미존재
        LikeRepository-->>LikeService: null
        Note over LikeService: Like Entity 생성
        LikeService->>LikeRepository: save(Like)
    end

    LikeService-->>LikeFacade: 성공
    LikeFacade-->>LikeV1Controller: 성공
    LikeV1Controller-->>User: 200 OK
```

- **레이어별 책임**
  - `LikeV1Controller (Interfaces)`: 사용자 인증, HTTP 요청 처리
  - `LikeFacade (Application)`: 유스케이스 흐름 조율, 상품 존재 검증
  - `LikeService (Domain Service)`: 멱등성 보장, 중복 확인 로직
  - `Like (Domain Entity)`: User와 Product 관계 표현
  - `ProductRepository, LikeRepository (Domain Interface)`: Repository 인터페이스
- **설계 포인트**
  - **도메인 간 협력**: LikeFacade가 ProductRepository를 통해 상품 존재 여부 확인
  - **멱등성 보장**: 중복 확인 로직은 Domain Service(LikeService)에 위치
  - **Repository는 Domain Interface**: 도메인이 필요로 하는 기능을 인터페이스로 정의

## 3. 상품 좋아요 취소

- 사용자가 상품에 대한 좋아요를 취소하는 흐름
- 멱등성을 보장
- 삭제된 행 수 확인 후 Redis 감소 및 캐시 무효화

```mermaid
sequenceDiagram
    participant User
    participant LikeV1Controller
    participant LikeFacade
    participant LikeService
    participant ProductRepository
    participant LikeRepository
    participant ProductLikeCountService
    participant RedisTemplate

    User->>LikeV1Controller: DELETE /products/{productId}/likes
    Note over LikeV1Controller: [Interfaces Layer]<br/>X-USER-ID 헤더로 사용자 식별

    LikeV1Controller->>LikeFacade: removeLike(userId, productId)
    Note over LikeFacade: [Application Layer]

    LikeFacade->>LikeService: removeLike(userId, productId)
    Note over LikeService: [Domain Service]<br/>멱등성 보장

    LikeService->>ProductRepository: existsById(productId)
    alt 상품이 존재하지 않음
        ProductRepository-->>LikeService: false
        LikeService-->>LikeFacade: CoreException (NOT_FOUND)
        LikeFacade-->>LikeV1Controller: 404 Not Found
        LikeV1Controller-->>User: 404 Not Found
    end
    ProductRepository-->>LikeService: true

    LikeService->>LikeRepository: deleteByUserIdAndProductId(userId, productId)
    Note over LikeRepository: [Domain Interface]<br/>삭제된 행 수 반환 (Long)
    LikeRepository-->>LikeService: deletedCount (0 또는 1)

    alt 실제로 삭제된 경우 (deletedCount > 0)
        LikeService->>ProductLikeCountService: decrement(productId)
        Note over ProductLikeCountService: Redis Lua 스크립트로<br/>원자적 감소 (0 이하 방지)
        ProductLikeCountService-->>LikeService: 감소 후 카운트

        LikeService->>RedisTemplate: evictProductCache(productId)
        Note over RedisTemplate: 상품 상세/목록 캐시 삭제<br/>SCAN 사용 (비블로킹)
        RedisTemplate-->>LikeService: 캐시 무효화 완료
    else 삭제된 행이 없는 경우 (deletedCount = 0)
        Note over LikeService: 멱등성: Redis 감소 및 캐시 무효화 생략
    end

    LikeService-->>LikeFacade: 성공
    LikeFacade-->>LikeV1Controller: 성공
    LikeV1Controller-->>User: 200 OK
```

- **레이어별 책임**
  - `LikeV1Controller (Interfaces)`: 사용자 인증, HTTP 요청 처리
  - `LikeFacade (Application)`: 유스케이스 흐름 조율
  - `LikeService (Domain Service)`: 멱등성 보장, 삭제 후 행 수 확인, Redis 감소 및 캐시 무효화 제어
  - `ProductLikeCountService (Domain Service)`: Redis 원자적 감소 (Lua 스크립트 사용)
  - `ProductRepository, LikeRepository (Domain Interface)`: Repository 인터페이스
  - `RedisTemplate`: Redis 캐시 관리 (SCAN 방식 사용)
- **설계 포인트**
  - **멱등성 보장**: 이미 취소된 경우 (deletedCount = 0) Redis 감소 및 캐시 무효화를 생략
  - **정확한 카운트 관리**: 실제로 삭제된 경우에만 Redis 카운트 감소
  - **Lua 스크립트 사용**: Redis에서 원자적으로 감소하되 0 이하로 내려가지 않도록 보장
  - **비블로킹 캐시 무효화**: Redis KEYS 대신 SCAN 사용으로 성능 향상

## 4. 주문 생성

- 사용자가 여러 상품을 주문하는 흐름
- 재고 차감, 포인트 차감을 포함

```mermaid
sequenceDiagram
    participant User
    participant OrderV1Controller
    participant OrderFacade
    participant ProductQueryService
    participant StockService
    participant CouponService
    participant PointService
    participant OrderService

    User->>OrderV1Controller: POST /orders {items: [{productId, quantity}], couponId?}
    Note over OrderV1Controller: [Interfaces Layer]<br/>X-USER-ID 헤더 검증

    OrderV1Controller->>OrderFacade: createOrder(userId, orderRequest)
    Note over OrderFacade: [Application Layer]<br/>@Transactional

    Note over OrderFacade: 1. 상품 조회 및 재고 검증
    loop 각 주문 항목
        OrderFacade->>ProductQueryService: getProductDetail(productId)
        alt 상품 미존재
            ProductQueryService-->>OrderFacade: ProductNotFoundException
            OrderFacade-->>OrderV1Controller: 404 Error
            OrderV1Controller-->>User: 404 Not Found
        end
        ProductQueryService-->>OrderFacade: ProductDetailData(product, stock)

        OrderFacade->>StockService: validateStockAvailability(stock, name, quantity)
        alt 재고 부족
            StockService-->>OrderFacade: InsufficientStockException
            OrderFacade-->>OrderV1Controller: 400 Error
            OrderV1Controller-->>User: 400 Bad Request (재고 부족)
        end

        Note over OrderFacade: OrderItem 스냅샷 생성<br/>(product, brand, price)
    end

    Note over OrderFacade: 2. 총액 계산 (Money VO)

    Note over OrderFacade: === 트랜잭션 시작 (@Transactional) ===

    Note over OrderFacade: 3. 쿠폰 적용 (선택적)
    alt couponId가 있는 경우
        OrderFacade->>CouponService: useUserCoupon(userId, couponId)
        Note over CouponService: findByIdAndUserIdWithLock<br/>(PESSIMISTIC_WRITE)
        alt 쿠폰 미존재 또는 타인 쿠폰
            CouponService-->>OrderFacade: CoreException (쿠폰을 찾을 수 없음)
            OrderFacade-->>OrderV1Controller: 404 Error
            OrderV1Controller-->>User: 404 Not Found
        end
        alt 이미 사용된 쿠폰
            CouponService-->>OrderFacade: CoreException (이미 사용된 쿠폰)
            OrderFacade-->>OrderV1Controller: 400 Error
            OrderV1Controller-->>User: 400 Bad Request
        end
        Note over CouponService: UserCoupon.use()<br/>Coupon.calculateDiscount()
        CouponService-->>OrderFacade: UserCoupon (할인 금액 포함)
        Note over OrderFacade: 최종 금액 = 총액 - 할인 금액
    end

    OrderFacade->>PointService: validateUserPoint(userId, finalAmount)
    Note over PointService: findByUserIdWithLock<br/>(비관적 락으로 조회 및 검증)
    alt 포인트 부족
        PointService-->>OrderFacade: InsufficientPointException
        OrderFacade-->>OrderV1Controller: 400 Error
        OrderV1Controller-->>User: 400 Bad Request (포인트 부족)
    end

    Note over OrderFacade: 4. 주문 생성
    OrderFacade->>OrderService: createOrder(userId, orderItems)
    Note over OrderService: Order Entity 생성 및 저장
    OrderService-->>OrderFacade: Order

    Note over OrderFacade: 5. 재고 차감 (비관적 락)
    loop 각 주문 항목 (정렬된 순서로)
        OrderFacade->>StockService: decreaseStock(productId, quantity)
        Note over StockService: findByProductIdWithLock<br/>(PESSIMISTIC_WRITE)
        StockService-->>OrderFacade: Stock (updated)
    end

    Note over OrderFacade: 6. 포인트 차감 (비관적 락)
    OrderFacade->>PointService: deductPoint(userId, finalAmount)
    Note over PointService: findByUserIdWithLock<br/>(PESSIMISTIC_WRITE)<br/>Point.deduct() 호출
    PointService-->>OrderFacade: Point (updated)

    Note over OrderFacade: === 트랜잭션 커밋 ===

    OrderFacade-->>OrderV1Controller: OrderCreateInfo
    OrderV1Controller-->>User: 200 OK (주문 정보)
```

- **레이어별 책임**
  - `OrderV1Controller (Interfaces)`: HTTP 요청 검증 및 응답 변환
  - `OrderFacade (Application)`: 유스케이스 흐름 조율, 트랜잭션 경계, OrderItem 스냅샷 생성, 쿠폰 적용 및 할인 계산 통합
  - `OrderService (Domain Service)`: 주문 생성 및 저장
  - `StockService (Domain Service)`: 재고 검증 및 차감 (비관적 락)
  - `CouponService (Domain Service)`: 쿠폰 사용 및 할인 계산 (비관적 락)
  - `PointService (Domain Service)`: 포인트 검증 및 차감 (비관적 락)
  - `ProductQueryService (Domain Service)`: 상품과 재고 조회
  - `Order (Entity, Aggregate Root)`: 주문 정보, OrderItem 관리, 총액 계산
  - `OrderItem (Entity)`: 상품/브랜드 스냅샷 보존
  - `Stock (Entity)`: 재고 관리, `decrease()`, `isAvailable()` 메서드
  - `Point (Entity)`: 포인트 관리, `deduct()`, `canDeduct()` 메서드
  - `Coupon (Entity)`: 쿠폰 정보, `calculateDiscount()` 메서드
  - `UserCoupon (Entity)`: 사용자 쿠폰 소유 관계, `canUse()`, `use()` 메서드
  - `Money (VO)`: 금액 연산
- **설계 포인트**
  - **Facade 패턴**: OrderFacade가 여러 Domain Service를 조율
  - **스냅샷 패턴**: OrderItem에 상품명, 브랜드 정보, 가격을 복사하여 히스토리 보존
  - **Entity의 책임**: 각 Entity는 자신의 상태 변경 로직을 직접 처리
  - **VO의 활용**: Money VO로 금액 연산의 안정성 확보
  - **Repository는 Domain Interface**: DIP 적용으로 테스트 용이성 확보
  - **트랜잭션 범위**: Application Layer에서 관리, Domain Service는 비즈니스 로직에 집중
  - **비관적 락**: Stock과 Point 조회 시 SELECT FOR UPDATE로 동시성 제어
  - **사전 검증**: 트랜잭션 실패 최소화를 위한 검증

- **동시성 제어 및 정합성 보장**
  - **TOCTOU (Time-of-Check-Time-of-Use) 갭 최소화**:
    - 사전 검증 (`isAvailable()`): 트랜잭션 전 빠른 실패로 불필요한 트랜잭션 방지
    - 트랜잭션 내 차감 (`decrease()`): 비관적 락으로 최종 확인 및 차감을 원자적으로 수행
    - 두 호출 사이의 경쟁 상태는 트랜잭션 내 비관적 락으로 해결
  - **Stock 동시성 제어**:
    - `StockRepository.findByProductIdWithLock()`: SELECT FOR UPDATE 사용
    - `Stock.decrease()`: 재고 차감 도메인 로직, 음수 방지 검증
    - 여러 사용자의 동시 주문 시 재고 정합성 확보
  - **Point 동시성 제어**:
    - `PointRepository.findByUserIdWithLock()`: SELECT FOR UPDATE 사용
    - `Point.deduct()`: 포인트 차감 도메인 로직, 음수 방지 검증
  - **외부 시스템 연동 실패 처리**:
    - 트랜잭션 외부에서 처리하여 트랜잭션 롤백 방지
    - 실패 시 재시도 큐에 등록 또는 관리자 알람 발송
    - 초기: 재시도 로직 구현 (예: 3회 재시도, 지수 백오프)
    - 향후: 필요 시 보상 트랜잭션 추가 (주문 취소, 재고 복구)

## 5. 주문 상세 조회

- 사용자가 자신의 주문 상세 정보를 조회하는 흐름

```mermaid
sequenceDiagram
    participant User
    participant OrderV1Controller
    participant OrderFacade
    participant OrderQueryService
    participant Order

    User->>OrderV1Controller: GET /orders/{orderId}
    Note over OrderV1Controller: [Interfaces Layer]<br/>X-USER-ID 헤더 검증

    OrderV1Controller->>OrderFacade: getOrderDetail(userId, orderId)
    Note over OrderFacade: [Application Layer]

    OrderFacade->>OrderQueryService: getOrderDetail(userId, orderId)
    Note over OrderQueryService: [Domain Service]

    OrderQueryService->>OrderRepository: findById(orderId)
    alt 주문이 존재하지 않음
        OrderRepository-->>OrderQueryService: null
        OrderQueryService-->>OrderFacade: OrderNotFoundException
        OrderFacade-->>OrderV1Controller: 404 Error
        OrderV1Controller-->>User: 404 Not Found
    end
    OrderRepository-->>OrderQueryService: Order (with OrderItems)

    OrderQueryService->>Order: isOwnedBy(userId)
    Note over Order: [Domain Entity]<br/>권한 검증 로직
    alt 다른 사용자의 주문
        Order-->>OrderQueryService: false
        OrderQueryService-->>OrderFacade: ForbiddenException
        OrderFacade-->>OrderV1Controller: 403 Forbidden
        OrderV1Controller-->>User: 403 Forbidden
    end
    Order-->>OrderQueryService: true

    OrderQueryService-->>OrderFacade: Order
    OrderFacade-->>OrderV1Controller: OrderDetailInfo
    OrderV1Controller-->>User: 200 OK (주문 상세 정보)
```

- **레이어별 책임**
  - `OrderV1Controller (Interfaces)`: HTTP 요청 검증 및 응답 변환
  - `OrderFacade (Application)`: 유스케이스 흐름 조율
  - `OrderQueryService (Domain Service)`: 주문 조회 및 권한 검증 로직
  - `Order (Entity, Aggregate Root)`: 주문 소유자 확인 메서드 (`isOwnedBy()`)
  - `OrderRepository (Domain Interface)`: 주문 조회 인터페이스
- **설계 포인트**
  - **도메인 로직**: 권한 검증 로직은 Order Entity에 위치 (`isOwnedBy()`)
  - **Query Service**: 조회 전용 로직을 분리
  - **Aggregate**: Order는 OrderItem을 포함하는 Aggregate Root
  - **응답 데이터**: 주문 항목(OrderItems) 정보 포함

## 설계 원칙 정리

### 1. 레이어드 아키텍처 + DIP

#### 의존성 방향

`Application → Domain ← Infrastructure`

```txt
[Interfaces Layer]
      ↓ (의존)
[Application Layer]
      ↓ (의존)
[Domain Layer] ← (구현) [Infrastructure Layer]
```

#### 핵심 원칙

- **Domain Layer는 독립적**: 다른 계층에 의존하지 않음
- **Repository Interface는 Domain에 위치**: 도메인이 필요로 하는 계약 정의
- **Infrastructure는 Domain을 구현**: JPA 구현체가 Domain Interface를 구현
- **DIP (Dependency Inversion Principle)**: 추상화에 의존, 구체 구현에 의존하지 않음

#### 올바른 의존성

- ✅ `OrderService (Domain)` → `OrderRepository (Domain Interface)`
- ✅ `OrderRepositoryImpl (Infrastructure)` implements `OrderRepository (Domain Interface)`
- ✅ `LikeService (Domain)` → `ProductRepository (Domain Interface)`
- ❌ `OrderService (Domain)` → `OrderRepositoryImpl (Infrastructure)` (직접 구현체 의존)
- ❌ `OrderRepository (Domain Interface)` → `JpaRepository (Infrastructure)` (인터페이스가 인프라에 의존)

### 2. 도메인 모델링: Entity, VO, Domain Service

#### Entity (식별자 O, 상태 변경 O)

- `User`, `Product`, `Order`, `Brand`, `Like`, `Stock`, `Point`
- 도메인 로직을 직접 처리: `Stock.decrease()`, `Point.deduct()`, `Order.isOwnedBy()`

#### Value Object (식별자 X, 불변 O)

- `Money`, `Price`
- 금액 연산의 안정성 확보: `Money.add()`, `Price.multiply()`

#### Domain Service (상태 X, 협력 조율 O)

- `OrderService`: Order, Stock, Point 협력 조율
- `ProductQueryService`: Product 조회와 Like 집계 조합
- `LikeService`: 멱등성 보장 로직

### 3. 레이어별 책임

#### Interfaces Layer

- HTTP 요청 검증 및 응답 DTO 변환
- 사용자 인증 (X-USER-ID 헤더)
- 예: `ProductV1Controller`, `OrderV1Controller`, `LikeV1Controller`, `BrandV1Controller`

#### Application Layer

- 유스케이스 흐름 조율 (경량)
- 트랜잭션 경계 설정 (`@Transactional`)
- 여러 Domain Service 조합
- 예: `ProductFacade`, `OrderFacade`, `LikeFacade`, `BrandFacade`

#### Domain Layer

- 비즈니스 로직의 핵심
- Entity, VO, Domain Service, Query Service
- Repository Interface 정의
- 예: `Order`, `Stock`, `Money`, `OrderService`, `StockService`, `PointService`, `OrderQueryService`, `ProductQueryService`, `LikeQueryService`, `OrderRepository (Interface)`

#### Infrastructure Layer

- Domain Interface 구현
- JPA, Redis, Kafka 등 기술 의존
- 예: `OrderRepositoryImpl`, `StockRepositoryImpl`

### 4. 멱등성 보장

- 좋아요 등록/취소는 중복 요청 시에도 동일한 결과 반환
- 멱등성 로직은 **Domain Service**에 위치
- 에러 대신 성공 응답으로 멱등성 구현

### 5. 트랜잭션 관리

- 트랜잭션 경계는 **Application Layer**에서 설정 (`@Transactional`)
- Domain Service는 비즈니스 로직에 집중, 트랜잭션을 직접 관리하지 않음
- 주문 생성 시 재고 차감, 포인트 차감, 주문 저장을 하나의 트랜잭션으로 처리
- 외부 시스템 연동은 트랜잭션 외부에서 처리

### 6. 동시성 제어

- 비관적 락 (Pessimistic Lock) 사용: `SELECT FOR UPDATE`
- `StockRepository.findByProductIdWithLock()`, `PointRepository.findByUserIdWithLock()`
- Entity 메서드에서 검증: `Stock.decrease()`, `Point.deduct()`

### 7. 테스트 가능성

- **DIP 적용으로 테스트 용이성 확보**
- Repository를 Fake/Mock으로 교체 가능
- Domain Layer는 독립적이므로 외부 의존 없이 테스트 가능
- 예: `FakeOrderRepository`, `InMemoryStockRepository`

### 8. 예외 처리

- 비즈니스 예외는 적절한 HTTP 상태 코드로 변환 (Interfaces Layer)
- 명확한 에러 메시지 제공
- Domain Layer에서 발생한 예외는 Application Layer를 거쳐 Controller로 전파

### 9. 성능 고려

- N+1 문제 고려 (좋아요 수 집계)
- 읽기 전용 작업은 별도 Query Service로 분리 (`ProductQueryService`)
- 비관적 락의 범위 최소화로 성능 확보

## 6. 카드 결제 요청 (CARD Payment)

- 사용자가 카드로 결제하는 흐름
- PG 연동을 통한 비동기 결제 처리

```mermaid
sequenceDiagram
    participant User
    participant OrderV1Controller
    participant OrderFacade
    participant StockService
    participant OrderService
    participant PaymentFacade
    participant PaymentService
    participant PgClient

    User->>OrderV1Controller: POST /orders {items, paymentMethod: "CARD", cardType, cardNo}
    Note over OrderV1Controller: [Interfaces Layer]<br/>X-USER-ID 헤더 검증

    OrderV1Controller->>OrderFacade: createOrder(userId, request)
    Note over OrderFacade: [Application Layer]<br/>@Transactional

    Note over OrderFacade: 1. 상품 및 재고 검증 (생략)

    Note over OrderFacade: 2. 재고 차감 (정렬된 순서)
    loop 각 주문 항목
        OrderFacade->>StockService: decreaseStock(productId, quantity)
        Note over StockService: findByProductIdWithLock<br/>(PESSIMISTIC_WRITE)
        StockService-->>OrderFacade: Stock (updated)
    end

    Note over OrderFacade: 3. 주문 생성
    OrderFacade->>OrderService: createOrder(userId, orderItems)
    Note over OrderService: Order Entity 생성 및 저장
    OrderService-->>OrderFacade: Order (status = PENDING)

    Note over OrderFacade: 4. 카드 결제 요청
    OrderFacade->>PaymentFacade: requestCardPayment(paymentRequest)
    Note over PaymentFacade: [Application Layer]

    PaymentFacade->>PaymentService: createPayment(userId, orderId, amount, cardType, cardNo)
    Note over PaymentService: Payment Entity 생성<br/>(status = PENDING)
    PaymentService-->>PaymentFacade: Payment

    PaymentFacade->>PaymentService: requestPgPayment(payment)
    Note over PaymentService: [Domain Service]<br/>@Retry, @CircuitBreaker

    PaymentService->>PgClient: requestPayment(request)
    Note over PgClient: [Infrastructure - Feign]<br/>Timeout: 1s connect, 3s read

    alt PG 요청 성공
        PgClient-->>PaymentService: PgTransactionResponse (transactionKey, status: PENDING)
        Note over PaymentService: Payment.updateTransactionKey()
        PaymentService-->>PaymentFacade: transactionKey
        PaymentFacade-->>OrderFacade: PaymentInfo (status = PENDING)
        OrderFacade-->>OrderV1Controller: OrderCreateInfo
        OrderV1Controller-->>User: 200 OK (주문 완료, 결제 PENDING)

        Note over User,PgClient: === PG가 비동기로 결제 처리 ===
    else PG 요청 실패 (Retry 후)
        PgClient-->>PaymentService: Exception (Timeout, Network Error)
        Note over PaymentService: Retry 3회 실행<br/>(1s, 2s, 4s 간격)
        alt Retry 실패 후 Circuit Breaker 열림
            PaymentService-->>PaymentFacade: Fallback 실행
            Note over PaymentFacade: 재고 복구 필요
            PaymentFacade->>StockService: increaseStock(productId, quantity)
            PaymentFacade-->>OrderV1Controller: CoreException (PG 연동 실패)
            OrderV1Controller-->>User: 500 Internal Error
        end
    end
```

- **레이어별 책임**
  - `OrderV1Controller (Interfaces)`: HTTP 요청 검증 및 응답 변환
  - `OrderFacade (Application)`: 주문 생성 흐름 조율, 결제 방식 분기 처리, 재고 복구
  - `PaymentFacade (Application)`: 결제 요청 흐름 조율
  - `PaymentService (Domain Service)`: PG 연동 로직, Resilience4j 패턴 적용
  - `PgClient (Infrastructure)`: Feign Client를 통한 PG API 호출, Timeout 설정
  - `StockService (Domain Service)`: 재고 차감 및 복구
  - `OrderService (Domain Service)`: 주문 생성
- **설계 포인트**
  - **비동기 결제**: 주문 생성 후 PG 요청, PENDING 상태로 반환
  - **Resilience 패턴**: `@Retry`, `@CircuitBreaker`, Fallback 적용
  - **재고 복구**: PG 요청 실패 시 차감한 재고 복구
  - **트랜잭션 분리**: PG 요청은 트랜잭션 외부에서 처리

## 7. PG 콜백 처리

- PG가 결제 처리 완료 후 시스템에 결과를 전달하는 흐름

```mermaid
sequenceDiagram
    participant PG
    participant PaymentV1Controller
    participant PaymentFacade
    participant PaymentService
    participant PaymentRepository
    participant Payment

    PG->>PaymentV1Controller: POST /api/v1/payments/callback {transactionKey, status, reason}
    Note over PaymentV1Controller: [Interfaces Layer]<br/>PG로부터 Callback 수신

    PaymentV1Controller->>PaymentFacade: handleCallback(request)
    Note over PaymentFacade: [Application Layer]<br/>@Transactional

    PaymentFacade->>PaymentService: handlePaymentCallback(transactionKey, status, reason)
    Note over PaymentService: [Domain Service]

    PaymentService->>PaymentRepository: findByTransactionKey(transactionKey)
    Note over PaymentRepository: [Domain Interface]

    alt Payment 존재하지 않음
        PaymentRepository-->>PaymentService: null
        PaymentService-->>PaymentFacade: CoreException (Payment not found)
        PaymentFacade-->>PaymentV1Controller: 404 Not Found
        PaymentV1Controller-->>PG: 404 Not Found
    end

    PaymentRepository-->>PaymentService: Payment (current status)

    PaymentService->>Payment: complete() or fail(reason)
    Note over Payment: [Domain Entity]<br/>상태 업데이트<br/>(PENDING → COMPLETED/FAILED)

    alt 이미 처리된 경우 (멱등성)
        Payment-->>PaymentService: 상태 변경 없음
        Note over PaymentService: 멱등성: 이미 완료된 경우<br/>중복 처리하지 않음
    else 상태 업데이트 성공
        Payment-->>PaymentService: 상태 업데이트됨
        PaymentService->>PaymentRepository: save(payment)
        PaymentRepository-->>PaymentService: Payment (updated)
    end

    PaymentService-->>PaymentFacade: Payment
    PaymentFacade-->>PaymentV1Controller: 성공
    PaymentV1Controller-->>PG: 200 OK
```

- **레이어별 책임**
  - `PaymentV1Controller (Interfaces)`: PG Callback 수신 및 응답
  - `PaymentFacade (Application)`: 콜백 처리 흐름 조율, 트랜잭션 경계
  - `PaymentService (Domain Service)`: 결제 상태 업데이트 로직
  - `Payment (Domain Entity)`: 상태 전이 메서드 (`complete()`, `fail()`)
  - `PaymentRepository (Domain Interface)`: 결제 정보 조회/저장
- **설계 포인트**
  - **멱등성**: 동일한 Callback 중복 호출 시에도 안전
  - **트랜잭션**: Callback 처리는 트랜잭션 내에서 수행
  - **상태 전이**: Payment Entity가 상태 변경 로직 보유

## 8. 결제 상태 확인 스케줄러

- 5분마다 PENDING 상태 결제를 확인하고 동기화하는 흐름

## 9. 좋아요 추가 - Outbox 패턴 (Round 8)

- 좋아요 추가 시 이벤트를 Outbox 테이블에 저장하는 흐름
- Transactional Outbox Pattern 적용

```mermaid
sequenceDiagram
    participant User
    participant LikeV1Controller
    participant LikeFacade
    participant LikeService
    participant LikeRepository
    participant OutboxEventPublisher
    participant OutboxEventRepository

    User->>LikeV1Controller: POST /products/{productId}/likes
    Note over LikeV1Controller: [Interfaces Layer]<br/>X-USER-ID 헤더로 사용자 식별

    LikeV1Controller->>LikeFacade: addLike(userId, productId)
    Note over LikeFacade: [Application Layer]<br/>@Transactional

    LikeFacade->>LikeService: addLike(userId, productId)
    Note over LikeService: [Domain Service]<br/>멱등성 보장

    LikeService->>LikeRepository: existsByUserIdAndProductId(userId, productId)
    alt 이미 좋아요 존재
        LikeRepository-->>LikeService: true
        Note over LikeService: 멱등성: 중복 저장 없음
        LikeService-->>LikeFacade: 성공 (중복 처리 안 함)
    else 좋아요 미존재
        LikeRepository-->>LikeService: false

        Note over LikeService: Like Entity 생성
        LikeService->>LikeRepository: save(Like)
        LikeRepository-->>LikeService: Like

        Note over LikeService: === Outbox 이벤트 저장 ===
        LikeService->>OutboxEventPublisher: publish(LikeAddedEvent)
        Note over OutboxEventPublisher: [Domain Service]<br/>Outbox 테이블에 이벤트 저장

        OutboxEventPublisher->>OutboxEventRepository: save(OutboxEvent)
        Note over OutboxEventRepository: [Domain Interface]<br/>OutboxEvent.create(...)<br/>- topic: catalog-events<br/>- partitionKey: productId<br/>- status: PENDING
        OutboxEventRepository-->>OutboxEventPublisher: OutboxEvent (PENDING)

        OutboxEventPublisher-->>LikeService: 성공

        LikeService-->>LikeFacade: 성공
    end

    Note over LikeFacade: === 트랜잭션 커밋 ===<br/>Like + OutboxEvent 동시 저장

    LikeFacade-->>LikeV1Controller: 성공
    LikeV1Controller-->>User: 200 OK

    Note over OutboxEventRepository: === 별도 스케줄러 (5초마다) ===<br/>OutboxRelayScheduler가<br/>PENDING 이벤트를 Kafka로 발행
```

- **레이어별 책임**
  - `LikeV1Controller (Interfaces)`: HTTP 요청 처리
  - `LikeFacade (Application)`: 유스케이스 흐름 조율, 트랜잭션 경계
  - `LikeService (Domain Service)`: 좋아요 로직, 멱등성 보장
  - `OutboxEventPublisher (Domain Service)`: Outbox 이벤트 발행
  - `OutboxEvent (Entity)`: Outbox 이벤트 엔티티
  - `LikeRepository, OutboxEventRepository (Domain Interface)`: Repository 인터페이스
- **설계 포인트**
  - **Transactional Outbox Pattern**: Like 저장과 OutboxEvent 저장을 하나의 트랜잭션으로 처리
  - **At Least Once 보장**: 트랜잭션 커밋 성공 시 OutboxEvent는 반드시 저장됨
  - **비동기 발행**: OutboxRelayScheduler가 별도로 Kafka로 발행
  - **순서 보장**: partitionKey로 같은 상품의 이벤트는 같은 파티션으로 전송

## 10. Outbox Relay - Kafka 발행 (Round 8)

- OutboxRelayScheduler가 PENDING 이벤트를 Kafka로 발행하는 흐름

```mermaid
sequenceDiagram
    participant Scheduler as OutboxRelayScheduler
    participant Processor as OutboxEventProcessor
    participant OutboxRepo as OutboxEventRepository
    participant KafkaProducer as KafkaProducerService
    participant Kafka

    Note over Scheduler: [Infrastructure]<br/>@Scheduled(fixedDelay = 5000)<br/>5초마다 실행

    Scheduler->>OutboxRepo: findPendingEvents(batchSize = 100)
    Note over OutboxRepo: SELECT * FROM outbox_events<br/>WHERE status = 'PENDING'<br/>ORDER BY created_at<br/>LIMIT 100
    OutboxRepo-->>Scheduler: List<OutboxEvent> (PENDING)

    loop 각 PENDING 이벤트
        Scheduler->>Processor: process(outboxEvent)
        Note over Processor: [Domain Service]<br/>개별 이벤트 처리

        Processor->>KafkaProducer: send(topic, partitionKey, payload, eventType)
        Note over KafkaProducer: [Infrastructure]<br/>eventType 헤더 추가

        KafkaProducer->>Kafka: ProducerRecord.send()
        Note over Kafka: acks=all<br/>idempotence=true

        alt Kafka 전송 성공
            Kafka-->>KafkaProducer: Metadata (offset, partition)
            KafkaProducer-->>Processor: 성공

            Note over Processor: OutboxEvent.markAsPublished()
            Processor->>OutboxRepo: save(outboxEvent)
            Note over OutboxRepo: status = PUBLISHED<br/>published_at = NOW()
            OutboxRepo-->>Processor: OutboxEvent (PUBLISHED)

        else Kafka 전송 실패
            Kafka-->>KafkaProducer: Exception
            KafkaProducer-->>Processor: 실패

            Note over Processor: OutboxEvent.markAsFailed(...)<br/>retry_count++
            Processor->>OutboxRepo: save(outboxEvent)
            Note over OutboxRepo: retry_count++<br/>last_attempt_at = NOW()<br/>status = FAILED (max retry 초과 시)
            OutboxRepo-->>Processor: OutboxEvent (PENDING or FAILED)
        end
    end

    Scheduler-->>Scheduler: 5초 대기 후 재실행
```

- **레이어별 책임**
  - `OutboxRelayScheduler (Infrastructure)`: 주기적 실행
  - `OutboxEventProcessor (Domain Service)`: 개별 이벤트 처리
  - `KafkaProducerService (Infrastructure)`: Kafka 전송
  - `OutboxEventRepository (Domain Interface)`: Outbox 이벤트 조회/저장
- **설계 포인트**
  - **배치 처리**: 한 번에 100개씩 처리
  - **재시도 로직**: 실패 시 retry_count 증가, 최대 재시도 횟수 초과 시 FAILED 상태
  - **At Least Once 보장**: PENDING 상태는 계속 재시도됨

## 11. Kafka Consumer - 이벤트 소비 (Round 8)

- commerce-streamer가 Kafka 이벤트를 소비하여 ProductMetrics를 업데이트하는 흐름

```mermaid
sequenceDiagram
    participant Kafka
    participant Consumer as KafkaEventConsumer
    participant EventHandledRepo as EventHandledRepository
    participant MetricsRepo as ProductMetricsRepository
    participant Metrics as ProductMetrics

    Kafka->>Consumer: catalog-events 메시지 수신
    Note over Consumer: [Infrastructure]<br/>@KafkaListener<br/>@Header("eventType")

    Consumer->>Consumer: 이벤트 타입 라우팅
    Note over Consumer: when (eventType) {<br/>  "LikeAddedEvent" → handleLikeAdded()<br/>  "LikeRemovedEvent" → handleLikeRemoved()<br/>}

    Note over Consumer: [Application Layer]<br/>@Transactional

    Consumer->>EventHandledRepo: existsByEventId(eventId)
    Note over EventHandledRepo: [Domain Interface]<br/>멱등성 체크

    alt 이미 처리된 이벤트
        EventHandledRepo-->>Consumer: true
        Note over Consumer: 멱등성: 중복 처리 안 함<br/>바로 Ack
        Consumer->>Kafka: acknowledgment.acknowledge()
        Note over Kafka: Manual Ack<br/>Offset 커밋
    else 새로운 이벤트
        EventHandledRepo-->>Consumer: false

        Note over Consumer: === 비즈니스 로직 실행 ===
        Consumer->>MetricsRepo: findByProductIdOrCreate(productId)
        MetricsRepo-->>Consumer: ProductMetrics

        alt LikeAddedEvent
            Consumer->>Metrics: incrementLikeCount()
            Note over Metrics: likeCount++
        else LikeRemovedEvent
            Consumer->>Metrics: decrementLikeCount()
            Note over Metrics: likeCount-- (0 이하 방지)
        end

        Consumer->>MetricsRepo: save(productMetrics)
        MetricsRepo-->>Consumer: ProductMetrics (updated)

        Note over Consumer: === EventHandled 저장 ===
        Consumer->>EventHandledRepo: save(EventHandled.create(...))
        Note over EventHandledRepo: eventId 저장<br/>(UNIQUE 제약)
        EventHandledRepo-->>Consumer: EventHandled

        Note over Consumer: === 트랜잭션 커밋 ===<br/>ProductMetrics + EventHandled 동시 저장

        Consumer->>Kafka: acknowledgment.acknowledge()
        Note over Kafka: Manual Ack<br/>Offset 커밋
    end
```

- **레이어별 책임**
  - `KafkaEventConsumer (Infrastructure)`: Kafka 메시지 수신, 이벤트 타입 라우팅
  - `EventHandledRepository (Domain Interface)`: 멱등성 체크
  - `ProductMetricsRepository (Domain Interface)`: 메트릭 조회/저장
  - `ProductMetrics (Entity)`: 메트릭 증감 로직
- **설계 포인트**
  - **Idempotent Consumer Pattern**: EventHandled 테이블로 중복 처리 방지
  - **At Most Once 보장**: 같은 이벤트는 한 번만 처리
  - **Manual Ack**: 처리 완료 후에만 Offset 커밋
  - **eventType 헤더**: JSON 파싱 없이 빠른 라우팅
  - **트랜잭션**: ProductMetrics 업데이트와 EventHandled 저장을 하나의 트랜잭션으로 처리

## 설계 원칙 정리

### Round 8에서 추가된 이벤트 기반 아키텍처

**Transactional Outbox Pattern (Producer)**
- commerce-api에서 도메인 데이터 변경과 이벤트 저장을 하나의 트랜잭션으로 처리
- OutboxRelayScheduler가 주기적으로 PENDING 이벤트를 Kafka로 발행
- At Least Once 보장: 실패 시 재시도 로직으로 메시지 유실 방지

**Idempotent Consumer Pattern (Consumer)**
- commerce-streamer에서 EventHandled 테이블로 중복 이벤트 처리 방지
- Manual Ack로 처리 완료 후에만 커밋
- At Most Once 보장: 같은 이벤트가 여러 번 수신되어도 한 번만 처리

**이벤트 기반 실시간 집계**
- commerce-streamer에서 ProductMetrics 집계 처리
- 도메인 로직과 집계 로직 완전 분리 (별도 애플리케이션)
- 집계 실패해도 도메인 로직에 영향 없음 (장애 격리)
