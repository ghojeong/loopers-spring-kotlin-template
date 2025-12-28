# E-Commerce Platform (Spring + Kotlin)

이 프로젝트는 10주간의 학습을 하며 구축한 실전 이커머스 플랫폼입니다.  
TDD, 동시성 제어, 성능 최적화, 이벤트 기반 아키텍처, 분산 시스템 설계까지  
백엔드 개발의 개념들을 단계적으로 적용했습니다.

## 프로젝트 개요

### 비즈니스 도메인

- 상품 조회 및 브랜드별 필터링
- 좋아요 기능 (찜하기)
- 포인트 관리 및 충전
- 주문/결제 (쿠폰 할인 지원)
- 인기 상품 랭킹 시스템

### 핵심 기술 스택

- **언어**: Kotlin 2.2.10
- **프레임워크**: Spring Boot 4.0.1, Spring Data JPA
- **클라우드**: Spring Cloud 2025.1.0
- **데이터베이스**: MySQL 8.0, Flyway (마이그레이션)
- **캐싱**: Redis 7.0
- **메시징**: Kafka 3.x
- **배치**: Spring Batch
- **직렬화**: Jackson 3.0.3
- **API 문서**: SpringDoc OpenAPI 3.0.0
- **결제 연동**: 외부 PG사 연동 (Resilience4j)
- **모니터링**: Prometheus + Grafana
- **테스트**: JUnit 5, MockK 5.0.1, Mockito 5.21.0, Instancio 5.5.1

---

## 주요 구현 기능

### 1. 상품 관리 (Product)

- **상품 조회**: 브랜드별 필터링, 가격/좋아요 순 정렬
- **재고 관리**: 비관적 락을 통한 동시성 제어
- **좋아요 기능**: UniqueConstraint + 멱등성 보장

**기술적 특징:**

- 복합 인덱스 (`brand_id, like_count`, `brand_id, price`)
- Redis 캐싱으로 조회 성능 향상
- 비정규화 (likeCount 컬럼)를 통한 JOIN 제거

### 2. 주문 시스템 (Order)

- **주문 생성**: 재고 차감, 포인트 결제, 쿠폰 할인 적용
- **주문 취소**: 재고 복구, 포인트 환불
- **동시성 제어**: 비관적 락 + 재시도 전략

**트랜잭션 관리:**

```txt
// 핵심 주문 생성 로직 (트랜잭션 내)
1. 쿠폰 사용 (FOR UPDATE 락)
2. 할인 금액 계산
3. 포인트 차감 (FOR UPDATE 락)
4. 재고 차감 (FOR UPDATE 락)
5. 주문 생성
```

### 3. 포인트 시스템 (Point)

- **충전/차감**: 동시성 안전 보장 (비관적 락)
- **잔액 조회**: 실시간 잔액 확인

**동시성 제어:**

- `PESSIMISTIC_WRITE` 락으로 Lost Update 방지
- 재시도 전략 (최대 3회, 지수 백오프)
- 포인트 음수 방지 보장

### 4. 쿠폰 시스템 (Coupon)

- **정액/정률 할인**: 다양한 할인 정책 지원
- **1회 사용 제한**: 중복 사용 방지
- **동시성 제어**: 비관적 락으로 쿠폰 중복 사용 차단

### 5. 결제 연동 (Payment)

- **외부 PG 연동**: Timeout, Retry, Circuit Breaker 패턴 적용
- **장애 대응**: Fallback 메커니즘으로 안정성 확보
- **결제 상태 관리**: 스케줄러로 주기적 상태 동기화

### 6. 랭킹 시스템 (Ranking)

- **실시간 랭킹**: Redis ZSET으로 O(log N) 성능
- **시간 기반 분할**: 시간/일간 윈도우 (Time Quantization)
- **콜드 스타트 방지**: 이전 윈도우 점수 10% 승계
- **공정한 스코어링**: 로그 정규화로 극단값 완화

### 7. 주간/월간 랭킹 집계 (Batch)

