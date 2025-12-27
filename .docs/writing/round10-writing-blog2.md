# Weekly I Learned 10 (Spring Batch로 주간/월간 랭킹 만들기)

## "주간 랭킹 API가 12초 걸리는데요?"

이번 주는 **Spring Batch를 활용한 주간/월간 상품 랭킹 시스템**을 만들었다. 지난주 실시간 일간 랭킹에 이어 이번엔 주간/월간 집계다.

"7일치 데이터 집계하면 끝 아냐?"라고 생각했는데... 완전히 다른 세계였다.

배치는 **실시간이 아니라도 괜찮은 것들을 미리 계산해두는 전략**이었다.

## 실무 요구사항: 이번 주 베스트 상품을 보여주세요

지난주에 실시간 일간 랭킹을 성공적으로 만들고 나니, PM님이 새로운 요구사항을 가져왔다.

> "일간 랭킹은 잘 되는데요, **'이번 주 베스트 상품'**, **'이번 달 베스트셀러'**도 보여주면 좋겠어요. 사용자들이 주간 트렌드를 보고 싶어 해요."

"쉽겠네!" 지난 7일치 일간 랭킹 데이터를 조회해서 평균 내면 될 것 같았다.

```kotlin
// 첫 번째 시도: 매번 7일치 집계
fun getWeeklyRanking(): List<Ranking> {
    val last7Days = (0..6).map { LocalDate.now().minusDays(it.toLong()) }

    // 7일치 데이터 조회
    val allData = last7Days.flatMap { date ->
        productRankDailyRepository.findByRankingDate(date)
    }

    // 상품별 평균 계산
    return allData
        .groupBy { it.productId }
        .map { (productId, ranks) ->
            Ranking(productId, ranks.map { it.score }.average())
        }
        .sortedByDescending { it.score }
        .take(100)
}
```

배포하고 부하 테스트를 돌려봤다.

## 충격: API 응답이 12초

부하 테스트 결과를 보고 충격을 받았다.

```
[성능 테스트 결과]
일간 랭킹: 25ms ✅
주간 랭킹: 3,500ms ❌
월간 랭킹: 12,000ms ❌❌❌

[DB 상태]
CPU: 95%
쿼리 수: 주간 7개, 월간 30개
동시 접속 100명: 에러율 15%
```

**"이건 운영에 못 올린다..."**

PM님이 당황했다.

> "주간 랭킹 보는데 왜 12초나 걸려요? 사용자들이 이탈하겠어요."

### 문제 분석: 매번 집계는 비효율적

| 랭킹 종류 | 데이터 조회량 | 쿼리 수 | 집계 연산 | 응답 시간 |
|---------|-----------|--------|---------|---------|
| 일간 (Redis) | 0.5KB | 1개 | 없음 | 25ms |
| 주간 (실시간 집계) | 70KB | 7개 | 평균 계산 | 3,500ms |
| 월간 (실시간 집계) | 300KB | 30개 | 평균 계산 | 12,000ms |

**깨달음**: "매번 조회할 때마다 7일치, 30일치 데이터를 집계하니까 느린 거구나..."

```
[일간 랭킹 조회]
사용자 → Redis 조회 (0.5KB) → 응답 (25ms) ✅

[주간 랭킹 조회 - 실시간 집계]
사용자 → DB 7번 조회 (70KB) → 메모리에서 집계 → 응답 (3,500ms) ❌

[월간 랭킹 조회 - 실시간 집계]
사용자 → DB 30번 조회 (300KB) → 메모리에서 집계 → 응답 (12,000ms) ❌❌
```

**"매번 집계하는 건 말이 안 된다. 미리 계산해두면 되지 않을까?"**

멘토님이 힌트를 주셨다.

> "주간/월간 랭킹은 실시간일 필요가 없어. 어제까지의 데이터로도 충분하거든. **Materialized View**를 만들어봐."

## 해결책 1: Materialized View (사전 집계)

### 핵심 아이디어

