# Round 8: Kafka 기반 이벤트 파이프라인 구현

## 구현 개요

Round 7에서 구현한 ApplicationEvent 기반 이벤트 처리를 Kafka 기반으로 확장하여,
**서비스 경계를 넘어 이벤트를 안전하게 전달**하는 구조를 구현했습니다.

## 핵심 패턴

### 1. Transactional Outbox Pattern

**Producer 측 At Least Once 보장**

```
도메인 로직 트랜잭션
├── 도메인 데이터 변경 (e.g. Like 저장)
└── Outbox 테이블에 이벤트 저장

별도 스케줄러 (Outbox Relay)
├── Outbox 테이블에서 PENDING 이벤트 조회
├── Kafka로 발행
└── 상태를 PUBLISHED로 변경
```

**구현 파일:**
- `OutboxEvent.kt`: Outbox 이벤트 엔티티
- `OutboxEventPublisher.kt`: 이벤트를 Outbox 테이블에 저장
- `OutboxRelayScheduler.kt`: Outbox → Kafka 릴레이

### 2. Idempotent Consumer Pattern

**Consumer 측 At Most Once 보장**

```
Kafka 메시지 수신
├── EventHandled 테이블 확인 (이미 처리?)
│   ├── Yes → Skip (멱등성 보장)
│   └── No → 처리 진행
├── 비즈니스 로직 실행 (e.g. ProductMetrics 업데이트)
├── EventHandled 테이블에 기록
└── Manual Ack
```

**구현 파일:**
- `EventHandled.kt`: 이벤트 처리 기록 엔티티
- `KafkaEventConsumer.kt`: Kafka 메시지 소비 및 멱등 처리

### 3. Product Metrics 집계

**이벤트 기반 실시간 집계**

```
catalog-events 토픽
├── LikeAddedEvent → 좋아요 수 증가
├── LikeRemovedEvent → 좋아요 수 감소
└── ProductViewEvent → 조회 수 증가

order-events 토픽
├── OrderCreatedEvent → 판매량/판매 금액 증가
└── OrderCancelledEvent → 판매량/판매 금액 감소
```

**구현 파일:**
- `ProductMetrics.kt`: 상품별 집계 메트릭 엔티티

## 구현된 컴포넌트

### 1. Domain Layer

#### Outbox 관련
- `OutboxEvent`: Outbox 이벤트 엔티티
  - `eventType`: 이벤트 타입 (e.g. LikeAddedEvent)
  - `topic`: Kafka 토픽
  - `partitionKey`: 파티션 키 (순서 보장)
  - `payload`: JSON 페이로드
  - `status`: PENDING, PUBLISHED, FAILED
  - `retryCount`: 재시도 횟수

#### 멱등성 보장
- `EventHandled`: 이벤트 처리 기록
  - `eventType`: 이벤트 타입
  - `aggregateType`: 집계 타입 (e.g. Product, Order)
  - `aggregateId`: 집계 ID
  - `eventVersion`: 이벤트 버전 (중복 방지)

#### 집계
- `ProductMetrics`: 상품 메트릭
  - `likeCount`: 좋아요 수
  - `viewCount`: 조회 수
  - `salesCount`: 판매량
  - `totalSalesAmount`: 총 판매 금액

### 2. Infrastructure Layer

#### Kafka 설정
- `kafka.yml`: Kafka 설정
  - Producer: `acks=all`, `enable.idempotence=true`
  - Consumer: `enable-auto-commit=false`, `ack-mode=manual`
  - Topics: `catalog-events`, `order-events`, DLQ

#### Producer
- `KafkaProducerService`: Kafka 메시지 전송
- `OutboxEventPublisher`: Outbox 테이블에 이벤트 저장
- `OutboxRelayScheduler`: Outbox → Kafka 릴레이 (5초마다)

#### Consumer
- `KafkaEventConsumer`: Kafka 메시지 소비 및 처리
  - `catalog-events` 리스너
  - `order-events` 리스너
  - 멱등성 체크 및 집계 처리

## 메시지 전달 보장

### Producer → Broker (At Least Once)

**설정:**
```yaml
producer:
  acks: all  # 모든 ISR replica 확인
  retries: 3  # 실패 시 재시도
  properties:
    enable.idempotence: true  # 멱등 프로듀서
```

**Outbox 패턴:**
1. 도메인 데이터 + Outbox 이벤트를 **하나의 트랜잭션**으로 저장
2. 별도 스케줄러가 Outbox를 폴링하여 Kafka로 발행
3. 실패 시 계속 재시도 (최대 3회)
4. 성공 시 PUBLISHED 상태로 변경

