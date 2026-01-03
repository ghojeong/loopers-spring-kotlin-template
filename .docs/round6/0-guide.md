# Failure-Ready Systems

외부 시스템(PG) 연동 과정에서 발생할 수 있는 장애와 지연에 대응하기 위해 **타임아웃, 재시도, 서킷 브레이커와 폴백 처리** 등 다양한 회복 전략을 적용합니다.

## Summary

- 외부 시스템(PG) 연동 시 발생할 수 있는 지연, 장애, 실패에 대응합니다.
- 타임아웃, 재시도, 서킷 브레이커, 폴백 전략을 통해 회복력 있는 구조를 학습합니다.
- 장애가 전체 시스템에 전파되지 않도록 보호 설계를 실습합니다.

## Keywords

- Circuit Breaker
- Timeout & Retry
- Fallback 처리
- 외부 시스템 연동

## 실무에서 겪는 장애 전파 문제

- 외부 시스템과의 연동은 대부분의 실무 서비스에서 필수적이며 특히 이는 단순히 서버간 요청 뿐만 아니라 DB, Redis 와 같은 외부 인프라도 마찬가지예요.
- 예를 들면 PG 서버가 일시적으로 느려지거나, 아예 응답을 주지 않는 상황이 종종 발생합니다. 이때 클라이언트가 끝까지 기다리면, 해당 요청은 스레드를 점유한 채 대기 상태로 남게 됩니다.
- 이런 요청이 수십~수백 개 쌓이면, 애플리케이션 전체가 마비될 수 있습니다.

---

## Timeout

외부 시스템의 응답 지연을 제어하고, 전체 시스템의 자원을 보호하기 위한 가장 기본적인 전략입니다.

- **요청이 일정 시간 내에 응답하지 않으면 실패로 간주하고 종료**합니다.
- 타임아웃이 없다면, 외부 시스템 하나의 장애가 전체 시스템으로 전파됩니다.
- 대부분의 실무 장애는 **실패보다는 지연**에서 시작됩니다.

### 실무에서 겪는 문제

외부 시스템(PG 등)이 응답을 지연시키거나 멈추는 경우, 요청을 끝까지 기다리면 스레드나 커넥션이 점유된 채로 대기하게 됩니다.

이런 요청이 누적되면 전체 시스템이 느려지거나 멈추게 되며, 장애가 외부에서 시작됐더라도 결국 내부 시스템 전체로 확산됩니다.

## 실전 설정 예시

### Http 요청 (Feign Client)

```java
@Configuration
public class FeignClientTimeoutConfig {
    @Bean
    public Request.Options feignOptions() {
        return new Request.Options(1000, 3000); // 연결/응답 타임아웃 (ms)
    }
}

@FeignClient(
    name = "pgClient",
    url = "https://pg.example.com",
    configuration = FeignClientTimeoutConfig.class
)
public interface PgClient {
    @PostMapping("/pay")
    PaymentResponse requestPayment(@RequestBody PaymentRequest request);
}
```

### JPA (HikariCP)

```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 3000       # 커넥션 풀에서 커넥션 얻는 최대 대기 시간
      validation-timeout: 2000       # 커넥션 유효성 검사 제한 시간
```

### Redis (Lettuce 기반)

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 3000                 # 명령 실행 제한 시간
```

### 실무 TIPs

- **Feign**: connectTimeout과 readTimeout을 명확히 나눠 설정하세요.
- **JPA**: 커넥션 풀에서 대기 없이 바로 실패하도록 `connection-timeout`은 필수입니다.
- **Redis**: Lettuce의 `commandTimeout`을 걸지 않으면 무기한 대기할 수 있습니다.
- 보통 타임아웃은 2~5초 사이로 잡으며, 지연 허용 범위는 기능 특성과 요청 수에 따라 조절합니다.

---

## Retry

Retry는 일시적인 장애 상황에서 재시도를 통해 정상 응답을 받아내는 회복 전략입니다. 특히 네트워크 연결 실패, 서버 과부하 등 **일시적 실패(transient fault)** 에 매우 효과적입니다.

- 너무 잦은 재시도는 서버에 부하를 주거나 **DoS 공격처럼 동작할 수 있습니다.**
- 반드시 재시도 간 **대기 시간(backoff)** 을 설정해야 하며, **최대 시도 횟수 제한**도 중요합니다.
- 타임아웃과 조합해서 **최대 몇 초 안에 몇 번까지만** 이라는 제어가 필요합니다.
- 끝내 재시도 요청이 실패했을 경우, `fallback` 로직으로 이어질 수 있도록 하는 처리 또한 고려해야 합니다.

### 실무에서 겪는 문제

PG 서버가 일시적으로 503 에러를 반환하거나 네트워크에서 패킷 손실이 발생하는 경우, 실패한 요청을 즉시 종료하는 것보다는 **일정 횟수 재시도**만으로도 정상 처리가 가능한 경우가 많습니다.

하지만 별도 설정 없이 무작정 재요청을 반복하거나, 예외 상황을 고려하지 않은 채 재시도하면 오히려 시스템에 더 큰 부하를 유발할 수 있습니다.

### Resilience4j Retry

Resilience4j는 **Retry, CircuitBreaker, TimeLimiter 등** 여러 회복 전략을 조합할 수 있는 라이브러리이며 실무에서 가장 범용적으로 활용되고 있는 오픈소스 라이브러리입니다. 본 과정에서는 **Resilience4j 기반**으로 설명을 진행합니다.

[Getting Started](https://resilience4j.readme.io/docs/getting-started-3)

### 실전 설정 예시 - Resilience4j with Spring Boot

#### **Gradle 의존성 설정 (non-reactive stack)**

```gradle
dependencies {
  implementation "io.github.resilience4j:resilience4j-spring-boot3"
  implementation "org.springframework.boot:spring-boot-starter-aop"
}
```

#### application.yml 설정

```yaml
resilience4j:
  retry:
    instances:
      pgRetry:
        max-attempts: 3
        wait-duration: 1s
        retry-exceptions:
          - feign.RetryableException
        fail-after-max-attempts: true
