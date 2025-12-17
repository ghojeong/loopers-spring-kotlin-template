# Weekly I Learned 6 (장애 대응 시스템)

## 뉴스에 나온 7분간의 장애

이번 주는... 정말 아찔한 한 주였다. 신규 인프라 마이그레이션 작업 중 **방화벽 설정 하나 놓쳐서 전사 서비스가 7분간 마비**됐다.

뉴스에도 나올 정도로 큰 장애였고, CTO님이 직접 "재발 방지 대책 보고서 작성하라"고 지시하셨다. 멘붕 그 자체.

## 무엇이 문제였나?

### 장애 시나리오

1. 신규 DB 인프라로 전환하는 작업 진행
2. 배포 후 신규 인프라 연결 시도
3. **방화벽 설정 누락**으로 연결 실패
4. 애플리케이션이 무한 대기 상태에 빠짐 (타임아웃 설정 없음)
5. 모든 스레드가 대기 상태로 막혀버림
6. **전체 서비스 응답 불가 → 장애 발생**

### 왜 이렇게 심각했나?

신규 인프라 연결에 실패했을 때, **기존 인프라로 자동 전환되는 로직이 없었다**.
- 신규 인프라 연결 실패 = 전체 장애
- 기존 인프라는 멀쩡히 살아있었는데도 사용 못함
- **단일 장애점(Single Point of Failure)** 그 자체

## CTO님의 한마디: "Circuit Breaker와 Fallback이 있었다면?"

회의 시간에 CTO님이 물으셨다.

> "Circuit Breaker와 Fallback 로직이 있었다면 어땠을까요?"

그 순간 머리를 한 대 맞은 기분이었다. 공부만 하고 실무에 적용 안 한 게 이렇게 큰 장애로 돌아올 줄이야.

## 개선 방안: Circuit Breaker + Fallback

### 1. Resilience4j Circuit Breaker 적용

```kotlin
// application.yml
resilience4j:
  circuitbreaker:
    instances:
      newInfraCircuit:
        sliding-window-size: 10
        failure-rate-threshold: 50  # 실패율 50% 넘으면 Open
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 2
```

```kotlin
@CircuitBreaker(name = "newInfraCircuit", fallbackMethod = "fallbackToOldInfra")
fun connectToNewInfra(): Connection {
    return newInfraClient.connect()
}

fun fallbackToOldInfra(ex: Throwable): Connection {
    logger.warn("신규 인프라 연결 실패, 기존 인프라로 전환", ex)
    return oldInfraClient.connect()
}
```

**효과:**
- 신규 인프라 연결이 반복 실패하면 **Circuit이 Open**됨
- Open 상태에서는 **즉시 Fallback**으로 전환 (대기 없음)
- 10초 후 Half-Open으로 전환해서 복구 시도

### 2. Timeout 설정

```kotlin
@FeignClient(
    name = "newInfraClient",
    configuration = [TimeoutConfig::class]
)
interface NewInfraClient {
    @PostMapping("/connect")
    fun connect(): Connection
}

@Configuration
class TimeoutConfig {
    @Bean
    fun feignOptions() = Request.Options(1000, 3000)  // 연결 1초, 응답 3초
}
```

**효과:**
- 무한 대기 방지
- 3초 안에 응답 없으면 바로 Fallback

### 3. Health Check 추가

```kotlin
@Scheduled(fixedDelay = 5000)
fun checkInfraHealth() {
    try {
        newInfraClient.healthCheck()
        circuitBreakerRegistry.circuitBreaker("newInfraCircuit").transitionToClosedState()
    } catch (e: Exception) {
        logger.warn("신규 인프라 Health Check 실패")
    }
}
```

**효과:**
- 정기적으로 신규 인프라 상태 확인
- 복구되면 자동으로 Circuit을 Close로 전환

## 만약 이게 있었다면?

재발 방지 보고서에 다음과 같이 작성했다:

> "Circuit Breaker가 있었다면:
> - 신규 인프라 연결 실패 시 **10초 내 자동으로 기존 인프라로 전환**
> - **전체 장애가 아닌 부분 장애**로 국한
> - **7분 장애 → 최대 30초 일시 지연**으로 축소 가능"

CTO님이 고개를 끄덕이셨고, 전사 인프라팀에 Circuit Breaker 패턴 도입이 결정됐다.

## 이번 주 배운 핵심

- **Timeout**: 무한 대기는 재앙이다. 모든 외부 연동에 타임아웃 필수
- **Circuit Breaker**: 실패가 반복되면 회로를 열어서 시스템 보호
- **Fallback**: 실패했을 때 대안을 준비해두기
- **Graceful Degradation**: 완벽하게 동작 못하더라도, 최소한 핵심 기능은 유지

장애는 누구에게나 올 수 있다. 중요한 건 **장애를 어떻게 국소화하고, 빠르게 복구하느냐**였다. 다음 주에는 이벤트 기반 아키텍처로 시스템 결합도를 낮춰볼 예정!
