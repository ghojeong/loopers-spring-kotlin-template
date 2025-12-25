# "인기 상품 1위가 3년째 바뀌지 않는 이유"

**TL;DR**: 상품 랭킹 시스템을 만들면서, **누적 점수만 쌓으면 오래된 상품이 영원히 상위권을 차지**한다는 걸 깨달았다. "시간 양자화(Time Quantization)"로 일간/시간별 랭킹을 분리하니 최근 인기 상품이 즉시 반영됐다. 그런데 **새벽 0시, 매시간 정각에는 랭킹이 텅 비었다**. "콜드 스타트 문제구나..." 싶어서 이전 랭킹의 10%를 미리 복사하는 Score Carry-Over를 구현했다. 그리고 주문 금액 차이가 10배여도 점수는 1.2배만 차이 나도록 **로그 정규화**를 적용하니, 드디어 **"진짜 인기 상품" 랭킹**이 완성됐다. Redis ZSET만 쓰면 끝날 줄 알았는데, **랭킹은 데이터 구조가 아니라 시간 관리 문제**였다.

## "우리 쇼핑몰 1위 상품이 3년째 그대로예요"

### 처음 마주한 문제

Kafka 이벤트 파이프라인이 완성되고 나니, 제품 매니저가 새로운 요구사항을 가져왔다:

> "실시간 인기 상품 랭킹을 보여주고 싶어요. 지금 가장 핫한 상품을 사용자들이 볼 수 있게요."

"뭐 어렵겠어?" Redis ZSET으로 점수 누적하면 될 것 같았다.

**첫 번째 구현:**

```kotlin
// 좋아요 이벤트 수신 → 점수 증가
@KafkaListener(topics = ["catalog-events"])
fun handleLikeAdded(event: LikeAddedEvent) {
    val score = 1.0  // 좋아요 1점
    redis.zIncr("ranking:all", event.productId, score)
}

// 랭킹 조회
fun getTopRankings(size: Int): List<Product> {
    val productIds = redis.zRevRange("ranking:all", 0, size - 1)
    return productRepository.findAllById(productIds)
}
```

배포하고 며칠 지켜봤다.

### 충격적인 결과

1주일 후 랭킹을 확인했다.

```bash
# 랭킹 Top 10 조회
ZREVRANGE ranking:all 0 9 WITHSCORES

1) "100" (score: 1520.0)  # 출시 3년된 스테디셀러
2) "102" (score: 1350.0)  # 작년 베스트셀러
3) "105" (score: 980.0)   # 2년 전 인기 상품
...
10) "201" (score: 450.0)  # 이번 달 신상품
```

**"새로 출시한 인기 상품이 10위권 밖이야?"**

제품 매니저가 물어왔다:

> "어제 SNS에서 바이럴 난 상품 201번이 왜 10위 밖이에요? 지금 주문량 1위인데..."

**문제 분석:**

| 상품 | 출시일 | 오늘 조회수 | 오늘 주문수 | 누적 점수 | 순위 |
|------|--------|-----------|-----------|----------|------|
| 100 | 3년 전 | 50 | 5 | 1520 | **1위** |
| 201 | 이번 주 | 500 | 100 | 450 | **10위** |

**"누적 점수로는 최근 인기 상품을 반영할 수 없구나..."**

이게 바로 **Long Tail Problem**이었다. 시간이 지날수록 과거 데이터가 쌓여서, 최근 인기 상품이 순위에 반영되지 않는다.

### Long Tail Problem

```
시간이 지날수록 누적 점수 격차가 벌어짐:

Day 1:
상품A: 10점 (1위)
상품B: 0점

Day 10:
상품A: 100점 (1위)  ← 이제 하루 0점이어도
상품B: 15점         ← 상품B가 따라잡기 어려움

Day 100:
상품A: 500점 (1위)  ← 인기가 식어도 계속 1위
상품B: 200점
상품C: 50점 (신상)  ← 지금 핫해도 하위권
```

**"시간이 쌓일수록 오래된 상품이 유리해지는 구조다..."**

## "시간을 잘라내야 최근 트렌드가 보인다"

### 시간 양자화(Time Quantization)

