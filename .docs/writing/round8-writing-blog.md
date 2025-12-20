# "서버를 두 대로 늘렸는데 이벤트가 안 가는 이유"

**TL;DR**: Round 7에서 ApplicationEvent로 트랜잭션을 분리하고 장애 격리에 성공했다. 그런데 **서버를 스케일 아웃하니 이벤트가 다른 인스턴스로 전달되지 않았다**. "JVM 내부에서만 동작하는구나..." 싶어서 Kafka를 도입했더니, **서비스 경계를 넘어 안전하게 이벤트를 전달**할 수 있었다. Transactional Outbox Pattern과 Idempotent Consumer Pattern을 적용하니, **메시지가 유실되지도 중복 처리되지도 않았다**. 이벤트 기반 아키텍처는 단순히 비동기 처리가 아니라, **분산 시스템에서의 데이터 전파** 문제였다.

## "서버를 늘렸는데 메트릭이 반만 집계돼요"

### 처음 마주한 문제

Round 7에서 ApplicationEvent 기반 이벤트 처리를 구현하고 나니 뿌듯했다. 쿠폰 서비스가 느려져도 주문은 정상 생성됐고, 집계 실패해도 도메인 로직은 영향받지 않았다.

**"이제 완벽한 거 아냐?"**

그런데 트래픽이 증가하면서 새로운 요구사항이 들어왔다:

> "서버를 2대로 늘려서 부하를 분산해주세요."

"뭐 어렵겠어?" Docker Compose로 인스턴스를 2개 띄우고, 로드 밸런서를 앞단에 붙였다.

**배포 구성:**

```yaml
# docker-compose.yml
services:
  app-1:
    image: commerce-api
    ports:
      - "8081:8080"

  app-2:
    image: commerce-api
    ports:
      - "8082:8080"

  nginx:
    image: nginx
    ports:
      - "80:80"
    # round-robin으로 app-1, app-2에 분산
```

배포하고 테스트를 해봤다.

### 충격적인 결과

좋아요를 10번 추가하고 메트릭을 확인했다.

```sql
SELECT product_id, like_count FROM product_metrics WHERE product_id = 100;

-- 예상: like_count = 10
-- 실제: like_count = 5 😱
```

**"왜 절반만 집계되지?"**

로그를 보니 패턴이 보였다:

```
[app-1] 좋아요 추가: userId=1, productId=100
[app-1] 이벤트 발행: LikeAddedEvent(userId=1, productId=100)
[app-1] 이벤트 처리: ProductMetrics 업데이트 ✅

[app-2] 좋아요 추가: userId=2, productId=100
[app-2] 이벤트 발행: LikeAddedEvent(userId=2, productId=100)
[app-2] 이벤트 처리: ProductMetrics 업데이트 ✅

[app-1] 좋아요 추가: userId=3, productId=100
[app-1] 이벤트 발행: LikeAddedEvent(userId=3, productId=100)
[app-1] 이벤트 처리: ProductMetrics 업데이트 ✅

❌ app-1의 이벤트는 app-2로 전달 안 됨!
❌ app-2의 이벤트는 app-1로 전달 안 됨!
```

**문제 분석:**

| 서버 | 처리한 좋아요 | 집계한 메트릭 | 문제점 |
|------|-------------|--------------|--------|
| app-1 | 5개 | 5개 | app-2의 이벤트 못 받음 |
| app-2 | 5개 | 5개 | app-1의 이벤트 못 받음 |
| **실제 총합** | **10개** | **각자 5개씩** | 🔴 **절반만 집계** |

**"ApplicationEvent는 JVM 내부에서만 동작하는구나..."**

처음 알았다. `ApplicationEventPublisher`는 **같은 JVM의 리스너에만** 이벤트를 전달한다는 것을.

### Spring의 ApplicationEvent 동작 원리

```kotlin
// 이벤트 발행
applicationEventPublisher.publishEvent(LikeAddedEvent(...))

// Spring 내부 동작
// 1. 현재 ApplicationContext의 리스너 목록 조회
// 2. 같은 JVM 내의 @EventListener만 찾음
// 3. 해당 리스너들만 호출

// ❌ 다른 서버의 리스너는 알 수 없음!
```

**ApplicationEvent의 한계:**

| 항목 | 동작 | 한계 |
|------|------|------|
| 전달 범위 | **단일 JVM 내부** | 다른 서버로 전달 불가 |
| 확장성 | Scale-up만 가능 | Scale-out 불가능 |
| 고가용성 | 서버 1대 장애 시 전체 영향 | 장애 격리 불가능 |
| 서비스 분리 | 불가능 | 모놀리스만 가능 |

**"서비스를 분리하려면 다른 방법이 필요하다..."**

## "메시지 브로커가 필요하다"

### 첫 번째 시도: HTTP API로 전달?

"그럼 HTTP로 다른 서버에 알려주면 되지 않나?"

**시도해본 방법:**

```kotlin
@Async
@TransactionalEventListener(phase = AFTER_COMMIT)
fun handleLikeAdded(event: LikeAddedEvent) {
    // 모든 서버에 HTTP 요청
    servers.forEach { serverUrl ->
        restTemplate.postForEntity(
            "$serverUrl/internal/metrics/like",
            event,
            Void::class.java
        )
    }
}
```

**문제점:**

