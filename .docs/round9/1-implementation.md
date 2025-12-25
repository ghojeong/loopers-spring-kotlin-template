# Round 9: Redis ZSET 기반 실시간 랭킹 시스템 구현

## 구현 개요

Round 8에서 구축한 Kafka 이벤트 파이프라인을 기반으로,
**Redis ZSET을 활용한 실시간 랭킹 시스템**을 구현했습니다.

**핵심 특징:**
- **가중치 기반 점수 계산**: 조회(0.1), 좋아요(0.2), 주문(0.7)을 가중치로 반영
- **시간 양자화(Time Quantization)**: 일간/시간별 랭킹 분리로 Long Tail 문제 해결
- **콜드 스타트 방지(Cold Start Prevention)**: Score Carry-Over로 새 윈도우 초기화 방지
- **배치 처리**: Kafka Consumer에서 배치 단위로 Redis 업데이트

**애플리케이션 역할:**
- **commerce-streamer**: Kafka 이벤트 수신 → 랭킹 점수 업데이트
- **commerce-api**: 랭킹 조회 API 제공

## 핵심 개념

### 1. Redis ZSET (Sorted Set)

**ZSET 특징:**
```
ZADD ranking:all:daily:20251220 0.1 "product:100"
ZADD ranking:all:daily:20251220 0.2 "product:101"

ranking:all:daily:20251220
├── product:101 (score: 0.2)
└── product:100 (score: 0.1)
```

**주요 명령어:**
- `ZINCRBY`: 점수 증가 (기존 값에 더하기)
- `ZREVRANGE`: 내림차순 범위 조회 (Top-N)
- `ZREVRANK`: 내림차순 순위 조회 (특정 상품)
- `ZSCORE`: 점수 조회
- `ZCARD`: 멤버 수 조회

### 2. 시간 양자화 (Time Quantization)

**문제: Long Tail Problem**
- 시간이 지날수록 과거 데이터가 누적되어 최근 인기 상품이 순위에 반영되지 않음
- 오래된 인기 상품이 계속 상위권 유지

**해결: 시간 윈도우 분리**
```
ranking:all:daily:20251220  # 일간 랭킹 (2025년 12월 20일)
ranking:all:daily:20251221  # 일간 랭킹 (2025년 12월 21일)

ranking:all:hourly:2025122014  # 시간별 랭킹 (2025년 12월 20일 14시)
ranking:all:hourly:2025122015  # 시간별 랭킹 (2025년 12월 20일 15시)
```

**장점:**
- 최근 인기 상품이 즉시 순위에 반영
- TTL로 자동 삭제 (일간 2일, 시간별 1일)
- 시간대별 트렌드 분석 가능

### 3. 가중치 기반 점수 계산

**가중치 설정:**
```kotlin
companion object {
    private const val WEIGHT_VIEW = 0.1    // 조회
    private const val WEIGHT_LIKE = 0.2    // 좋아요
    private const val WEIGHT_ORDER = 0.7   // 주문
}
```

**점수 계산 로직:**
```kotlin
// 조회: 0.1점
fun fromView(): RankingScore = RankingScore(WEIGHT_VIEW)

// 좋아요: 0.2점
fun fromLike(): RankingScore = RankingScore(WEIGHT_LIKE)

// 주문: 0.7 * log(금액) (로그 정규화)
fun fromOrder(priceAtOrder: Long, quantity: Int): RankingScore {
    val totalAmount = priceAtOrder * quantity
    val normalizedScore = 1.0 + ln(totalAmount.toDouble())
    return RankingScore(WEIGHT_ORDER * normalizedScore)
}
```

**로그 정규화 이유:**
- 고액 주문이 점수를 독점하는 것을 방지
- 100,000원 주문 → 0.7 * 12.5 = 8.75점
- 1,000,000원 주문 → 0.7 * 14.8 = 10.36점
- 금액 차이 10배 → 점수 차이 1.18배

### 4. 콜드 스타트 방지 (Score Carry-Over)

