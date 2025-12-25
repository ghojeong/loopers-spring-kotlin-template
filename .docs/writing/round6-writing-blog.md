# "결제 서비스가 불안정합니다" 알림이 새벽 3시에 왔을 때

**TL;DR**: "카드 결제 기능 추가해주세요"라는 요구사항을 받고 PG 연동을 시작했다. 로컬에서는 잘 됐는데, **PG 서버가 느려지자 우리 서버까지 멈춰버렸다**. "응답이 안 와서 실패 처리했는데, PG에선 결제가 됐다고?" 하는 상황도 겪었다. Timeout, Retry, Circuit Breaker를 공부하고 적용했더니, **PG 장애 상황에서도 우리 서비스는 멈추지 않았다**. 외부 시스템 연동은 "그냥 HTTP 요청 하나 보내면 되겠지"가 아니었다.

## "카드 결제 기능 추가해주세요"

### 처음 마주한 요구사항

Round 5에서 성능 최적화를 마치고 나니 뿌듯했다. "이제 웬만한 건 다 할 수 있다"고 생각했다.

그때 새로운 요구사항이 들어왔다:

> "포인트만으로는 부족해요. 카드 결제 기능을 추가해주세요."

"뭐 어렵겠어?" PG(Payment Gateway) API 문서를 보니 단순해 보였다.

**PG API 스펙:**

```http
POST /api/v1/payments
{
  "orderId": "1234",
  "cardType": "SAMSUNG",
  "cardNo": "1234-5678-9012-3456",
  "amount": 50000,
  "callbackUrl": "http://localhost:8080/api/v1/payments/callback"
}

→ Response: { "transactionKey": "20250816:TR:9577c5", "status": "PENDING" }
```

"POST 요청 하나 보내면 되네!" 바로 구현했다.

```kotlin
@Service
class PaymentService(
    private val pgClient: PgClient
) {
    fun requestPayment(payment: Payment): String {
        val response = pgClient.requestPayment(...)
        return response.transactionKey
    }
}
```

로컬에서 돌려보니 잘 작동했다. 결제 요청이 성공하고, 콜백도 잘 받았다.

### 충격적인 결과

"실제 환경에서는 어떨까?" 걱정이 되어서 부하 테스트를 해봤다.

PG Simulator는 실제 PG처럼 동작하도록 만들어져 있었다:
- **요청 성공 확률**: 60% (40%는 실패)
- **요청 지연**: 100ms ~ 500ms
- **처리 지연**: 1s ~ 5s (비동기)

부하를 걸어봤다.

```bash
# 동시 100명이 결제 요청
ab -n 100 -c 100 http://localhost:8080/api/v1/orders
```

**결과:**
- 첫 10초: 정상 동작
- 20초 후: 응답 속도 급격히 느려짐
- 30초 후: **서버 전체가 응답하지 않음**

"왜?! 결제만 느린 건데, 왜 다른 API까지 멈춰?"

### 로그를 보고 알게 된 것

로그를 확인하니 충격적이었다:

```
23:15:30 [http-nio-200] Waiting for PG response...
23:15:31 [http-nio-200] Waiting for PG response...
23:15:32 [http-nio-200] Waiting for PG response...
...
23:16:00 [http-nio-200] Waiting for PG response... (30초째 대기)
```

**스레드 풀이 전부 고갈**되어 있었다.

#### 📊 스레드 상태 분석

| 시점 | 가용 스레드 | PG 대기 중 | 신규 요청 처리 |
|------|------------|-----------|--------------|
| 초기 | 200개 | 0개 | ✅ 가능 |
| 10초 후 | 150개 | 50개 | ✅ 가능 |
| 30초 후 | **0개** | **200개** | 🔴 **불가능** |

PG가 응답하지 않자, 우리 서버의 모든 스레드가 PG 응답을 기다리며 멈춰있었다.

**"외부 시스템 하나가 우리 전체를 멈출 수 있구나..."**

처음 알았다. 외부 시스템과의 연동은 단순히 HTTP 요청을 보내는 것이 아니라는 것을.

## "타임아웃이라도 걸어야겠다"

### 첫 번째 시도: 타임아웃 설정

가장 먼저 떠오른 건 타임아웃이었다.

"무한정 기다리니까 문제지. 3초만 기다리면 되겠지?"