| 문제 | 설명 | 영향 |
|------|------|------|
| **서버 목록 관리** | 어떤 서버들이 있는지 알아야 함 | 서버 추가/제거 시 설정 변경 필요 |
| **네트워크 장애** | HTTP 요청 실패 시 재시도? | 복잡한 재시도 로직 필요 |
| **순서 보장 어려움** | 네트워크 지연, 재시도로 전송 순서 != 도착 순서 | 같은 상품에 좋아요 추가→취소 순서가 뒤바뀔 수 있음 |
| **중복 처리** | 재시도 시 같은 메시지 중복 수신 | 멱등성 처리 필요 |

**순서 보장 문제 예시:**

```
[Server A에서 발생]
1. 좋아요 추가 (productId=100) → HTTP 전송 시작 (네트워크 지연 500ms)
2. 좋아요 취소 (productId=100) → HTTP 전송 시작 (네트워크 지연 100ms)

[Server B에서 수신]
1. 좋아요 취소 먼저 도착 ❌
2. 좋아요 추가 나중에 도착 ❌

→ 실제: 취소 상태
→ 결과: 추가 상태 (잘못됨!)
```

**"이건 너무 복잡하다... 전문적인 메시지 브로커가 필요해"**

### Kafka를 선택한 이유

메시지 브로커 옵션을 검토했다:

| 옵션 | 특징 | 선택 여부 |
|------|------|----------|
| **RabbitMQ** | 전통적 메시지 큐, 다양한 라우팅 | ⚪ 괜찮음 |
| **AWS SQS** | 관리형, 간단함 | ⚪ Cloud 종속 |
| **Kafka** | **고성능, 순서 보장, 이벤트 스트리밍** | ✅ **선택** |
| **Redis Pub/Sub** | 빠르지만 메시지 유실 가능 | ❌ 신뢰성 부족 |

**Kafka 선택 이유:**

```
1. 순서 보장: Partition 단위로 순서 유지
2. 고성능: 초당 수백만 메시지 처리
3. 재처리 가능: Consumer가 offset을 관리
4. 확장성: Partition 추가로 쉽게 확장
5. 이벤트 저장: 메시지가 디스크에 영구 보관
```

**"이벤트 기반 아키텍처의 표준이구나"**

## "메시지를 안전하게 전달하려면"

### Kafka를 띄우고 Producer 작성

Docker로 Kafka를 띄우고, 간단한 Producer를 만들었다.

**첫 번째 구현:**

```kotlin
@Service
class LikeService(
    private val likeRepository: LikeRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun addLike(userId: Long, productId: Long) {
        // 1. Like 저장
        val like = Like(userId = userId, productId = productId)
        likeRepository.save(like)

        // 2. Kafka로 이벤트 전송
        val event = LikeAddedEvent(userId, productId, LocalDateTime.now())
        kafkaTemplate.send(
            "catalog-events",
            productId.toString(),
            objectMapper.writeValueAsString(event)
        )
    }
}
```

로컬에서 돌려보니 잘 작동했다. Consumer도 메시지를 받아서 메트릭을 업데이트했다.

**"이제 완벽하다!"**

### 하지만 새로운 문제

실제로 부하 테스트를 해보니 문제가 발생했다.

**시나리오 1: Kafka가 느려지면?**

**잘못된 구현 (트랜잭션 안에서 Kafka 호출):**

```kotlin
@Transactional  // ❌ 문제: Kafka 호출까지 트랜잭션에 포함
fun addLike(userId: Long, productId: Long) {
    likeRepository.save(like)         // 50ms
    kafkaTemplate.send(...)           // 1000ms ⚠️ 느림!
    // 트랜잭션 커밋은 Kafka 응답 후에야 가능
}
```

**문제점:**

```
[트랜잭션 시작]
  ├── Like 저장 (50ms)
  ├── Kafka 전송 (1000ms) ⚠️ 느림!
  │   - 네트워크 왕복
  │   - Kafka 브로커 응답 대기
  │   - 트랜잭션은 계속 유지됨!
  └── 커밋 (10ms)

총 소요 시간: ~1060ms
→ DB 커넥션을 1060ms 동안 점유!
```

**영향:**
- Kafka가 느리면 **트랜잭션도 길어짐**
- DB 커넥션 점유 시간 증가 (1초 이상)
- 커넥션 풀 고갈 → 다른 요청 대기
- 동시 요청 처리량 급감

**"그럼 트랜잭션을 나누면 되지 않나?"**

```kotlin
// 시도: 트랜잭션 분리
@Transactional
fun addLike(userId: Long, productId: Long) {
    likeRepository.save(like)  // ✅ 빠른 커밋
}
// 트랜잭션 종료

kafkaTemplate.send(...)  // Kafka는 별도로 전송
```

**하지만 새로운 문제 발생 → 시나리오 3으로 이어짐**

**시나리오 2: Kafka 전송 실패 시?**

```kotlin
@Transactional
fun addLike(userId: Long, productId: Long) {
    likeRepository.save(like)  // ✅ 성공

    kafkaTemplate.send(...)  // ❌ 실패 (Kafka 다운)
    // 예외 발생!
}
```

**문제:**
- Kafka 실패 시 **전체 트랜잭션 롤백**?
- Like도 저장 안 됨
- Kafka 장애가 도메인 로직에 직접 영향