**핵심 아이디어:**
- 누적 랭킹이 아니라 **시간 윈도우별 랭킹**
- 매일, 매시간 **새로운 랭킹 키**를 사용
- 과거 데이터는 자동으로 만료(TTL)

**일간 랭킹 설계:**

```kotlin
// 기존: 하나의 키에 모든 점수 누적
ranking:all → 영원히 누적

// 개선: 날짜별로 키 분리
ranking:all:daily:20251220 → 2025년 12월 20일 랭킹
ranking:all:daily:20251221 → 2025년 12월 21일 랭킹
ranking:all:daily:20251222 → 2025년 12월 22일 랭킹
```

**시간별 랭킹 설계:**

```kotlin
ranking:all:hourly:2025122014 → 2025년 12월 20일 14시 랭킹
ranking:all:hourly:2025122015 → 2025년 12월 20일 15시 랭킹
```

**구현:**

```kotlin
data class RankingKey(
    val scope: RankingScope,      // ALL, CATEGORY, BRAND
    val window: TimeWindow,        // DAILY, HOURLY
    val timestamp: LocalDateTime,  // 시간 정보
) {
    fun toRedisKey(): String {
        return when (window) {
            TimeWindow.DAILY ->
                "ranking:${scope.value}:daily:${timestamp.format(DAILY_FORMAT)}"
            TimeWindow.HOURLY ->
                "ranking:${scope.value}:hourly:${timestamp.format(HOURLY_FORMAT)}"
        }
    }

    companion object {
        private val DAILY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val HOURLY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH")

        fun currentDaily(scope: RankingScope): RankingKey {
            return RankingKey(scope, TimeWindow.DAILY, LocalDate.now().atStartOfDay())
        }

        fun currentHourly(scope: RankingScope): RankingKey {
            val now = LocalDateTime.now()
            return RankingKey(scope, TimeWindow.HOURLY, now.withMinute(0).withSecond(0))
        }
    }
}
```

**Kafka Consumer 수정:**

```kotlin
@KafkaListener(topics = ["catalog-events"])
fun consumeCatalogEvents(records: List<ConsumerRecord<String, String>>) {
    // 일간/시간별 랭킹 키 생성 (배치 시작 시점 고정)
    val dailyKey = RankingKey.currentDaily(RankingScope.ALL)
    val hourlyKey = RankingKey.currentHourly(RankingScope.ALL)

    val dailyScoreMap = mutableMapOf<Long, RankingScore>()
    val hourlyScoreMap = mutableMapOf<Long, RankingScore>()

    records.forEach { record ->
        val score = RankingScore.fromLike()  // 0.2점
        dailyScoreMap.merge(productId, score) { old, new ->
            RankingScore(old.value + new.value)  // 누적
        }
        hourlyScoreMap.merge(productId, score) { old, new ->
            RankingScore(old.value + new.value)
        }
    }

    // 배치 업데이트
    rankingRepository.incrementScoreBatch(dailyKey, dailyScoreMap)
    rankingRepository.incrementScoreBatch(hourlyKey, hourlyScoreMap)

    // TTL 설정 (일간 2일, 시간별 1일)
    rankingRepository.setExpire(dailyKey)
    rankingRepository.setExpire(hourlyKey)
}
```

**TTL 자동 만료:**

```kotlin
override fun setExpire(key: RankingKey) {
    val redisKey = key.toRedisKey()
    val ttl = when (key.window) {
        TimeWindow.DAILY -> Duration.ofDays(2)   // 일간: 2일 보관
        TimeWindow.HOURLY -> Duration.ofDays(1)  // 시간별: 1일 보관
    }

    redisTemplate.expire(redisKey, ttl)
}
```

### 결과

다시 랭킹을 확인했다.

```bash
# 오늘(12월 20일) 일간 랭킹
ZREVRANGE ranking:all:daily:20251220 0 9 WITHSCORES

1) "201" (score: 55.0)   # 오늘 인기 상품 (신상)
2) "105" (score: 48.0)   # 오늘 인기 상품
3) "100" (score: 12.0)   # 스테디셀러 (오늘은 조용)
```

**"드디어 최근 인기 상품이 1위다!"**

## "0시가 되니 랭킹이 텅 비었어요"

### 새로운 문제

시간 양자화로 최근 트렌드를 반영하는 데는 성공했다. 그런데 새로운 문제가 생겼다.