**Feign Client 타임아웃 설정:**

```kotlin
@Configuration
class PgClientConfig {
    @Bean
    fun feignOptions(): Request.Options {
        return Request.Options(
            1000L, TimeUnit.MILLISECONDS, // 연결 타임아웃
            3000L, TimeUnit.MILLISECONDS, // 응답 타임아웃
            true,
        )
    }
}
```

다시 테스트를 돌렸다.

```bash
ab -n 100 -c 100 http://localhost:8080/api/v1/orders
```

**결과:**
- ✅ 서버가 멈추지 않음
- ✅ 3초 안에 응답 없으면 실패 처리
- ⚠️ 하지만... 실패율이 너무 높음 (60%)

로그를 보니:

```
Payment failed: Read timed out
Payment failed: Read timed out
Payment failed: Read timed out
```

**"타임아웃으로 실패 처리했는데, 나중에 보니 PG에선 결제가 성공했다고?"**

### 타임아웃만으로는 부족했던 이유

PG는 비동기로 동작한다:

**비동기 결제 플로우:**

```
1. Client → Our Server: 결제 요청
2. Our Server → PG: 결제 요청
3. PG → Our Server: PENDING 응답 (즉시)
   ↓
4. [1~5초 후] PG가 실제 결제 처리
   ↓
5. PG → Our Server: Callback (SUCCESS or FAILED)
```

문제는 **3단계에서 타임아웃**이 발생하는 경우였다:

| 시나리오 | Our Server | PG Server | 문제점 |
|----------|-----------|-----------|--------|
| 정상 | PENDING 받음 → Callback 대기 | PENDING 반환 → 처리 → Callback | ✅ 정상 |
| **타임아웃** | 3초 대기 → 실패 처리 | (느리게) PENDING 반환 → 처리 → Callback | 🔴 **Callback을 못 받음** |
| **타임아웃 + 처리 완료** | 실패 처리 완료 | 결제 승인 → Callback 시도 | 😱 **이중 결제 위험** |

**"응답이 늦어서 실패 처리했는데, 나중에 결제가 승인됐다고?"**

이 문제를 어떻게 해결할까?

## "재시도하면 되지 않을까?"

### Retry 패턴 적용

"일시적인 네트워크 오류일 수도 있으니, 재시도하면 성공할 수도 있지 않을까?"

Resilience4j의 Retry를 적용했다.

**Retry 설정:**

```yaml
resilience4j:
  retry:
    instances:
      pgRetry:
        max-attempts: 3          # 최대 3회
        wait-duration: 1s        # 1초 간격
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
```

```kotlin
@Retry(name = "pgRetry")
fun requestPgPayment(payment: Payment): String {
    val response = pgClient.requestPayment(...)
    return response.transactionKey
}
```

**재시도 동작:**

```
1차 시도 → 실패 (timeout)
   ↓ 1초 대기
2차 시도 → 실패 (timeout)
   ↓ 2초 대기 (exponential backoff)
3차 시도 → 성공!
```

테스트 결과:

| 항목 | Retry 전 | Retry 후 | 개선 |
|------|----------|----------|------|
| 성공률 | 40% | **75%** | +35%p |
| 평균 응답 시간 | 500ms | 800ms | -300ms |
| 사용자 경험 | 🔴 실패 많음 | ✅ 성공 많음 | 개선 |

"재시도만으로 성공률이 2배 가까이 올랐다!"

### 하지만 새로운 문제

Retry를 적용하고 나니 또 다른 문제가 생겼다.

**PG 서버가 완전히 죽었을 때:**

```
1차 시도 → 실패 (connection refused)
   ↓ 1초 대기
2차 시도 → 실패 (connection refused)
   ↓ 2초 대기
3차 시도 → 실패 (connection refused)
   ↓
총 소요 시간: 3초 + 6초(대기) = 9초
```

**"PG가 죽었는데도 9초씩 기다려? 다른 요청들은?"**

## "Circuit Breaker가 필요하다"

### Circuit Breaker 이해하기

Circuit Breaker는 **누전 차단기**처럼 동작한다.

| 상태 | 동작 | 조건 |
|------|------|------|
| **CLOSED** | 정상 요청 허용 | 실패율 < 50% |
| **OPEN** | 모든 요청 차단 | 실패율 ≥ 50% |
| **HALF-OPEN** | 일부만 시도 | 10초 후 재시도 |