**시나리오 3: DB 커밋 후 Kafka 실패? (트랜잭션 분리 시)**

**트랜잭션을 나눈 경우:**

```kotlin
@Transactional
fun addLike(userId: Long, productId: Long) {
    likeRepository.save(like)
    // 여기서 트랜잭션 커밋 ✅
}

// 트랜잭션 밖에서 Kafka 전송
kafkaTemplate.send(...)  // ❌ 실패 가능!
```

**문제:**

```
1. Like 저장 성공
2. DB 커밋 ✅
3. (트랜잭션 종료)
4. Kafka 전송 시도
5. ❌ Kafka 전송 실패 (네트워크 오류, Kafka 다운 등)

결과: Like는 저장됐는데, 이벤트는 미발행 😱
```

**"트랜잭션 안에 넣어도 문제, 빼도 문제... 어떻게 하지?"**

**"DB와 Kafka를 하나의 트랜잭션으로 묶을 수 없잖아..."**

## Transactional Outbox Pattern

### 문제의 본질

핵심 문제는 **DB 트랜잭션과 메시지 전송을 원자적으로 처리할 수 없다**는 것이었다.

**원하는 것:**

```
[원자적 처리]
  ├── DB에 Like 저장
  └── Kafka로 이벤트 전송

둘 다 성공하거나, 둘 다 실패해야 함
```

**현실:**

```
[DB 트랜잭션] ≠ [Kafka 전송]

Case 1: DB 성공, Kafka 실패 → 이벤트 유실
Case 2: Kafka 성공, DB 롤백 → 잘못된 이벤트 발행
Case 3: Kafka 느림 → DB 트랜잭션 길어짐
```

**"트랜잭션을 나누되, 메시지는 반드시 전달되어야 한다"**

### Outbox Pattern의 아이디어

해결책은 의외로 간단했다.

**"메시지도 DB에 저장하면 되지 않을까?"**

```kotlin
@Transactional
fun addLike(userId: Long, productId: Long) {
    // 1. Like 저장
    likeRepository.save(like)

    // 2. Outbox에 이벤트 저장 (같은 트랜잭션!)
    outboxEventRepository.save(
        OutboxEvent.create(
            eventType = "LikeAddedEvent",
            topic = "catalog-events",
            partitionKey = productId.toString(),
            payload = objectMapper.writeValueAsString(event),
            aggregateType = "Product",
            aggregateId = productId
        )
    )

    // 커밋되면 둘 다 저장됨 ✅
    // 롤백되면 둘 다 롤백됨 ✅
}
```

**Outbox 테이블:**

```sql
CREATE TABLE outbox_events (
    id BIGINT PRIMARY KEY,
    event_type VARCHAR(255),  -- "LikeAddedEvent"
    topic VARCHAR(255),        -- "catalog-events"
    partition_key VARCHAR(255), -- "100"
    payload TEXT,              -- JSON 형태의 이벤트 데이터
    status VARCHAR(20),        -- PENDING, PUBLISHED, FAILED
    retry_count INT,
    created_at TIMESTAMP,
    published_at TIMESTAMP
);
```

**그럼 Kafka로는 언제 보내지?**

### OutboxRelayScheduler: 배달부

Outbox에 저장된 이벤트를 Kafka로 전달하는 스케줄러를 만들었다.

```kotlin
@Component
class OutboxRelayScheduler(
    private val outboxEventRepository: OutboxEventRepository,
    private val kafkaProducerService: KafkaProducerService,
) {
    /**
     * 5초마다 PENDING 이벤트를 Kafka로 발행
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    fun relayPendingEvents() {
        // 1. PENDING 상태의 이벤트 조회
        val pendingEvents = outboxEventRepository.findPendingEvents(batchSize = 100)

        if (pendingEvents.isEmpty()) return

        logger.info("Outbox Relay 시작: ${pendingEvents.size}개 이벤트 처리")

        // 2. 각 이벤트를 Kafka로 전송
        pendingEvents.forEach { event ->
            processEvent(event)
        }
    }

    @Transactional
    fun processEvent(event: OutboxEvent): Boolean {
        return try {
            // Kafka로 메시지 전송
            val future = kafkaProducerService.send(
                topic = event.topic,
                key = event.partitionKey,
                message = event.payload
            )

            // 동기적으로 결과 대기 (타임아웃 5초)
            future.get(5, TimeUnit.SECONDS)

            // 성공 처리
            event.markAsPublished()
            outboxEventRepository.save(event)

            true
        } catch (e: Exception) {
            // 실패 처리
            event.markAsFailed(e.message ?: "알 수 없는 오류")
            outboxEventRepository.save(event)

            logger.error("Outbox 이벤트 발행 실패: eventId=${event.id}", e)
            false
        }
    }
}
```

**Outbox Pattern 동작 흐름:**

```
[사용자 요청: 좋아요 추가]
   ↓
[LikeService - 트랜잭션 1]
   ├── Like 저장
   └── OutboxEvent 저장 (status: PENDING)
   ↓ 커밋

[5초 후 - OutboxRelayScheduler]
   ↓
[별도 트랜잭션 2]
   ├── PENDING 이벤트 조회
   ├── Kafka로 전송
   │   ├─ 성공 → status: PUBLISHED
   │   └─ 실패 → status: FAILED, retryCount++
   └── 상태 업데이트
```

