# 완료된 구현

- FETCH JOIN이 아닌 일반 LEFT JOIN을 사용한 이유
  - FETCH JOIN은 연관 엔티티를 즉시 로딩하기 위한 것
  - 여기서는 COUNT 집계를 위한 조인이므로 일반 LEFT JOIN이 적절
  - FETCH JOIN은 GROUP BY와 함께 사용할 수 없습니다

## 1. Domain Layer ✓

- Entities: User, Brand, Product, Stock, Like, Point, Order, OrderItem
- Value Objects: Price, Money
- Enums: Gender, Currency, OrderStatus
- Repository Interfaces: UserRepository, BrandRepository,
ProductRepository, StockRepository, LikeRepository, PointRepository,
OrderRepository
- Domain Services: LikeService, ProductQueryService, OrderService

## 2. Infrastructure Layer ✓

- JPA Repositories:
  - UserJpaRepository, BrandJpaRepository, ProductJpaRepository
  - StockJpaRepository, LikeJpaRepository, PointJpaRepository,
OrderJpaRepository
- Repository Implementations:
  - UserRepositoryImpl, BrandRepositoryImpl, ProductRepositoryImpl
  - StockRepositoryImpl, LikeRepositoryImpl, PointRepositoryImpl,
OrderRepositoryImpl
- 비관적 락 구현 (StockJpaRepository, PointJpaRepository)

## 3. Application Layer ✓

- Facades: BrandFacade, ProductFacade, LikeFacade, OrderFacade
- Info DTOs: BrandInfo, ProductListInfo, ProductDetailInfo,
LikedProductInfo, OrderCreateInfo, OrderListInfo, OrderDetailInfo

## 4. Interfaces Layer ✓

- API Specs: BrandV1ApiSpec, ProductV1ApiSpec, LikeV1ApiSpec,
OrderV1ApiSpec
- Controllers: BrandV1Controller, ProductV1Controller, LikeV1Controller,
OrderV1Controller
- Response DTOs: BrandV1Dto, ProductV1Dto, LikeV1Dto, OrderV1Dto

구현된 API 엔드포인트

Brand API

- GET /api/v1/brands/{brandId} - 브랜드 조회

Product API

- GET /api/v1/products - 상품 목록 조회 (필터링, 정렬, 페이징)
- GET /api/v1/products/{productId} - 상품 상세 조회

Like API

- POST /api/v1/products/{productId}/likes - 좋아요 등록
- DELETE /api/v1/products/{productId}/likes - 좋아요 취소
- GET /api/v1/users/likes - 좋아요한 상품 목록 조회

Order API

- POST /api/v1/orders - 주문 생성
- GET /api/v1/orders - 주문 목록 조회
- GET /api/v1/orders/{orderId} - 주문 상세 조회

주요 구현 특징

- 레이어드 아키텍처 + DIP: Repository 인터페이스는 Domain Layer, 구현체는
Infrastructure Layer
- 도메인 모델링: Entity, Value Object, Domain Service 구분
- 동시성 제어: 재고 차감 및 포인트 차감에 비관적 락 적용
- 멱등성: 좋아요 등록/취소 멱등성 보장
- Swagger 문서화: OpenAPI 3.0 어노테이션 적용

이제 ./gradlew test 명령으로 전체 테스트를 실행하실 수 있습니다!
