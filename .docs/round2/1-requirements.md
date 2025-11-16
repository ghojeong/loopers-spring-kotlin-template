# 요구사항 명세

이커머스 시스템의 상품, 브랜드, 좋아요, 주문 도메인에 대한 기능 요구사항을 정리

## 도메인 모델링 원칙

이 요구사항은 **도메인 주도 설계(DDD)** 와 **레이어드 아키텍처** 를 기반으로 구현됩니다.

### Entity vs Value Object vs Domain Service

- **Entity**: 고유 식별자를 가지며 생명주기가 독립적인 객체 (User, Product, Order, Brand, Like)
- **Value Object (VO)**: 식별자 없이 값으로만 구분되는 불변 객체 (Money, Price)
- **Domain Service**: 여러 도메인 객체의 협력이 필요한 로직을 처리하는 무상태 서비스

### 레이어별 책임

```txt
[Interfaces Layer]  → 요청/응답 처리, 검증
[Application Layer] → 유스케이스 조율 (경량)
[Domain Layer]      → 비즈니스 로직의 핵심 (Entity, VO, Domain Service, Repository Interface)
[Infrastructure]    → 외부 기술 의존 (JPA 구현, Repository 구현체)
```

## 유비쿼터스 언어

| 한글 | 영문 | 설명 |
| --- | --- | --- |
| 사용자 | User | 시스템을 이용하는 고객 |
| 상품 | Product | 구매 가능한 아이템 |
| 브랜드 | Brand | 상품을 제공하는 브랜드 |
| 좋아요 | Like | 사용자가 상품에 표시하는 관심 표시 |
| 주문 | Order | 사용자의 상품 구매 요청 |
| 주문 항목 | OrderItem | 주문에 포함된 개별 상품 정보 |
| 재고 | Stock | 상품의 판매 가능 수량 |
| 포인트 | Point | 결제에 사용되는 가상 화폐 |
| 가격 | Price | 상품의 금액 |
| 쿠폰 | Coupon | 할인을 제공하는 쿠폰 (정액/정률) |
| 사용자 쿠폰 | UserCoupon | 사용자가 소유한 쿠폰 (1회 사용 제한) |

## 도메인별 요구사항

### 1. 사용자 (User)

#### 1.1 사용자 회원가입

- **유저 스토리**
  - 사용자는 회원가입을 통해 시스템에 등록할 수 있다.
- **기능 흐름**
  - 1. 회원가입 요청을 검증한다.
    - 이름, 이메일, 성별, 생년월일 필수
  - 2. 이메일 중복 확인
    - 이미 등록된 이메일이면 가입 실패 (400 에러)
  - 3. 사용자 정보를 저장한다.
  - 4. DB 제약 조건 위반 시 재확인 후 적절한 에러 메시지 반환 (이메일 중복 경합 상황 처리)
  - 5. 사용자 정보를 반환한다.
- **제약사항**
  - 이메일은 유니크해야 함
  - 이메일 형식 검증
  - 생년월일은 미래일 수 없음
  - DB 유니크 제약 위반 시 DataIntegrityViolationException 처리
- **도메인 객체 책임**
  - `User (Entity)`: 사용자 기본 정보, 도메인 규칙 검증
  - `UserRepository (Interface in Domain)`: 사용자 저장/조회 인터페이스
  - `UserService (Domain Service)`: 이메일 중복 확인, 회원가입 로직, DataIntegrityViolationException 처리

#### 1.2 사용자 정보 조회

- **유저 스토리**
  - 로그인한 사용자는 자신의 정보를 조회할 수 있다.
- **기능 흐름**
  - 1. X-USER-ID 헤더로 사용자를 식별한다.
  - 2. 사용자 정보를 조회한다.
  - 3. 사용자가 존재하지 않으면 404 에러를 반환한다.
  - 4. 사용자 정보(ID, 이름, 이메일, 성별, 생년월일)를 반환한다.
- **제약사항**
  - X-USER-ID 헤더 필수
- **도메인 객체 책임**
  - `User (Entity)`: 사용자 정보
  - `UserRepository (Interface in Domain)`: 사용자 조회

### 2. 포인트 (Point)

#### 2.1 포인트 충전

- **유저 스토리**
  - 로그인한 사용자는 포인트를 충전할 수 있다.
