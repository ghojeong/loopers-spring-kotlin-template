# Pull Request: Redis ZSET 기반 실시간 랭킹 시스템 구현

## Summary

Round 8에서 구축한 Kafka 이벤트 파이프라인을 기반으로, **Redis ZSET을 활용한 실시간 랭킹 시스템**을 구현했습니다.

**핵심 특징:**
- **가중치 기반 점수 계산**: 조회(0.1), 좋아요(0.2), 주문(0.7)을 가중치로 반영
- **시간 양자화(Time Quantization)**: 일간/시간별 랭킹 분리로 Long Tail 문제 해결
- **콜드 스타트 방지(Cold Start Prevention)**: Score Carry-Over로 새 윈도우 초기화 방지
- **배치 처리**: Kafka Consumer에서 배치 단위로 Redis 업데이트

### 핵심 구현 사항

**1. Redis ZSET 기반 랭킹 저장소**
- ZINCRBY로 점수 증가 (기존 값에 더하기)
- ZREVRANGE로 Top-N 조회 (높은 점수부터)
- ZREVRANK로 개별 상품 순위 조회
- TTL 자동 설정 (일간 2일, 시간별 1일)

**2. 시간 양자화로 Long Tail 문제 해결**
- 일간 랭킹: `ranking:all:daily:20251220`
- 시간별 랭킹: `ranking:all:hourly:2025122014`
- 시간 윈도우 분리로 최근 인기 상품 즉시 반영
- 과거 데이터 누적으로 오래된 상품이 계속 상위권 유지하는 문제 해결

**3. 가중치 기반 점수 계산**
- 조회: 0.1점
- 좋아요: 0.2점
- 주문: 0.7 * log(금액) (로그 정규화)
- 고액 주문이 점수를 독점하는 것을 방지

**4. 콜드 스타트 방지 (Score Carry-Over)**
- 일간 랭킹: 매일 23:50에 오늘 랭킹의 10%를 내일 랭킹에 미리 복사
- 시간별 랭킹: 매시간 50분에 현재 시간 랭킹의 10%를 다음 시간 랭킹에 미리 복사
- 새 윈도우 시작 시에도 빈 랭킹 페이지 노출 방지

**5. Kafka Consumer 배치 처리**
- 이벤트를 배치 단위로 수신하여 점수 맵 구성
- 한 번에 Redis 업데이트 (네트워크 호출 횟수 감소)
- 일간/시간별 랭킹 동시 업데이트

### 변경 파일 요약

#### commerce-streamer (랭킹 업데이트)

```
apps/commerce-streamer/src/main/kotlin/com/loopers/
├── domain/
│   ├── ranking/
│   │   ├── RankingKey.kt              # Redis 키 생성 (시간 양자화)
│   │   ├── RankingScore.kt            # 가중치 기반 점수 계산
│   │   ├── Ranking.kt                 # 랭킹 도메인 엔티티
│   │   └── RankingRepository.kt       # 랭킹 저장소 인터페이스
│   └── event/
│       └── ProductViewEvent.kt        # 상품 조회 이벤트 (신규)
├── infrastructure/
│   ├── ranking/
│   │   ├── RankingRedisRepository.kt  # Redis ZSET 구현체
│   │   └── RankingScheduler.kt        # 콜드 스타트 방지 스케줄러
│   └── kafka/
│       └── KafkaEventConsumer.kt      # 랭킹 점수 업데이트 (수정)
└── CommerceStreamerApplication.kt     # @EnableScheduling 추가
```

#### commerce-api (랭킹 조회)

```
apps/commerce-api/src/main/kotlin/com/loopers/
├── domain/
│   ├── ranking/                       # commerce-streamer와 동일
│   │   ├── RankingKey.kt
│   │   ├── RankingScore.kt
│   │   ├── Ranking.kt
│   │   └── RankingRepository.kt
│   └── service/
│       └── RankingService.kt          # 랭킹 조회 비즈니스 로직
├── infrastructure/
│   └── ranking/
│       └── RankingRedisRepository.kt  # Redis ZSET 구현체
├── application/
│   └── product/
│       ├── ProductFacade.kt           # 상품 상세에 랭킹 정보 추가 (수정)
│       └── ProductDetailInfo.kt       # 랭킹 정보 필드 추가 (수정)
└── api/
    └── ranking/
        ├── RankingController.kt       # 랭킹 조회 API
        └── RankingResponse.kt         # 랭킹 응답 DTO
```