```

#### Retry 적용

```java
@Retry(name = "pgRetry", fallbackMethod = "fallback")
public PaymentResponse requestPayment(PaymentRequest request) {
    return pgClient.requestPayment(request);
}

public PaymentResponse fallback(PaymentRequest request, Throwable t) {
    return new PaymentResponse("결제 대기 상태", false);
}
```

### 실무 TIPs

- `fail-after-max-attempts`를 true로 설정하면, 재시도 실패 시 바로 fallback을 실행할 수 있습니다.
- 재시도할 예외는 반드시 명시해야 합니다. (`RetryableException`, `SocketTimeoutException` 등)
- retry 간 간격은 **wait-duration** 으로 제어하며, **random backoff** 또는 **exponential backoff** 전략도 지원됩니다.

---

## Circuit Breaker

Circuit Breaker는 외부 시스템이 반복적으로 실패하면 **일시적으로 회로를 열어 호출을 차단**하는 전략입니다.

마치 누전 차단기처럼, 계속해서 실패하는 요청을 끊고 전체 시스템을 보호합니다.

다음 상태들을 이해하고 있어야 해요.

- **Closed** – 정상 상태, 호출 가능
- **Open** – 실패율이 기준치를 넘으면 차단
- **Half-Open** – 일정 시간 후 일부만 호출 시도 → 성공 시 Close, 실패 시 다시 Open

### 실무에서 겪는 문제들

외부 시스템(PG 등)이 완전히 죽었을 때, 모든 요청이 계속해서 실패하며 애플리케이션 로그가 뒤덮이고, 불필요한 재시도와 에러가 대량으로 발생합니다. 

결과적으로 **CPU 사용률이 급등**하거나, **전체 서비스의 반응 속도가 저하**되는 현상이 발생합니다.

이때 필요한 것이 **"더 이상 호출하지 않도록" 차단하는 장치**, 즉 Circuit Breaker입니다.

### 실전 설정 예시 - Resilience4j with Spring Boot

#### application.yml 설정

```yaml
resilience4j:
  circuitbreaker:
    instances:
      pgCircuit:
        sliding-window-size: 10
        failure-rate-threshold: 50       # 실패율이 50% 넘으면 Open
        wait-duration-in-open-state: 10s # Open 상태 유지 시간
        permitted-number-of-calls-in-half-open-state: 2
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 50
```

#### CircuitBreaker 적용

```java
@CircuitBreaker(name = "pgCircuit", fallbackMethod = "fallback")
public PaymentResponse requestPayment(PaymentRequest request) {
    return pgClient.requestPayment(request);
}

public PaymentResponse fallback(PaymentRequest request, Throwable t) {
    return new PaymentResponse("결제 대기 상태", false);
}
```

### 실무 TIPs

- Circuit Breaker는 **정상/실패 여부만 판단**하는 게 아니라, **느린 응답도 실패로 간주**할 수 있습니다.
  - 이를 위해 `slow-call-duration-threshold` 와 `slow-call-rate-threshold` 설정이 매우 중요합니다.
- Half-Open 상태에서 몇 개의 요청만 통과시키고, 그 결과에 따라 다시 회로를 닫거나 유지합니다.
- Circuit Breaker는 **Retry와 함께 사용**해야 하면 더 강력하게 활용할 수 있습니다.
  - Retry가 실패를 일정 횟수 누적
  - Circuit Breaker가 **이제는 아예 보내지 말자** 를 결정합니다.
- `fallbackMethod` 를 활용해 **현재 시스템에서 가능한 대응**을 정의해두는 것이 UX와 장애 확산 방지 측면에서 중요합니다.