- **기능 흐름**
  - 1. X-USER-ID 헤더로 사용자를 식별한다.
  - 2. 충전 요청을 검증한다.
    - 충전 금액은 0보다 커야 함
  - 3. 포인트 정보를 조회한다.
  - 4. 포인트를 충전한다.
  - 5. 갱신된 포인트 정보를 반환한다.
- **제약사항**
  - 충전 금액은 양수여야 함
  - 포인트 정보가 없으면 404 에러
- **도메인 객체 책임**
  - `Point (Entity)`: 포인트 잔액 관리, `charge()` 메서드
  - `Money (VO)`: 금액 표현 및 연산
  - `PointRepository (Interface in Domain)`: 포인트 조회/저장
  - `PointService (Domain Service)`: 포인트 충전 로직

#### 2.2 포인트 조회

- **유저 스토리**
  - 로그인한 사용자는 자신의 보유 포인트를 조회할 수 있다.
- **기능 흐름**
  - 1. X-USER-ID 헤더로 사용자를 식별한다.
  - 2. 포인트 정보를 조회한다.
  - 3. 포인트가 존재하지 않으면 404 에러를 반환한다.
  - 4. 포인트 정보(잔액, 통화)를 반환한다.
- **제약사항**
  - X-USER-ID 헤더 필수
- **도메인 객체 책임**
  - `Point (Entity)`: 포인트 잔액 정보
  - `PointRepository (Interface in Domain)`: 포인트 조회
  - `PointService (Domain Service)`: 포인트 조회 로직 (`getPoint()`)

### 3. 브랜드 & 상품 조회

#### 3.1 브랜드 정보 조회

- **유저 스토리**
  - 사용자는 특정 브랜드의 정보를 조회할 수 있다.
- **기능 흐름**
  - 브랜드 ID로 브랜드 정보를 조회한다.
  - 브랜드가 존재하지 않으면 404 에러를 반환한다.
  - 브랜드 정보(ID, 이름 등)를 반환한다.
- **제약사항**
  - 존재하지 않는 브랜드 조회 시 적절한 에러 메시지 제공
- **도메인 객체 책임**
  - `Brand (Entity)`: 브랜드 기본 정보 보유
  - `BrandRepository (Interface in Domain)`: 브랜드 조회 인터페이스
  - `BrandRepositoryImpl (in Infrastructure)`: JPA 기반 구현체

#### 3.2 상품 목록 조회

- **유저 스토리**
  - 1. 사용자는 상품 목록을 페이징하여 조회할 수 있다.
  - 2. 사용자는 특정 브랜드의 상품만 필터링할 수 있다.
  - 3. 사용자는 최신순, 가격순, 좋아요순으로 정렬할 수 있다.
- **기능 흐름**
  - 1. 요청 파라미터를 검증한다.
    - brandId (선택): 특정 브랜드 필터링
    - sort (필수): latest / price_asc / likes_desc
    - page (기본값 0): 페이지 번호
    - size (기본값 20): 페이지당 상품 수
  - 2. 조건에 맞는 상품 목록을 조회한다.
  - 3. 각 상품의 총 좋아요 수를 포함하여 반환한다.
- **제약사항**
  - 정렬 기준은 latest, price_asc, likes_desc만 지원
  - 페이징 정보(전체 개수, 현재 페이지 등) 포함
- **도메인 객체 책임**
  - `Product (Entity)`: 상품 정보와 가격 정보 보유
  - `Price (VO)`: 금액과 통화 정보를 불변 객체로 표현
  - `ProductRepository (Interface in Domain)`: 정렬/필터링 조회 인터페이스
  - `ProductQueryService (Domain Service)`: 상품 목록 조회와 좋아요 수 집계를 조합

#### 3.3 상품 상세 조회

- **유저 스토리**
  - 사용자는 특정 상품의 상세 정보를 조회할 수 있다.
- **기능 흐름**
  - 1. 상품 ID로 상품 정보를 조회한다.
  - 2. 상품이 존재하지 않으면 404 에러를 반환한다.
  - 3. 상품 상세 정보(ID, 이름, 가격, 브랜드, 재고, 총 좋아요 수)를 반환한다.
- **제약사항**
  - 총 좋아요 수를 포함하여 반환
  - 현재 재고 수량 정보 포함
- **도메인 객체 책임**
  - `Product (Entity)`: 상품 기본 정보, Brand 참조, 재고 확인 메서드 제공
  - `Stock (Entity)`: 재고 수량 관리 (Product와 1:1)
  - `ProductQueryService (Domain Service)`: Product + Brand + Like 정보 조합