**새벽 0시에 랭킹 조회:**

```bash
# 12월 20일 23:59
ZREVRANGE ranking:all:daily:20251220 0 9 WITHSCORES
1) "201" (score: 55.0)
2) "105" (score: 48.0)
...

# 12월 21일 00:00 (1분 후)
ZREVRANGE ranking:all:daily:20251221 0 9 WITHSCORES
(empty list)  # 😱 텅 빔!
```

**"새 윈도우가 시작되면 랭킹이 없네..."**

사용자가 0시에 랭킹 페이지를 열면 아무것도 안 보인다. "인기 상품이 없나요?" 같은 문의가 들어올 것이다.

### 콜드 스타트(Cold Start) 문제

**문제:**
- 새 시간 윈도우가 시작하면 랭킹 데이터가 없음
- 이벤트가 쌓이기 전까지는 빈 랭킹

**타임라인:**

```
23:59 (어제):
ranking:all:daily:20251220
├── 상품A: 100점
├── 상품B: 80점
└── 상품C: 60점

00:00 (오늘):
ranking:all:daily:20251221
└── (empty) ❌ 사용자에게 빈 페이지 노출

00:10 (이벤트 10개 발생 후):
ranking:all:daily:20251221
├── 상품D: 2점
├── 상품A: 1점
└── 상품B: 1점
```

**"어제의 인기 상품이 오늘도 어느 정도 인기 있을 텐데..."**

### Score Carry-Over 솔루션

**핵심 아이디어:**
- 이전 윈도우 랭킹을 **미리 복사**
- 점수에 **10% 가중치**를 곱해서 복사
- 새 윈도우 시작 시에도 랭킹 존재

**왜 10%인가?**

| 가중치 | 효과 | 문제 |
|--------|------|------|
| 0% | 완전히 새로운 랭킹 | 빈 랭킹 노출 ❌ |
| 10% | 이전 10% + 새 90% | 균형적 ✅ |
| 50% | 이전 50% + 새 50% | 과거 의존도 너무 높음 |
| 100% | 이전 데이터 그대로 | Long Tail 재발 ❌ |

**시뮬레이션:**

```
23시 랭킹 (어제):
1위: 상품A (100점)
2위: 상품B (80점)
3위: 상품C (60점)

00시 랭킹 (오늘) - Carry-Over 10%:
1위: 상품A (10점)  ← 100 * 0.1
2위: 상품B (8점)   ← 80 * 0.1
3위: 상품C (6점)   ← 60 * 0.1

00시 10분 - 새 이벤트 10개 발생:
1위: 상품D (15점)  ← 새로운 인기 상품 즉시 1위
2위: 상품A (12점)  ← 10 + 2 (기존 인기도 + 새 점수)
3위: 상품B (10점)  ← 8 + 2
```

**구현:**

```kotlin
@Component
class RankingScheduler(
    private val rankingRepository: RankingRepository,
) {
    private val logger = LoggerFactory.getLogger(RankingScheduler::class.java)

    /**
     * 일간 랭킹 콜드 스타트 방지
     * 매일 23시 50분에 실행
     */
    @Scheduled(cron = "0 50 23 * * *")
    fun carryOverDailyRanking() {
        try {
            val today = LocalDate.now()
            val tomorrow = today.plusDays(1)

            val todayKey = RankingKey.daily(RankingScope.ALL, today)
            val tomorrowKey = RankingKey.daily(RankingScope.ALL, tomorrow)

            // 오늘 랭킹이 존재하는지 확인
            val todayCount = rankingRepository.getCount(todayKey)
            if (todayCount == 0L) {
                logger.warn("오늘 랭킹 데이터가 없어 복사하지 않음")
                return
            }

            // 10% 가중치로 내일 랭킹에 복사
            val carryOverWeight = 0.1
            rankingRepository.copyWithWeight(todayKey, tomorrowKey, carryOverWeight)

            // 내일 랭킹에 TTL 설정
            rankingRepository.setExpire(tomorrowKey)

            logger.info("일간 랭킹 콜드 스타트 방지 완료: weight=$carryOverWeight, count=$todayCount")
        } catch (e: Exception) {
            logger.error("일간 랭킹 콜드 스타트 방지 실패", e)
        }
    }

    /**
     * 시간별 랭킹 콜드 스타트 방지
     * 매시간 50분에 실행
     */
    @Scheduled(cron = "0 50 * * * *")
    fun carryOverHourlyRanking() {
        // 일간과 동일한 로직
    }
}
```