**문제:**
- 새 시간 윈도우 시작 시 랭킹 데이터가 없음
- 사용자에게 빈 랭킹 페이지 노출

**해결: Score Carry-Over**
```
일간 랭킹:
23:50에 실행
├── 오늘 랭킹(20251220) 조회
├── 점수에 10% 가중치 곱하기
└── 내일 랭킹(20251221)에 미리 복사

시간별 랭킹:
매시간 50분에 실행
├── 현재 시간 랭킹(2025122014) 조회
├── 점수에 10% 가중치 곱하기
└── 다음 시간 랭킹(2025122015)에 미리 복사
```

**구현:**
```kotlin
@Scheduled(cron = "0 50 23 * * *")
fun carryOverDailyRanking() {
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)

    val todayKey = RankingKey.daily(RankingScope.ALL, today)
    val tomorrowKey = RankingKey.daily(RankingScope.ALL, tomorrow)

    // 10% 가중치로 복사
    rankingRepository.copyWithWeight(todayKey, tomorrowKey, 0.1)
    rankingRepository.setExpire(tomorrowKey)
}
```

**효과:**
- 새 윈도우 시작 시에도 랭킹 데이터 존재
- 이전 인기 상품 10% 반영 + 새 이벤트 90% 반영
- 자연스러운 순위 전환

### 5. 배치 처리

**Kafka Consumer에서 배치 단위로 처리:**
```kotlin
@KafkaListener(
    topics = ["catalog-events"],
    groupId = "commerce-streamer",
    containerFactory = "batchKafkaListenerContainerFactory"
)
fun consumeCatalogEvents(
    records: List<ConsumerRecord<String, String>>,
    ack: Acknowledgment
) {
    // 배치 단위로 점수 맵 구성
    val dailyScoreMap = mutableMapOf<Long, RankingScore>()
    val hourlyScoreMap = mutableMapOf<Long, RankingScore>()

    records.forEach { record ->
        // 이벤트 처리 및 점수 누적
    }

    // 한 번에 Redis 업데이트
    rankingRepository.incrementScoreBatch(dailyKey, dailyScoreMap)
    rankingRepository.incrementScoreBatch(hourlyKey, hourlyScoreMap)
}
```

**장점:**
- Redis 네트워크 호출 횟수 감소
- 처리 성능 향상
- 원자성 보장

## 구현된 컴포넌트

### 1. Shared Domain Layer (libs/domain-core)

**모듈 구조 개선**
- Round 9 리팩토링: 랭킹 도메인 타입을 공유 모듈로 분리
- commerce-api와 commerce-streamer가 공통으로 사용하는 타입을 `libs/domain-core`에 위치
- 각 모듈은 필요한 경우 RankingScoreCalculator로 확장

#### RankingKey
- Redis ZSET 키 생성 로직
- 시간 양자화 전략 구현
- **위치**: libs/domain-core/src/main/kotlin/com/loopers/domain/ranking/RankingKey.kt
```kotlin
data class RankingKey(
    val scope: RankingScope,      // ALL, CATEGORY, BRAND
    val window: TimeWindow,        // DAILY, HOURLY
    val timestamp: LocalDateTime,  // 시간 정보
) {
    fun toRedisKey(): String {
        return when (window) {
            TimeWindow.DAILY -> "ranking:${scope.value}:daily:${timestamp.format(DAILY_FORMAT)}"
            TimeWindow.HOURLY -> "ranking:${scope.value}:hourly:${timestamp.format(HOURLY_FORMAT)}"
        }
    }

    companion object {
        fun daily(scope: RankingScope, date: LocalDate): RankingKey
        fun hourly(scope: RankingScope, dateTime: LocalDateTime): RankingKey
        fun currentDaily(scope: RankingScope): RankingKey
        fun currentHourly(scope: RankingScope): RankingKey
    }
}
```