### 4. 좋아요 (Like)

#### 4.1 상품 좋아요 등록

- **유저 스토리**
  - 로그인한 사용자는 상품에 좋아요를 등록할 수 있다.
  - 이미 좋아요한 상품에 다시 좋아요를 시도하면 멱등하게 동작한다.
- **기능 흐름**
  - 1. X-USER-ID 헤더로 사용자를 식별한다.
  - 2. 상품 ID로 상품의 존재 여부를 확인한다.
  - 3. 해당 사용자의 좋아요 기록이 이미 존재하는지 확인한다.
  - 4. 존재하지 않으면 좋아요를 저장한다.
  - 5. 이미 존재하면 중복 등록하지 않는다 (멱등성).
  - 6. Application Layer에서 UniqueConstraint 위반 예외(`DataIntegrityViolationException`)를 catch하여 멱등성을 보장한다.
- **제약사항**
  - 사용자당 상품당 1개의 좋아요만 가능
  - 멱등성 보장: 동일 요청 반복 시 중복 생성 없음
  - 존재하지 않는 상품에 대한 좋아요는 불가능
  - 동시성 제어: UniqueConstraint(`uk_user_product`)를 활용한 중복 방지
  - **비관적 락 미사용 이유**: UniqueConstraint + 비관적 락 조합 시 Gap Lock으로 인한 데드락 위험
- **도메인 객체 책임**
  - `Like (Entity)`: User와 Product 간 관계를 표현, 좋아요 등록 시점 기록, UniqueConstraint(`uk_user_product`) 적용
  - `LikeService (Domain Service)`: 중복 확인 로직 (`existsByUserIdAndProductId()`), 멱등성 보장
  - `LikeRepository (Interface in Domain)`: 좋아요 저장/조회/삭제 인터페이스
  - `ProductRepository (Interface in Domain)`: 상품 존재 여부 확인
  - `LikeFacade (Application Service)`: UniqueConstraint 위반 예외 처리, 멱등성 보장 책임

#### 4.2 상품 좋아요 취소

- **유저 스토리**
  - 로그인한 사용자는 좋아요를 취소할 수 있다.
  - 좋아요하지 않은 상품에 대해 취소를 시도하면 멱등하게 동작한다.
- **기능 흐름**
  - 1. X-USER-ID 헤더로 사용자를 식별한다.
  - 2. 상품 ID로 상품의 존재 여부를 확인한다.
  - 3. 해당 사용자의 좋아요 기록을 조회한다.
  - 4. 존재하면 좋아요를 삭제한다.
  - 5. 존재하지 않으면 아무 동작도 하지 않는다 (멱등성).
- **제약사항**
  - 멱등성 보장: 동일 요청 반복 시 에러 없음
  - 존재하지 않는 상품에 대한 취소 요청 시 적절한 처리
- **도메인 객체 책임**
  - `LikeService (Domain Service)`: 존재 확인 로직, 멱등성 보장
  - `LikeRepository (Interface in Domain)`: 좋아요 삭제 인터페이스

#### 4.3 좋아요한 상품 목록 조회

- **유저 스토리**
  - 로그인한 사용자는 자신이 좋아요한 상품 목록을 조회할 수 있다.
- **기능 흐름**
  - 1. X-USER-ID 헤더로 사용자를 식별한다.
  - 2. 해당 사용자가 좋아요한 상품 목록을 조회한다.
  - 3. 상품 정보와 함께 페이징 처리하여 반환한다.
- **제약사항**
  - 페이징 처리 지원
  - 좋아요한 순서(최신순) 정렬
- **도메인 객체 책임**
  - `LikeRepository (Interface in Domain)`: 사용자별 좋아요 목록 조회

### 5. 쿠폰 (Coupon)

#### 5.1 쿠폰 도메인

- **유저 스토리**
  - 사용자는 주문 시 쿠폰을 사용하여 할인을 받을 수 있다.
  - 쿠폰은 정액 할인(FIXED_AMOUNT) 또는 정률 할인(PERCENTAGE) 방식을 지원한다.
  - 각 쿠폰은 1회만 사용 가능하다.
- **기능 흐름**
  - 1. 사용자는 쿠폰을 소유한다 (UserCoupon).
  - 2. 주문 생성 시 쿠폰 ID를 선택적으로 제공한다.
  - 3. 쿠폰 서비스가 쿠폰 사용 가능 여부를 확인한다 (비관적 락 사용).
    - 사용자가 소유한 쿠폰인지 확인
    - 아직 사용되지 않았는지 확인
  - 4. 쿠폰을 사용 상태로 변경하고 할인 금액을 계산한다.
  - 5. 할인이 적용된 최종 금액으로 포인트를 차감한다.