**copyWithWeight 구현:**

처음엔 Redis의 `ZUNIONSTORE` 명령어를 쓰려 했다.

```kotlin
// ❌ 시도했지만 실패
redisTemplate.execute { connection ->
    connection.zSetCommands().zUnionStore(
        targetKey.toByteArray(),
        Aggregate.SUM,
        Weights.of(weight),
        sourceKey.toByteArray(),
    )
}

// 문제: Spring Data Redis API 버전 호환성
// → Unresolved reference 'Aggregate'
```

**"API가 자꾸 바뀌는구나..." 수동으로 구현하기로 했다.**

```kotlin
override fun copyWithWeight(sourceKey: RankingKey, targetKey: RankingKey, weight: Double) {
    val sourceRedisKey = sourceKey.toRedisKey()
    val targetRedisKey = targetKey.toRedisKey()

    // 원본 ZSET의 모든 항목 조회
    val items = zSetOps.reverseRangeWithScores(sourceRedisKey, 0, -1) ?: emptySet()

    if (items.isEmpty()) {
        logger.warn("원본 랭킹 데이터가 없음 - source=$sourceRedisKey")
        return
    }

    // 각 항목의 점수에 가중치를 곱해서 대상 ZSET에 추가
    items.forEach { item ->
        val member = item.value ?: return@forEach
        val originalScore = item.score ?: return@forEach
        val newScore = originalScore * weight

        zSetOps.add(targetRedisKey, member, newScore)
    }

    logger.info("랭킹 데이터 복사 완료: count=${items.size}")
}
```

**"오히려 이게 더 명확하고 디버깅하기 쉽네"**

### 결과

이제 0시에도 랭킹이 보인다.

```bash
# 12월 21일 00:00 (스케줄러 실행 직후)
ZREVRANGE ranking:all:daily:20251221 0 9 WITHSCORES

1) "201" (score: 5.5)   # 어제 55점 * 0.1
2) "105" (score: 4.8)   # 어제 48점 * 0.1
3) "100" (score: 1.2)   # 어제 12점 * 0.1

# 12월 21일 00:10 (새 이벤트 발생 후)
1) "207" (score: 12.0)  # 신상품이 즉시 1위로
2) "201" (score: 7.5)   # 5.5 + 2.0 (계속 인기)
3) "105" (score: 5.8)   # 4.8 + 1.0
```

**"빈 랭킹도 없고, 새 인기 상품도 즉시 반영된다!"**

## "100만원짜리 상품 1개 vs 1만원짜리 상품 100개"

### 가중치 설계의 고민

이제 랭킹 점수를 어떻게 계산할지 고민이었다.

**어떤 이벤트에 얼마의 점수를 줄 것인가?**

처음 생각:

```kotlin
// ❌ 단순한 방법
조회: 1점
좋아요: 1점
주문: 1점
```

**"이건 너무 단순한데... 주문이 훨씬 중요하지 않나?"**

### 가중치 기반 점수 계산

**의사결정:**

| 이벤트 | 가중치 | 이유 |
|--------|--------|------|
| 조회 | 0.1 | 관심도는 있지만 가벼운 행동 |
| 좋아요 | 0.2 | 더 강한 관심 표현 |
| 주문 | 0.7 | **실제 구매**로 가장 중요 |

**구현:**

```kotlin
@JvmInline
value class RankingScore(val value: Double) {
    companion object {
        private const val WEIGHT_VIEW = 0.1
        private const val WEIGHT_LIKE = 0.2
        private const val WEIGHT_ORDER = 0.7

        fun fromView(): RankingScore = RankingScore(WEIGHT_VIEW)

        fun fromLike(): RankingScore = RankingScore(WEIGHT_LIKE)

        fun fromOrder(priceAtOrder: Long, quantity: Int): RankingScore {
            // TODO: 주문 금액을 어떻게 반영할까?
        }
    }
}
```

### 주문 금액의 딜레마