**"미리 계산해두고, 조회할 때는 그냥 읽기만 하자"**

```
Before: 매번 집계
┌──────────────┐
│ 사용자 요청     │
└──────┬───────┘
       ↓
┌──────────────┐
│ 7일치 조회     │  ← 느림 (쿼리 7개)
└──────┬───────┘
       ↓
┌──────────────┐
│ 평균 계산      │  ← 느림 (연산 많음)
└──────┬───────┘
       ↓
┌──────────────┐
│ 응답 (3.5초)  │
└──────────────┘

After: 사전 집계 (Materialized View)
┌──────────────┐
│ 배치 (주 1회)  │
└──────┬───────┘
       ↓
┌──────────────┐
│ 7일치 조회     │  ← 주 1회만
└──────┬───────┘
       ↓
┌──────────────┐
│ 평균 계산      │  ← 주 1회만
└──────┬───────┘
       ↓
┌──────────────┐
│ DB 저장       │  ← mv_product_rank_weekly
└──────────────┘

[사용자 요청]
┌──────────────┐
│ 사용자 요청     │
└──────┬───────┘
       ↓
┌──────────────┐
│ DB 조회 (1개)  │  ← 빠름 (이미 계산됨)
└──────┬───────┘
       ↓
┌──────────────┐
│ 응답 (28ms)   │
└──────────────┘
```

**"복잡도를 시간축으로 이동시키는 거구나!"** (사용자 요청 시점 → 배치 실행 시점)

### 설계

**데이터 플로우:**

```
[매일 23:55] Redis → DB 영구 저장
Redis: ranking:all:daily:20251220
  ↓
product_rank_daily 테이블
- ranking_date: 2025-12-20
- product_id: 100
- score: 15.2
- rank: 1

[매주 일요일 01:00] 7일치 집계
product_rank_daily (7일치)
  ↓ Spring Batch 집계
mv_product_rank_weekly (TOP 100만)
- year_week: 2025W51
- product_id: 100
- score: 14.8 (7일 평균)
- rank: 1

[매월 1일 02:00] 월간 집계
product_rank_daily (30일치)
  ↓ Spring Batch 집계
mv_product_rank_monthly (TOP 100만)
- year_month: 202512
- product_id: 100
- score: 13.5 (월간 평균)
- rank: 1
```

## Spring Batch와의 첫 만남

### "왜 @Scheduled로 안 하나요?"

처음엔 Spring Scheduler로 직접 구현하려 했다.

```kotlin
// ❌ 시도했지만 포기
@Scheduled(cron = "0 0 1 * * SUN")
fun aggregateWeeklyRanking() {
    // 1. 7일치 데이터 전체 조회
    val allData = repository.findByDateBetween(start, end)  // 메모리 부족 위험

    // 2. 집계
    val aggregated = allData.groupBy { it.productId }  // 대량 연산

    // 3. 저장
    repository.saveAll(aggregated)  // 트랜잭션 타임아웃 위험
}
```

**문제:**
1. 전체 데이터를 메모리에 올리면 OOM 위험
2. 트랜잭션이 너무 길어지면 타임아웃
3. 실패 시 처음부터 재시작

멘토님이 조언해주셨다.

> "대량 데이터 배치 처리는 Spring Batch 쓰는 게 정석이야. **Chunk-Oriented Processing**이 바로 이런 걸 위한 거거든."

### Spring Batch 핵심 개념

**Job / Step / Chunk:**

```
Job: weeklyRankingAggregationJob
 ↓
Step: weeklyRankingAggregationStep
 ↓
Chunk: 100개씩 나눠서 처리
 ↓
Reader → Processor → Writer
   ↓         ↓          ↓
 100개    100개      100개 (트랜잭션 커밋)
```

**장점:**
- 메모리 안전 (100개씩만 메모리에 올림)
- 자동 트랜잭션 관리 (청크 단위로 커밋/롤백)
- 실패 시 재시작 가능
- 실행 이력 자동 기록

**"아, 이래서 Spring Batch를 쓰는구나!"**

