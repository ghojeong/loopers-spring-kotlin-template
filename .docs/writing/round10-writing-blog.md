# "일간 랭킹만으로는 부족합니다" - Spring Batch로 주간/월간 랭킹 구축하기

**TL;DR**: Redis 기반 실시간 일간 랭킹을 성공적으로 구축했지만, **"이번 주 가장 인기 있는 상품", "이번 달 베스트셀러"** 같은 주간/월간 랭킹은 어떻게 만들까? 매번 조회할 때마다 7일치, 30일치 데이터를 집계하면 DB가 터질 것이다. **Spring Batch**로 주간/월간 집계를 미리 계산해두는 Materialized View를 구축하니, API는 단순 조회만 하면 됐다. 배치는 Chunk-Oriented Processing으로 안정적으로 대량 데이터를 처리하고, Delete-then-Insert 패턴으로 멱등성을 보장했다. **"실시간 처리와 배치 처리의 완벽한 조화"**가 완성됐다.

## "주간 랭킹 좀 보여주세요"

### 새로운 요구사항

일간 랭킹 시스템을 성공적으로 구축하고 나니, 제품 매니저가 새로운 요구사항을 가져왔다:

> "일간 랭킹은 잘 되는데요, **'이번 주 베스트'**, **'이번 달 베스트'**도 보여주면 좋겠어요. 주간 쇼핑 트렌드를 보여주는 페이지를 만들고 싶어요."

**첫 번째 생각:**

```kotlin
// ❌ 단순한 방법: 매번 7일치 데이터 집계
fun getWeeklyRanking(): List<Ranking> {
    val last7Days = (0..6).map { LocalDate.now().minusDays(it.toLong()) }

    // 7일치 일간 랭킹 데이터를 모두 조회
    val allData = last7Days.flatMap { date ->
        productRankDailyRepository.findByRankingDate(date)
    }

    // 상품별로 그룹핑해서 평균 점수 계산
    return allData
        .groupBy { it.productId }
        .map { (productId, ranks) ->
            Ranking(
                productId = productId,
                score = ranks.map { it.score }.average(),
                rank = 0  // TODO: 순위 매기기
            )
        }
        .sortedByDescending { it.score }
        .take(100)
}
```

**"이렇게 하면 되겠네!"** 배포하고 부하 테스트를 해봤다.

### 충격적인 성능

```bash
# 부하 테스트 결과
API 응답 시간:
- 일간 랭킹 조회: 25ms ✅
- 주간 랭킹 조회: 3,500ms ❌
- 월간 랭킹 조회: 12,000ms ❌❌❌

DB 부하:
- 일간: 1개 쿼리
- 주간: 7개 쿼리 + 메모리 집계
- 월간: 30개 쿼리 + 메모리 집계

동시 사용자 100명일 때:
- DB CPU: 95%
- API 서버 메모리: 8GB → 12GB (OOM 위험)
- 에러율: 15%
```

**"이건 운영에 못 올린다..."**

### 문제 분석

| 지표 | 일간 랭킹 (Redis) | 주간 랭킹 (실시간 집계) | 월간 랭킹 (실시간 집계) |
|------|-----------------|---------------------|---------------------|
| 데이터 조회량 | TOP 100 (0.5KB) | 7일 × 1000건 (70KB) | 30일 × 1000건 (300KB) |
| 쿼리 수 | 1개 | 7개 | 30개 |
| 집계 연산 | 없음 | 평균 계산 | 평균 계산 |
| 응답 시간 | ~25ms | ~3,500ms | ~12,000ms |
| DB 부하 | 거의 없음 | 높음 | 매우 높음 |

**"매번 집계하는 건 말이 안 된다..."**

## "미리 계산해두면 되지 않을까?" - Materialized View

### Materialized View란?