### Outbox Pattern의 장점

| 문제 | Before (직접 전송) | After (Outbox) |
|------|-------------------|----------------|
| **트랜잭션 길이** | Kafka 응답까지 대기 | DB 저장만 (빠름) |
| **Kafka 장애** | 전체 롤백 🔴 | Like 저장 성공 ✅ |
| **메시지 유실** | 커밋 후 실패 시 유실 가능 🔴 | 재시도로 반드시 전달 ✅ |
| **성능** | Kafka 속도에 영향받음 | DB 속도에만 영향 ✅ |

**"DB 트랜잭션 내에서는 DB만 다루고, 메시지 전송은 나중에 한다"**

이게 Outbox Pattern의 핵심이다.

### At Least Once Delivery

Outbox Pattern은 **At Least Once** 전달을 보장한다.

**정상 흐름:**

```
1. OutboxEvent 저장 (PENDING)
2. Scheduler가 조회
3. Kafka 전송 성공
4. status → PUBLISHED
→ ✅ 메시지 정확히 1번 전달
```

**실패 후 재시도:**

```
1. OutboxEvent 저장 (PENDING)
2. Scheduler가 조회
3. Kafka 전송 실패 (네트워크 오류)
4. status → FAILED, retryCount = 1

[5초 후 재시도]
5. 같은 OutboxEvent 다시 조회
6. Kafka 전송 성공
7. status → PUBLISHED
→ ✅ 메시지 반드시 전달 (최소 1번)
```

**극단적 케이스: Kafka 전송 성공 후 DB 업데이트 실패?**

```
1. OutboxEvent 저장 (PENDING)
2. Kafka 전송 성공 ✅
3. status 업데이트 시도
4. ❌ DB 장애 발생 (업데이트 실패)

[5초 후]
5. 같은 이벤트 다시 조회 (여전히 PENDING)
6. Kafka로 다시 전송 ✅
→ 🔔 메시지 중복 전달!
```

**"최소 1번 전달은 보장하지만, 중복 전달될 수 있다"**

이게 At Least Once의 의미다. 그럼 중복은 어떻게 처리하지?

## Idempotent Consumer Pattern

### Consumer의 고민

Producer는 Outbox로 해결했다. 그런데 Consumer는?

**Consumer가 받는 메시지:**

```
메시지 1: LikeAddedEvent(userId=1, productId=100)
메시지 2: LikeAddedEvent(userId=1, productId=100)  // 중복!
메시지 3: LikeAddedEvent(userId=2, productId=100)
```

**중복 처리 시 문제:**

```kotlin
@KafkaListener(topics = ["catalog-events"])
fun consumeCatalogEvents(message: String) {
    val event = objectMapper.readValue(message, LikeAddedEvent::class.java)

    // ProductMetrics의 likeCount 증가
    val metrics = productMetricsRepository.findByProductId(event.productId)
    metrics.incrementLikeCount()
    productMetricsRepository.save(metrics)
}
```

**문제:**

```
메시지 1 처리 → likeCount = 1 ✅
메시지 2 처리 → likeCount = 2 (중복!) ❌
메시지 3 처리 → likeCount = 3 ✅

실제 좋아요: 2개
집계된 좋아요: 3개 😱
```

**"같은 메시지를 여러 번 처리해도 결과가 같아야 한다"**

이게 **멱등성(Idempotency)**이다.

### EventHandled 테이블: 처리 기록

해결책은 "이미 처리한 메시지인지 기록"하는 것이었다.

**EventHandled 테이블:**

```sql
CREATE TABLE event_handled (
    id BIGINT PRIMARY KEY,
    event_type VARCHAR(255),    -- "LikeAddedEvent"
    aggregate_type VARCHAR(255), -- "Product"
    aggregate_id BIGINT,         -- 100
    event_version BIGINT,        -- createdAt.nano (유일성 보장)
    handled_at TIMESTAMP,

    UNIQUE INDEX idx_event_key (
        event_type,
        aggregate_type,
        aggregate_id,
        event_version
    )
);
```

**EventHandled의 역할:**

```
이벤트 처리 전에 확인:
"이 이벤트를 이미 처리했나?"

→ 처리한 적 있음: Skip
→ 처리한 적 없음: 처리 + 기록
```

### 멱등 Consumer 구현

```kotlin
@Component
class KafkaEventConsumer(
    private val productMetricsRepository: ProductMetricsRepository,
    private val eventHandledRepository: EventHandledRepository,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = ["catalog-events"],
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    fun consumeCatalogEvents(
        message: String,
        acknowledgment: Acknowledgment
    ) {
        try {
            val event = objectMapper.readValue(message, LikeAddedEvent::class.java)

            // 1. 멱등성 체크: 이미 처리한 이벤트인가?
            val eventKey = EventHandled.EventKey(
                eventType = "LikeAddedEvent",
                aggregateType = "Product",
                aggregateId = event.productId,
                eventVersion = event.createdAt.nano.toLong()
            )

            if (eventHandledRepository.existsByEventKey(eventKey)) {
                logger.info("중복 이벤트 Skip: productId=${event.productId}")
                acknowledgment.acknowledge()  // 중복이지만 성공 처리
                return
            }

            // 2. 비즈니스 로직 실행
            val metrics = productMetricsRepository.findByProductId(event.productId)
                ?: ProductMetrics.create(event.productId)

            metrics.incrementLikeCount()
            productMetricsRepository.save(metrics)

            // 3. 처리 기록 저장
            eventHandledRepository.save(EventHandled.create(eventKey))

            // 4. Kafka Ack
            acknowledgment.acknowledge()

            logger.info("이벤트 처리 완료: productId=${event.productId}")
        } catch (e: Exception) {
            logger.error("이벤트 처리 실패", e)
            // Ack 안 하면 재처리됨
            throw e
        }
    }
}
```