## 구현: Phase 1 - 일간 랭킹 영구 저장

주간/월간 집계를 하려면, 먼저 **일간 랭킹을 DB에 영구 저장**해야 했다. Redis는 휘발성이니까.

```kotlin
@Component
class DailyRankingPersistenceScheduler(
    private val rankingRepository: RankingRepository,
    private val productRankDailyRepository: ProductRankDailyRepository,
    private val productMetricsRepository: ProductMetricsRepository,
) {
    @Scheduled(cron = "0 55 23 * * *", zone = "Asia/Seoul")
    @Transactional
    fun persistDailyRanking() {
        val today = LocalDate.now()
        val key = RankingKey.daily(RankingScope.ALL, today)

        // 1. Redis에서 TOP 1000 조회
        val rankings = rankingRepository.getTopN(key, 0, 999)

        // 2. ProductMetrics에서 메트릭 조회
        val productIds = rankings.map { it.productId }
        val metricsMap = productMetricsRepository
            .findAllByProductIdIn(productIds)
            .associateBy { it.productId }

        // 3. ProductRankDaily 엔티티 생성
        val dailyRankings = rankings.map { ranking ->
            ProductRankDaily.from(today, ranking, metricsMap[ranking.productId])
        }

        // 4. 멱등성 보장: 기존 데이터 삭제 후 저장
        productRankDailyRepository.deleteByRankingDate(today)
        productRankDailyRepository.saveAll(dailyRankings)

        logger.info("일간 랭킹 영구 저장 완료: count=${dailyRankings.size}")
    }
}
```

**핵심 포인트:**
- 23:55 실행으로 오늘의 모든 이벤트 반영
- Delete-then-Insert로 멱등성 보장 (재실행해도 안전)
- like_count, view_count도 함께 스냅샷

## 구현: Phase 2 - 주간 랭킹 집계 배치

드디어 Spring Batch를 써볼 차례다.

### JobConfig 작성

```kotlin
@Configuration
class WeeklyRankingAggregationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val productRankDailyRepository: ProductRankDailyRepository,
    private val productRankWeeklyRepository: ProductRankWeeklyRepository,
) {
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
            .chunk<AggregatedRanking, ProductRankWeekly>(100, transactionManager)
            .reader(weeklyRankingAggregationReader(null, null))
            .processor(weeklyRankingAggregationProcessor(null))
            .writer(weeklyRankingAggregationWriter(null))
            .build()
    }
}
```

**청크 크기 100 선택 이유:**
- 너무 작으면: 트랜잭션 오버헤드 증가
- 너무 크면: 메모리 부족 위험
- **100이 메모리와 성능의 균형점**

### Reader: 7일치 데이터 집계

```kotlin
@Bean
@StepScope
fun weeklyRankingAggregationReader(
    @Value("#{jobParameters['targetDate']}") targetDateStr: String?,
    @Value("#{jobParameters['limit']}") limit: Int?,
): ItemReader<AggregatedRanking> {
    val targetDate = LocalDate.parse(targetDateStr ?: LocalDate.now().minusDays(1).toString())
    val topN = limit ?: 100

    val endDate = targetDate
    val startDate = endDate.minusDays(6)  // 7일치

    return ItemReader {
        if (alreadyRead) {
            null  // 한 번만 읽음
        } else {
            alreadyRead = true

            // 1. 7일치 데이터 조회
            val dailyRankings = productRankDailyRepository
                .findByRankingDateBetween(startDate, endDate)

            // 2. 상품별 평균 점수 계산
            val aggregated = dailyRankings
                .groupBy { it.productId }
                .mapValues { (_, ranks) ->
                    AggregatedRanking(
                        productId = it.key,
                        score = ranks.map { it.score }.average(),
                        periodStart = startDate,
                        periodEnd = endDate
                    )
                }

            // 3. TOP 100 선정
            aggregated.values
                .sortedByDescending { it.score }
                .take(topN)
                .firstOrNull()
        }
    }
}
```