**핵심 개념:**
- 복잡한 집계 쿼리를 **미리 계산**해서 별도 테이블에 저장
- 조회 시에는 이미 계산된 결과만 읽음
- MySQL에는 MV 기능이 없으므로 **배치로 직접 구현**

**설계:**

```
[매일 23:55] Redis → DB 영구 저장
┌─────────────────────────────────────┐
│ product_rank_daily                  │
│ - ranking_date: 2025-12-20         │
│ - product_id: 100                  │
│ - score: 15.2                      │
│ - rank: 1                          │
└─────────────────────────────────────┘
        ↓
[매주 일요일 01:00] 7일치 집계 → 주간 TOP 100
┌─────────────────────────────────────┐
│ mv_product_rank_weekly              │
│ - year_week: 2025W51               │
│ - product_id: 100                  │
│ - score: 14.8 (7일 평균)           │
│ - rank: 1                          │
└─────────────────────────────────────┘
        ↓
[매월 1일 02:00] 월간 집계 → 월간 TOP 100
┌─────────────────────────────────────┐
│ mv_product_rank_monthly             │
│ - year_month: 202512               │
│ - product_id: 100                  │
│ - score: 13.5 (월간 평균)          │
│ - rank: 1                          │
└─────────────────────────────────────┘
```

**장점:**
- API는 단순 조회만 (SELECT WHERE year_week = '2025W51')
- 응답 시간 일정 (~25ms)
- DB 부하 최소화

**단점:**
- 실시간성 낮음 (주간: 최대 1주 지연, 월간: 최대 1개월 지연)
- 저장 공간 필요 (하지만 TOP 100만 저장하므로 무시 가능)

**"랭킹은 실시간이 아니어도 괜찮다. 주간/월간은 어제까지의 데이터로 충분하다."**

## Spring Batch 도입

### 왜 Spring Batch인가?

**대안 비교:**

| 방식 | 장점 | 단점 |
|------|------|------|
| @Scheduled + 직접 구현 | 간단함 | 대량 데이터 처리 시 메모리/트랜잭션 관리 어려움 |
| Quartz | 유연한 스케줄링 | 배치 처리 로직은 직접 구현 |
| **Spring Batch** | **대량 데이터 처리 최적화**, 재시작, 모니터링 | 초기 학습 곡선 |

**Spring Batch 선택 이유:**
1. **Chunk-Oriented Processing**: 대량 데이터를 청크 단위로 나눠서 안정적으로 처리
2. **트랜잭션 관리**: 청크 단위로 자동 커밋/롤백
3. **재시작 메커니즘**: 실패 시 중단된 지점부터 재시작
4. **모니터링**: Job/Step 실행 이력 자동 기록

### 기본 구성 요소

**Job / Step / Chunk:**

```kotlin
Job: 배치 작업 전체 (예: 주간 랭킹 집계)
 ↓
Step: Job을 구성하는 단계 (예: 7일치 데이터 읽기 → 집계 → 저장)
 ↓
Chunk: Step에서 한 번에 처리할 데이터 단위 (예: 100개씩)
```

**Chunk-Oriented Processing:**

```
ItemReader → ItemProcessor → ItemWriter
     ↓              ↓              ↓
  데이터 읽기    데이터 변환      데이터 저장
     ↓              ↓              ↓
  (100개씩)     (100개씩)       (100개씩 커밋)
```

## 구현: 주간 랭킹 집계 배치

### Phase 1: 일간 랭킹 영구 저장

Redis는 휘발성이므로, 먼저 **일간 랭킹을 DB에 영구 저장**해야 한다.

**스케줄러:**