#### RankingScore (기본 타입)
- 랭킹 점수의 기본 타입
- 점수 연산 및 검증
- **위치**: libs/domain-core/src/main/kotlin/com/loopers/domain/ranking/RankingScore.kt
```kotlin
data class RankingScore(val value: Double) {
    init {
        require(value >= 0) { "랭킹 점수는 0 이상이어야 합니다: value=$value" }
    }

    operator fun plus(other: RankingScore): RankingScore

    operator fun times(multiplier: Double): RankingScore {
        require(multiplier >= 0) { "배수는 0 이상이어야 합니다: multiplier=$multiplier" }
        return RankingScore(this.value * multiplier)
    }

    companion object {
        fun zero(): RankingScore = RankingScore(0.0)
        fun sum(scores: List<RankingScore>): RankingScore
    }
}
```

#### RankingScoreCalculator (모듈별 확장)
- 각 모듈은 RankingScoreCalculator 객체로 점수 계산 로직 구현
- **commerce-api**: 고정 가중치 사용
  - 위치: apps/commerce-api/src/main/kotlin/com/loopers/domain/ranking/RankingScoreCalculator.kt
```kotlin
object RankingScoreCalculator {
    private const val WEIGHT_VIEW = 0.1
    private const val WEIGHT_LIKE = 0.2
    private const val WEIGHT_ORDER = 0.7

    fun fromView(): RankingScore = RankingScore(WEIGHT_VIEW)
    fun fromLike(): RankingScore = RankingScore(WEIGHT_LIKE)

    fun fromOrder(priceAtOrder: Long, quantity: Int): RankingScore {
        val totalAmount = priceAtOrder * quantity
        if (totalAmount <= 0) return RankingScore(0.0)

        val safeAmount = max(1.0, totalAmount.toDouble())
        val normalizedScore = 1.0 + ln(safeAmount)
        return RankingScore(WEIGHT_ORDER * normalizedScore)
    }
}
```

- **commerce-streamer**: 유연한 가중치 조정 지원
  - 위치: apps/commerce-streamer/src/main/kotlin/com/loopers/domain/ranking/RankingScoreCalculator.kt
```kotlin
object RankingScoreCalculator {
    fun fromView(weight: Double = 0.1): RankingScore = RankingScore(weight)
    fun fromLike(weight: Double = 0.2): RankingScore = RankingScore(weight)

    fun fromOrder(priceAtOrder: Long, quantity: Int, weight: Double = 0.7): RankingScore {
        val totalAmount = priceAtOrder * quantity
        if (totalAmount <= 0) return RankingScore(0.0)

        val safeAmount = max(1.0, totalAmount.toDouble())
        val normalizedScore = 1.0 + ln(safeAmount)
        return RankingScore(weight * normalizedScore)
    }
}
```

#### Ranking
- 랭킹 도메인 엔티티
- 순위, 점수, 상품 ID 포함
- **위치**: libs/domain-core/src/main/kotlin/com/loopers/domain/ranking/Ranking.kt
```kotlin
data class Ranking(
    val productId: Long,
    val score: RankingScore,
    val rank: Int,
) {
    init {
        require(rank > 0) { "순위는 1 이상이어야 합니다: rank=$rank" }
    }

    companion object {
        fun from(productId: Long, score: Double, rank: Int): Ranking
    }
}
```

#### RankingRepository
- 랭킹 저장소 인터페이스
- **위치**: libs/domain-core/src/main/kotlin/com/loopers/domain/ranking/RankingRepository.kt
```kotlin
interface RankingRepository {
    fun incrementScore(key: RankingKey, productId: Long, score: RankingScore): Double
    fun incrementScoreBatch(key: RankingKey, scoreMap: Map<Long, RankingScore>)
    fun getTopN(key: RankingKey, start: Int, end: Int): List<Ranking>
    fun getRank(key: RankingKey, productId: Long): Int?
    fun getScore(key: RankingKey, productId: Long): RankingScore?
    fun getCount(key: RankingKey): Long
    fun setExpire(key: RankingKey)
    fun copyWithWeight(sourceKey: RankingKey, targetKey: RankingKey, weight: Double)
}
```

### 2. Infrastructure Layer (commerce-streamer)

