# 시퀀스 다이어그램

주요 기능의 객체 간 협력 구조를 시각화

## 1. 상품 목록 조회 (필터링 & 정렬)

- 사용자가 상품 목록을 조회하는 흐름
- 브랜드 필터링, 정렬 기준, 페이징을 지원

```mermaid
sequenceDiagram
    participant User
    participant ProductController
    participant ProductService
    participant LikeFacade
    participant ProductRepository

    User->>ProductController: GET /products?brandId=1&sort=likes_desc&page=0&size=20
    ProductController->>ProductService: getProducts(brandId, sort, page, size)

    ProductService->>ProductRepository: findByBrandIdWithPaging(brandId, sort, pageable)
    ProductRepository-->>ProductService: List<Product>

    loop 각 상품에 대해
        ProductService->>LikeFacade: countByProductId(productId)
        Note over LikeFacade: Like BC의 공개 인터페이스
        LikeFacade-->>ProductService: likeCount
    end

    ProductService-->>ProductController: ProductListResponse (with likeCount)
    ProductController-->>User: 200 OK (상품 목록 + 좋아요 수)
```

- **주요 책임**
  - `ProductController`: 요청 검증 및 응답 변환
  - `ProductService`: 비즈니스 로직 조율 (상품 조회 + 좋아요 수 집계)
  - `LikeFacade`: Like BC의 공개 인터페이스 (좋아요 조회 기능 제공)
  - `ProductRepository`: 데이터 접근
- **설계 포인트**
  - **Bounded Context 분리**: ProductService는 Like BC의 내부 구현(LikeRepository)에 직접 접근하지 않고, 공개 인터페이스인 LikeFacade를 통해 협력
  - 좋아요 수는 별도 집계 (N+1 문제 고려 필요)

## 2. 상품 좋아요 등록

- 사용자가 상품에 좋아요를 등록하는 흐름
- 멱등성을 보장

```mermaid
sequenceDiagram
    participant User
    participant LikeController
    participant LikeService
    participant ProductFacade
    participant LikeRepository

    User->>LikeController: POST /like/products/{productId}
    Note over LikeController: X-USER-ID 헤더로 사용자 식별

    LikeController->>LikeService: addLike(userId, productId)

    LikeService->>ProductFacade: getById(productId)
    Note over ProductFacade: Product BC의 공개 인터페이스
    alt 상품이 존재하지 않음
        ProductFacade-->>LikeService: ProductNotFoundException
        LikeService-->>LikeController: 404 Error
        LikeController-->>User: 404 Not Found
    end

    ProductFacade-->>LikeService: Product

    LikeService->>LikeRepository: existsByUserIdAndProductId(userId, productId)
    LikeRepository-->>LikeService: boolean

    alt 좋아요가 이미 존재
        LikeService-->>LikeController: 이미 좋아요한 상품 (멱등성)
        LikeController-->>User: 200 OK
    else 좋아요가 존재하지 않음
        LikeService->>LikeRepository: save(Like(userId, productId))
        LikeRepository-->>LikeService: Like
        LikeService-->>LikeController: 좋아요 등록 완료
        LikeController-->>User: 200 OK
    end
```

- **주요 책임**
  - `LikeController`: 사용자 인증 및 요청 처리
  - `LikeService`: 좋아요 비즈니스 로직 (멱등성 보장)
  - `ProductFacade`: Product BC의 공개 인터페이스 (상품 조회 기능 제공)
  - `LikeRepository`: 좋아요 데이터 접근
- **설계 포인트**
  - **Bounded Context 분리**: LikeService는 Product BC의 공개 인터페이스인 ProductFacade를 통해 상품 정보에 접근
  - 멱등성 보장: 이미 좋아요한 경우 중복 저장하지 않음
  - 상품 존재 여부 사전 검증

## 3. 상품 좋아요 취소

- 사용자가 상품에 대한 좋아요를 취소하는 흐름
- 멱등성을 보장