**Circuit Breaker 동작 흐름:**

```
[CLOSED 상태]
요청 10개 중 6개 실패 (60%)
   ↓ 실패율 임계치 초과!

[OPEN 상태로 전환]
- 모든 요청 즉시 차단 (Fallback 실행)
- 10초간 대기
   ↓ 10초 경과

[HALF-OPEN 상태로 전환]
- 3개 요청만 허용
   ├─ 성공 → CLOSED로 복귀
   └─ 실패 → 다시 OPEN (10초 대기)
```

### Circuit Breaker 적용

**설정:**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      pgCircuit:
        sliding-window-size: 10           # 최근 10개 요청 기준
        failure-rate-threshold: 50        # 실패율 50% 초과 시
        wait-duration-in-open-state: 10s  # OPEN 상태 10초 유지
        slow-call-duration-threshold: 2s  # 2초 이상이면 느린 호출
        slow-call-rate-threshold: 50      # 느린 호출 50% 초과 시도 차단
```

```kotlin
@Retry(name = "pgRetry", fallbackMethod = "requestPgPaymentFallback")
@CircuitBreaker(name = "pgCircuit", fallbackMethod = "requestPgPaymentFallback")
fun requestPgPayment(payment: Payment): String {
    val response = pgClient.requestPayment(...)
    return response.transactionKey
}

private fun requestPgPaymentFallback(payment: Payment, throwable: Throwable): String {
    logger.error("PG 결제 요청 실패 (Fallback 실행): ${throwable.message}")
    throw CoreException(
        ErrorType.INTERNAL_ERROR,
        "결제 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요."
    )
}
```

### 극적인 효과

PG 서버를 강제로 다운시키고 테스트했다.

#### 📊 Circuit Breaker 전후 비교

**Circuit Breaker 없음 (AS-IS):**

```
요청 1 → PG 접속 시도 → 3초 타임아웃 → Retry 3회 → 총 9초 소요
요청 2 → PG 접속 시도 → 3초 타임아웃 → Retry 3회 → 총 9초 소요
요청 3 → PG 접속 시도 → 3초 타임아웃 → Retry 3회 → 총 9초 소요
...
```

| 항목 | 값 |
|------|-----|
| 각 요청 소요 시간 | ~9초 |
| 100개 요청 처리 시간 | ~15분 |
| 서버 부하 | 🔴 매우 높음 |

**Circuit Breaker 있음 (TO-BE):**

```
요청 1~5 → 실패 (각 9초)
   ↓ 실패율 100% → Circuit OPEN!

요청 6~100 → 즉시 Fallback (각 10ms)
   ↓
총 소요 시간: 45초 + 0.95초 = 약 46초
```

| 항목 | AS-IS | TO-BE | 개선율 |
|------|-------|-------|--------|
| 100개 요청 처리 시간 | ~15분 | **~46초** | **95% ↑** |
| 평균 응답 시간 | 9초 | **0.5초** | **94% ↑** |
| 서버 부하 | 매우 높음 | **정상** | 극적 개선 |

**"PG가 죽어도 우리 서비스는 살아있다!"**

처음 알았다. Circuit Breaker는 단순히 재시도를 막는 게 아니라, **장애가 전파되지 않도록 차단**하는 것이라는 걸.

## "콜백을 못 받으면 어떻게 하지?"

### 콜백 의존의 위험성

PG 결제는 비동기로 동작하기 때문에 **콜백에 의존**한다.

**정상 플로우:**

```
1. 결제 요청 → PENDING 응답 받음
2. DB에 Payment 저장 (status = PENDING)
3. 3초 후 PG 처리 완료
4. PG가 Callback 호출 → status = SUCCESS 업데이트
```

하지만 콜백이 안 오는 경우들:

| 상황 | 원인 | 결과 |
|------|------|------|
| 네트워크 장애 | PG → Our Server 연결 실패 | Callback 유실 |
| 서버 재시작 | Callback 수신 중 서버 다운 | Callback 유실 |
| PG 버그 | Callback 전송 실패 | Callback 유실 |
| 타임아웃 | 3단계에서 타임아웃 발생 | Callback URL 전달 안 됨 |

**"콜백이 안 오면 주문은 영원히 PENDING?"**

### 상태 확인 스케줄러

해결책은 **능동적으로 확인**하는 것이었다.

PG는 상태 조회 API를 제공한다:

```http
GET /api/v1/payments/{transactionKey}