#### RankingRedisRepository
- Redis ZSET 기반 랭킹 저장소 구현체
```kotlin
@Repository
class RankingRedisRepository(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private val redisTemplate: RedisTemplate<String, String>,
) : RankingRepository {
    private val zSetOps: ZSetOperations<String, String> = redisTemplate.opsForZSet()

    override fun incrementScore(key: RankingKey, productId: Long, score: RankingScore): Double {
        val redisKey = key.toRedisKey()
        val member = productId.toString()
        return zSetOps.incrementScore(redisKey, member, score.value) ?: 0.0
    }

    override fun copyWithWeight(sourceKey: RankingKey, targetKey: RankingKey, weight: Double) {
        val items = zSetOps.reverseRangeWithScores(sourceRedisKey, 0, -1) ?: emptySet()
        items.forEach { item ->
            val newScore = (item.score ?: 0.0) * weight
            zSetOps.add(targetRedisKey, item.value!!, newScore)
        }
    }
}
```

#### KafkaEventConsumer (수정)
- Kafka 이벤트 수신 시 랭킹 점수 업데이트
```kotlin
@Component
class KafkaEventConsumer(
    private val rankingRepository: RankingRepository,
    // ... existing dependencies
) {
    @KafkaListener(topics = ["catalog-events"], ...)
    fun consumeCatalogEvents(records: List<ConsumerRecord<String, String>>, ack: Acknowledgment) {
        // 배치 단위로 점수 맵 구성
        val dailyScoreMap = mutableMapOf<Long, RankingScore>()
        val hourlyScoreMap = mutableMapOf<Long, RankingScore>()

        records.forEach { record ->
            when (eventType) {
                "ProductViewEvent" -> {
                    val event = objectMapper.readValue<ProductViewEvent>(payload)
                    val score = RankingScore.fromView()
                    dailyScoreMap.merge(event.productId, score) { old, new ->
                        RankingScore(old.value + new.value)
                    }
                    hourlyScoreMap.merge(event.productId, score) { old, new ->
                        RankingScore(old.value + new.value)
                    }
                }
                "LikeAddedEvent" -> {
                    val score = RankingScore.fromLike()
                    // ... 점수 누적
                }
            }
        }

        // 배치 업데이트
        val dailyKey = RankingKey.currentDaily(RankingScope.ALL)
        val hourlyKey = RankingKey.currentHourly(RankingScope.ALL)

        rankingRepository.incrementScoreBatch(dailyKey, dailyScoreMap)
        rankingRepository.incrementScoreBatch(hourlyKey, hourlyScoreMap)

        rankingRepository.setExpire(dailyKey)
        rankingRepository.setExpire(hourlyKey)
    }
}
```

#### RankingScheduler
- 콜드 스타트 방지를 위한 스케줄러
- **개선사항**: 타임존 명시 (Asia/Seoul)
```kotlin
@Component
class RankingScheduler(
    private val rankingRepository: RankingRepository,
) {
    /**
     * 일간 랭킹: 매일 23:50 (Asia/Seoul)
     * - 오늘의 랭킹 데이터를 10% 가중치로 내일 랭킹에 미리 복사
     */
    @Scheduled(cron = "0 50 23 * * *", zone = "Asia/Seoul")
    fun carryOverDailyRanking() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)

        val todayKey = RankingKey.daily(RankingScope.ALL, today)
        val tomorrowKey = RankingKey.daily(RankingScope.ALL, tomorrow)

        val todayCount = rankingRepository.getCount(todayKey)
        if (todayCount == 0L) {
            logger.warn("일간 랭킹 콜드 스타트 방지: 오늘 랭킹 데이터가 없어 복사하지 않음")
            return
        }

        rankingRepository.copyWithWeight(todayKey, tomorrowKey, 0.1)
        rankingRepository.setExpire(tomorrowKey)
    }

    /**
     * 시간별 랭킹: 매시간 50분 (Asia/Seoul)
     * - 현재 시간 랭킹 데이터를 10% 가중치로 다음 시간 랭킹에 미리 복사
     */
    @Scheduled(cron = "0 50 * * * *", zone = "Asia/Seoul")
    fun carryOverHourlyRanking() {
        // 유사 로직
    }
}
```