**왜 평균인가?**

처음엔 합계를 생각했다.

```
상품A: 7일 모두 10점 = 70점
상품B: 5일만 14점 = 70점

합계로 비교하면 같은 점수?
→ 하지만 상품B가 평균적으로 더 인기 있음
```

**평균으로 계산:**
```
상품A: 평균 10점 (70 / 7)
상품B: 평균 14점 (70 / 5)

→ 상품B가 더 높은 순위 ✅
```

**"결측일에 대한 공정성이 중요하다"**

### Processor: 순위 매기기

```kotlin
@Bean
@StepScope
fun weeklyRankingAggregationProcessor(
    @Value("#{jobParameters['targetDate']}") targetDateStr: String?,
): ItemProcessor<AggregatedRanking, ProductRankWeekly> {
    val targetDate = LocalDate.parse(targetDateStr ?: LocalDate.now().minusDays(1).toString())
    val yearWeek = calculateYearWeek(targetDate)  // "2025W51"

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

private fun calculateYearWeek(date: LocalDate): String {
    val weekFields = WeekFields.of(DayOfWeek.MONDAY, 4)
    val year = date.get(weekFields.weekBasedYear())
    val week = date.get(weekFields.weekOfWeekBasedYear())
    return String.format("%04dW%02d", year, week)  // ISO 8601
}
```

**ISO 8601 Week 포맷:**
- `2025W01` = 2025년 1주차
- `2025W51` = 2025년 51주차
- 국제 표준이라 명확함

### Writer: DB 저장 (멱등성 보장)

```kotlin
@Bean
@StepScope
fun weeklyRankingAggregationWriter(
    @Value("#{jobParameters['targetDate']}") targetDateStr: String?,
): ItemWriter<ProductRankWeekly> {
    val targetDate = LocalDate.parse(targetDateStr ?: LocalDate.now().minusDays(1).toString())
    val yearWeek = calculateYearWeek(targetDate)

    return ItemWriter { items ->
        // 1. 기존 데이터 삭제 (멱등성 보장)
        productRankWeeklyRepository.deleteByYearWeek(yearWeek)

        // 2. 새 데이터 저장
        productRankWeeklyRepository.saveAll(items)

        logger.info("주간 랭킹 저장: yearWeek=$yearWeek, count=${items.size()}")
    }
}
```

**Delete-then-Insert 패턴:**

```
1차 실행:
DELETE FROM mv_product_rank_weekly WHERE year_week = '2025W51';
INSERT ...

2차 실행 (재시작):
DELETE FROM mv_product_rank_weekly WHERE year_week = '2025W51';  ← 1차 결과 삭제
INSERT ...  ← 동일 데이터 저장

→ 결과: 같음 (멱등성 보장)
```

**"실패해서 재실행해도 안전하다!"**

## 구현: Phase 3 - 스케줄러로 자동 실행

```kotlin
@Component
class RankingBatchScheduler(
    private val jobLauncher: JobLauncher,
    @Qualifier("weeklyRankingAggregationJob") private val weeklyJob: Job,
) {
    @Scheduled(cron = "0 0 1 * * SUN", zone = "Asia/Seoul")
    fun runWeeklyRankingAggregation() {
        // 어제(토요일)를 기준으로 지난 7일 집계
        val targetDate = LocalDate.now().minusDays(1)

        val jobParameters = JobParametersBuilder()
            .addString("targetDate", targetDate.toString())
            .addLong("timestamp", System.currentTimeMillis())  // 중복 실행 방지
            .toJobParameters()

        val execution = jobLauncher.run(weeklyJob, jobParameters)

        logger.info("주간 랭킹 집계 완료: status=${execution.status}")
    }
}
```

**스케줄:**
- 매주 일요일 01:00 실행
- 어제(토요일)까지의 7일치 집계
- 일요일 오전부터 사용자에게 "이번 주 베스트" 노출

## 구현: Phase 4 - API 확장

### RankingService 수정