- **Spring Batch**: Chunk-Oriented Processing으로 대용량 데이터 처리
- **Materialized View**: 집계된 랭킹 데이터를 별도 테이블에 저장
- **Delete-then-Insert**: 멱등성 보장

**배치 처리 흐름:**

```txt
ItemReader (일간 랭킹 조회)
    ↓
ItemProcessor (주간/월간 집계)
    ↓
ItemWriter (Materialized View 저장)
```

### 8. 이벤트 기반 아키텍처

- **Spring ApplicationEvent**: 트랜잭션 분리 및 도메인 결합도 감소
- **Kafka**: 분산 환경에서의 이벤트 처리
- **Transactional Outbox**: 이벤트 유실 방지
- **Idempotent Consumer**: 중복 처리 방지

---

## 아키텍처 설계

### 계층 구조 (Layered Architecture)

```
┌─────────────────────────────────────┐
│   Presentation Layer (Controller)   │
├─────────────────────────────────────┤
│   Application Layer (Facade/Service)│
├─────────────────────────────────────┤
│   Domain Layer (Entity, VO, Logic)  │
├─────────────────────────────────────┤
│   Infrastructure Layer (Repository) │
└─────────────────────────────────────┘
```

### Bounded Context 분리

- **User BC**: 사용자 관리, 포인트
- **Brand BC**: 브랜드 정보
- **Product BC**: 상품, 재고
- **Like BC**: 좋아요
- **Order BC**: 주문, 주문 항목
- **Coupon BC**: 쿠폰, 사용자 쿠폰
- **Payment BC**: 결제 정보
- **Ranking BC**: 랭킹 시스템

**Facade 패턴으로 BC 간 협력:**

```kotlin
// Product BC가 Like BC 정보 조회
class ProductService(
    private val likeFacade: LikeFacade  // 인터페이스를 통한 의존
) {
    fun getProductWithLikeCount(productId: Long): ProductData {
        val product = productRepository.findById(productId)
        val likeCount = likeFacade.countByProductId(productId)
        return ProductData(product, likeCount)
    }
}
```

### 도메인 모델링

- **Value Object**: Price, Money (불변, 자체 검증)
- **Entity**: Product, Order, Stock (ID 식별, 상태 관리)
- **Aggregate Root**: Order와 OrderItem의 일관성 보장

**Value Object 예시:**

```kotlin
@Embeddable
data class Price(
    val amount: BigDecimal,
    val currency: Currency = Currency.KRW
) {
    init {
        require(amount >= BigDecimal.ZERO) { "금액은 0 이상이어야 합니다." }
    }

    operator fun plus(other: Price): Price {
        require(currency == other.currency) { "통화가 다릅니다." }
        return Price(amount + other.amount, currency)
    }
}
```

### 동시성 제어 전략

| 도메인 | 전략 | 이유 |
|:--------|:------:|------:|
| 재고 (Stock) | 비관적 락 | 음수 재고 절대 방지 |
| 포인트 (Point) | 비관적 락 | 음수 잔액 절대 방지 |
| 쿠폰 (UserCoupon) | 비관적 락 | 중복 사용 절대 방지 |
| 좋아요 (Like) | UniqueConstraint | 중복만 막으면 됨, Gap Lock 회피 |
| 좋아요 카운트 | Redis Atomic | 동시성 안전 + 성능 |

---

## 프로젝트 구조

### Multi-Module Architecture

```txt
Root
├── apps (Spring Boot Applications)
│   ├── 📦 commerce-api (메인 API 서버)
│   ├── 📦 commerce-streamer (Kafka Consumer)
│   └── 📦 pg-simulator (PG 결제 시뮬레이터)
├── modules (재사용 가능한 설정)
│   ├── 📦 jpa (JPA 설정, Auditing)
│   ├── 📦 redis (Redis 설정, Cache)
│   └── 📦 kafka (Kafka 설정)
├── libs (도메인 라이브러리)
│   └── 📦 domain-core (공통 도메인 로직)
└── supports (부가 기능)
    ├── 📦 jackson (JSON 직렬화)
    ├── 📦 logging (Structured Logging)
    └── 📦 monitoring (Prometheus, Grafana)
```

