# Round 7 구현 내용

## 개요

이번 라운드에서는 **ApplicationEvent**를 활용하여 트랜잭션을 분리하고, 이벤트 기반 아키텍처를 구현했습니다.

## 구현 항목

### 1. 이벤트 정의

#### 주문-결제 관련 이벤트

- `OrderCreatedEvent`: 주문 생성 시 발행
- `PaymentCompletedEvent`: 결제 완료 시 발행
- `PaymentFailedEvent`: 결제 실패 시 발행

#### 좋아요 관련 이벤트

- `LikeAddedEvent`: 좋아요 추가 시 발행
- `LikeRemovedEvent`: 좋아요 제거 시 발행

#### 유저 행동 로깅 이벤트

- `UserActionEvent`: 사용자의 주요 행동 추적
  - 상품 조회, 좋아요, 주문, 결제, 쿠폰 사용 등

### 2. 이벤트 핸들러

#### OrderEventHandler

- **쿠폰 사용 처리** (`@TransactionalEventListener(AFTER_COMMIT) + @Async`)
  - 주문 생성 트랜잭션과 분리하여 쿠폰 사용 처리
  - 쿠폰 사용 실패 시에도 주문은 정상적으로 생성됨

- **데이터 플랫폼 전송** (`@TransactionalEventListener(AFTER_COMMIT) + @Async`)
  - 주문 정보를 외부 데이터 플랫폼에 비동기 전송
  - 전송 실패 시에도 주문은 유지됨

- **주문 상태 업데이트** (`@EventListener + @Transactional(REQUIRES_NEW)`)
  - 결제 완료 후 주문 상태를 CONFIRMED로 변경
  - 별도 트랜잭션에서 처리하여 결제 트랜잭션과 분리

#### LikeEventHandler

- **좋아요 집계 처리** (`@TransactionalEventListener(AFTER_COMMIT) + @Async`)
  - 좋아요 추가/제거 시 Redis 카운트 업데이트
  - 캐시 무효화 처리
  - 집계 실패 시에도 좋아요는 정상적으로 추가/제거됨

#### UserActionEventHandler

- **유저 행동 로깅** (`@EventListener + @Async`)
  - 모든 사용자 행동을 비동기로 로깅
  - 분석 시스템에 전송 (Mock 구현)

### 3. 서비스 레이어 수정

#### OrderFacade

**Before:**
```kotlin
val discountAmount = applyCoupon(userId, request.couponId, totalMoney)
// applyCoupon 내부에서 useUserCoupon 호출 (동기)
```

**After:**
```kotlin
val discountAmount = calculateCouponDiscount(userId, request.couponId, totalMoney)
// 할인 금액만 계산, 실제 사용은 이벤트로 처리

eventPublisher.publishEvent(OrderCreatedEvent.from(order, request.couponId))
```

#### PaymentService

**Before:**
```kotlin
when (status) {
    SUCCESS -> payment.complete(reason)
    FAILED -> payment.fail(reason)
}
```

**After:**
```kotlin
when (status) {
    SUCCESS -> {
        payment.complete(reason)
        eventPublisher.publishEvent(PaymentCompletedEvent.from(payment))
    }
    FAILED -> {
        payment.fail(reason)
        eventPublisher.publishEvent(PaymentFailedEvent.from(payment))
    }
}
```

#### LikeService

**Before:**
```kotlin
likeRepository.save(like)
productLikeCountService.increment(productId) // 동기
evictProductCache(productId) // 동기
```

**After:**
```kotlin
likeRepository.save(like)
eventPublisher.publishEvent(LikeAddedEvent(userId, productId))
// 집계 및 캐시 무효화는 이벤트 핸들러에서 비동기 처리
```

## 아키텍처 개선

### 트랜잭션 분리

#### Before (동기 처리)

```
[주문 생성 트랜잭션]
  ├── 주문 저장
  ├── 쿠폰 사용 ← 동기 (실패 시 전체 롤백)
  ├── 포인트 차감
  └── 결제 요청
```

#### After (이벤트 기반)

```
[주문 생성 트랜잭션]
  ├── 주문 저장
  ├── 포인트 차감
  ├── 결제 요청
  └── 이벤트 발행
      ↓
[쿠폰 사용 트랜잭션 (AFTER_COMMIT, Async)]
  └── 쿠폰 사용 (실패해도 주문 유지)
      ↓
[데이터 플랫폼 전송 (AFTER_COMMIT, Async)]
  └── 주문 정보 전송 (실패해도 주문 유지)
```

### Eventual Consistency (최종적 일관성)

좋아요 집계는 즉시 반영되지 않을 수 있지만, 최종적으로는 일관성이 보장됩니다.

```
[시간 t0] 사용자가 좋아요 클릭
  └─> Like 저장 (트랜잭션 커밋)

[시간 t1] 트랜잭션 커밋 완료
  └─> LikeAddedEvent 발행

[시간 t2] 이벤트 핸들러 실행 (비동기)
  ├─> Redis 카운트 증가
  └─> 캐시 무효화

[시간 t3] 사용자가 좋아요 수 조회
  └─> 최신 카운트 반환 (Redis)
```

## 장점

### 1. 트랜잭션 최소화
- 주요 비즈니스 로직만 트랜잭션에 포함
- DB 락 유지 시간 감소로 성능 향상

### 2. 결합도 감소
- OrderService는 쿠폰, 포인트, 결제 세부 사항을 몰라도 됨
- 이벤트만 발행하면 나머지는 핸들러가 처리

### 3. 장애 격리
- 쿠폰 사용 실패 → 주문은 정상 생성
- 데이터 플랫폼 장애 → 주문은 정상 생성
- 좋아요 집계 실패 → 좋아요는 정상 추가

### 4. 확장 가능성
- 새로운 기능 추가 시 이벤트 리스너만 추가
- 기존 코드 수정 없이 확장 가능

## 주의사항

### 1. 예외 은닉
- 이벤트 핸들러 내 실패는 사용자에게 즉시 노출되지 않음
- 로그 적재 및 모니터링 필요

### 2. 순서 보장 어려움
- 이벤트 핸들러는 병렬 실행될 수 있음
- 순서 의존이 없는 흐름만 분리

### 3. 중복 실행 가능성
- 트랜잭션 재시도 시 이벤트 중복 발행 가능
- Idempotency 처리 필요 (쿠폰 사용은 이미 멱등성 보장)

### 4. 데이터 정합성
- 최종적으로는 일관성이 보장되지만, 즉시는 아님
- 비즈니스 요구사항에 따라 적절히 선택

## 테스트

모든 기존 테스트가 통과하며, 이벤트 발행을 위한 `ApplicationEventPublisher` mock 추가:

```kotlin
private val eventPublisher: ApplicationEventPublisher = mockk(relaxed = true)
```

## 설정

### Application 설정

```kotlin
@EnableAsync  // 추가
@EnableRetry
@EnableScheduling
@EnableFeignClients
@SpringBootApplication
class CommerceApiApplication
```

비동기 이벤트 처리를 위해 `@EnableAsync` 어노테이션 추가