- **제약사항**
  - 쿠폰은 1회만 사용 가능 (isUsed 플래그)
  - 이미 사용된 쿠폰 재사용 시 400 에러
  - 다른 사용자의 쿠폰 사용 시 404 에러
  - 정률 할인은 최대 100%까지 가능
  - 할인 금액은 주문 금액을 초과할 수 없음
  - 동시성 제어: 비관적 락(SELECT FOR UPDATE) 사용
- **도메인 객체 책임**
  - `Coupon (Entity)`: 쿠폰 기본 정보 (이름, 할인 타입, 할인 값), `calculateDiscount()` 메서드
  - `UserCoupon (Entity)`: 사용자 쿠폰 소유 관계, 사용 상태 관리 (`canUse()`, `use()` 메서드)
  - `CouponType (Enum)`: 할인 타입 (FIXED_AMOUNT, PERCENTAGE)
  - `CouponService (Domain Service)`: 쿠폰 사용 로직 (`useUserCoupon()`), 비관적 락을 통한 동시성 제어
  - `CouponRepository (Interface in Domain)`: 쿠폰 조회 인터페이스
  - `UserCouponRepository (Interface in Domain)`: 사용자 쿠폰 조회/저장 인터페이스 (`findByIdAndUserIdWithLock()` 포함)

### 6. 주문 / 결제 (Orders)

#### 6.1 주문 생성

- **유저 스토리**
  - 로그인한 사용자는 여러 상품을 한 번에 주문할 수 있다.
  - 주문 시 쿠폰을 사용하여 할인을 받을 수 있다 (선택적).
  - 주문 시 재고가 부족하면 주문이 실패한다.
  - 주문 시 보유 포인트가 부족하면 주문이 실패한다.
  - 주문 정보는 외부 시스템으로 전송된다.
- **기능 흐름**
  - 1. X-USER-ID 헤더로 사용자를 식별한다.
  - 2. 주문 요청을 검증한다.
    - 주문 항목이 1개 이상인지 확인
    - 각 상품의 수량이 1개 이상인지 확인
  - 3. 각 상품의 재고를 확인한다.
    - 재고가 부족하면 주문 실패 (400 에러)
  - 4. 총 주문 금액을 계산한다.
  - 5. 쿠폰을 적용한다 (couponId가 제공된 경우).
    - 쿠폰 사용 가능 여부 확인 및 사용 처리 (비관적 락)
    - 할인 금액 계산
    - 최종 결제 금액 = 주문 금액 - 할인 금액
  - 6. 사용자의 보유 포인트를 확인한다.
    - 포인트가 부족하면 주문 실패 (400 에러)
  - 7. 트랜잭션으로 다음을 처리한다:
    - 주문 정보 저장
    - 각 상품의 재고 차감
    - 사용자 포인트 차감 (할인 적용 후 금액)
  - 8. 외부 시스템으로 주문 정보를 전송한다 (비동기 또는 Mock 가능).
  - 9. 주문 결과를 반환한다.
- **제약사항**
  - 재고 차감, 포인트 차감, 주문 저장은 트랜잭션으로 처리
  - 재고 부족 시 명확한 에러 메시지
  - 포인트 부족 시 명확한 에러 메시지
  - 외부 시스템 연동 실패는 별도 처리 (재시도, 보상 트랜잭션 등)
  - 동시성 제어 고려 필요 (재고 차감, 포인트 차감)
- **예외 상황**
  - 존재하지 않는 상품 주문 시도
  - 재고 부족
  - 포인트 부족
  - 외부 시스템 연동 실패