**처리 흐름:**

```
메시지 1 수신: LikeAddedEvent(userId=1, productId=100, createdAt=12:00:00.123)
   ↓
[멱등성 체크]
   SELECT * FROM event_handled
   WHERE event_type='LikeAddedEvent' AND aggregate_id=100
     AND event_version=123
   → 결과: 없음
   ↓
[비즈니스 로직]
   likeCount: 0 → 1
   ↓
[처리 기록]
   INSERT INTO event_handled (..., event_version=123)
   ↓
[Ack]
   acknowledgment.acknowledge()

메시지 2 수신: 같은 이벤트 (중복!)
   ↓
[멱등성 체크]
   SELECT * FROM event_handled WHERE ... event_version=123
   → 결과: ✅ 있음!
   ↓
[Skip]
   logger.info("중복 이벤트 Skip")
   acknowledgment.acknowledge()

→ likeCount는 여전히 1 (중복 처리 안 됨!) ✅
```

### At Most Once Processing

EventHandled 패턴은 **At Most Once** 처리를 보장한다.

| 시나리오 | 동작 | 결과 |
|---------|------|------|
| 정상 처리 | 처리 + 기록 | ✅ 1번 처리 |
| 중복 수신 | Skip | ✅ 1번만 처리 |
| 처리 중 실패 | 재시도 | ✅ 결국 1번 처리 |

**"최소 1번 전달 + 최대 1번 처리 = Exactly Once Semantics"**

Producer의 Outbox + Consumer의 EventHandled = **정확히 1번 처리**

## 왜 EventHandled와 OutboxEvent를 분리했는가?

### 처음의 의문

"둘 다 이벤트 기록인데, 하나로 합치면 안 될까?"

테이블 스키마를 보면 비슷해 보인다:

```sql
-- OutboxEvent
outbox_events (
    event_type, topic, partition_key, payload,
    status, created_at
)

-- EventHandled
event_handled (
    event_type, aggregate_type, aggregate_id,
    event_version, handled_at
)
```

**"이벤트 로그 테이블 하나로 관리하면 간단하지 않나?"**

### 완전히 다른 책임

하지만 두 테이블은 **완전히 다른 질문**에 답한다.

**OutboxEvent가 답하는 질문:**

```
"이 이벤트를 Kafka로 발행했는가?"

→ Producer의 관심사
→ PENDING 이벤트를 찾아서 Kafka로 전송
→ 순차 조회 (created_at 순서)
```

**EventHandled가 답하는 질문:**

```
"이 이벤트를 이미 처리했는가?"

→ Consumer의 관심사
→ 중복 체크 (빠른 존재 여부 확인)
→ 랜덤 액세스 (유니크 키 조회)
```

### 쿼리 패턴의 차이

**OutboxEvent 쿼리:**

```kotlin
// Producer: 배치로 PENDING 이벤트 조회
fun findPendingEvents(limit: Int): List<OutboxEvent> {
    return jpaQueryFactory
        .selectFrom(outboxEvent)
        .where(outboxEvent.status.eq(OutboxEventStatus.PENDING))
        .orderBy(outboxEvent.createdAt.asc())  // 순차 조회
        .limit(limit.toLong())
        .fetch()
}

// 인덱스: (status, created_at)
```

**EventHandled 쿼리:**

```kotlin
// Consumer: 빠른 중복 체크
fun existsByEventKey(eventKey: EventKey): Boolean {
    return exists(
        event_handled
        WHERE event_type = ?
          AND aggregate_id = ?
          AND event_version = ?
    )
}

// 유니크 인덱스: (event_type, aggregate_type, aggregate_id, event_version)
// → O(1) 조회
```

### 성능 차이

만약 하나의 테이블로 합친다면?

```sql
CREATE TABLE event_log (
    id BIGINT,
    event_type VARCHAR(255),
    payload TEXT,
    -- Producer용 컬럼
    status VARCHAR(20),        -- PENDING, PUBLISHED
    created_at TIMESTAMP,
    -- Consumer용 컬럼
    aggregate_id BIGINT,
    event_version BIGINT,
    handled BOOLEAN,
    handled_at TIMESTAMP
);
```

**문제점:**

| 문제 | 설명 | 영향 |
|------|------|------|
| **인덱스 충돌** | Producer는 (status, created_at), Consumer는 (aggregate_id, event_version) 필요 | 인덱스 비효율 |
| **테이블 락 경합** | Producer INSERT + Consumer SELECT 동시 발생 | 성능 저하 |
| **데이터 크기** | OutboxEvent는 payload 포함 (큼), EventHandled는 키만 (작음) | 불필요한 저장 공간 |

### 라이프사이클의 차이

**OutboxEvent:**