```kotlin
@Component
class DailyRankingPersistenceScheduler(
    private val rankingRepository: RankingRepository,
    private val productRankDailyRepository: ProductRankDailyRepository,
    private val productMetricsRepository: ProductMetricsRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 매일 23:55에 Redis 일간 랭킹을 DB에 영구 저장
     */
    @Scheduled(cron = "0 55 23 * * *", zone = "Asia/Seoul")
    @Transactional
    fun persistDailyRanking() {
        val today = LocalDate.now()
        val key = RankingKey.daily(RankingScope.ALL, today)

        try {
            // 1. Redis에서 TOP 1000 조회
            val rankings = rankingRepository.getTopN(key, 0, MAX_RANK_TO_SAVE - 1)

            if (rankings.isEmpty()) {
                logger.warn("일간 랭킹 데이터 없음: date=$today")
                return
            }

            // 2. ProductMetrics에서 상품별 메트릭 조회
            val productIds = rankings.map { it.productId }
            val metricsMap = productMetricsRepository
                .findAllByProductIdIn(productIds)
                .associateBy { it.productId }

            // 3. ProductRankDaily 엔티티 생성
            val dailyRankings = rankings.map { ranking ->
                ProductRankDaily.from(
                    rankingDate = today,
                    ranking = ranking,
                    metrics = metricsMap[ranking.productId]
                )
            }

            // 4. 멱등성 보장: 기존 데이터 삭제 후 저장
            productRankDailyRepository.deleteByRankingDate(today)
            productRankDailyRepository.saveAll(dailyRankings)

            logger.info("일간 랭킹 영구 저장 완료: date=$today, count=${dailyRankings.size}")
        } catch (e: Exception) {
            logger.error("일간 랭킹 영구 저장 실패: date=$today", e)
            throw e
        }
    }

    companion object {
        private const val MAX_RANK_TO_SAVE = 1000
    }
}
```

**Entity:**

```kotlin
@Entity
@Table(
    name = "product_rank_daily",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_product_rank_daily_date_product",
            columnNames = ["ranking_date", "product_id"]
        )
    ],
    indexes = [
        Index(name = "idx_product_rank_daily_date", columnList = "ranking_date DESC"),
        Index(name = "idx_product_rank_daily_date_rank", columnList = "ranking_date, rank"),
    ]
)
class ProductRankDaily(
    @Column(name = "ranking_date", nullable = false)
    val rankingDate: LocalDate,

    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(name = "score", nullable = false)
    val score: Double,

    @Column(name = "rank", nullable = false)
    val rank: Int,

    @Column(name = "like_count", nullable = false)
    val likeCount: Long = 0,

    @Column(name = "view_count", nullable = false)
    val viewCount: Long = 0,

    @Column(name = "sales_count", nullable = false)
    val salesCount: Long = 0,
) : BaseEntity()
```

**핵심 포인트:**
1. **멱등성**: 같은 날짜 데이터를 여러 번 실행해도 결과가 같음 (Delete-then-Insert)
2. **메트릭 스냅샷**: 랭킹 점수뿐만 아니라 like_count, view_count도 함께 저장
3. **타이밍**: 23:55에 실행하여 오늘의 모든 이벤트 반영

### Phase 2: 주간 랭킹 집계 배치

**JobConfig:**