- **도메인 객체 책임**
  - `Order (Entity, Aggregate Root)`: 주문 정보, OrderItem 집합 관리, 총액 계산 메서드 제공
  - `OrderItem (Entity)`: 주문 항목, 상품 스냅샷(상품명, 브랜드 정보, 가격) 보관
  - `Money (VO)`: 금액 연산 (총액 계산, 포인트 차감, 할인 금액 계산 등)
  - `Stock (Entity)`: 재고 차감 메서드 (`decrease()`), 재고 확인 메서드 (`isAvailable()`)
  - `Point (Entity)`: 포인트 차감 메서드 (`deduct()`), 잔액 확인 메서드 (`canDeduct()`)
  - `Coupon (Entity)`: 할인 금액 계산 메서드 (`calculateDiscount()`)
  - `UserCoupon (Entity)`: 쿠폰 사용 상태 관리 (`canUse()`, `use()`)
  - `OrderService (Domain Service)`: Order, Stock, Point 협력 조율, 트랜잭션 경계
  - `CouponService (Domain Service)`: 쿠폰 사용 로직 (`useUserCoupon()`), 비관적 락을 통한 동시성 제어
  - `OrderFacade (Application Service)`: 주문 생성 흐름 조율, 쿠폰 적용 및 할인 계산 통합
  - `ProductRepository (Interface in Domain)`: 상품 조회
  - `StockRepository (Interface in Domain)`: 재고 조회/수정 (비관적 락)
  - `PointRepository (Interface in Domain)`: 포인트 조회/수정 (비관적 락)
  - `UserCouponRepository (Interface in Domain)`: 사용자 쿠폰 조회/수정 (비관적 락)
  - `OrderRepository (Interface in Domain)`: 주문 저장

#### 6.2 주문 목록 조회

- **유저 스토리**
  - 로그인한 사용자는 자신의 주문 목록을 조회할 수 있다.
- **기능 흐름**
  - 1. X-USER-ID 헤더로 사용자를 식별한다.
  - 2. 해당 사용자의 주문 목록을 조회한다.
  - 3. 주문 기본 정보(주문 ID, 총 금액, 주문 일시 등)를 페이징하여 반환한다.
- **제약사항**
  - 페이징 처리 지원
  - 최신 주문순 정렬
- **도메인 객체 책임**
  - `OrderRepository (Interface in Domain)`: 사용자별 주문 목록 조회

#### 6.3 주문 상세 조회

- **유저 스토리**
  - 로그인한 사용자는 자신의 특정 주문의 상세 정보를 조회할 수 있다.
- **기능 흐름**
  - 1. X-USER-ID 헤더로 사용자를 식별한다.
  - 2. 주문 ID로 주문 정보를 조회한다.
  - 3. 해당 주문이 현재 사용자의 주문인지 확인한다.
    - 다른 사용자의 주문이면 403 에러 반환
  - 4. 주문 상세 정보(주문 항목, 각 항목별 상품 정보, 수량, 가격 등)를 반환한다.
- **제약사항**
  - 본인의 주문만 조회 가능
  - 주문 항목 정보 포함
- **도메인 객체 책임**
  - `Order (Entity, Aggregate Root)`: 주문 소유자 확인 메서드 (`isOwnedBy(userId)`)
  - `OrderRepository (Interface in Domain)`: 주문 ID로 조회 (OrderItem 포함)

## 비기능 요구사항

### 인증/인가

- 모든 API는 X-USER-ID 헤더로 사용자를 식별
- 별도의 JWT, Session 인증은 구현하지 않음

### 성능

- 상품 목록 조회 시 페이징 처리 필수
- 좋아요 수 집계는 효율적으로 처리

### 데이터 정합성

- 주문 생성 시 재고 차감, 포인트 차감은 트랜잭션으로 처리
- 동시성 제어 필요 (재고, 포인트, 좋아요)
- 비관적 락(Pessimistic Lock) 사용: Stock, Point, Like 조회 시 SELECT FOR UPDATE

### 멱등성

- 좋아요 등록/취소는 멱등하게 동작
- 동일한 요청을 여러 번 보내도 결과가 동일
- 소프트 삭제(Soft Delete) 멱등성: `delete()`, `restore()` 메서드는 중복 호출 시에도 안전

### 확장성

- 외부 시스템 연동은 확장 가능하도록 설계
- 추후 쿠폰, 이벤트 등 기능 추가 고려

### 소프트 삭제 (Soft Delete)

- 모든 엔티티는 `BaseEntity`를 상속받아 `deletedAt` 컬럼을 가짐
- 물리적 삭제 대신 `deletedAt` 타임스탬프를 설정하여 논리적 삭제
- `delete()` 메서드: 멱등하게 동작 (이미 삭제된 경우 무시)
- `restore()` 메서드: 삭제된 엔티티 복구 (멱등하게 동작)
- 조회 시 `deletedAt IS NULL` 조건으로 삭제되지 않은 데이터만 조회
- 삭제된 상품은 좋아요 목록 조회 시 필터링됨 (`findValidLikesByUserId()`)