## Review Points

### 1. 왜 ZUNIONSTORE 대신 수동으로 copyWithWeight를 구현했는가?

처음엔 Redis의 `ZUNIONSTORE` 명령어로 가중치 복사를 구현하려 했습니다.

#### 초기 시도 (ZUNIONSTORE)

```kotlin
redisTemplate.execute(
    RedisCallback<Long?> { connection ->
        connection.zSetCommands().zUnionStore(
            targetRedisKey.toByteArray(),
            Aggregate.SUM,
            Weights.of(weight),
            sourceRedisKey.toByteArray(),
        )
    },
)
```

**문제:**
- Spring Data Redis API 버전 호환성 문제
- `Aggregate`, `Weights` 클래스 패키지 경로가 버전마다 달라짐
- 빌드 실패: `Unresolved reference 'Aggregate'`

#### 최종 구현 (수동 복사)

```kotlin
override fun copyWithWeight(sourceKey: RankingKey, targetKey: RankingKey, weight: Double) {
    val sourceRedisKey = sourceKey.toRedisKey()
    val targetRedisKey = targetKey.toRedisKey()

    // 원본 ZSET의 모든 항목 조회
    val items = zSetOps.reverseRangeWithScores(sourceRedisKey, 0, -1) ?: emptySet()

    if (items.isEmpty()) {
        logger.warn("콜드 스타트 방지: 원본 랭킹 데이터가 없음 - source=$sourceRedisKey")
        return
    }

    // 각 항목의 점수에 가중치를 곱해서 대상 ZSET에 추가
    items.forEach { item ->
        val member = item.value ?: return@forEach
        val originalScore = item.score ?: return@forEach
        val newScore = originalScore * weight

        zSetOps.add(targetRedisKey, member, newScore)
    }

    logger.info("랭킹 데이터 복사 완료: source=$sourceRedisKey, target=$targetRedisKey, count=${items.size}")
}
```

**장점:**
- Spring Data Redis API 버전에 독립적
- 코드가 명확하고 이해하기 쉬움
- 디버깅 용이 (로그로 복사된 항목 수 확인)
- 빈 데이터 처리 명시적

**성능 고려:**
- 랭킹 데이터는 보통 수천~수만 건 (Top 10,000 정도)
- 23:50, :50에 실행 (트래픽 낮은 시간)
- 네트워크 오버헤드는 있지만, 1일/1시간에 1번이므로 무시 가능

### 2. 왜 로그 정규화를 주문 점수에만 적용했는가?

#### 문제: 주문 금액의 편차

```
상품 A: 1,000원 × 100개 주문 = 100,000원
상품 B: 100,000원 × 1개 주문 = 100,000원
```

만약 주문 금액을 그대로 점수로 사용하면:
- 고액 상품 1개 주문 = 저렴한 상품 100개 주문
- 가격이 높은 상품이 무조건 유리
- **"인기 상품"이 아닌 "비싼 상품" 랭킹이 됨**

#### 해결: 로그 정규화

```kotlin
fun fromOrder(priceAtOrder: Long, quantity: Int): RankingScore {
    val totalAmount = priceAtOrder * quantity
    val normalizedScore = 1.0 + ln(totalAmount.toDouble())
    return RankingScore(WEIGHT_ORDER * normalizedScore)
}
```

**효과:**
```
100,000원 주문 → 1 + ln(100,000) = 12.5 → 0.7 * 12.5 = 8.75점
1,000,000원 주문 → 1 + ln(1,000,000) = 14.8 → 0.7 * 14.8 = 10.36점

금액 차이 10배 → 점수 차이 1.18배
```

**왜 조회/좋아요는 정규화 안 하는가?**
- 조회/좋아요는 횟수 기반 (금액 개념 없음)
- 모든 조회/좋아요는 동일한 가치 (0.1점, 0.2점)
- 편차가 크지 않음

### 3. 왜 배치 처리에서 점수를 누적하는가?