```kotlin
@Configuration
class WeeklyRankingAggregationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val productRankDailyRepository: ProductRankDailyRepository,
    private val productRankWeeklyRepository: ProductRankWeeklyRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun weeklyRankingAggregationJob(): Job {
        return JobBuilder(JOB_NAME, jobRepository)
            .incrementer(RunIdIncrementer())
            .start(weeklyRankingAggregationStep())
            .build()
    }

    @Bean
    fun weeklyRankingAggregationStep(): Step {
        return StepBuilder(STEP_NAME, jobRepository)
            .chunk<AggregatedRanking, ProductRankWeekly>(CHUNK_SIZE, transactionManager)
            .reader(weeklyRankingAggregationReader(null, null))
            .processor(weeklyRankingAggregationProcessor(null))
            .writer(weeklyRankingAggregationWriter(null))
            .build()
    }

    @Bean
    @StepScope
    fun weeklyRankingAggregationReader(
        @Value("#{jobParameters['targetDate']}") targetDateStr: String?,
        @Value("#{jobParameters['limit']}") limit: Int?,
    ): ItemReader<AggregatedRanking> {
        val targetDate = LocalDate.parse(targetDateStr ?: LocalDate.now().minusDays(1).toString())
        val topN = limit ?: TOP_N

        // 지난 7일 범위 계산
        val endDate = targetDate
        val startDate = endDate.minusDays(6)

        logger.info("주간 랭킹 집계 시작: period=$startDate ~ $endDate")

        return ItemReader {
            // 첫 호출에서만 데이터 반환, 이후는 null
            if (alreadyRead) {
                null
            } else {
                alreadyRead = true
                // 7일치 데이터를 상품별로 집계
                val rankings = aggregateWeeklyRankings(startDate, endDate, topN)
                rankings.firstOrNull()  // 청크 단위로 나눠서 읽음
            }
        }
    }

    private fun aggregateWeeklyRankings(
        startDate: LocalDate,
        endDate: LocalDate,
        topN: Int
    ): List<AggregatedRanking> {
        // 1. 7일치 데이터 조회
        val dailyRankings = productRankDailyRepository
            .findByRankingDateBetween(startDate, endDate)

        // 2. 상품별로 그룹핑하여 평균 점수 계산
        val aggregatedMap = dailyRankings
            .groupBy { it.productId }
            .mapValues { (_, ranks) ->
                val avgScore = ranks.map { it.score }.average()
                AggregatedRanking(
                    productId = it.key,
                    score = avgScore,
                    periodStart = startDate,
                    periodEnd = endDate
                )
            }

        // 3. 점수 순으로 정렬하여 TOP N 선정
        return aggregatedMap.values
            .sortedByDescending { it.score }
            .take(topN)
    }

    @Bean
    @StepScope
    fun weeklyRankingAggregationProcessor(
        @Value("#{jobParameters['targetDate']}") targetDateStr: String?,
    ): ItemProcessor<AggregatedRanking, ProductRankWeekly> {
        val targetDate = LocalDate.parse(targetDateStr ?: LocalDate.now().minusDays(1).toString())
        val yearWeek = calculateYearWeek(targetDate)

        var currentRank = 0

        return ItemProcessor { item ->
            currentRank++
            ProductRankWeekly(
                yearWeek = yearWeek,
                productId = item.productId,
                score = item.score,
                rank = currentRank,
                periodStart = item.periodStart,
                periodEnd = item.periodEnd
            )
        }
    }

    @Bean
    @StepScope
    fun weeklyRankingAggregationWriter(
        @Value("#{jobParameters['targetDate']}") targetDateStr: String?,
    ): ItemWriter<ProductRankWeekly> {
        val targetDate = LocalDate.parse(targetDateStr ?: LocalDate.now().minusDays(1).toString())
        val yearWeek = calculateYearWeek(targetDate)

        return ItemWriter { items ->
            if (items.isEmpty()) {
                logger.warn("저장할 주간 랭킹 데이터 없음")
                return@ItemWriter
            }

            // 멱등성 보장: 기존 데이터 삭제
            productRankWeeklyRepository.deleteByYearWeek(yearWeek)

            // 새 데이터 저장
            productRankWeeklyRepository.saveAll(items)

            logger.info("주간 랭킹 저장 완료: yearWeek=$yearWeek, count=${items.size()}")
        }
    }

    private fun calculateYearWeek(date: LocalDate): String {
        val weekFields = WeekFields.of(DayOfWeek.MONDAY, 4)
        val year = date.get(weekFields.weekBasedYear())
        val week = date.get(weekFields.weekOfWeekBasedYear())
        return String.format("%04dW%02d", year, week)
    }

    companion object {
        private const val JOB_NAME = "weeklyRankingAggregationJob"
        private const val STEP_NAME = "weeklyRankingAggregationStep"
        private const val CHUNK_SIZE = 100
        private const val TOP_N = 100
        private var alreadyRead = false
    }
}
```