### 3. Application Layer (commerce-api)

#### RankingService
- 랭킹 조회 비즈니스 로직
- **개선사항**: 타임스탬프 파싱 에러 처리 강화
```kotlin
@Service
class RankingService(
    private val rankingRepository: RankingRepository,
    private val productRepository: ProductRepository,
) {
    fun getTopN(
        window: TimeWindow,
        timestamp: String,
        page: Int,
        size: Int
    ): Pair<List<Ranking>, Long> {
        require(page >= 1) { "페이지 번호는 1 이상이어야 합니다: page=$page" }
        require(size > 0) { "페이지 크기는 0보다 커야 합니다: size=$size" }

        // DateTimeParseException을 사용자 친화적인 예외로 변환
        val key = try {
            when (window) {
                TimeWindow.DAILY -> {
                    val date = LocalDate.parse(timestamp, DateTimeFormatter.ofPattern("yyyyMMdd"))
                    RankingKey.daily(RankingScope.ALL, date)
                }
                TimeWindow.HOURLY -> {
                    val dateTime = LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("yyyyMMddHH"))
                    RankingKey.hourly(RankingScope.ALL, dateTime)
                }
            }
        } catch (e: DateTimeParseException) {
            val expectedFormat = when (window) {
                TimeWindow.DAILY -> "yyyyMMdd (예: 20250906)"
                TimeWindow.HOURLY -> "yyyyMMddHH (예: 2025090614)"
            }
            throw IllegalArgumentException(
                "잘못된 날짜/시간 형식입니다. 예상 형식: $expectedFormat, 입력값: $timestamp",
                e
            )
        }

        val start = (page - 1) * size
        val end = start + size - 1

        val rankings = rankingRepository.getTopN(key, start, end)
        val totalCount = rankingRepository.getCount(key)

        return rankings to totalCount
    }
}
```

#### ProductFacade (수정)
- 상품 상세 조회에 일간 랭킹 정보 추가
```kotlin
fun getProductDetail(productId: Long): ProductDetailInfo {
    val product = productRepository.findById(productId)

    // 일간 랭킹 조회
    val dailyKey = RankingKey.currentDaily(RankingScope.ALL)
    val rank = rankingRepository.getRank(dailyKey, productId)
    val score = rankingRepository.getScore(dailyKey, productId)

    val rankingInfo = if (rank != null && score != null) {
        ProductRankingInfo(
            rank = rank,
            score = score.value,
            window = TimeWindow.DAILY,
            timestamp = dailyKey.timestamp.format(...)
        )
    } else null

    return ProductDetailInfo.from(product, rankingInfo)
}
```

### 4. API Layer (commerce-api)

#### RankingV1Controller
- **개선사항**: TimeWindow 값 검증 강화 (400 에러 반환)
```kotlin
@RestController
@RequestMapping("/api/v1/rankings")
class RankingV1Controller(
    private val rankingFacade: RankingFacade,
) : RankingV1ApiSpec {
    @GetMapping
    override fun getRankings(
        @RequestParam(defaultValue = "DAILY") window: String,
        @RequestParam(required = false) date: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<RankingV1Dto.RankingPageResponse> {
        // IllegalArgumentException을 catch하여 명확한 400 에러로 변환
        val timeWindow = try {
            TimeWindow.valueOf(window.uppercase())
        } catch (e: IllegalArgumentException) {
            val validValues = TimeWindow.entries.joinToString(", ") { it.name }
            throw IllegalArgumentException(
                "잘못된 window 값입니다. 가능한 값: $validValues, 입력값: $window",
                e
            )
        }

        val rankingPageInfo = rankingFacade.getRankingPage(timeWindow, date, page, size)

        return RankingV1Dto.RankingPageResponse.from(rankingPageInfo)
            .let { ApiResponse.success(it) }
    }
}
```