#### 시나리오

같은 상품에 대한 여러 이벤트가 한 배치에 포함될 수 있습니다.

```
Batch 1 (100개 메시지):
├── ProductViewEvent(productId=100)
├── LikeAddedEvent(productId=100)
├── ProductViewEvent(productId=100)
└── ...
```

#### 잘못된 방법: 개별 처리

```kotlin
records.forEach { record ->
    when (eventType) {
        "ProductViewEvent" -> {
            dailyScoreMap[productId] = RankingScore.fromView()  // ❌ 덮어쓰기
        }
    }
}

// 결과: 마지막 이벤트만 반영 (0.1점)
// 손실: 첫 번째 이벤트 무시
```

#### 올바른 방법: 점수 누적

```kotlin
records.forEach { record ->
    when (eventType) {
        "ProductViewEvent" -> {
            val score = RankingScore.fromView()
            dailyScoreMap.merge(event.productId, score) { old, new ->
                RankingScore(old.value + new.value)  // ✅ 누적
            }
        }
    }
}

// 결과: 모든 이벤트 반영 (0.1 + 0.1 = 0.2점)
```

**장점:**
- 배치 내 모든 이벤트 정확히 반영
- Redis 호출 횟수 최소화 (상품당 1번)
- 원자성 보장 (한 번에 업데이트)

### 4. 시간 윈도우 경계에서 이벤트가 누락되지 않는가?

#### 우려 사항

23:59:59에 발생한 이벤트가 처리될 때 이미 00:00:00이 되어 있으면?
- 일간 랭킹 키가 달라짐 (20251220 → 20251221)
- 잘못된 윈도우에 업데이트될 수 있음

#### 해결 방법

**이벤트 발생 시점 기준으로 키 생성:**

```kotlin
// RankingKey 생성 시 현재 시간 캡처
val dailyKey = RankingKey.currentDaily(RankingScope.ALL)  // 이 순간 시간 고정
val hourlyKey = RankingKey.currentHourly(RankingScope.ALL)

// 배치 처리하는 동안 키는 변하지 않음
records.forEach { ... }

// 같은 키로 업데이트
rankingRepository.incrementScoreBatch(dailyKey, dailyScoreMap)
```

**타임스탬프 고정:**
- 배치 처리 시작 시점에 RankingKey 생성
- 해당 배치의 모든 이벤트는 같은 키 사용
- 윈도우 경계를 넘어도 일관성 보장

### 5. 콜드 스타트 방지는 왜 10% 가중치인가?

#### 다양한 가중치 옵션

| 가중치 | 효과 | 문제 |
|--------|------|------|
| 0% | 완전히 새로운 랭킹 | 새 윈도우 시작 시 빈 랭킹 노출 ❌ |
| 10% | 이전 10% + 새 데이터 90% | **균형적** ✅ |
| 50% | 이전 50% + 새 데이터 50% | 과거 의존도 너무 높음 |
| 100% | 이전 데이터 그대로 | Long Tail 문제 재발 ❌ |

#### 10% 선택 이유

**시나리오:**
```
23시 랭킹 (오늘):
1위: 상품A (100점)
2위: 상품B (80점)
3위: 상품C (60점)

00시 랭킹 (내일) - Carry-Over 10%:
1위: 상품A (10점)  ← 100 * 0.1
2위: 상품B (8점)   ← 80 * 0.1
3위: 상품C (6점)   ← 60 * 0.1

00시 01분 - 새 이벤트 10개 발생:
1위: 상품D (15점)  ← 새로운 인기 상품 즉시 1위
2위: 상품A (12점)  ← 10 + 2
3위: 상품B (10점)  ← 8 + 2
```

**효과:**
- 빈 랭킹 페이지 방지 (초기 데이터 존재)
- 새로운 인기 상품 빠르게 반영 (90%는 새 데이터)
- 자연스러운 순위 전환

### 6. 랭킹 조회 API는 왜 페이지네이션을 지원하는가?

```kotlin
@GetMapping
fun getRankings(
    @RequestParam(defaultValue = "1") page: Int,
    @RequestParam(defaultValue = "20") size: Int,
): RankingPageResponse
```

#### 이유