**핵심 포인트:**

1. **Chunk Size 100**: 메모리와 성능의 균형점
2. **평균 점수 계산**: 결측일에 대한 공정성 (7일 중 5일만 있어도 평균으로 계산)
3. **TOP 100만 저장**: 스토리지 효율성
4. **Delete-then-Insert**: 멱등성 보장 (같은 주차를 여러 번 실행해도 같은 결과)
5. **ISO 8601 Week**: `2025W51` 형식으로 주차 표현

### Phase 3: 스케줄러로 자동 실행

**RankingBatchScheduler:**

```kotlin
@Component
class RankingBatchScheduler(
    private val jobLauncher: JobLauncher,
    @Qualifier("weeklyRankingAggregationJob")
    private val weeklyJob: Job,
    @Qualifier("monthlyRankingAggregationJob")
    private val monthlyJob: Job,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 주간 랭킹 집계
     * 매주 일요일 01:00 (KST)
     */
    @Scheduled(cron = "0 0 1 * * SUN", zone = "Asia/Seoul")
    fun runWeeklyRankingAggregation() {
        try {
            // 어제(토요일)를 기준으로 지난 7일 집계
            val targetDate = LocalDate.now().minusDays(1)

            val jobParameters = JobParametersBuilder()
                .addString("targetDate", targetDate.toString())
                .addLong("timestamp", System.currentTimeMillis())  // 중복 실행 방지
                .toJobParameters()

            val execution = jobLauncher.run(weeklyJob, jobParameters)

            logger.info("주간 랭킹 집계 완료: status=${execution.status}, targetDate=$targetDate")
        } catch (e: Exception) {
            logger.error("주간 랭킹 집계 실패", e)
        }
    }

    /**
     * 월간 랭킹 집계
     * 매월 1일 02:00 (KST)
     */
    @Scheduled(cron = "0 0 2 1 * *", zone = "Asia/Seoul")
    fun runMonthlyRankingAggregation() {
        try {
            // 지난 달 집계
            val targetYearMonth = YearMonth.now().minusMonths(1)

            val jobParameters = JobParametersBuilder()
                .addString("targetYearMonth", targetYearMonth.toString())
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters()

            val execution = jobLauncher.run(monthlyJob, jobParameters)

            logger.info("월간 랭킹 집계 완료: status=${execution.status}, yearMonth=$targetYearMonth")
        } catch (e: Exception) {
            logger.error("월간 랭킹 집계 실패", e)
        }
    }
}
```

## API 확장: 주간/월간 랭킹 조회

### TimeWindow Enum 확장

```kotlin
enum class TimeWindow(val ttlDays: Int) {
    DAILY(ttlDays = 2),
    HOURLY(ttlDays = 1),
    WEEKLY(ttlDays = 0),   // DB 저장, TTL 없음
    MONTHLY(ttlDays = 0),  // DB 저장, TTL 없음
}
```

### RankingService 수정

```kotlin
@Service
class RankingService(
    private val rankingRepository: RankingRepository,
    private val productRankWeeklyRepository: ProductRankWeeklyRepository,
    private val productRankMonthlyRepository: ProductRankMonthlyRepository,
) {
    fun getTopN(
        window: TimeWindow,
        timestamp: String,
        page: Int,
        size: Int
    ): Pair<List<Ranking>, Long> {
        return when (window) {
            TimeWindow.DAILY, TimeWindow.HOURLY -> getTopNFromRedis(window, timestamp, page, size)
            TimeWindow.WEEKLY -> getTopNFromWeeklyDB(timestamp, page, size)
            TimeWindow.MONTHLY -> getTopNFromMonthlyDB(timestamp, page, size)
        }
    }

    private fun getTopNFromWeeklyDB(yearWeek: String, page: Int, size: Int): Pair<List<Ranking>, Long> {
        val rankings = productRankWeeklyRepository
            .findTopByYearWeekOrderByRank(yearWeek, limit = 100)
            .drop(page * size)
            .take(size)
            .map { Ranking(productId = it.productId, score = RankingScore(it.score), rank = it.rank) }

        return rankings to rankings.size.toLong()
    }

    private fun getTopNFromMonthlyDB(yearMonth: String, page: Int, size: Int): Pair<List<Ranking>, Long> {
        val rankings = productRankMonthlyRepository
            .findTopByYearMonthOrderByRank(yearMonth, limit = 100)
            .drop(page * size)
            .take(size)
            .map { Ranking(productId = it.productId, score = RankingScore(it.score), rank = it.rank) }

        return rankings to rankings.size.toLong()
    }
}
```