```kotlin
// PUBLISHED 이벤트는 7일 후 삭제 가능
@Scheduled(cron = "0 0 3 * * *")
fun cleanupOldPublishedEvents() {
    val threshold = ZonedDateTime.now().minusDays(7)
    outboxEventRepository.deletePublishedEventsBefore(threshold)
}
```

**EventHandled:**

```
// 멱등성 보장을 위해 장기 보관
// 삭제하면 중복 처리 위험!
→ 보관 또는 아카이빙
```

| 테이블 | 보관 기간 | 클린업 정책 | 이유 |
|--------|----------|------------|------|
| OutboxEvent | 7일 | 주기적 삭제 | Kafka 발행만 확인하면 됨 |
| EventHandled | 장기 | 보관/아카이빙 | 멱등성 보장 필요 |

### 트랜잭션 경계의 명확성

```kotlin
// Producer: OutboxEvent에만 의존
@Transactional
fun addLike(userId: Long, productId: Long) {
    likeRepository.save(Like(...))
    outboxEventPublisher.publish(LikeAddedEvent(...))
    // OutboxEvent 테이블에만 INSERT
}

// Consumer: EventHandled에만 의존
@Transactional
fun handleLikeAdded(event: LikeAddedEvent) {
    if (eventHandledRepository.exists(...)) return
    // EventHandled 테이블에만 SELECT

    processEvent(...)
    eventHandledRepository.save(EventHandled.create(...))
    // EventHandled 테이블에만 INSERT
}
```

**Producer와 Consumer가 독립적인 테이블 사용:**
- 서로 다른 트랜잭션
- 서로 다른 데이터베이스로 분리 가능
- 장애 격리

**"하나로 합치는 것은 중복이 아니라, 책임을 섞는 것이다"**

### 결론

EventHandled와 OutboxEvent는:

```
OutboxEvent = "발행 대기열"
  - Producer가 사용
  - 순차 조회
  - 단기 보관

EventHandled = "처리 기록 해시맵"
  - Consumer가 사용
  - 빠른 중복 체크
  - 장기 보관
```

처음엔 "중복 아닌가?"라고 생각했지만, 실제로는 **각자의 역할에 최적화된 설계**였다.

## 극적인 효과

### 스케일 아웃 테스트

Kafka를 적용하고 다시 2대로 스케일 아웃했다.

**동일한 테스트:**

```bash
# 좋아요 10번 추가
for i in {1..10}; do
  curl -X POST http://localhost/api/likes \
    -H "Content-Type: application/json" \
    -d "{\"userId\": $i, \"productId\": 100}"
done

# 10초 대기 (Outbox Relay 실행)
sleep 10
```

**결과:**

```sql
SELECT product_id, like_count FROM product_metrics WHERE product_id = 100;

-- Before (ApplicationEvent): like_count = 5 (절반만 집계)
-- After (Kafka): like_count = 10 ✅
```

**로그 확인:**

```
[app-1] 좋아요 추가: userId=1, productId=100
[app-1] OutboxEvent 저장: status=PENDING

[app-2] 좋아요 추가: userId=2, productId=100
[app-2] OutboxEvent 저장: status=PENDING

[app-1] OutboxRelayScheduler: Kafka 전송 (userId=1)
[app-2] OutboxRelayScheduler: Kafka 전송 (userId=2)

[app-1 Consumer] LikeAddedEvent 수신 (userId=1) → 집계 ✅
[app-1 Consumer] LikeAddedEvent 수신 (userId=2) → 집계 ✅

[app-2 Consumer] LikeAddedEvent 수신 (userId=1) → 중복 Skip
[app-2 Consumer] LikeAddedEvent 수신 (userId=2) → 중복 Skip

최종: like_count = 10 ✅
```

**"모든 서버가 같은 이벤트를 받고, 중복 없이 정확히 1번만 처리한다!"**

### 중복 메시지 멱등성 테스트

의도적으로 같은 메시지를 3번 전송했다.

```kotlin
@Test
fun `중복 메시지를 재전송해도 멱등성이 보장된다`() {
    val event = LikeAddedEvent(
        userId = 1L,
        productId = 300L,
        createdAt = LocalDateTime.now()
    )
    val payload = objectMapper.writeValueAsString(event)

    // 같은 메시지를 3번 전송
    repeat(3) {
        kafkaProducerService.send(
            topic = "catalog-events",
            key = "300",
            message = payload
        ).get(5, TimeUnit.SECONDS)
    }

    // Consumer가 처리할 때까지 대기
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted {
            val metrics = productMetricsRepository.findByProductId(300L)
            assertThat(metrics).isNotNull
            // 3번 전송했지만 1번만 증가 ✅
            assertThat(metrics!!.likeCount).isEqualTo(1)
        }
}
```

**결과: PASSED ✅**

| 시도 | EventHandled 존재? | 처리 | likeCount |
|------|-------------------|------|-----------|
| 1차 전송 | ❌ 없음 | ✅ 처리 | 1 |
| 2차 전송 | ✅ 있음 | Skip | 1 (유지) |
| 3차 전송 | ✅ 있음 | Skip | 1 (유지) |

**"중복 전송해도 정확히 1번만 처리된다"**

### Kafka 장애 시 안정성 테스트

Kafka를 강제로 다운시키고 테스트했다.