```kotlin
fun getTopN(
    window: TimeWindow,
    timestamp: String,
    page: Int,
    size: Int
): Pair<List<Ranking>, Long> {
    return when (window) {
        TimeWindow.DAILY, TimeWindow.HOURLY -> {
            // Redis 조회 (기존 로직)
            getTopNFromRedis(window, timestamp, page, size)
        }
        TimeWindow.WEEKLY -> {
            // DB 조회 (새로운 로직)
            getTopNFromWeeklyDB(timestamp, page, size)
        }
        TimeWindow.MONTHLY -> {
            // DB 조회 (새로운 로직)
            getTopNFromMonthlyDB(timestamp, page, size)
        }
    }
}

private fun getTopNFromWeeklyDB(yearWeek: String, page: Int, size: Int): Pair<List<Ranking>, Long> {
    val rankings = productRankWeeklyRepository
        .findTopByYearWeekOrderByRank(yearWeek, limit = 100)
        .drop(page * size)
        .take(size)
        .map { Ranking(it.productId, RankingScore(it.score), it.rank) }

    return rankings to rankings.size.toLong()
}
```

**설계 철학:**
- DAILY/HOURLY → Redis (실시간성 중요)
- WEEKLY/MONTHLY → DB (효율성 중요)

**"각 윈도우에 맞는 최적의 저장소를 선택했다"**

## 실전 투입 결과

### 성능 비교

```
[응답 시간]
Before (실시간 집계):
- 일간: 25ms
- 주간: 3,500ms ❌
- 월간: 12,000ms ❌❌❌

After (Materialized View):
- 일간: 25ms
- 주간: 28ms ✅ (125배 개선!)
- 월간: 32ms ✅ (375배 개선!!)

[DB 부하]
Before:
- CPU: 95%
- 쿼리: 주간 7개, 월간 30개

After:
- CPU: 5%
- 쿼리: 주간 1개, 월간 1개
```

**"드디어 운영에 올릴 수 있다!"**

### 스토리지 효율

```sql
-- 원본 데이터 (1년치)
SELECT COUNT(*) FROM product_rank_daily;
-- 365,000건 (~50MB)

-- 주간 랭킹 (TOP 100만)
SELECT COUNT(*) FROM mv_product_rank_weekly;
-- 5,200건 (~500KB)

-- 월간 랭킹 (TOP 100만)
SELECT COUNT(*) FROM mv_product_rank_monthly;
-- 1,200건 (~120KB)

-- 총: ~51MB (무시 가능)
```

**"TOP 100만 저장하니까 스토리지는 문제없다"**

사용자는 TOP 10~20만 보니까 TOP 100이면 충분했다.

## 실제로 마주한 문제: Spring Boot 4.0.1 + Spring Batch 6.0

### @EnableBatchProcessing이 안 됨

처음 구현할 때 큰 삽질을 했다.

```kotlin
// ❌ 이렇게 했더니 모든 클래스가 "Unresolved reference"
@SpringBootApplication
@EnableBatchProcessing
class CommerceStreamerApplication
```

**에러:**
```
Unresolved reference: Job
Unresolved reference: Step
Unresolved reference: JobRepository
```

**"의존성 추가했는데 왜 안 되지?"**

3시간 삽질 끝에 발견한 원인:

1. **Spring Boot 4.0.1은 Spring Batch 6.0 사용**
2. **Spring Boot 3.x부터 `@EnableBatchProcessing`이 자동 설정을 비활성화함**
3. **패키지 구조도 변경됨**

**해결:**

```kotlin
// 1. @EnableBatchProcessing 제거
@SpringBootApplication
class CommerceStreamerApplication

// 2. BatchConfig.kt 삭제 (자동 설정에 맡김)

// 3. 패키지 임포트 수정
// Before
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step

// After
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.infrastructure.item.ItemReader
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.ItemWriter
```

**"Spring Boot 버전업 따라가기 어렵다..."**

하지만 자동 설정 덕분에 코드가 더 간결해졌다.