그런데 주문 점수에 문제가 있었다.

**시나리오:**

```
상품A: 1,000원 × 100개 주문 = 100,000원
상품B: 100,000원 × 1개 주문 = 100,000원
```

만약 주문 금액을 그대로 점수로 쓰면:
- 고액 상품 1개 주문 = 저렴한 상품 100개 주문
- **"인기 상품"이 아니라 "비싼 상품" 랭킹이 됨**

**테스트 케이스:**

| 상품 | 가격 | 주문 수 | 총 금액 | 가중치 0.7 | 점수 |
|------|------|---------|---------|-----------|------|
| 저가 | 10,000원 | 10개 | 100,000원 | 0.7 | 70,000점 |
| 고가 | 1,000,000원 | 1개 | 1,000,000원 | 0.7 | 700,000점 |

**"금액 차이 10배 → 점수 차이 10배... 이건 공정하지 않다"**

### 로그 정규화(Log Normalization)

**핵심 아이디어:**
- 금액을 그대로 쓰지 말고 **로그 스케일**로 변환
- 금액 차이가 커도 점수 차이는 작게

**수식:**

```
score = 0.7 * (1 + ln(totalAmount))
```

**효과:**

| 총 금액 | ln(금액) | 1 + ln(금액) | 0.7 * 점수 | 차이 |
|---------|----------|-------------|-----------|------|
| 100,000원 | 11.5 | 12.5 | **8.75점** | - |
| 1,000,000원 | 13.8 | 14.8 | **10.36점** | 1.18배 |

**금액 차이 10배 → 점수 차이 1.18배**

**구현:**

```kotlin
fun fromOrder(priceAtOrder: Long, quantity: Int): RankingScore {
    val totalAmount = priceAtOrder * quantity
    val normalizedScore = 1.0 + ln(totalAmount.toDouble())
    return RankingScore(WEIGHT_ORDER * normalizedScore)
}
```

**"이제 가격이 아니라 실제 인기도를 반영한다!"**

### 배치 처리에서 점수 누적

Kafka Consumer가 배치로 메시지를 받으니까, 같은 상품에 대한 이벤트가 여러 개 올 수 있다.

**잘못된 방법:**

```kotlin
// ❌ 덮어쓰기
records.forEach { record ->
    dailyScoreMap[productId] = RankingScore.fromView()  // 마지막 것만 남음
}

// 결과: 같은 상품 조회 10번 → 0.1점만 반영
```

**올바른 방법:**

```kotlin
// ✅ 누적
records.forEach { record ->
    val score = RankingScore.fromView()
    dailyScoreMap.merge(productId, score) { old, new ->
        RankingScore(old.value + new.value)  // 0.1 + 0.1 + ... = 1.0
    }
}

// 결과: 같은 상품 조회 10번 → 1.0점 반영 ✅
```

**"배치 안에서도 모든 이벤트를 빠짐없이 반영해야 한다"**

## "23:59에 발생한 이벤트가 내일 랭킹에 들어가면?"

### 윈도우 경계 문제

새로운 우려가 생겼다.

**시나리오:**

```
23:59:59 - 이벤트 발생
   ↓
Kafka Consumer가 메시지 수신
   ↓
00:00:01 - RankingKey 생성
   ↓
❌ 내일(20251221) 키로 업데이트됨
```

**"어제 이벤트가 오늘 랭킹에 들어가버리네..."**

### 타임스탬프 고정

**해결:**

배치 처리 **시작 시점**에 RankingKey를 생성해서 고정한다.

```kotlin
@KafkaListener(topics = ["catalog-events"])
fun consumeCatalogEvents(records: List<ConsumerRecord<String, String>>) {
    // ✅ 배치 시작 시점에 키 생성 (이 순간 시간 고정)
    val dailyKey = RankingKey.currentDaily(RankingScope.ALL)
    val hourlyKey = RankingKey.currentHourly(RankingScope.ALL)

    // 배치 처리하는 동안 키는 변하지 않음
    records.forEach { record ->
        // ... 점수 누적
    }

    // 같은 키로 업데이트
    rankingRepository.incrementScoreBatch(dailyKey, dailyScoreMap)
    rankingRepository.incrementScoreBatch(hourlyKey, hourlyScoreMap)
}
```

