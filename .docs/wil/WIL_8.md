# Weekly I Learned 8 (Kafka)

## Kafka는 메시지 큐가 아니라고?

이번 주는 **Kafka**를 제대로 배웠다. 그리고 깨달았다. "Kafka는 Redis Pub/Sub이나 RabbitMQ 같은 메시지 큐가 아니구나."

가장 중요한 차이:
- **메시지 큐**: 메시지 소비하면 삭제됨 (휘발성)
- **Kafka**: 메시지를 **디스크에 영구 저장** (로그 저장소)

이 차이가 실무에서 생명줄이 됐다.

## 실무 적용: SBS 가요대전 방청권 신청 시스템

지난주에 만든 방청권 신청 시스템을 Kafka로 확장했다.

### 요구사항
- 신청 내역을 **데이터 플랫폼팀에 전송**해서 분석 가능하게
- 신청 완료 후 **포인트 적립, 이메일 발송** 등 후속 처리
- 어떤 이유로든 **신청 누락이 있으면 안 됨** (금융 서비스라 컴플라이언스 이슈)

### 왜 Kafka를 선택했나?

처음엔 "그냥 ApplicationEvent로 하면 되지 않나?" 싶었는데, 멘토님이 말씀하셨다.

> "ApplicationEvent는 같은 JVM 안에서만 동작해. 데이터 플랫폼팀은 다른 서버잖아? 그리고 만약 너희 서버가 죽으면 이벤트도 다 날아가는데, 그래도 괜찮을까?"

그 말에 Kafka를 선택했다.

## Kafka 구조 설계

### 1. Producer: 신청 이벤트 발행

```kotlin
@Service
class TicketApplicationService(
    private val ticketRepository: TicketRepository,
    private val kafkaTemplate: KafkaTemplate<String, TicketAppliedEvent>,
    private val outboxRepository: OutboxRepository
) {
    @Transactional
    fun applyTicket(request: TicketRequest): Ticket {
        // 1. 신청 처리
        val ticket = ticketRepository.save(Ticket.from(request))

        // 2. Outbox 테이블에 이벤트 기록
        val outbox = OutboxEvent(
            aggregateId = ticket.id.toString(),
            eventType = "TicketApplied",
            payload = objectMapper.writeValueAsString(TicketAppliedEvent.from(ticket))
        )
        outboxRepository.save(outbox)

        return ticket
    }

    // 별도 스케줄러가 Outbox → Kafka 전송
    @Scheduled(fixedDelay = 1000)
    fun relayToKafka() {
        val events = outboxRepository.findUnpublished()
        events.forEach { event ->
            kafkaTemplate.send("ticket-applied", event.aggregateId, event.toEvent())
            event.markAsPublished()
        }
    }
}
```

**왜 Outbox 패턴을 썼나?**
- DB 커밋과 Kafka 발행을 **원자적으로 묶을 수 없음**
- DB 커밋은 성공했는데 Kafka 발행 실패하면? → **신청 누락**
- Outbox를 쓰면 **DB에 기록된 건 반드시 Kafka에도 전송 보장**

### 2. Consumer: 이벤트 처리

```kotlin
@Service
class TicketEventConsumer(
    private val dataplatformClient: DataPlatformClient,
    private val processedEventRepository: ProcessedEventRepository
) {
    @KafkaListener(topics = ["ticket-applied"], groupId = "ticket-consumer-group")
    fun consume(event: TicketAppliedEvent) {
        // 멱등성 체크
        if (processedEventRepository.existsByEventId(event.eventId)) {
            logger.info("이미 처리된 이벤트: ${event.eventId}")
            return
        }

        try {
            // 데이터 플랫폼에 전송
            dataplatformClient.sendTicketData(event)

            // 처리 완료 기록
            processedEventRepository.save(ProcessedEvent(event.eventId))
        } catch (e: Exception) {
            logger.error("이벤트 처리 실패: ${event.eventId}", e)
            throw e  // DLQ로 전송
        }
    }
}
```

**핵심 포인트:**
- **멱등성(Idempotency)**: 같은 이벤트가 여러 번 와도 한 번만 처리
- **DLQ(Dead Letter Queue)**: 반복 실패하는 메시지는 DLQ로 격리

### 3. DLQ 설정

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false
      properties:
        max.poll.records: 10
    listener:
      ack-mode: manual

resilience4j:
  retry:
    instances:
      kafkaConsumer:
        max-attempts: 3
        wait-duration: 1s
```

실패한 메시지는 3번 재시도 후 DLQ(`ticket-applied.DLQ`)로 보냄.

## 실제로 마주한 문제: 신청 누락 의혹

티케팅 종료 후, 고객센터에서 연락이 왔다.

> "신청했는데 내역이 없다는 고객 문의가 들어왔어요. 확인 부탁드립니다."

일반적인 시스템이었다면?
- 로그 확인 → "신청 API 호출됐는지 알 수 없음"
- DB 확인 → "데이터 없으면 그냥 신청 안 한 걸로 간주"
- 끝

하지만 우리는 **Kafka가 있었다**.

### Kafka Offset으로 전수 조사

```bash
# 1. 해당 고객의 전화번호로 이벤트 검색
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic ticket-applied \
  --from-beginning \
  | grep "010-1234-5678"

# 결과: 이벤트는 발행됨 (Offset: 12345)
```

**발견:**
- Kafka에는 신청 이벤트가 존재
- DB에는 데이터 없음
- **Consumer가 처리 도중 실패했고, DLQ에도 적재 안 됨**

원인: Consumer 재시도 로직 버그 (Exception이 던져지지 않아 DLQ로도 안 감)

### 복구 방법

```kotlin
// DLQ에서 수동 재처리
@PostMapping("/admin/retry-dlq")
fun retryDlq() {
    val dlqRecords = kafkaTemplate.receive("ticket-applied.DLQ")
    dlqRecords.forEach { record ->
        ticketEventConsumer.consume(record.value())
    }
}
```

결과: **누락된 신청 건 복구 완료**

## SBS 가요대전 사후보고서 작성

보고서에 다음과 같이 작성했다:

> **신청 누락 방지 메커니즘**
> 1. Transactional Outbox 패턴으로 DB 커밋 = 이벤트 발행 보장
> 2. Kafka의 영구 저장으로 이벤트 유실 방지
> 3. Consumer Offset 추적으로 전수 조사 가능
> 4. DLQ로 실패 이벤트 격리 및 수동 재처리
>
> **결론:** Kafka 덕분에 단 한 건의 신청도 누락되지 않았음을 증명

CTO님이 "Kafka를 제대로 활용했네"라고 칭찬해주셨다.

## 이번 주 배운 핵심

- **Kafka는 분산 로그 저장소**: 메시지가 사라지지 않고 디스크에 보관
- **Transactional Outbox**: DB 커밋과 이벤트 발행을 원자적으로 묶기
- **Idempotency**: 중복 메시지 대응 필수
- **DLQ**: 실패한 메시지 격리 및 수동 재처리
- **Offset 추적**: 장애 발생 시 전수 조사 가능

Kafka는 단순한 메시지 전달 도구가 아니라, **이벤트 소싱의 기반**이고, **장애 대응의 마지막 보루**였다. 다음 프로젝트에서도 꼭 활용해야겠다!