```bash
docker-compose stop kafka
```

**좋아요 추가 요청:**

```
[app-1] 좋아요 추가 요청
   ↓
[LikeService]
   ├── Like 저장 ✅
   └── OutboxEvent 저장 (status: PENDING) ✅
   ↓
응답: 200 OK ✅ (사용자는 정상 처리로 인식)

[OutboxRelayScheduler]
   ├── PENDING 이벤트 조회
   ├── Kafka 전송 시도
   └── ❌ 실패 (Kafka 다운)
   ↓
   status: FAILED, retryCount: 1

[5초 후 재시도]
   ├── FAILED 이벤트 재조회
   └── ❌ 계속 실패 (Kafka 여전히 다운)
```

**Kafka 복구 후:**

```bash
docker-compose start kafka
```

```
[OutboxRelayScheduler]
   ├── FAILED 이벤트 조회
   ├── Kafka 전송 재시도
   └── ✅ 성공!
   ↓
   status: PUBLISHED

[Consumer]
   └── 이벤트 수신 → ProductMetrics 업데이트 ✅
```

| 상황 | Before (직접 전송) | After (Outbox) |
|------|-------------------|----------------|
| Kafka 다운 | 좋아요 추가 실패 🔴 | **좋아요 추가 성공** ✅ |
| 이벤트 전달 | 유실 🔴 | **복구 후 자동 전달** ✅ |
| 사용자 경험 | 오류 메시지 🔴 | **정상 응답** ✅ |

**"Kafka가 죽어도 도메인 로직은 멈추지 않는다!"**

## 배운 것들

### 1. ApplicationEvent의 한계

처음엔 ApplicationEvent로 충분하다고 생각했다.

하지만:

| 항목 | ApplicationEvent | Kafka |
|------|-----------------|-------|
| 전달 범위 | 단일 JVM | **서비스 간 전달** |
| 확장성 | Scale-up만 | **Scale-out 가능** |
| 영속성 | 메모리만 | **디스크 저장** |
| 재처리 | 불가능 | **Offset 조정 가능** |

**"모놀리스에서는 ApplicationEvent, 분산 시스템에서는 Kafka"**

### 2. Transactional Outbox Pattern

**"DB 트랜잭션과 메시지 전송을 원자적으로 처리할 수 없다"**

이 문제의 해결책은:

```
메시지도 DB에 저장하고,
별도 프로세스가 Kafka로 전송한다
```

**Outbox Pattern의 가치:**

| 측면 | 가치 |
|------|------|
| 원자성 | DB 커밋과 이벤트 저장이 동일 트랜잭션 |
| 성능 | Kafka 속도에 영향받지 않음 |
| 안정성 | Kafka 장애 시에도 도메인 로직 성공 |
| 재시도 | 자동 재시도로 메시지 유실 방지 |

### 3. Idempotent Consumer Pattern

**"At Least Once 전달은 중복을 의미한다"**

Producer가 "최소 1번" 전달을 보장하면, Consumer는 "최대 1번" 처리를 보장해야 한다.

**EventHandled 테이블의 역할:**

```
처리 전: "이미 처리했나?" 확인
처리 후: "처리했다" 기록

→ 같은 이벤트는 절대 2번 처리 안 됨
```

**멱등성의 핵심:**

```
f(x) = y
f(f(x)) = f(y) = y  // 같은 결과

좋아요 추가(event1) = likeCount++
좋아요 추가(event1 중복) = Skip (같은 결과 유지)
```

### 4. Manual Ack의 중요성

```kotlin
// ❌ Auto Ack
@KafkaListener(...)
fun consume(message: String) {
    // 메시지 수신 즉시 Ack됨
    processEvent(...)  // 실패해도 재처리 안 됨
}

// ✅ Manual Ack
@KafkaListener(...)
fun consume(message: String, ack: Acknowledgment) {
    processEvent(...)
    eventHandledRepository.save(...)
    ack.acknowledge()  // 모두 성공 후에만 Ack
}
```

**Manual Ack 없이는:**
- 처리 실패 시 메시지 유실
- 멱등성 보장 불가능 (EventHandled 저장 전 Ack)

### 5. Partition Key의 전략적 선택

```kotlin
OutboxEvent.create(
    partitionKey = productId.toString()
)
```

**Partition Key 선택 이유:**

```
같은 상품 = 같은 Partition
→ 순서 보장

다른 상품 = 다른 Partition
→ 병렬 처리
```

**예시:**

```
productId=100 → partition 0
  ├── LikeAdded
  ├── LikeRemoved  (순서 보장 ✅)
  └── LikeAdded

productId=200 → partition 1
  └── LikeAdded  (병렬 처리 ✅)
```

**"순서가 중요한 단위를 Partition Key로 선택한다"**

### 6. EventHandled vs OutboxEvent 분리의 지혜

처음엔 "중복 테이블"이라고 생각했다.

하지만:

```
OutboxEvent = Producer의 발행 큐
EventHandled = Consumer의 처리 기록

→ 완전히 다른 책임
→ 완전히 다른 액세스 패턴
→ 완전히 다른 라이프사이클
```

**"유사해 보이는 테이블도 책임이 다르면 분리해야 한다"**

## 한계와 개선 방향

### OutboxRelay 지연

현재는 5초마다 Outbox를 확인한다.