**타임라인:**

```
23:59:59.500 - 배치 시작
   ↓
23:59:59.500 - RankingKey 생성 (20251220 고정)
   ↓
00:00:00.100 - 배치 처리 중...
   ↓
00:00:00.500 - Redis 업데이트 (여전히 20251220 키 사용)
   ↓
✅ 올바르게 어제 랭킹에 반영됨
```

**"배치 시작 시점 기준으로 윈도우를 정하면 일관성이 보장된다"**

## "이제 진짜 인기 상품 랭킹이다"

### 최종 결과

모든 개선사항을 적용하고 랭킹을 확인했다.

**오늘 일간 랭킹:**

```bash
curl "http://localhost:8080/api/v1/rankings?window=DAILY&page=1&size=10"

{
  "rankings": [
    {
      "rank": 1,
      "score": 142.5,
      "product": {
        "id": 207,
        "name": "오늘 출시된 신상품",
        "price": 89000
      }
    },
    {
      "rank": 2,
      "score": 128.0,
      "product": {
        "id": 201,
        "name": "이번 주 인기 상품",
        "price": 129000
      }
    },
    {
      "rank": 3,
      "score": 95.5,
      "product": {
        "id": 100,
        "name": "스테디셀러",
        "price": 149000
      }
    }
  ],
  "window": "DAILY",
  "timestamp": "20251220",
  "page": 1,
  "size": 10,
  "totalCount": 156
}
```

**지금 이 시간 인기 상품 (시간별 랭킹):**

```bash
curl "http://localhost:8080/api/v1/rankings?window=HOURLY&page=1&size=5"

{
  "rankings": [
    {
      "rank": 1,
      "score": 45.2,
      "product": {
        "id": 207,
        "name": "지금 바이럴 중인 상품"
      }
    },
    ...
  ],
  "window": "HOURLY",
  "timestamp": "2025122014"
}
```

**상품 상세 페이지에 랭킹 정보 추가:**

```bash
curl "http://localhost:8080/api/v1/products/207"

{
  "id": 207,
  "name": "오늘 출시된 신상품",
  "price": 89000,
  "ranking": {
    "rank": 1,
    "score": 142.5,
    "window": "DAILY",
    "timestamp": "20251220"
  }
}
```

**"지금 가장 핫한 상품"을 정확히 보여준다!**

### 배운 것들

#### 1. 랭킹은 데이터 구조가 아니라 시간 관리 문제

처음엔 "Redis ZSET만 쓰면 끝"이라고 생각했다. 하지만:
- 누적 랭킹 → Long Tail Problem
- 시간 양자화 → 최근 트렌드 반영
- 콜드 스타트 방지 → 사용자 경험 개선

**"랭킹의 핵심은 '시간'을 어떻게 다루느냐다"**

#### 2. 로그 스케일의 힘

금액, 조회수 같은 지표는 편차가 크다. 로그 정규화로:
- 편차를 줄이고
- 공정성을 높이고
- 실제 인기도를 반영

**"선형 스케일이 항상 답은 아니다"**

#### 3. 배치 처리의 함정

배치 안에서 같은 키가 여러 번 나오면:
- 덮어쓰기 → 데이터 손실
- 누적 → 정확한 반영

**"Map.merge()로 간단히 해결된다"**

#### 4. API 버전 의존성 줄이기

Spring Data Redis API가 자꾸 바뀌니까:
- 복잡한 API → 버전 문제
- 수동 구현 → 명확하고 안정적

**"때로는 직접 구현이 더 나을 수 있다"**

#### 5. 스케줄러 타이밍

콜드 스타트 방지를 위해:
- 23:50에 내일 데이터 미리 준비
- :50분에 다음 시간 데이터 준비

**"10분 버퍼면 충분하다"**

### 성능 영향

**Redis 메모리:**
```
일간 랭킹: ~320KB (2일 보관)
시간별 랭킹: ~3.84MB (24시간 보관)
총: ~4.2MB (무시 가능)
```

**API 응답 시간:**
```
랭킹 조회: ~30ms
상품 상세 (+랭킹): ~60ms (기존 대비 +10ms)
```

**스케줄러 처리 시간:**
```
10,000개 상품 복사: ~510ms
실행 시각: 23:50, :50 (트래픽 낮은 시간)
```