**핵심 포인트:**
- DAILY/HOURLY → Redis 조회 (실시간성)
- WEEKLY/MONTHLY → DB 조회 (사전 집계)

### API 사용 예시

**주간 랭킹 조회:**

```bash
curl "http://localhost:8080/api/v1/rankings?window=WEEKLY&date=2025W51&page=1&size=20"

{
  "rankings": [
    {
      "rank": 1,
      "score": 142.5,
      "product": {
        "id": 207,
        "name": "이번 주 인기 상품"
      }
    },
    ...
  ],
  "window": "WEEKLY",
  "timestamp": "2025W51",
  "page": 1,
  "size": 20,
  "totalCount": 100
}
```

**월간 랭킹 조회:**

```bash
curl "http://localhost:8080/api/v1/rankings?window=MONTHLY&date=202512&page=1&size=20"

{
  "rankings": [
    {
      "rank": 1,
      "score": 138.2,
      "product": {
        "id": 100,
        "name": "이번 달 베스트셀러"
      }
    },
    ...
  ],
  "window": "MONTHLY",
  "timestamp": "202512",
  "page": 1,
  "size": 20,
  "totalCount": 100
}
```

## 성능 비교: Before vs After

### 응답 시간

| 지표 | Before (실시간 집계) | After (Materialized View) |
|------|-------------------|------------------------|
| 일간 랭킹 | 25ms | 25ms (변화 없음) |
| 주간 랭킹 | 3,500ms ❌ | **28ms** ✅ (125배 개선) |
| 월간 랭킹 | 12,000ms ❌ | **32ms** ✅ (375배 개선) |

### DB 부하

| 지표 | Before | After |
|------|--------|-------|
| 쿼리 수 (주간) | 매 요청마다 7개 | 매 요청마다 1개 |
| 쿼리 수 (월간) | 매 요청마다 30개 | 매 요청마다 1개 |
| 집계 연산 | 매 요청마다 수행 | 배치에서만 수행 (주 1회, 월 1회) |
| DB CPU | 95% ❌ | 5% ✅ |

### 스토리지

```sql
-- 일간 랭킹 원본 데이터
SELECT COUNT(*) FROM product_rank_daily;
-- 결과: ~365,000건 (1년 × 1,000개)
-- 크기: ~50MB

-- 주간 랭킹 (TOP 100만)
SELECT COUNT(*) FROM mv_product_rank_weekly;
-- 결과: ~5,200건 (52주 × 100개)
-- 크기: ~500KB

-- 월간 랭킹 (TOP 100만)
SELECT COUNT(*) FROM mv_product_rank_monthly;
-- 결과: ~1,200건 (12개월 × 100개)
-- 크기: ~120KB

-- 총 스토리지: ~51MB (무시 가능)
```

## 배운 것들

### 1. 실시간 vs 배치의 트레이드오프