```mermaid
sequenceDiagram
    participant User
    participant LikeController
    participant LikeService
    participant ProductFacade
    participant LikeRepository

    User->>LikeController: DELETE /like/products/{productId}
    Note over LikeController: X-USER-ID 헤더로 사용자 식별

    LikeController->>LikeService: removeLike(userId, productId)

    LikeService->>ProductFacade: getById(productId)
    Note over ProductFacade: Product BC의 공개 인터페이스
    alt 상품이 존재하지 않음
        ProductFacade-->>LikeService: ProductNotFoundException
        LikeService-->>LikeController: 404 Error
        LikeController-->>User: 404 Not Found
    end

    ProductFacade-->>LikeService: Product

    LikeService->>LikeRepository: findByUserIdAndProductId(userId, productId)
    LikeRepository-->>LikeService: Optional<Like>

    alt 좋아요가 존재
        LikeService->>LikeRepository: delete(like)
        LikeRepository-->>LikeService: void
        LikeService-->>LikeController: 좋아요 취소 완료
        LikeController-->>User: 200 OK
    else 좋아요가 존재하지 않음
        LikeService-->>LikeController: 이미 취소된 좋아요 (멱등성)
        LikeController-->>User: 200 OK
    end
```

- **주요 책임**
  - `LikeController`: 사용자 인증 및 요청 처리
  - `LikeService`: 좋아요 비즈니스 로직 (멱등성 보장)
  - `ProductFacade`: Product BC의 공개 인터페이스 (상품 조회 기능 제공)
  - `LikeRepository`: 좋아요 데이터 접근
- **설계 포인트**
  - **Bounded Context 분리**: LikeService는 Product BC의 공개 인터페이스인 ProductFacade를 통해 상품 정보에 접근
  - 멱등성 보장: 이미 취소된 경우에도 에러 없이 성공 응답
  - 상품 존재 여부 사전 검증

## 4. 주문 생성

- 사용자가 여러 상품을 주문하는 흐름
- 재고 차감, 포인트 차감, 외부 시스템 연동을 포함

```mermaid
sequenceDiagram
    participant User
    participant OrderController
    participant OrderService
    participant ProductFacade
    participant PointFacade
    participant OrderRepository
    participant ExternalOrderSystem

    User->>OrderController: POST /orders {items: [{productId, quantity}]}
    Note over OrderController: X-USER-ID 헤더로 사용자 식별

    OrderController->>OrderService: createOrder(userId, orderRequest)

    OrderService->>OrderService: 주문 요청 검증 (항목 개수, 수량)

    loop 각 주문 항목
        OrderService->>ProductFacade: getById(productId)
        Note over ProductFacade: Product BC의 공개 인터페이스
        alt 상품이 존재하지 않음
            ProductFacade-->>OrderService: ProductNotFoundException
            OrderService-->>OrderController: 404 Error
            OrderController-->>User: 404 Not Found
        end
        ProductFacade-->>OrderService: Product

        OrderService->>ProductFacade: checkStock(productId, quantity)
        alt 재고 부족
            ProductFacade-->>OrderService: InsufficientStockException
            OrderService-->>OrderController: 400 Error (재고 부족)
            OrderController-->>User: 400 Bad Request
        end
    end

    OrderService->>OrderService: 총 주문 금액 계산

    OrderService->>PointFacade: checkBalance(userId, totalAmount)
    Note over PointFacade: Point BC의 공개 인터페이스
    alt 포인트 부족
        PointFacade-->>OrderService: InsufficientPointException
        OrderService-->>OrderController: 400 Error (포인트 부족)
        OrderController-->>User: 400 Bad Request
    end

    Note over OrderService: 트랜잭션 시작

    OrderService->>OrderRepository: save(order)
    OrderRepository-->>OrderService: Order

    loop 각 주문 항목
        OrderService->>ProductFacade: decreaseStock(productId, quantity)
    end

    OrderService->>PointFacade: deductPoints(userId, totalAmount)

    Note over OrderService: 트랜잭션 커밋

    OrderService->>ExternalOrderSystem: sendOrderInfo(order)
    Note over ExternalOrderSystem: 비동기 처리 또는 Mock

    OrderService-->>OrderController: OrderResponse
    OrderController-->>User: 200 OK (주문 정보)
```

- **주요 책임**
  - `OrderController`: 요청 검증 및 응답 변환
  - `OrderService`: 주문 비즈니스 로직 조율 (검증, 트랜잭션 관리)
  - `ProductFacade`: Product BC의 공개 인터페이스 (상품 조회, 재고 확인 및 차감)
  - `PointFacade`: Point BC의 공개 인터페이스 (포인트 확인 및 차감)
  - `OrderRepository`: 주문 데이터 저장
  - `ExternalOrderSystem`: 외부 시스템 연동 (Mock 가능)