**지연 시나리오:**

```
00:00 - 좋아요 추가 (OutboxEvent 저장)
00:03 - OutboxRelay 실행 (너무 이름)
00:05 - Kafka 전송
00:06 - Consumer 처리

→ 최대 5초 지연
```

**개선 방안:**

```
1. Polling 간격 단축 (5초 → 1초)
2. CDC (Change Data Capture) 도입
   - Debezium으로 DB 변경 실시간 감지
   - OutboxEvent INSERT 즉시 Kafka 전송
```

### DLQ 처리 미구현

현재는 DLQ 토픽만 생성하고, 전송 로직은 TODO로 남겨두었다.

**향후 구현:**

```kotlin
@KafkaListener(...)
fun consume(message: String, ack: Acknowledgment) {
    try {
        processEvent(...)
    } catch (e: Exception) {
        if (retryCount >= 3) {
            // DLQ로 전송
            kafkaTemplate.send("catalog-events-dlq", message)
            ack.acknowledge()
        } else {
            throw e  // 재시도
        }
    }
}
```

### Outbox 클린업

PUBLISHED 이벤트가 계속 쌓인다.

**클린업 정책 필요:**

```kotlin
@Scheduled(cron = "0 0 3 * * *")  // 매일 새벽 3시
fun cleanupPublishedEvents() {
    val threshold = ZonedDateTime.now().minusDays(7)
    val deleted = outboxEventRepository
        .deletePublishedEventsBefore(threshold)

    logger.info("Outbox 클린업: 삭제=$deleted")
}
```

## 다음에 시도해보고 싶은 것

### 1. CDC (Change Data Capture)

Outbox Pattern의 다음 단계는 CDC다.

**Debezium 동작:**

```
[DB]
  ├── OutboxEvent INSERT 감지
  ↓
[Debezium]
  ├── 변경 로그 캡처
  ├── Kafka로 자동 전송
  ↓
[Kafka]
  └── Consumer가 수신

→ OutboxRelayScheduler 불필요!
→ 실시간 전송 (지연 최소화)
```

### 2. Kafka Streams

현재는 Consumer가 하나씩 집계한다.

**Kafka Streams로 개선:**

```
[Kafka Streams]
  ├── LikeAddedEvent 스트림
  ├── 실시간 집계 (Windowing)
  └── Materialized View로 저장

→ DB 조회 없이 집계 가능
→ 초당 수십만 건 처리 가능
```

### 3. CQRS (Command Query Responsibility Segregation)

명령과 조회를 완전히 분리:

**Write Model (Command):**

```
좋아요 추가
  ↓
Like 테이블에만 저장
  ↓
이벤트 발행
```

**Read Model (Query):**

```
ProductMetrics (집계 테이블)
  ↓
이벤트 구독하여 업데이트
  ↓
빠른 조회 (인덱스 최적화)
```

### 4. Event Sourcing

모든 상태 변경을 이벤트로 저장:

```
현재 상태 = 모든 이벤트의 합

Product.likeCount =
  LikeAddedEvent(1) +
  LikeAddedEvent(2) +
  LikeRemovedEvent(1) +
  LikeAddedEvent(3)
  = 3
```

**장점:**
- 완벽한 이력 추적
- 재계산 가능
- 시점 복원 가능

## 마치며

### "서버 2대로 늘렸는데 메트릭이 반만 집계되는 이유"

처음엔 간단한 문제라고 생각했다. ApplicationEvent만 있으면 충분할 줄 알았다.

하지만 현실은 달랐다:
- 스케일 아웃하니 이벤트가 전달 안 됐다
- Kafka로 직접 전송하니 트랜잭션 관리가 어려웠다
- 메시지가 유실되거나 중복 처리됐다

**"분산 시스템에서의 이벤트 전달은 완전히 다른 문제였다"**

### 가장 중요한 깨달음

**"메시지 전달의 보장은 Producer와 Consumer가 함께 만든다"**

```
Producer: Transactional Outbox Pattern
  → "메시지를 최소 1번 전달한다"

Consumer: Idempotent Consumer Pattern
  → "메시지를 최대 1번 처리한다"

= Exactly Once Semantics
  → "정확히 1번 처리된다"
```

Round 5에서 "빠르게 돌아간다"를 배웠다면,
Round 6에서 "장애에도 멈추지 않는다"를 배웠고,
Round 7에서 "느슨하게 연결하되, 안전하게 동작한다"를 배웠고,
Round 8에서는 **"서비스 경계를 넘어 안전하게 전달한다"**를 배웠다.

### 다음은

이제 기본적인 Kafka 기반 이벤트 파이프라인은 구축했다.

하지만 여전히 궁금한 게 많다:

**다음 단계:**
- **CDC**: Debezium으로 실시간 이벤트 캡처
- **Kafka Streams**: 실시간 스트림 처리
- **CQRS**: 읽기와 쓰기의 완전한 분리
- **Event Sourcing**: 모든 상태를 이벤트로 관리
- **Saga Pattern**: 분산 트랜잭션 관리

"서버를 두 대로 늘렸는데 이벤트가 안 간다"는 경험에서 시작해서,
Kafka, Transactional Outbox, Idempotent Consumer까지 배웠다.

분산 시스템에서의 이벤트 전달은 이제 시작일 뿐이다. 🚀
