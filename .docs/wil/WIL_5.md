# Weekly I Learned 5 (읽기 성능 최적화)

## 티케팅 트래픽이 1.7만 RPS라고?

이번 주는 **읽기 성능 최적화**를 배웠다. 그리고 배우자마자 바로 실전 투입됐다. 바로 **SBS 가요대전 방청권 티케팅 서비스**.

요구사항은 간단했다:
- "RPS(초당 요청 수) 1.7만을 버틸 수 있도록 만들어라"

...간단? **미친 거 아냐?**

일반적인 API가 RPS 100~200 정도 처리하는데, 1.7만이라니. 이건 DB 직접 조회로는 절대 못 버틴다. Redis 캐시 없이는 불가능한 수준이었다.

## 문제 1: 중복 티케팅 방지

방청권은 **1인 1매**만 신청 가능해야 했다. 휴대폰 본인인증을 통해 중복 체크를 해야 하는데, 문제는:

- DB에서 매번 조회하면 느림 (RPS 1.7만 감당 불가)
- 동시에 같은 사람이 여러 번 신청하면? (Race Condition)

### 해결: Redis SETNX 연산

```kotlin
val key = "ticket:apply:$phoneNumber"
val success = redisTemplate.opsForValue().setIfAbsent(key, "applied", Duration.ofMinutes(30))

if (!success) {
    throw AlreadyAppliedException("이미 신청한 번호입니다")
}
```

**왜 효과적이었나?**
- `SETNX`(Set If Not Exists)는 **원자적 연산** → 동시 요청에도 정확히 1번만 성공
- Redis는 인메모리라서 조회 속도가 DB의 100배 이상
- TTL(30분)을 걸어두면 자동으로 만료되어 메모리 관리도 용이

## 문제 2: DB 쓰기 부하

신청이 성공하면 DB에 기록해야 하는데, 1.7만 RPS가 전부 DB INSERT를 때리면 DB가 터진다.

### 해결: Write-Behind (Write-Back) 패턴

```kotlin
// 1. 일단 Redis에만 기록 (빠름)
redisTemplate.opsForList().rightPush("ticket:queue", applyRequest)

// 2. 별도 스케줄러가 주기적으로 배치 INSERT
@Scheduled(fixedDelay = 5000)
fun flushToDatabase() {
    val requests = redisTemplate.opsForList().range("ticket:queue", 0, 1000)
    ticketRepository.saveAll(requests)
    redisTemplate.opsForList().trim("ticket:queue", requests.size, -1)
}
```

**왜 효과적이었나?**
- 실시간 요청은 Redis에만 쓰기 → **응답 속도 폭발적으로 빠름**
- DB는 배치로 몰아서 INSERT → **트랜잭션 부하 감소**
- 만약 Redis가 날아가도 복구 가능하도록 주기를 5초로 짧게 설정

## 문제 3: 조회 트래픽 (잔여 좌석 수)

사용자들이 "남은 좌석이 얼마나 있나요?" 페이지를 계속 새로고침한다. 이것도 DB 직접 조회하면 부하가 심함.

### 해결: 캐시 + TTL 전략

```kotlin
@Cacheable(cacheNames = ["remainingSeats"], key = "'seats'")
fun getRemainingSeats(): Int {
    return ticketRepository.countRemaining()
}

// Redis 설정
spring.cache.redis.time-to-live: 10s  # 10초마다 갱신
```

**왜 효과적이었나?**
- 10초 동안은 **같은 결과를 재사용** → DB 부하 1/100로 감소
- 10초 정도 실시간성 희생은 UX에 큰 영향 없음
- 실제로 좌석이 빠르게 소진되는 구간에서는 5초로 단축

## 결과: 안정적으로 티케팅 완료

실제 오픈 날:
- **피크 RPS 약 1.5만** 달성 (목표 1.7만 근접)
- **평균 응답 속도 50ms 이하** 유지
- **DB CPU 사용률 30% 미만** 안정적
- **장애 없이 전체 티케팅 완료**

팀장님이 "신입이 이 정도 트래픽을 처리한 건 처음"이라고 칭찬해주셨다. Redis 공부 안 했으면 진짜 큰일 날 뻔했다.

## 이번 주 배운 핵심

- **인덱스 설계**: 복합 인덱스 순서가 성능에 미치는 영향
- **비정규화 전략**: 실시간성보다 속도가 중요하면 집계 컬럼 유지
- **Redis 캐시 활용**: TTL, SETNX, Write-Behind 패턴
- **캐시 무효화 전략**: 언제 갱신하고 언제 삭제할지 명확히 정의

다음 주에는 외부 시스템 연동 시 장애 대응(Circuit Breaker)을 배워볼 예정. 외부 API도 언제 터질지 모르니까!