### 주요 패키지 구조

```txt
com.loopers.commerce
├── api
│   ├── controller         # REST API 엔드포인트
│   └── dto                # Request/Response DTO
├── application
│   ├── facade             # 여러 도메인 조합
│   └── service            # 비즈니스 로직
├── domain
│   ├── entity             # JPA 엔티티
│   ├── vo                 # Value Object
│   └── repository         # 인터페이스
├── infrastructure
│   ├── jpa                # JPA 구현체
│   ├── redis              # Redis 구현체
│   └── kafka              # Kafka Producer
└── event                  # 도메인 이벤트
```

---

## Getting Started

### 1. 사전 요구사항

- Java 21
- Docker & Docker Compose
- Gradle 9.2.1

### 2. 프로젝트 초기 설정

```bash
# pre-commit 훅 설치 (ktlint 자동 점검)
make init
```

### 3. 인프라 실행

```bash
# MySQL, Redis, Kafka 실행
docker-compose -f ./docker/infra-compose.yml up -d

# 실행 확인
docker-compose -f ./docker/infra-compose.yml ps
```

**포트 정보:**

- MySQL: `localhost:3306`
- Redis: `localhost:6379`
- Kafka: `localhost:19092` (외부 접속용)
- Zookeeper: `localhost:2181`

### 4. 애플리케이션 실행

```bash
# API 서버 실행
./gradlew :apps:commerce-api:bootRun

# Kafka Consumer 실행 (별도 터미널)
KAFKA_BOOTSTRAP_SERVERS=localhost:19092 ./gradlew :apps:commerce-streamer:bootRun
```

애플리케이션 실행 확인:

- API 서버: http://localhost:8080
- Actuator Health: http://localhost:8080/actuator/health

### 5. 모니터링 (선택사항)

```bash
# Prometheus & Grafana 실행
docker-compose -f ./docker/monitoring-compose.yml up -d

# Grafana 접속
# http://localhost:3000 (admin/admin)
```

---

## 테스트 실행

### 전체 테스트 실행

```bash
# 모든 모듈의 테스트 실행
./gradlew test

# 특정 모듈만 실행
./gradlew :apps:commerce-api:test
```

### 테스트 실행법

#### 빌드 시 테스트 실행

```bash
# docker compose -f ./docker/infra-compose.yml up 실행 필수
./gradlew clean build
```

#### 단위 및 통합 테스트

```bash
# API 테스트 (infra-compose.yml 실행 필수)
./gradlew :apps:commerce-api:test
```

#### Kafka 연동 테스트

```bash
# Kafka Consumer 통합 테스트 (infra-compose.yml 실행 필수)
KAFKA_BOOTSTRAP_SERVERS=localhost:19092 ./gradlew :apps:commerce-streamer:test
```

---

## 주요 API 엔드포인트

### 상품 (Product)

```http
# 상품 목록 조회 (페이징, 정렬, 필터)
GET /api/v1/products?brandId=1&sort=likes_desc&page=0&size=20

# 상품 상세 조회
GET /api/v1/products/{productId}

# 좋아요 추가
POST /api/v1/likes
{
  "userId": 1,
  "productId": 123
}

# 좋아요 취소
DELETE /api/v1/likes/{userId}/{productId}
```

### 주문 (Order)

```http
# 주문 생성
POST /api/v1/orders
{
  "userId": 1,
  "items": [
    {"productId": 123, "quantity": 2}
  ],
  "couponId": 5  // 선택적
}

# 주문 조회
GET /api/v1/orders/{orderId}

# 주문 목록 조회
GET /api/v1/orders?userId=1&page=0&size=20

# 주문 취소
POST /api/v1/orders/{orderId}/cancel
```

### 포인트 (Point)

```http
# 포인트 충전
POST /api/v1/points/charge
{
  "userId": 1,
  "amount": 10000
}

# 포인트 조회
GET /api/v1/points/{userId}
```

### 랭킹 (Ranking)