**"성능 영향은 거의 없다"**

## "앞으로 해볼 것들"

### 완성된 것
- ✅ 시간 양자화로 Long Tail 해결
- ✅ 콜드 스타트 방지 (Score Carry-Over)
- ✅ 가중치 기반 점수 계산
- ✅ 로그 정규화로 공정성 확보
- ✅ 배치 처리로 성능 최적화
- ✅ 일간/시간별 랭킹 API
- ✅ 상품 상세에 랭킹 정보 표시

### 다음에 해보고 싶은 것

**1. 카테고리/브랜드별 랭킹**
```
ranking:category:electronics:daily:20251220
ranking:brand:nike:daily:20251220
```

**2. 랭킹 변동 추이**
```json
{
  "rank": 3,
  "previousRank": 5,
  "change": "UP",  // ⬆️ 2위 상승
  "arrow": "⬆️"
}
```

**3. 개인화 랭킹**
```
userId에 따라 가중치 조정
- 스포츠 좋아하는 사용자 → 스포츠 상품 가중치 ↑
- 패션 관심 사용자 → 패션 상품 가중치 ↑
```

**4. Time Decay**
```
최신 이벤트에 더 높은 가중치
score = baseScore * exp(-λ * hours)
```

**5. A/B 테스트**
```
가중치 조합을 바꿔가며 실험
- 조회(0.1), 좋아요(0.2), 주문(0.7)
- 조회(0.05), 좋아요(0.15), 주문(0.8)

어떤 조합이 사용자 만족도가 높을까?
```

## "회고"

### 잘한 점

**1. 문제를 먼저 이해했다**
- Long Tail Problem을 직접 경험하고 이해
- 콜드 스타트 문제를 사용자 관점에서 발견

**2. 단순함을 유지했다**
- ZUNIONSTORE 대신 수동 구현
- 복잡한 알고리즘 대신 명확한 가중치

**3. 실용적인 선택**
- 10% 가중치 (0%, 50%, 100% 모두 문제)
- 로그 정규화 (선형은 불공평)
- 배치 처리 (성능 vs 일관성)

### 아쉬운 점

**1. 테스트 부족**
- 스케줄러 동작 검증 (시간 고정 테스트)
- 윈도우 경계 시나리오 테스트
- 점수 누적 정확도 테스트

**2. 모니터링 미비**
- 랭킹 데이터 크기 추적
- Carry-Over 성공률 측정
- API 응답 시간 분포

**3. 문서화 부족**
- 가중치 선택 근거 문서화
- 10% Carry-Over 실험 결과
- 성능 벤치마크 데이터

### 다음엔 이렇게

**1. 테스트 우선**
- 스케줄러부터 테스트 작성
- 경계 케이스 먼저 검증
- 부하 테스트로 성능 확인

**2. 데이터 기반 의사결정**
- A/B 테스트로 가중치 최적화
- 사용자 행동 데이터 수집
- 랭킹 조회 패턴 분석

**3. 점진적 개선**
- 일간 랭킹 먼저 → 시간별 추가
- ALL 스코프 먼저 → 카테고리별 추가
- 기본 가중치 → 개인화 가중치

**"완벽한 랭킹은 없다. 계속 개선하는 랭킹만 있을 뿐"**

## 마치며

Redis ZSET으로 랭킹 시스템을 만드는 건 쉽다. 하지만 **"진짜 인기 상품"을 보여주는 랭킹**을 만드는 건 어렵다.

- 시간을 어떻게 다룰 것인가 (Time Quantization)
- 빈 랭킹을 어떻게 방지할 것인가 (Cold Start Prevention)
- 공정한 점수를 어떻게 계산할 것인가 (Log Normalization)

이 모든 질문에 답하면서, **랭킹은 단순한 정렬이 아니라 복잡한 시스템**이라는 걸 배웠다.

그리고 가장 중요한 걸 깨달았다:

**"랭킹의 목적은 점수를 매기는 게 아니라, 사용자가 원하는 상품을 빠르게 찾게 하는 것"**

이제 우리 쇼핑몰 1위 상품은 3년째 그대로가 아니다. **지금 이 순간 가장 핫한 상품**이다.