**API 사용 예시:**
```bash
# 일간 랭킹 조회 (오늘)
GET /api/v1/rankings?window=DAILY&page=1&size=20

# 일간 랭킹 조회 (특정 날짜)
GET /api/v1/rankings?window=DAILY&date=20251220&page=1&size=20

# 시간별 랭킹 조회 (현재 시간)
GET /api/v1/rankings?window=HOURLY&page=1&size=20

# 시간별 랭킹 조회 (특정 시간)
GET /api/v1/rankings?window=HOURLY&date=2025122014&page=1&size=20
```

## 실행 방법

### 1. Redis 인프라 시작

```bash
cd docker
docker-compose -f infra-compose.yml up -d redis
```

- Redis: `localhost:16379`

### 2. 환경 변수 설정

```bash
export REDIS_HOST=localhost
export REDIS_PORT=16379
```

### 3. 애플리케이션 실행

**Streamer (랭킹 점수 업데이트):**
```bash
./gradlew :apps:commerce-streamer:bootRun
```

**API (랭킹 조회):**
```bash
./gradlew :apps:commerce-api:bootRun
```

### 4. 동작 확인

#### Redis ZSET 확인
```bash
# Redis CLI 접속
docker exec -it docker-redis-1 redis-cli

# 일간 랭킹 확인
ZREVRANGE ranking:all:daily:20251220 0 9 WITHSCORES

# 시간별 랭킹 확인
ZREVRANGE ranking:all:hourly:2025122014 0 9 WITHSCORES

# 특정 상품 순위 확인
ZREVRANK ranking:all:daily:20251220 100

# 특정 상품 점수 확인
ZSCORE ranking:all:daily:20251220 100
```

#### 랭킹 API 호출
```bash
# 일간 랭킹 Top 20
curl "http://localhost:8080/api/v1/rankings?window=DAILY&page=1&size=20"

# 시간별 랭킹 Top 10
curl "http://localhost:8080/api/v1/rankings?window=HOURLY&page=1&size=10"
```

#### 상품 상세 조회 (랭킹 정보 포함)
```bash
curl "http://localhost:8080/api/v1/products/100"
```

## 모니터링 포인트

### Redis ZSET
- **메모리 사용량**: 랭킹 데이터 크기
- **TTL 설정**: 만료된 키 자동 삭제 확인
- **점수 분포**: 상위권 점수 편차 확인

### 스케줄러
- **Carry-Over 성공률**: 스케줄러 실행 성공/실패
- **복사된 데이터 수**: 이전 윈도우 데이터 크기
- **실행 시간**: 스케줄러 처리 시간 (50분에 실행 → 59분 전 완료)

### Consumer
- **배치 크기**: Kafka Consumer 배치당 레코드 수
- **처리 시간**: 배치 처리 소요 시간
- **점수 업데이트 빈도**: 초당 ZINCRBY 호출 수

### API
- **응답 시간**: 랭킹 조회 API 응답 시간
- **캐시 히트율**: Redis 조회 성공률
- **Top-N 조회 분포**: 요청 페이지 분포 (1페이지가 대부분)

## 개선 사항

### 완료
- ✅ Redis ZSET 기반 랭킹 저장소
- ✅ 가중치 기반 점수 계산
- ✅ 시간 양자화 (일간/시간별)
- ✅ 콜드 스타트 방지 (Score Carry-Over)
- ✅ 배치 처리 (Kafka Consumer)
- ✅ TTL 자동 설정
- ✅ 랭킹 조회 API
- ✅ 상품 상세에 랭킹 정보 추가
- ✅ **[Round 9 리팩토링]** 공유 도메인 모듈 분리 (libs/domain-core)
- ✅ **[Round 9 리팩토링]** RankingScore 검증 강화 (negative multiplier, ln(0) 방지)
- ✅ **[Round 9 리팩토링]** 에러 처리 개선 (타임스탬프 파싱, TimeWindow 검증)
- ✅ **[Round 9 리팩토링]** 스케줄러 타임존 명시 (Asia/Seoul)