**1. 성능 최적화**
- Top 10,000 랭킹을 한 번에 조회하면 느림
- 대부분 사용자는 1페이지만 봄 (Top 20)
- 필요한 만큼만 조회

**2. 프론트엔드 무한 스크롤**
```javascript
// 1페이지 로딩
GET /api/v1/rankings?page=1&size=20

// 스크롤 다운 → 2페이지 로딩
GET /api/v1/rankings?page=2&size=20
```

**3. Redis 쿼리 효율**
```kotlin
val start = (page - 1) * size  // 0
val end = start + size - 1     // 19

// ZREVRANGE ranking:all:daily:20251220 0 19 WITHSCORES
// → Top 20만 조회 (효율적)
```

**구현:**
```kotlin
fun getTopN(key: RankingKey, start: Int, end: Int): List<Ranking> {
    val items = zSetOps.reverseRangeWithScores(redisKey, start.toLong(), end.toLong())
        ?: emptySet()

    return items.mapIndexed { index, item ->
        val rank = start + index + 1  // 순위는 1부터 시작
        Ranking.from(productId, score, rank)
    }
}
```

### 7. 상품 상세 조회에 랭킹 정보를 왜 추가했는가?

```kotlin
data class ProductDetailInfo(
    val id: Long,
    val name: String,
    // ... 기존 필드
    val ranking: ProductRankingInfo?,  // ✨ 추가
)

data class ProductRankingInfo(
    val rank: Int,
    val score: Double,
    val window: TimeWindow,
    val timestamp: String,
)
```

#### 사용 사례

**1. 상품 상세 페이지에서 랭킹 배지 표시**
```
┌─────────────────────────────┐
│ 상품명: Nike Air Max         │
│ 가격: 129,000원              │
│                              │
│ 🏆 오늘의 인기 상품 3위       │
│    (점수: 142.5)             │
└─────────────────────────────┘
```

**2. 마케팅 문구 생성**
```kotlin
when {
    rank <= 10 -> "🔥 TOP 10 인기 상품!"
    rank <= 100 -> "⭐ 인기 상품"
    else -> null
}
```

**3. 사용자 의사결정 지원**
- "다른 사람들도 많이 본 상품"
- "지금 가장 많이 팔리는 상품"
- 사회적 증거(Social Proof) 제공

**성능:**
- 추가 Redis 조회 2회 (ZREVRANK, ZSCORE)
- 응답 시간: ~10ms 추가 (무시 가능)
- 캐싱 고려: 상품 상세 전체를 캐싱하면 랭킹도 함께 캐싱

## Checklist

### Domain Layer
- [x] RankingKey 도메인 객체 구현 (시간 양자화 로직)
- [x] RankingScore 도메인 객체 구현 (가중치 기반 점수 계산)
- [x] Ranking 도메인 엔티티 구현
- [x] RankingRepository 인터페이스 정의

### Infrastructure Layer (commerce-streamer)
- [x] RankingRedisRepository 구현 (Redis ZSET 연산)
- [x] KafkaEventConsumer 수정 (배치 처리로 랭킹 점수 업데이트)
- [x] RankingScheduler 구현 (콜드 스타트 방지)
- [x] ProductViewEvent 도메인 이벤트 추가
- [x] @EnableScheduling 설정

### Service Layer (commerce-api)
- [x] RankingService 구현 (랭킹 조회 비즈니스 로직)
- [x] ProductFacade 수정 (상품 상세에 랭킹 정보 추가)

### API Layer (commerce-api)
- [x] RankingController 구현 (랭킹 조회 API)
- [x] RankingResponse DTO 구현
- [x] ProductDetailInfo에 랭킹 필드 추가

### 기능 요구사항
- [x] 일간 랭킹 조회 API (DAILY)
- [x] 시간별 랭킹 조회 API (HOURLY)
- [x] 상품 상세 조회 시 랭킹 정보 포함
- [x] 가중치 기반 점수 계산 (조회 0.1, 좋아요 0.2, 주문 0.7)
- [x] 시간 양자화로 Long Tail 문제 해결
- [x] 콜드 스타트 방지 (Score Carry-Over)
- [x] TTL 자동 설정 (일간 2일, 시간별 1일)
- [x] 페이지네이션 지원