```http
# 인기 상품 랭킹 (실시간)
GET /api/v1/rankings/products?window=DAILY&limit=100

# 주간 랭킹 (집계된 데이터)
GET /api/v1/rankings/weekly?weekStartDate=2024-01-01&limit=50

# 월간 랭킹 (집계된 데이터)
GET /api/v1/rankings/monthly?yearMonth=2024-01&limit=50
```

---

## 10주간의 개발 히스토리

### Week 1: 테스트 가능한 구조

**주제:** TDD

**학습 내용:**

- **단위 테스트의 가치**: E2E 테스트보다 단위 테스트가 개발 사이클을 빠르게 만듦
- **도메인으로 검증 분리**: Controller의 `@Valid` 대신 User 도메인에 검증 로직 이동
- **Mock vs Fake vs Spy**: 각각의 차이와 사용 시점 이해
- **TDD의 본질**: "테스트 먼저"가 아닌 "테스트 가능한 구조"

---

### Week 2: 이커머스 설계

**주제:** "좋아요를 두 번 누르면?", "재고가 음수가 되면?"

**학습 내용:**

- **멱등성 설계**: 중복 요청 시 에러가 아닌 성공 처리
- **동시성 문제 인식**: Lost Update, TOCTOU 갭
- **비관적 락 vs 낙관적 락**: 각각의 트레이드오프
- **트랜잭션 범위 결정**: 외부 시스템은 트랜잭션 밖으로
- **Bounded Context**: Facade 패턴으로 도메인 경계 명확화

---

### Week 3: 도메인 객체

**주제:** "응집성, 데이터를 가진 쪽이 로직도 가져야 한다"

**학습 내용:**

- **Value Object**: Price, Money로 비즈니스 규칙 캡슐화
- **Entity 책임**: Stock이 스스로 재고 감소 검증
- **상태 전이 제어**: Order가 스스로 취소 가능 여부 판단
- **Tell, Don't Ask**: `stock.decrease(5)` vs `stock.quantity -= 5`

---

### Week 4: 트랜잭션, 락, 동시성 문제

**주제:** 트랜잭션과 락은 정답이 없다. '이 도메인에서 무엇이 가장 중요한가?'를 먼저 고민해야 한다.

**학습 내용:**

- **@Transactional의 한계**: 동시성 제어는 별개
- **Gap Lock 데드락**: UniqueConstraint + 비관적 락의 위험
- **REQUIRES_NEW 함정**: 외부 트랜잭션과 원자성 깨짐
- **재시도 전략**: PessimisticLockException 발생 시 재시도

---

### Week 5: 조회 성능 개선

**주제:** 비정규화와 캐싱을 통한 조회 성능 향상

**학습 내용:**

- **정규화의 함정**: 10만건 데이터에서 1초씩 걸리는 쿼리
- **비정규화 결정**: likeCount 비정규화 컬럼 추가로 JOIN 제거
- **복합 인덱스 설계**: `(brand_id, like_count)` 순서의 중요성
- **Redis 캐싱**: 조회 성능 향상
- **Atomic 연산**: Redis INCR/DECR로 동시성 안전

---

### Week 6: 외부 서비스가 연동

**주제:** 외부 시스템은 언제든 죽을 수 있다. 우리 시스템은 그래도 살아있어야 한다.

**학습 내용:**

- **Resilience4j**: Timeout, Retry, Circuit Breaker 패턴
- **Fallback 메커니즘**: 외부 장애 시 안전한 대체 동작
- **결제 상태 동기화**: 스케줄러로 주기적 상태 확인

---

### Week 7: 이벤트 기반 아키텍처로 트랜잭션 분리

**주제:** 트랜잭션을 분리gksl 결합도가 낮아지고, 각 도메인이 독립적으로 확장 가능해진다.

**학습 내용:**

- **ApplicationEvent**: Spring 내장 이벤트 시스템
- **@TransactionalEventListener**: AFTER_COMMIT으로 트랜잭션 분리
- **@Async**: 비동기 처리로 응답 속도 개선

---

### Week 8: 카프카

**주제:** 분산 환경에서는 'At-Least-Once' 를 보장하고, 멱등성으로 'Exactly-Once' 효과를 낸다.