- **설계 포인트**
  - **Bounded Context 분리**: OrderService는 Product BC와 Point BC의 공개 인터페이스(Facade)를 통해서만 협력
  - **Stock은 Product BC의 일부**: 재고 관련 작업도 ProductFacade를 통해 처리
  - 트랜잭션 범위: 주문 저장, 재고 차감, 포인트 차감
  - 사전 검증: 상품 존재, 재고 확인, 포인트 확인
  - 외부 시스템 연동은 트랜잭션 외부에서 처리 (또는 비동기)
  - 예외 처리: 재고 부족, 포인트 부족 등 명확한 에러 메시지

## 5. 주문 상세 조회

- 사용자가 자신의 주문 상세 정보를 조회하는 흐름

```mermaid
sequenceDiagram
    participant User
    participant OrderController
    participant OrderService
    participant OrderReader
    participant OrderRepository

    User->>OrderController: GET /orders/{orderId}
    Note over OrderController: X-USER-ID 헤더로 사용자 식별

    OrderController->>OrderService: getOrderDetail(userId, orderId)

    OrderService->>OrderReader: findById(orderId)
    alt 주문이 존재하지 않음
        OrderReader-->>OrderService: OrderNotFoundException
        OrderService-->>OrderController: 404 Error
        OrderController-->>User: 404 Not Found
    end

    OrderReader-->>OrderService: Order (with OrderItems)

    OrderService->>OrderService: 주문 소유자 검증
    alt 다른 사용자의 주문
        OrderService-->>OrderController: 403 Forbidden
        OrderController-->>User: 403 Forbidden
    end

    OrderService-->>OrderController: OrderDetailResponse
    OrderController-->>User: 200 OK (주문 상세 정보)
```

- **주요 책임**
  - `OrderController`: 요청 검증 및 응답 변환
  - `OrderService`: 주문 조회 및 권한 검증
  - `OrderReader`: 주문 조회 전용 인터페이스
  - `OrderRepository`: 데이터 접근
- **설계 포인트**
  - 본인 주문만 조회 가능 (권한 검증)
  - 주문 항목(OrderItems) 정보 포함
  - 읽기 전용 작업은 Reader 인터페이스 활용

## 설계 원칙 정리

### 1. Bounded Context 분리 (DDD)

각 도메인은 독립된 Bounded Context로 분리하며, **Facade 인터페이스**를 통해서만 협력합니다.

#### Bounded Context 목록
- **User BC**: 사용자 관리
- **Brand BC**: 브랜드 관리
- **Product BC**: 상품 및 재고 관리 (Stock 포함)
- **Like BC**: 좋아요 관리
- **Order BC**: 주문 관리
- **Point BC**: 포인트 관리

#### Facade 인터페이스
각 BC는 외부에 공개할 기능을 Facade 인터페이스로 제공합니다:
- **ProductFacade**: 상품 조회, 재고 확인/차감 기능
- **LikeFacade**: 좋아요 수 조회 기능
- **PointFacade**: 포인트 확인/차감 기능

#### BC 간 협력 규칙
- ✅ **올바른 협력**: `ServiceA` → `FacadeB` (다른 BC의 공개 인터페이스 사용)
- ❌ **잘못된 협력**: `ServiceA` → `RepositoryB` (다른 BC의 내부 구현 직접 접근)

**예시:**
- ✅ `ProductService` → `LikeFacade.countByProductId()`
- ❌ `ProductService` → `LikeRepository.countByProductId()`
- ✅ `LikeService` → `ProductFacade.getById()`
- ❌ `LikeService` → `ProductRepository.findById()`
- ✅ `OrderService` → `ProductFacade.decreaseStock()`
- ❌ `OrderService` → `StockService.decreaseStock()` (Stock은 Product BC 내부)

### 2. 책임 분리

- **Controller**: 요청/응답 변환, 사용자 인증
- **Service**: 비즈니스 로직 조율, 트랜잭션 관리
- **Facade**: BC의 공개 인터페이스 (다른 BC와의 협력)
- **Repository**: 데이터 접근 (BC 내부에서만 사용)

### 3. 멱등성 보장

- 좋아요 등록/취소는 중복 요청 시에도 동일한 결과 반환
- 에러 대신 성공 응답으로 멱등성 구현

### 4. 트랜잭션 관리

- 주문 생성 시 재고 차감, 포인트 차감, 주문 저장을 하나의 트랜잭션으로 처리
- 외부 시스템 연동은 트랜잭션 외부에서 처리

### 5. 예외 처리

- 비즈니스 예외는 적절한 HTTP 상태 코드로 변환
- 명확한 에러 메시지 제공

### 6. 성능 고려

- N+1 문제 고려 (좋아요 수 집계)
- 읽기 전용 작업은 별도 인터페이스로 분리