## 이번 주 배운 핵심

### 1. 실시간이 항상 답은 아니다

처음엔 "모든 걸 실시간으로!"라고 생각했다.

하지만:
- 주간/월간 랭킹은 어제까지 데이터로 충분함
- 실시간 집계는 DB 부하가 너무 큼
- **사전 계산으로 응답 시간 375배 개선**

**"요구사항에 맞는 적절한 타이밍이 최고의 성능을 만든다"**

### 2. Materialized View의 힘

**복잡도를 시간축으로 이동:**
- Before: 사용자 요청 시점에 집계 (느림)
- After: 배치 시점에 미리 계산 (빠름)

**"복잡한 쿼리를 단순 조회로 바꿨다"**

### 3. Spring Batch = 대량 처리의 정석

**직접 구현 vs Spring Batch:**

| 항목 | 직접 구현 | Spring Batch |
|------|---------|-------------|
| 메모리 | 전체 데이터 로드 (OOM 위험) | 청크 단위 (안전) |
| 트랜잭션 | 수동 관리 (복잡) | 자동 관리 (간단) |
| 재시작 | 처음부터 | 중단 지점부터 |
| 모니터링 | 직접 구현 | 자동 기록 |

**"대량 데이터는 Spring Batch가 답이다"**

### 4. Delete-then-Insert의 중요성

멱등성을 보장하는 간단한 패턴:

```kotlin
fun save(yearWeek: String, data: List<Ranking>) {
    repository.deleteByYearWeek(yearWeek)  // 기존 삭제
    repository.saveAll(data)  // 새로 저장
}

// 재실행해도 결과 같음 ✅
```

**"배치는 재실행될 수 있다. 멱등성이 필수다"**

### 5. TOP N 저장 전략

**전체 저장 vs TOP 100:**
- 전체: ~10,000건/주 (~1MB)
- TOP 100: ~100건/주 (~10KB)
- 사용자는 TOP 10~20만 봄

**"필요한 것만 저장하면 효율적이다"**

### 6. 평균 vs 합계

처음엔 합계로 집계하려 했다.

```
상품A: 7일 모두 10점 = 70점
상품B: 5일만 14점 = 70점

합계: 같은 점수
평균: 상품B(14점) > 상품A(10점) ✅
```

**"결측일에 대한 공정성을 고려해야 한다"**

## 배치 작업 사후보고서

성공적으로 배포하고 보고서를 작성했다:

> **주간/월간 랭킹 시스템 성과**
> 1. Spring Batch로 안정적인 대량 데이터 처리 → **실패 0건**
> 2. Materialized View로 응답 시간 **125~375배 개선**
> 3. DB 부하 **95% → 5%** 감소
> 4. 멱등성 보장으로 재실행 안전성 확보
> 5. Chunk-Oriented Processing으로 메모리 효율성 달성
>
> **결론**: 실시간 처리와 배치 처리의 완벽한 조화

CTO님이 "신입이 Spring Batch까지 이해하고 쓴 건 인상적"이라고 칭찬해주셨다.

## 다음 주 계획

주간/월간 랭킹은 성공적으로 끝났다. 다음엔:

1. **카테고리별 랭킹** - 전자제품, 패션, 식품 등
2. **브랜드별 랭킹** - 나이키, 아디다스 등
3. **랭킹 변동 추이** - "이번 주 5위 → 3위 (⬆️2)"
4. **개인화 랭킹** - 사용자 취향 반영

이번 주를 통해 깨달았다. 배치는 단순히 스케줄 작업이 아니라, **복잡도를 시간축으로 이동시켜 시스템 전체를 효율적으로 만드는 전략**이라는 것을. 그리고 가장 중요한 건:

**"실시간이 항상 좋은 게 아니다. 적재적소에 맞는 타이밍이 최고의 UX를 만든다"**

Redis로 실시간 일간 랭킹을 만들고, Spring Batch로 주간/월간 랭킹을 사전 집계하니, 완벽한 조화가 만들어졌다.