### Consumer ← Broker (At Most Once)

**설정:**
```yaml
consumer:
  enable-auto-commit: false  # 수동 커밋
listener:
  ack-mode: manual  # 수동 Ack
```

**멱등성 보장:**
1. 메시지 수신 시 `EventHandled` 테이블 확인
2. 이미 처리된 메시지면 Skip
3. 새 메시지면 처리 후 `EventHandled`에 기록
4. Manual Ack 호출

## 토픽 설계

### catalog-events
- **Partition Key**: `productId`
- **이벤트:**
  - `LikeAddedEvent`: 좋아요 추가
  - `LikeRemovedEvent`: 좋아요 제거
  - `ProductViewEvent`: 상품 조회 (TODO)

### order-events
- **Partition Key**: `orderId`
- **이벤트:**
  - `OrderCreatedEvent`: 주문 생성
  - `OrderCancelledEvent`: 주문 취소 (TODO)
  - `PaymentCompletedEvent`: 결제 완료 (TODO)

## DLQ (Dead Letter Queue)

실패한 메시지를 DLQ로 격리하여 운영자가 수동으로 처리할 수 있도록 준비

**Topics:**
- `catalog-events-dlq`
- `order-events-dlq`

**TODO:** DLQ 전송 로직 구현

## 실행 방법

### 1. Kafka 인프라 시작

```bash
cd docker
docker-compose -f infra-compose.yml up -d kafka kafka-ui
```

- Kafka: `localhost:19092`
- Kafka UI: `http://localhost:9099`

### 2. 환경 변수 설정

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:19092
```

### 3. 애플리케이션 실행

```bash
./gradlew :apps:commerce-api:bootRun
```

### 4. 동작 확인

#### Outbox 이벤트 저장 확인
```sql
SELECT * FROM outbox_events WHERE status = 'PENDING';
```

#### Kafka 메시지 확인
Kafka UI (`http://localhost:9099`)에서 토픽 메시지 확인

#### 집계 결과 확인
```sql
SELECT * FROM product_metrics WHERE product_id = 100;
```

## 모니터링 포인트

### Outbox Relay
- **Lag**: PENDING 이벤트 수
- **Error Rate**: FAILED 이벤트 비율
- **Throughput**: 초당 처리 이벤트 수

### Consumer
- **Consumer Lag**: Kafka offset lag
- **Duplicate Rate**: 중복 이벤트 비율 (EventHandled 확인)
- **Processing Time**: 이벤트 처리 시간

### Metrics
- **Data Freshness**: 이벤트 발생부터 집계 반영까지의 시간
- **Consistency**: DB vs Redis vs Kafka 데이터 일관성

## 개선 사항

### 완료
- ✅ Transactional Outbox Pattern 구현
- ✅ Idempotent Consumer 구현
- ✅ Product Metrics 집계
- ✅ Manual Ack 처리
- ✅ Partition Key 기반 순서 보장

### TODO
- ⬜ DLQ 처리 로직
- ⬜ 배치 처리 (단건 → 배치)
- ⬜ Outbox 클린업 스케줄러 (완료된 이벤트 삭제)
- ⬜ Consumer Retry 정책
- ⬜ 상품 조회 이벤트 집계
- ⬜ 주문 취소 시 판매량 감소
- ⬜ 재고 소진 시 캐시 갱신
- ⬜ 통합 테스트 (Testcontainers Kafka)

## 주요 파일 위치

```
apps/commerce-api/src/main/kotlin/com/loopers/
├── domain/
│   ├── outbox/
│   │   ├── OutboxEvent.kt
│   │   ├── OutboxEventRepository.kt
│   │   └── OutboxEventPublisher.kt
│   ├── event/
│   │   ├── EventHandled.kt
│   │   └── EventHandledRepository.kt
│   └── product/
│       ├── ProductMetrics.kt
│       └── ProductMetricsRepository.kt
├── infrastructure/
│   ├── kafka/
│   │   ├── KafkaConfig.kt
│   │   ├── KafkaProducerService.kt
│   │   ├── KafkaEventConsumer.kt
│   │   └── OutboxRelayScheduler.kt
│   ├── outbox/
│   │   └── OutboxEventRepositoryImpl.kt
│   ├── event/
│   │   └── EventHandledRepositoryImpl.kt
│   └── product/
│       └── ProductMetricsRepositoryImpl.kt
└── resources/
    └── kafka.yml
```

## 참고 자료

- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [Idempotent Consumer](https://microservices.io/patterns/communication-style/idempotent-consumer.html)
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Spring Kafka Reference](https://docs.spring.io/spring-kafka/reference/)