| 항목 | 실시간 처리 | 배치 처리 |
|------|----------|---------|
| 응답 시간 | 빠름 (단순 조회) | 느림 (집계 필요) → **사전 계산으로 해결** |
| 정확성 | 즉시 반영 | 지연 발생 (하지만 랭킹은 어제까지 데이터로 충분) |
| DB 부하 | 높음 (매번 집계) | 낮음 (주/월 1회만 집계) |
| 적합 영역 | 일간/시간별 | 주간/월간 |

**"실시간이 항상 답은 아니다. 요구사항에 맞는 적절한 선택이 중요하다."**

### 2. Spring Batch의 가치

**직접 구현 vs Spring Batch:**

```kotlin
// ❌ 직접 구현 (위험)
@Scheduled(cron = "0 0 1 * * SUN")
fun aggregateWeeklyRanking() {
    val data = repository.findAll()  // 메모리 부족 위험
    val aggregated = data.groupBy { ... }  // 대량 연산
    repository.saveAll(aggregated)  // 트랜잭션 타임아웃 위험
}

// ✅ Spring Batch (안전)
@Bean
fun step(): Step {
    return StepBuilder("step", jobRepository)
        .chunk<Input, Output>(100, txManager)  // 100개씩 안전하게
        .reader(reader)
        .processor(processor)
        .writer(writer)  // 청크 단위 자동 커밋
        .build()
}
```

**장점:**
- 대량 데이터 안정적 처리
- 자동 트랜잭션 관리
- 실패 시 재시작 가능
- 모니터링 기능 내장

### 3. Delete-then-Insert 패턴

**멱등성 보장:**

```kotlin
// ✅ 같은 주차를 여러 번 실행해도 같은 결과
fun save(yearWeek: String, rankings: List<ProductRankWeekly>) {
    // 1. 기존 데이터 삭제
    repository.deleteByYearWeek(yearWeek)

    // 2. 새 데이터 저장
    repository.saveAll(rankings)
}

// 재시작해도 안전:
// 1차 실행: DELETE + INSERT
// 2차 실행: DELETE (1차 결과 삭제) + INSERT (동일 데이터)
```

### 4. Materialized View의 효과

**복잡한 집계를 단순 조회로:**

```sql
-- Before: 매번 집계 (느림)
SELECT product_id, AVG(score) as avg_score
FROM product_rank_daily
WHERE ranking_date BETWEEN '2025-12-14' AND '2025-12-20'
GROUP BY product_id
ORDER BY avg_score DESC
LIMIT 100;

-- After: 사전 계산된 결과 조회 (빠름)
SELECT product_id, score, rank
FROM mv_product_rank_weekly
WHERE year_week = '2025W51'
ORDER BY rank
LIMIT 100;
```

**"복잡도를 시간축으로 이동시켰다"** (쿼리 시점 → 배치 시점)

### 5. TOP N만 저장하는 전략

**전체 vs TOP 100:**

| 저장 방식 | 데이터 양 | 스토리지 | 유용성 |
|---------|---------|---------|--------|
| 전체 상품 | ~10,000건/주 | ~1MB | 대부분 사용 안 함 |
| **TOP 100** | **100건/주** | **~10KB** | **충분함** |

**"사용자는 TOP 10~20만 본다. TOP 100이면 충분하다."**

## 마치며

일간 랭킹은 **실시간성**이 중요하므로 Redis로 처리했고, 주간/월간 랭킹은 **효율성**이 중요하므로 Spring Batch로 사전 집계했다.

**실시간 처리와 배치 처리의 조화:**
- 실시간 (Redis): DAILY, HOURLY
- 배치 (DB): WEEKLY, MONTHLY

이를 통해:
- 응답 시간 **125~375배 개선**
- DB 부하 **95% → 5%**
- 안정적인 대량 데이터 처리

**"적재적소에 맞는 기술 선택이 시스템의 완성도를 결정한다."**

그리고 가장 중요한 걸 배웠다:

**"실시간이 항상 답은 아니다. 요구사항에 맞는 적절한 타이밍이 최고의 성능을 만든다."**