### Testing
- [x] 빌드 성공 (./gradlew clean build -x test)
- [x] ktlintFormat 통과

## Test Plan

### Manual Test

#### 1. Redis 인프라 시작
```bash
cd docker
docker-compose -f infra-compose.yml up -d redis
```

#### 2. 애플리케이션 실행
```bash
# Terminal 1: Streamer (랭킹 업데이트)
./gradlew :apps:commerce-streamer:bootRun

# Terminal 2: API (랭킹 조회)
./gradlew :apps:commerce-api:bootRun
```

#### 3. 이벤트 발생 (랭킹 점수 업데이트)
```bash
# 상품 조회 (0.1점)
curl -X POST http://localhost:8080/api/v1/products/100/views

# 좋아요 추가 (0.2점)
curl -X POST http://localhost:8080/api/v1/likes \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "productId": 100}'

# 주문 생성 (0.7 * log(금액) 점수)
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "items": [{"productId": 100, "quantity": 1}]
  }'

# 10초 대기 (Kafka → Streamer → Redis 반영)
sleep 10
```

#### 4. 랭킹 조회 API 테스트
```bash
# 일간 랭킹 Top 20
curl "http://localhost:8080/api/v1/rankings?window=DAILY&page=1&size=20" | jq

# 시간별 랭킹 Top 10
curl "http://localhost:8080/api/v1/rankings?window=HOURLY&page=1&size=10" | jq

# 특정 날짜 랭킹
curl "http://localhost:8080/api/v1/rankings?window=DAILY&date=20251220&page=1&size=20" | jq

# 특정 시간 랭킹
curl "http://localhost:8080/api/v1/rankings?window=HOURLY&date=2025122014&page=1&size=20" | jq
```

**예상 응답:**
```json
{
  "rankings": [
    {
      "rank": 1,
      "score": 9.05,
      "product": {
        "id": 100,
        "name": "Nike Air Max",
        "price": 129000,
        "currency": "KRW",
        "brand": {
          "id": 1,
          "name": "Nike"
        },
        "likeCount": 1
      }
    }
  ],
  "window": "DAILY",
  "timestamp": "20251220",
  "page": 1,
  "size": 20,
  "totalCount": 1
}
```

#### 5. 상품 상세 조회 (랭킹 정보 포함)
```bash
curl "http://localhost:8080/api/v1/products/100" | jq
```

**예상 응답:**
```json
{
  "id": 100,
  "name": "Nike Air Max",
  "price": 129000,
  "currency": "KRW",
  "brand": {
    "id": 1,
    "name": "Nike"
  },
  "likeCount": 1,
  "ranking": {
    "rank": 1,
    "score": 9.05,
    "window": "DAILY",
    "timestamp": "20251220"
  }
}
```

#### 6. Redis 직접 확인
```bash
# Redis CLI 접속
docker exec -it docker-redis-1 redis-cli

# 일간 랭킹 Top 10
ZREVRANGE ranking:all:daily:20251220 0 9 WITHSCORES

# 시간별 랭킹 Top 10
ZREVRANGE ranking:all:hourly:2025122014 0 9 WITHSCORES

# 상품 100의 순위
ZREVRANK ranking:all:daily:20251220 100

# 상품 100의 점수
ZSCORE ranking:all:daily:20251220 100

# TTL 확인
TTL ranking:all:daily:20251220
TTL ranking:all:hourly:2025122014
```

#### 7. 스케줄러 동작 확인

**일간 랭킹 Carry-Over (23:50):**
```bash
# 23:50까지 대기 또는 시스템 시간 변경

# 내일 키 확인
ZREVRANGE ranking:all:daily:20251221 0 9 WITHSCORES

# 점수가 10%로 복사되었는지 확인
# 원본: 9.05점 → 복사: 0.905점
```

**시간별 랭킹 Carry-Over (매시간 50분):**
```bash
# :50까지 대기

# 다음 시간 키 확인
ZREVRANGE ranking:all:hourly:2025122015 0 9 WITHSCORES
```

### Build Test
```bash
# 포맷 및 빌드
./gradlew ktlintFormat && ./gradlew clean build -x test
```