→ Response: { "status": "SUCCESS", "reason": "정상 승인" }
```

스케줄러를 만들었다:

```kotlin
@Component
class PaymentStatusScheduler(
    private val paymentService: PaymentService
) {
    /**
     * 10분 이상 PENDING 상태인 결제 건들을 확인하고 동기화
     * 5분마다 실행
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    fun checkPendingPayments() {
        // 1. 10분 이상 PENDING인 결제 조회
        val pendingPayments = paymentRepository
            .findPendingPaymentsOlderThan(minutes = 10)

        pendingPayments.forEach { payment ->
            if (payment.transactionKey != null) {
                try {
                    // 2. PG에서 실제 상태 확인
                    val status = paymentService.checkPaymentStatus(
                        userId = payment.userId,
                        transactionKey = payment.transactionKey
                    )

                    // 3. 상태 동기화
                    when (status) {
                        SUCCESS -> payment.complete()
                        FAILED -> payment.fail("PG 결제 실패")
                        PENDING -> // 계속 대기
                    }
                } catch (e: Exception) {
                    // 4. 확인 실패 시 타임아웃 처리
                    payment.timeout()
                }
            }
        }
    }
}
```

**상태 복구 프로세스:**

```
[23:00] 결제 요청 → PENDING
[23:03] Callback 유실 (네트워크 오류)
   ↓
[23:05] 스케줄러 실행
   - 아직 5분 안 됨, Skip
   ↓
[23:10] 스케줄러 실행
   - 10분 이상 PENDING 발견!
   - PG에 상태 조회 → SUCCESS 확인
   - DB 업데이트 → SUCCESS
   ↓
[23:10] 고객에게 정상 처리 안내 가능!
```

### 트레이드오프

| 측면 | 장점 | 단점 |
|------|------|------|
| 데이터 정합성 | ✅ 최대 10분 내 복구 | ⏱️ 실시간 아님 |
| 시스템 안정성 | ✅ Callback 유실 대응 | 🔧 스케줄러 관리 필요 |
| PG 부하 | ⚠️ 주기적 상태 조회 | - |

**"실시간이어야 하는가?"**를 먼저 고민해야 한다.

결제는 5-10분 지연되어도 괜찮다. 하지만 **영원히 PENDING**인 건 안 된다.

## "주문 생성과 결제는 어떻게 엮지?"

### 트랜잭션 경계의 딜레마

처음엔 간단하게 생각했다:

```kotlin
@Transactional
fun createOrder(request: OrderCreateRequest) {
    val order = orderService.createOrder(...)
    stockService.deductStock(...)

    // 카드 결제 요청
    paymentService.requestCardPayment(...)

    // 모두 성공하면 커밋!
}
```

하지만 문제가 있었다:

| 시나리오 | 문제점 |
|----------|--------|
| PG 요청 중 타임아웃 | 트랜잭션 롤백? → 주문도 없어짐 |
| PG는 성공, 우리는 타임아웃 | 롤백? → 결제만 됨 (이중 결제 위험) |
| 주문 생성 후 PG 실패 | 롤백? → 재고는? |

**"결제 실패하면 주문을 무조건 롤백해야 할까?"**

### 선택: 비동기 결제 모델

결정했다. **주문과 결제를 분리**한다.

**개선된 플로우:**

```kotlin
@Transactional
fun createOrder(request: OrderCreateRequest): OrderCreateInfo {
    when (paymentMethod) {
        POINT -> {
            // 포인트: 동기 처리 (즉시 확인 가능)
            pointService.validateUserPoint(userId, amount)
            val order = orderService.createOrder(...)
            stockService.deductStock(...)
            pointService.deductPoint(userId, amount)
            return OrderCreateInfo.from(order)
        }

        CARD -> {
            // 카드: 비동기 처리 (나중에 확인)
            stockService.deductStock(...)  // 재고는 먼저 차감
            val order = orderService.createOrder(...)

            try {
                paymentService.requestCardPayment(...)
                // 성공: PENDING 상태로 주문 유지
            } catch (e: Exception) {
                // 실패: 재고 복구 + 예외 던지기
                stockService.increaseStock(...)
                throw CoreException("결제 처리 실패")
            }

            return OrderCreateInfo.from(order)
        }
    }
}
```

**상태 전이:**

```
[주문 생성]
   ↓
Order: PENDING
Payment: PENDING
   ↓
[PG 결제 처리 중...]
   ↓
   ├─ SUCCESS → Order: CONFIRMED, Payment: COMPLETED
   ├─ FAILED → Order: PENDING (고객이 다시 시도 가능)
   └─ TIMEOUT → 스케줄러가 나중에 확인
```

**핵심 원칙:**

| 상황 | 처리 방식 | 이유 |
|------|-----------|------|
| 포인트 부족 | 주문 생성 전 검증 | 즉시 확인 가능 |
| 재고 부족 | 주문 생성 전 검증 | 즉시 확인 가능 |
| **PG 실패** | 주문 생성 후 처리 | 나중에 재시도 가능 |
| **PG 타임아웃** | 주문 PENDING 유지 | 스케줄러가 확인 |

**"실패는 롤백이 아니라, 복구 가능하게"**

## 결과: 장애에 강한 시스템

### 전체 Resilience 패턴 적용

최종적으로 적용한 패턴들:

#### 📊 적용된 패턴 요약

| 패턴 | 설정 | 효과 |
|------|------|------|
| **Timeout** | 연결 1초, 응답 3초 | 무한 대기 방지 |
| **Retry** | 최대 3회, 지수 백오프 | 일시적 오류 극복 |
| **Circuit Breaker** | 실패율 50% 초과 시 차단 | 장애 전파 차단 |
| **Fallback** | 사용자 친화적 메시지 | UX 개선 |
| **Scheduler** | 5분마다 상태 확인 | 데이터 정합성 |

**장애 시나리오별 대응:**

| 시나리오 | 기존 | 개선 후 |
|----------|------|---------|
| PG 응답 3초 지연 | ⏱️ 무한 대기 | ✅ Timeout → 실패 처리 |
| 네트워크 일시 장애 | 🔴 즉시 실패 | ✅ Retry 3회 → 성공 |
| PG 서버 완전 다운 | 😱 전체 시스템 마비 | ✅ Circuit Open → Fallback |
| Callback 유실 | 💀 영원히 PENDING | ✅ Scheduler → 상태 복구 |
| PG는 성공, 우리는 타임아웃 | 😨 이중 결제 위험 | ✅ Scheduler → 정상 처리 |

### 성능 영향

Resilience 패턴 적용 전후:

**정상 상황:**

| 항목 | 적용 전 | 적용 후 | 변화 |
|------|---------|---------|------|
| 평균 응답 시간 | ~500ms | ~600ms | +100ms |
| 성공률 | 60% | **85%** | +25%p |
| 서버 부하 | 정상 | 정상 | 동일 |

**PG 장애 상황:**

| 항목 | 적용 전 | 적용 후 | 개선율 |
|------|---------|---------|--------|
| 서버 생존 | 🔴 멈춤 (30초) | ✅ **정상 동작** | - |
| 평균 응답 시간 | ~15초 | **~500ms** | **97% ↑** |
| 다른 API 영향 | 🔴 전체 마비 | ✅ **영향 없음** | - |

**"PG 장애가 와도 우리 서비스는 멈추지 않는다"**

## 배운 것들

### 1. 외부 시스템은 믿을 수 없다

처음엔 "PG API 호출하면 되겠지"라고 생각했다.

하지만 외부 시스템은:

| 가정 | 현실 |
|------|------|
| 항상 빠르게 응답한다 | ⚠️ 느릴 수 있다 |
| 항상 성공한다 | ⚠️ 실패할 수 있다 |
| 장애가 없다 | ⚠️ 장애가 발생한다 |
| Callback이 온다 | ⚠️ 안 올 수 있다 |

**핵심 원칙: "외부 시스템은 항상 실패할 수 있다고 가정하라"**

### 2. Timeout은 필수, Retry는 선택, Circuit Breaker는 생존

| 패턴 | 목적 | 우선순위 |
|------|------|---------|
| **Timeout** | 무한 대기 방지 | 🔥🔥🔥 필수 |
| **Retry** | 일시적 오류 극복 | ⭐⭐ 권장 |
| **Circuit Breaker** | 장애 전파 차단 | ⭐⭐⭐ 매우 중요 |

Timeout 없이는 서버가 멈춘다.
Retry 없이는 성공률이 낮다.
Circuit Breaker 없이는 **장애가 전파된다**.

### 3. 재시도 횟수의 트레이드오프

"재시도는 많을수록 좋다"고 생각했다.

하지만:

| 재시도 횟수 | 장점 | 단점 |
|------------|------|------|
| 1회 | 빠른 실패 | 성공률 낮음 |
| 3회 | **균형적** | - |
| 10회 | 성공률 높음 | 너무 느림, PG 부하 |

현재 설정: **3회 + 지수 백오프**

```
1차: 즉시
2차: 1초 후
3차: 2초 후
→ 총 3초 안에 판단
```

### 4. Fallback은 마지막 방어선

Circuit Breaker가 열리면 **Fallback**이 실행된다.

처음엔 "그냥 에러 던지면 되지"라고 생각했다.

**잘못된 Fallback:**

```kotlin
fun fallback(throwable: Throwable): String {
    throw Exception("PG Error") // 🔴 의미 없음
}
```

**올바른 Fallback:**

```kotlin
fun fallback(throwable: Throwable): String {
    logger.error("PG 장애 감지: ${throwable.message}")

    // 사용자에게 명확한 안내
    throw CoreException(
        ErrorType.INTERNAL_ERROR,
        "결제 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요."
    )
}
```

| 항목 | 나쁜 Fallback | 좋은 Fallback |
|------|--------------|--------------|
| 메시지 | "Error" | "결제 서비스 일시 불안정" |
| 로깅 | 없음 | ✅ 장애 감지 로그 |
| 모니터링 | 불가능 | ✅ 알림 발송 가능 |
| UX | 🔴 나쁨 | ✅ 사용자 이해 가능 |

### 5. 동기 vs 비동기의 선택

포인트 결제는 **동기**, 카드 결제는 **비동기**로 구현했다.

| 결제 수단 | 방식 | 이유 |
|----------|------|------|
| 포인트 | 동기 | 즉시 확인 가능, 실패 시 롤백 명확 |
| 카드 (PG) | **비동기** | 외부 시스템, 지연 가능, 재시도 필요 |

**비동기 선택 이유:**

```
동기 처리의 문제:
- PG 지연 → 트랜잭션 길어짐 → 락 경합
- PG 실패 → 롤백? → 재시도 불가능
- PG 타임아웃 → 상태 불명확

비동기 처리의 장점:
- 주문은 빠르게 생성
- PG 실패해도 재시도 가능
- 상태는 나중에 확인
```

### 6. 스케줄러의 중요성

Callback을 못 받는 경우를 대비해 **스케줄러**를 만들었다.

처음엔 "Callback만 믿으면 되지 않나?"라고 생각했다.

하지만 실제로:

| 상황 | 발생 빈도 | 영향 |
|------|----------|------|
| Callback 정상 수신 | 95% | ✅ 정상 |
| Callback 유실 | 3% | 🔴 PENDING 고착 |
| Callback 지연 | 2% | ⚠️ 고객 불안 |

**스케줄러가 없으면:**
- 5%의 주문이 영원히 PENDING
- 고객 문의 폭주
- 수동으로 DB 수정

**스케줄러가 있으면:**
- 최대 10분 내 자동 복구
- 고객 만족도 유지
- 운영 부담 감소

## 한계와 개선 방향

### 스케줄러 동기화 지연

현재는 **최대 10분 지연**이 있다.

**동기화 타임라인:**

```
23:00 - 결제 요청
23:03 - Callback 유실
23:05 - 스케줄러 실행 (Skip, 5분 안 됨)
23:10 - 스케줄러 실행 → 상태 확인 → 복구
```

| 영향 | 대응 방안 |
|------|-----------|
| 고객 불안 | 결제 상태 페이지 제공 |
| 10분 대기 | "처리 중" 안내 메시지 |
| 즉시 확인 요청 | 수동 상태 확인 API 제공 |

### PG 부하 증가

스케줄러가 **모든 PENDING 건을 조회**한다.

| PENDING 건수 | PG 조회 횟수/5분 | 부하 |
|-------------|-----------------|------|
| 10건 | 10회 | ✅ 낮음 |
| 100건 | 100회 | ⚠️ 보통 |
| 1000건 | 1000회 | 🔴 높음 |

**개선 방안:**
- Batch API 사용 (여러 건 한번에 조회)
- 조회 간격 조정 (5분 → 10분)
- 우선순위 큐 (금액 큰 건부터)

### Circuit Breaker 튜닝

현재 설정은 "추측"이다:

| 설정 | 값 | 근거 |
|------|-----|------|
| 실패율 임계치 | 50% | ❓ "절반 넘으면 문제겠지" |
| Window Size | 10 | ❓ "10개면 충분하겠지" |
| Wait Duration | 10초 | ❓ "10초면 복구되지 않을까" |

**실제 운영 환경에서는:**
- APM 도구로 실패율 모니터링
- A/B 테스트로 최적값 찾기
- PG 특성에 맞게 조정

## 다음에 시도해보고 싶은 것

### 1. 이벤트 기반 아키텍처

현재는 동기 호출이지만, **이벤트로 분리**하면:

```
[Order Service]
   ↓ 주문 생성
   ↓ 이벤트 발행: OrderCreated

[Payment Service]
   ↓ 이벤트 구독
   ↓ 비동기 결제 처리
   ↓ 이벤트 발행: PaymentCompleted

[Order Service]
   ↓ 이벤트 구독
   ↓ 주문 상태 업데이트
```

**장점:**
- 서비스 간 결합도 감소
- 재시도 자동화 (메시지 큐)
- 확장성 향상

### 2. Saga 패턴

결제 실패 시 **보상 트랜잭션** 자동화:

```
1. 주문 생성 → 성공
2. 재고 차감 → 성공
3. 결제 요청 → 실패
   ↓
[Saga Orchestrator]
   ↓ 보상 트랜잭션 시작
   ↓ 재고 복구
   ↓ 주문 취소
```

### 3. Observability 강화

현재는 로그만 있지만:

| 도구 | 목적 |
|------|------|
| Prometheus | Circuit Breaker 상태 모니터링 |
| Grafana | 실패율, 응답 시간 대시보드 |
| Jaeger | 분산 트레이싱 |
| Sentry | 에러 추적 및 알림 |

**알고 싶은 것:**
- Circuit Breaker가 열리는 빈도
- Retry 성공률
- PG 응답 시간 분포
- Fallback 실행 횟수

## 마치며

### "그냥 HTTP 요청 하나 보내면 되겠지"

처음엔 간단해 보였다. PG API 문서를 보고 POST 요청만 보내면 될 것 같았다.

하지만 외부 시스템 연동은 **완전히 다른 세계**였다.

**배운 핵심:**

| 착각 | 현실 |
|------|------|
| 외부 시스템은 안정적이다 | 항상 실패할 수 있다 |
| Timeout만 걸면 된다 | Retry, Circuit Breaker 필요 |
| 동기 처리가 간단하다 | 비동기가 더 안전할 수 있다 |
| Callback만 믿으면 된다 | 능동적 확인이 필요하다 |

**"외부 시스템과의 연동은 설계부터 다르다"**

Round 5에서 "빠르게 돌아간다"를 배웠다면, Round 6에서는 **"장애에도 멈추지 않는다"**를 배웠다.

### 가장 중요한 깨달음

**"실패는 예외가 아니라 정상이다"**

이 말의 의미를 이제야 알았다.

- Timeout은 **발생할 것**이다
- Retry는 **필요할 것**이다
- Circuit Breaker는 **열릴 것**이다
- Callback은 **유실될 것**이다

중요한 건 **이 모든 상황에서도 시스템이 멈추지 않는 것**이다.

### 다음은

이제 기본적인 외부 시스템 연동은 안전하게 작동한다.

하지만 여전히 궁금한 게 많다:

**다음 단계:**
- **분산 트랜잭션**: Saga, 2PC, TCC
- **메시지 큐**: 이벤트 기반 아키텍처
- **모니터링**: APM, 알림, 대시보드
- **카나리 배포**: 점진적 트래픽 전환

"결제 서비스가 불안정합니다"라는 알림에서 시작해서,
Timeout, Retry, Circuit Breaker, Fallback, Scheduler까지 배웠다.

장애 대응은 이제 시작일 뿐이다. 🚀
