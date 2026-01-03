# Weekly I Learned 7 (이벤트 기반 분리)

## 알림톡 하나 때문에 트랜잭션이 터진다고?

이번 주는 **이벤트 기반 아키텍처**를 배웠다. 그리고 실무에서 바로 써먹었다. 바로 지난주에 했던 **방청권 신청 시스템**.

사용자 입장에서는 "신청 버튼 하나 누르면 끝"이지만, 내부적으로는:

1. 휴대폰 번호 중복 체크
2. 신청 내역 DB 저장
3. **알림톡 발송** ← 여기가 문제

이 세 가지가 한 트랜잭션 안에 들어가 있었다.

## 문제: 외부 시스템이 트랜잭션을 잡아먹는다

### 알림톡 발송의 함정

알림톡은 외부 업체(카카오비즈메시지) API를 호출해야 하는데:
- **평균 응답 시간 1~2초** (느림)
- **가끔 5초 이상 걸림** (더 느림)
- **실패할 수도 있음** (타임아웃, 네트워크 오류)
- **메시지 검수 때문에 지연** (부적절한 내용 필터링)

이걸 트랜잭션 안에 넣으면?

```kotlin
@Transactional
fun applyTicket(request: TicketRequest) {
    // 1. 중복 체크 (0.01초)
    checkDuplicate(request.phoneNumber)

    // 2. DB 저장 (0.05초)
    ticketRepository.save(ticket)

    // 3. 알림톡 발송 (2~5초) ← 여기서 트랜잭션이 잡혀있음!
    kakaoClient.sendMessage(request.phoneNumber, "신청 완료")
}
```

**문제점:**
- 트랜잭션이 5초씩 잡혀있으면 **DB 커넥션 고갈**
- 알림톡 실패하면 **신청 자체도 롤백**됨 (말이 안 됨)
- 동시 요청 처리량이 폭락

실제로 테스트 중에 알림톡 API가 느려지자 **전체 시스템이 느려지는** 현상이 발생했다.

## 해결: 이벤트로 분리하기

### 1. Spring ApplicationEvent 사용

```kotlin
// 이벤트 정의
data class TicketAppliedEvent(
    val ticketId: Long,
    val phoneNumber: String,
    val applyTime: LocalDateTime
)

// 신청 처리 (핵심 트랜잭션만)
@Transactional
fun applyTicket(request: TicketRequest) {
    checkDuplicate(request.phoneNumber)
    val ticket = ticketRepository.save(ticket)

    // 이벤트 발행만 하고 끝
    eventPublisher.publishEvent(TicketAppliedEvent.from(ticket))
}

// 별도 핸들러에서 알림톡 처리
@Component
class TicketEventHandler(
    private val kakaoClient: KakaoClient
) {
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Async
    fun handleTicketApplied(event: TicketAppliedEvent) {
        try {
            kakaoClient.sendMessage(event.phoneNumber, "신청 완료")
        } catch (e: Exception) {
            logger.error("알림톡 발송 실패", e)
            // DLQ에 적재하거나 재시도 로직 추가
        }
    }
}
```

**효과:**
- **핵심 트랜잭션은 0.1초 이내**로 완료
- 알림톡 실패해도 **신청은 정상 처리**됨
- **비동기 처리**로 응답 속도 폭발적 향상
- DB 커넥션 점유 시간 최소화

### 2. 결합도 완전히 끊어내기

이벤트 방식의 가장 큰 장점:

```
Before:
  ApplyService → KakaoClient (직접 의존)

After:
  ApplyService → Event 발행
                   ↓
              EventHandler → KakaoClient
```

- `ApplyService`는 `KakaoClient`의 존재를 몰라도 됨
- 알림톡 외에도 **SMS, 이메일, 포인트 적립** 등 후속 처리를 추가하려면?
  - **리스너 하나만 더 만들면 끝**
  - 기존 코드 수정 불필요

## 실전 적용 결과

### Before (동기 처리)
- 평균 응답 속도: **2.5초**
- RPS: **약 400**
- 알림톡 실패 시: **신청도 실패**

### After (이벤트 분리)
- 평균 응답 속도: **80ms**
- RPS: **약 1.2만**
- 알림톡 실패 시: **신청은 성공, 알림톡만 재시도**

무려 **30배 빨라졌고**, RPS는 **30배 증가**했다.

## 주의할 점: 이벤트도 완벽하지 않다

이벤트 기반이 만능은 아니다. 실제로 겪은 문제들:

### 1. 예외가 조용히 사라진다
```kotlin
@Async
fun handleEvent(event: SomeEvent) {
    throw RuntimeException("에러 발생!")  // 이 예외는 사용자에게 안 보임
}
```
→ **로그 모니터링 필수**, **DLQ(Dead Letter Queue)** 구축 필요

### 2. 중복 실행 가능성
같은 이벤트가 여러 번 발행될 수 있음 (네트워크 재시도 등)
→ **멱등성(Idempotency) 처리** 필수

```kotlin
@TransactionalEventListener
fun handleEvent(event: TicketAppliedEvent) {
    // 이미 처리된 이벤트인지 체크
    if (processedEventRepository.existsByEventId(event.eventId)) {
        return  // 중복 실행 방지
    }

    // 실제 처리
    processedEventRepository.save(event.eventId)
    kakaoClient.sendMessage(...)
}
```

## 이번 주 배운 핵심

- **ApplicationEvent**: Spring 내부에서 이벤트 기반 통신 구현
- **@TransactionalEventListener**: 트랜잭션 커밋 후에만 이벤트 처리
- **@Async**: 비동기 처리로 응답 속도 향상
- **Decoupling**: 핵심 로직과 부가 기능을 이벤트로 분리

"모든 걸 한 번에 처리해야 한다"는 생각을 버리니 시스템이 훨씬 유연해졌다. 다음 주에는 서비스 경계를 넘어 **Kafka로 이벤트를 발행**해볼 예정!