**학습 내용:**

- **Kafka**: 분산 메시징 시스템
- **Transactional Outbox Pattern**: 이벤트 유실 방지
- **Idempotent Consumer Pattern**: 중복 처리 방지
- **Manual Acknowledgment**: 처리 성공 후에만 Commit

---

### Week 9: 상품 인기 시스템

**주제:** 좋은 인기 랭킹은 단순히 '많이 팔린 순'이 아니라, 시간과 맥락을 고려한 설계에서 나온다.

**학습 내용:**

- **Time Quantization**: 시간 단위로 윈도우 분할
- **Score Carry-Over**: 이전 윈도우 점수 10% 승계 (콜드 스타트 방지)
- **Log Normalization**: 극단값 완화로 공정한 점수

---

### Week 10: 주간/월간 인기 랭킹 배치

**주제:** 실시간 집계는 비용이 크다. 배치로 미리 계산하면 조회는 단순 SELECT가 된다.

**학습 내용:**

- **Spring Batch**: Chunk-Oriented Processing
- **Materialized View**: 집계 결과를 별도 테이블에 저장
- **Delete-then-Insert**: 멱등성 보장
- **Flyway**: 스키마 마이그레이션

---

## 핵심 학습 요약

### 설계 원칙

1. **단일 책임**: 각 도메인 객체는 하나의 책임만
2. **Tell, Don't Ask**: 묻지 말고 시켜라
3. **명시적 제어**: 추상화보다 명확함을 우선
4. **트레이드오프 인식**: 은탄환은 없다

### 동시성 제어

- **재고/포인트/쿠폰**: 비관적 락 (정합성 최우선)
- **좋아요**: UniqueConstraint (Gap Lock 회피)
- **카운트**: Redis Atomic 연산 (성능 + 안전성)

### 성능 최적화

- **비정규화**: 읽기 >> 쓰기일 때
- **복합 인덱스**: WHERE → ORDER BY 순서
- **캐싱**: TTL 전략 중요 (5-10분)
- **배치**: 대용량 집계는 미리 계산

### 분산 시스템

- **이벤트 기반**: 트랜잭션 분리, 결합도 감소
- **Outbox 패턴**: 이벤트 유실 방지
- **멱등성**: At-Least-Once → Exactly-Once 효과
- **Circuit Breaker**: 외부 장애 격리

---

## 관련 문서

- [프로젝트 설계 문서](.docs/_architecture/)
  - [시나리오 및 요구사항](.docs/_architecture/1-requirements.md)
  - [시퀀스 다이어그램](.docs/_architecture/2-sequence-diagrams.md)
  - [클래스 다이어그램](.docs/_architecture/3-class-diagram.md)
  - [ERD](.docs/_architecture/4-erd.md)

## PR 링크

- [Round 1: 테스트 가능한 구조](https://github.com/ghojeong/loopers-round-1/pull/1)
- [Round 2: 이커머스 설계](https://github.com/Loopers-dev-lab/loopers-spring-kotlin-template/pull/19)
- [Round 3: 도메인 모델링](https://github.com/Loopers-dev-lab/loopers-spring-kotlin-template/pull/25)
- [Round 4: 트랜잭션과 락](https://github.com/Loopers-dev-lab/loopers-spring-kotlin-template/pull/31)
- [Round 5: 성능 최적화](https://github.com/Loopers-dev-lab/loopers-spring-kotlin-template/pull/41)
- [Round 6: 외부 시스템 연동](https://github.com/Loopers-dev-lab/loopers-spring-kotlin-template/pull/48)
- [Round 7: 이벤트 기반 아키텍처](https://github.com/Loopers-dev-lab/loopers-spring-kotlin-template/pull/54)
- [Round 8: 분산 메시징](https://github.com/Loopers-dev-lab/loopers-spring-kotlin-template/pull/64)
- [Round 9: 랭킹 시스템](https://github.com/Loopers-dev-lab/loopers-spring-kotlin-template/pull/70)
- [Round 10: 배치 처리](https://github.com/Loopers-dev-lab/loopers-spring-kotlin-template/pull/76)