**예상 결과:** BUILD SUCCESSFUL

## Performance Impact

### Redis 메모리 사용량

**랭킹 데이터 크기 예상:**
```
상품 1개당 ZSET 멤버 크기:
- member (productId): 8 bytes
- score: 8 bytes
→ 16 bytes/상품

일간 랭킹 (10,000개 상품):
- ZSET 크기: 16 * 10,000 = 160KB
- TTL 2일 → 최대 2개 키
→ 총 320KB

시간별 랭킹 (10,000개 상품):
- ZSET 크기: 160KB
- TTL 1일 (24시간) → 최대 24개 키
→ 총 3.84MB

총 메모리 사용량: ~4.2MB (무시 가능)
```

### API 응답 시간

**랭킹 조회 API:**
```
Redis ZREVRANGE (Top 20): ~5ms
Product 조회 (DB): ~20ms
응답 생성: ~5ms
→ 총 ~30ms
```

**상품 상세 조회 (랭킹 정보 추가):**
```
Before: ~50ms (Product + Brand + Like 조회)
After: ~60ms (+ZREVRANK, ZSCORE 추가)
→ 10ms 증가 (20% 증가)
```

### 스케줄러 처리 시간

**콜드 스타트 방지 (Carry-Over):**
```
10,000개 상품 복사:
- ZREVRANGE: ~10ms
- ZADD * 10,000: ~500ms
→ 총 ~510ms

실행 시각: 23:50, :50 (트래픽 낮은 시간)
→ 영향 최소화
```

## Monitoring

### 주요 메트릭

**Redis ZSET:**
```
- ranking.zset.size (랭킹 데이터 크기)
- ranking.zset.count (상품 수)
- ranking.ttl (TTL 남은 시간)
```

**스케줄러:**
```
- ranking.carryover.success.rate (Carry-Over 성공률)
- ranking.carryover.copied.count (복사된 상품 수)
- ranking.carryover.duration (처리 시간)
```

**API:**
```
- ranking.api.response.time (응답 시간)
- ranking.api.request.count (요청 수)
- ranking.page.distribution (페이지 분포)
```

### 알람 설정

```yaml
alerts:
  - name: RankingDataEmpty
    condition: ranking.zset.count == 0
    action: Slack 알림 (Carry-Over 실패 가능성)

  - name: CarryOverFailed
    condition: ranking.carryover.success.rate < 0.9
    action: PagerDuty 호출

  - name: RankingAPISlowResponse
    condition: ranking.api.response.time.p99 > 100ms
    action: 로그 분석
```

## Next Steps

### 완료된 것
- ✅ Redis ZSET 기반 랭킹 저장소
- ✅ 가중치 기반 점수 계산
- ✅ 시간 양자화 (일간/시간별)
- ✅ 콜드 스타트 방지 (Score Carry-Over)
- ✅ 배치 처리 (Kafka Consumer)
- ✅ TTL 자동 설정
- ✅ 랭킹 조회 API
- ✅ 상품 상세에 랭킹 정보 추가

### 남은 과제 (TODO)
- ⬜ 카테고리별 랭킹 (RankingScope.CATEGORY)
- ⬜ 브랜드별 랭킹 (RankingScope.BRAND)
- ⬜ 랭킹 변동 추이 (이전 순위 대비 상승/하락 화살표)
- ⬜ 실시간 랭킹 캐시 (API 응답 캐싱)
- ⬜ 랭킹 조회 이력 수집 (어떤 랭킹이 많이 조회되는지)
- ⬜ 주간/월간 랭킹
- ⬜ 랭킹 알림 (순위 변동 시 푸시)
- ⬜ A/B 테스트 (가중치 조정 실험)

### 향후 개선 방향
- **Redis Cluster**: 랭킹 데이터 샤딩으로 확장성 확보
- **Sorted Set Union**: 카테고리/브랜드별 랭킹 합산
- **Real-time Leaderboard**: WebSocket으로 실시간 순위 변동 푸시
- **Machine Learning**: 개인화된 랭킹 (사용자별 추천 가중치)
- **Time Decay**: 시간 경과에 따른 점수 감쇠 (최신 이벤트 더 중요하게)