### TODO
- ⬜ 카테고리별 랭킹 (RankingScope.CATEGORY)
- ⬜ 브랜드별 랭킹 (RankingScope.BRAND)
- ⬜ 랭킹 변동 추이 (이전 순위 대비 상승/하락)
- ⬜ 실시간 랭킹 캐시 (API 응답 캐싱)
- ⬜ 랭킹 조회 이력 수집 (어떤 랭킹이 많이 조회되는지)
- ⬜ A/B 테스트 (가중치 조정 실험)
- ⬜ 랭킹 알림 (순위 변동 시 푸시)
- ⬜ 주간/월간 랭킹

## 주요 파일 위치

### libs/domain-core (공유 도메인 모듈) - **신규**

```
libs/domain-core/
├── build.gradle.kts                   # 공유 모듈 빌드 설정
└── src/main/kotlin/com/loopers/domain/ranking/
    ├── RankingKey.kt                  # Redis 키 생성 로직 (공유)
    ├── RankingScore.kt                # 랭킹 점수 기본 타입 (공유)
    ├── Ranking.kt                     # 랭킹 엔티티 (공유)
    ├── RankingRepository.kt           # 랭킹 저장소 인터페이스 (공유)
    ├── RankingScope.kt                # 랭킹 범위 Enum (공유)
    └── TimeWindow.kt                  # 시간 윈도우 Enum (공유)
```

### commerce-streamer (랭킹 업데이트)

```
apps/commerce-streamer/src/main/kotlin/com/loopers/
├── domain/
│   ├── ranking/
│   │   └── RankingScoreCalculator.kt  # 점수 계산 로직 (유연한 가중치)
│   └── event/
│       └── ProductViewEvent.kt        # 상품 조회 이벤트
├── infrastructure/
│   ├── ranking/
│   │   ├── RankingRedisRepository.kt  # Redis ZSET 구현체
│   │   └── RankingScheduler.kt        # 콜드 스타트 방지 스케줄러
│   └── kafka/
│       └── KafkaEventConsumer.kt      # 랭킹 점수 업데이트 (수정)
└── CommerceStreamerApplication.kt     # @EnableScheduling 추가
```

### commerce-api (랭킹 조회)

```
apps/commerce-api/src/main/kotlin/com/loopers/
├── domain/
│   ├── ranking/
│   │   └── RankingScoreCalculator.kt  # 점수 계산 로직 (고정 가중치)
│   └── service/
│       └── RankingService.kt          # 랭킹 조회 비즈니스 로직
├── infrastructure/
│   └── ranking/
│       └── RankingRedisRepository.kt  # Redis ZSET 구현체
├── application/
│   ├── product/
│   │   ├── ProductFacade.kt           # 상품 상세에 랭킹 정보 추가 (수정)
│   │   └── ProductDetailInfo.kt       # 랭킹 정보 필드 추가 (수정)
│   └── ranking/
│       ├── RankingFacade.kt           # 랭킹 조회 Facade
│       ├── RankingPageInfo.kt         # 랭킹 페이지 정보
│       ├── RankingItemInfo.kt         # 랭킹 항목 정보
│       └── RankingProductInfo.kt      # 랭킹용 상품 정보
└── interfaces/
    └── api/
        └── ranking/
            ├── RankingV1Controller.kt # 랭킹 조회 API (에러 처리 개선)
            ├── RankingV1Dto.kt        # 랭킹 응답 DTO
            └── RankingV1ApiSpec.kt    # API 명세
```

### 설정 파일

```
apps/commerce-streamer/src/main/resources/
└── application.yml                    # @EnableScheduling 활성화

apps/commerce-api/src/main/resources/
└── application.yml                    # (변경 없음)

modules/redis/src/main/resources/
└── redis.yml                          # Redis Master/Replica 설정
```

## 참고 자료

- [Redis ZSET Commands](https://redis.io/commands/?group=sorted-set)
- [Time Quantization in Ranking Systems](https://redis.io/solutions/leaderboards/)
- [Cold Start Problem in Recommendation Systems](https://en.wikipedia.org/wiki/Cold_start_(recommender_systems))
- [Spring @Scheduled](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [Weighted Scoring in E-commerce](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-function-score-query.html)
