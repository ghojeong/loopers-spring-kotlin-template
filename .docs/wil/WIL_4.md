# Weekly I Learned 4 (트랜잭션과 동시성)

## 수습과제가 터질 뻔했다

이번 주는 **트랜잭션**을 제대로 배웠다. 그리고 배우길 진짜 잘했다. 안 배웠으면 1차 수습평가에서 광탈했을 것 같다.

## 문제: 전체 회원 데이터 정합성 체크 배치

수습과제로 "고객/회원 정합성 체크" 배치를 구현해야 했다.
- 전체 회원 데이터를 조회해서
- KYC 상태와 실제 인증 데이터가 일치하는지 검증하고
- 불일치하면 별도 테이블에 기록

문제는 **우리 서비스 회원이 수십만 명**이라는 것. 이걸 잘못 짜면:

1. **실서비스 DB가 터진다** - 배치가 무거운 쿼리를 날리면 실사용자 요청이 느려짐
2. **락으로 인한 병목** - 읽기 작업인데도 불필요한 락이 걸리면 쓰기 트랜잭션이 대기
3. **메모리 부족** - 전체 데이터를 한 번에 메모리에 올리면 OOM(Out Of Memory)

처음엔 "그냥 SELECT 한 번 날리면 되는 거 아냐?" 싶었는데... 생각보다 함정이 많았다.

## 트랜잭션 공부가 생명줄이 되다

멘토님이 "트랜잭션 격리 수준과 JDBC 옵션을 잘 활용하면 영향을 최소화할 수 있다"고 조언해주셨다.

### 1. READ_ONLY 트랜잭션으로 락 방지

```kotlin
@Transactional(readOnly = true)
fun checkConsistency() {
    // 전체 회원 조회 및 검증
}
```

**왜 중요했나?**
- `readOnly = true`를 명시하면 **DB에 쓰기 락을 걸지 않음**
- 읽기 전용이라는 걸 명시해서 DB 옵티마이저가 더 효율적으로 처리
- 실서비스 쓰기 트랜잭션에 영향을 주지 않음

### 2. JDBC TYPE_FORWARD_ONLY로 스트리밍 처리

```kotlin
entityManager.createQuery("SELECT c FROM Customer c")
    .setHint(QueryHints.HINT_FETCH_SIZE, 1000)
    .setHint(QueryHints.HINT_READONLY, true)
    .resultStream
    .forEach { customer ->
        // 한 건씩 처리
    }
```

**왜 중요했나?**
- `TYPE_FORWARD_ONLY`는 커서를 순방향으로만 이동 (뒤로 못 감)
- DB에서 전체 데이터를 한 번에 가져오지 않고, **조금씩 스트리밍**으로 받음
- 메모리에 올라가는 데이터가 제한되어 OOM 방지
- DB 부하도 분산됨

### 3. HikariCP 설정 튜닝

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 5  # 배치는 적은 커넥션으로
      connection-timeout: 3000
      validation-timeout: 2000
```

배치는 **동시성이 필요 없으니** 커넥션 풀을 최소화해서 실서비스 커넥션을 보호했다.

## 결과: 1차 수습평가 통과!

이렇게 설정하고 나니:
- 배치가 돌아도 실서비스 응답 속도에 영향 없음
- DB CPU 사용률도 안정적으로 유지
- 메모리도 일정 수준 이하로 유지

멘토님이 "신입이 트랜잭션까지 고려해서 구현한 건 처음 봤다"고 칭찬해주셨고, **1차 수습평가를 무사히 통과**할 수 있었다.

## 이번 주 배운 핵심

- **트랜잭션 격리 수준**: READ_COMMITTED vs REPEATABLE_READ 차이 이해
- **@Transactional 제대로 쓰기**: readOnly, isolation, propagation 속성의 의미
- **JDBC 옵션 활용**: TYPE_FORWARD_ONLY, FETCH_SIZE로 스트리밍 처리
- **HikariCP 튜닝**: 커넥션 풀 설정이 성능에 미치는 영향

트랜잭션은 "그냥 @Transactional 붙이면 되는 거 아냐?"가 아니라, **어떻게 써야 실서비스를 지킬 수 있는가**가 핵심이었다. 다음 주에는 읽기 성능 최적화를 해볼 예정!
