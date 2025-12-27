# [Round 10] Spring Batch 기반 주간/월간 랭킹 시스템 구현

## 📌 개요

Spring Batch를 활용하여 주간/월간 랭킹 시스템을 구축했습니다.
일간 Redis 랭킹 데이터를 DB에 영구 저장하고, 이를 기반으로 주간/월간 TOP 100 랭킹을 배치 집계하여 Materialized View 테이블에 저장합니다.

## ✅ 구현 내용

### 1. Daily Persistence (일간 랭킹 영구 저장)

**DailyRankingPersistenceScheduler**
- **스케줄**: 매일 23:55 (Asia/Seoul)
- **동작**: Redis 일간 랭킹 TOP 1000 → DB 저장
- **목적**: 주간/월간 배치 집계의 데이터 소스
- **멱등성**: `(ranking_date, product_id)` 복합 유니크 키로 중복 방지

**핵심 로직**:
```kotlin
@Scheduled(cron = "0 55 23 * * *", zone = "Asia/Seoul")
@Transactional
fun persistDailyRanking() {
    val today = LocalDate.now()
    val key = RankingKey.daily(RankingScope.ALL, today)
    val rankings = rankingRepository.getTopN(key, 0, MAX_RANK_TO_SAVE - 1)

    // ProductMetrics 조회
    val productIds = rankings.map { it.productId }
    val metricsMap = productMetricsRepository.findAllByProductIdIn(productIds)
        .associateBy { it.productId }

    // ProductRankDaily 엔티티 생성 및 저장
    val dailyRankings = rankings.map { ranking ->
        ProductRankDaily.from(today, ranking, metricsMap[ranking.productId])
    }

    productRankDailyRepository.deleteByRankingDate(today)
    productRankDailyRepository.saveAll(dailyRankings)
}
```

### 2. Spring Batch Jobs (주간/월간 집계)

#### 2.1 Spring Batch 6.0 설정

**Spring Boot 4.0.1 호환성**:
- ❌ `@EnableBatchProcessing` 제거 (Spring Boot 3.x+에서 auto-configuration 비활성화)
- ✅ Auto-configuration 활용
- ✅ Package 변경 대응:
  - `org.springframework.batch.core.job.Job` (변경됨)
  - `org.springframework.batch.core.step.Step` (변경됨)
  - `org.springframework.batch.infrastructure.item.*` (변경됨)

#### 2.2 Weekly Ranking Aggregation Job

**WeeklyRankingAggregationJobConfig**
- **Job 이름**: `weeklyRankingAggregationJob`
- **스케줄**: 매주 일요일 01:00 (Asia/Seoul)
- **Job Parameter**: `targetDate` (집계 대상 주의 마지막 날)

**Chunk-Oriented Processing** (chunk size: 100):
1. **Reader**: `product_rank_daily` 에서 지난 7일 데이터 읽기 → 상품별 평균 점수 계산 → TOP 100 선정
2. **Processor**: 집계 데이터를 `ProductRankWeekly` 엔티티로 변환 (`yearWeek` 계산)
3. **Writer**: 기존 데이터 삭제 후 신규 데이터 저장 (멱등성 보장)

**핵심 로직**:
```kotlin
@Bean
fun weeklyRankingAggregationReader(
    @Value("#{jobParameters['targetDate']}") targetDate: String,
    productRankDailyRepository: ProductRankDailyRepository
): ItemReader<AggregatedRanking> {
    val endDate = LocalDate.parse(targetDate)
    val startDate = endDate.minusDays(6)

    // 7일 데이터 조회 및 평균 점수 계산
    val dailyRankings = productRankDailyRepository.findByRankingDateBetween(startDate, endDate)
    val aggregated = dailyRankings
        .groupBy { it.productId }
        .map { (productId, rankings) ->
            val avgScore = rankings.map { it.score }.average()
            AggregatedRanking(productId, avgScore)
        }
        .sortedByDescending { it.score }
        .take(TOP_RANK_LIMIT) // TOP 100
        .mapIndexed { index, item -> item.copy(rank = index + 1) }

    return ListItemReader(aggregated)
}

@Bean
fun weeklyRankingAggregationWriter(
    weeklyRepository: ProductRankWeeklyRepository
): ItemWriter<ProductRankWeekly> = ItemWriter { items ->
    val yearWeek = items.items.firstOrNull()?.yearWeek ?: return@ItemWriter
    weeklyRepository.deleteByYearWeek(yearWeek)
    weeklyRepository.saveAll(items.items)
}
```

#### 2.3 Monthly Ranking Aggregation Job

**MonthlyRankingAggregationJobConfig**
- **Job 이름**: `monthlyRankingAggregationJob`
- **스케줄**: 매월 1일 02:00 (Asia/Seoul)
- **Job Parameter**: `targetYearMonth` (예: "202501")

**Chunk-Oriented Processing** (chunk size: 100):
1. **Reader**: `product_rank_daily` 에서 해당 월 데이터 읽기 → 상품별 평균 점수 계산 → TOP 100 선정
2. **Processor**: 집계 데이터를 `ProductRankMonthly` 엔티티로 변환
3. **Writer**: 기존 데이터 삭제 후 신규 데이터 저장 (멱등성 보장)

### 3. 데이터베이스 스키마

#### 3.1 product_rank_daily (일간 랭킹 영구 저장)

```sql
CREATE TABLE product_rank_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ranking_date DATE NOT NULL,
    product_id BIGINT NOT NULL,
    score DOUBLE NOT NULL,
    rank INT NOT NULL,
    like_count BIGINT NOT NULL DEFAULT 0,
    view_count BIGINT NOT NULL DEFAULT 0,
    sales_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    UNIQUE KEY uk_product_rank_daily_date_product (ranking_date, product_id),
    INDEX idx_product_rank_daily_date (ranking_date DESC),
    INDEX idx_product_rank_daily_date_rank (ranking_date, rank),
    INDEX idx_product_rank_daily_product_id (product_id)
);
```

#### 3.2 mv_product_rank_weekly (주간 랭킹 - Materialized View)

```sql
CREATE TABLE mv_product_rank_weekly (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    year_week VARCHAR(7) NOT NULL,  -- 예: 2025W01
    product_id BIGINT NOT NULL,
    score DOUBLE NOT NULL,
    rank INT NOT NULL CHECK (rank > 0 AND rank <= 100),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    UNIQUE KEY uk_product_rank_weekly_year_week_product (year_week, product_id),
    INDEX idx_product_rank_weekly_year_week (year_week DESC),
    INDEX idx_product_rank_weekly_year_week_rank (year_week, rank)
);
```

#### 3.3 mv_product_rank_monthly (월간 랭킹 - Materialized View)

```sql
CREATE TABLE mv_product_rank_monthly (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    year_month VARCHAR(6) NOT NULL,  -- 예: 202501
    product_id BIGINT NOT NULL,
    score DOUBLE NOT NULL,
    rank INT NOT NULL CHECK (rank > 0 AND rank <= 100),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    UNIQUE KEY uk_product_rank_monthly_year_month_product (year_month, product_id),
    INDEX idx_product_rank_monthly_year_month (year_month DESC),
    INDEX idx_product_rank_monthly_year_month_rank (year_month, rank)
);
```

### 4. API 확장

#### 4.1 TimeWindow Enum 확장

```kotlin
enum class TimeWindow(val ttlDays: Int) {
    DAILY(ttlDays = 2),    // Redis
    HOURLY(ttlDays = 1),   // Redis
    WEEKLY(ttlDays = 0),   // DB
    MONTHLY(ttlDays = 0),  // DB
}
```

#### 4.2 RankingService 수정

```kotlin
fun getTopN(window: TimeWindow, timestamp: String, page: Int, size: Int): Pair<List<Ranking>, Long> {
    return when (window) {
        TimeWindow.DAILY, TimeWindow.HOURLY -> getTopNFromRedis(window, timestamp, page, size)
        TimeWindow.WEEKLY -> getTopNFromWeeklyDB(timestamp, page, size)
        TimeWindow.MONTHLY -> getTopNFromMonthlyDB(timestamp, page, size)
    }
}

private fun getTopNFromWeeklyDB(yearWeek: String, page: Int, size: Int): Pair<List<Ranking>, Long> {
    val rankings = productRankWeeklyRepository.findTopByYearWeekOrderByRank(yearWeek, size)
        .drop(page * size)
        .take(size)
        .map { Ranking(productId = it.productId, score = RankingScore(it.score), rank = it.rank) }
    return rankings to rankings.size.toLong()
}
```

#### 4.3 API 엔드포인트

**기존 API 확장**:
```
GET /api/v1/rankings?window=WEEKLY&date=2025W01&page=0&size=20
GET /api/v1/rankings?window=MONTHLY&date=202501&page=0&size=20
```

**timestamp 형식**:
- DAILY: `yyyyMMdd` (예: 20250906)
- HOURLY: `yyyyMMddHH` (예: 2025090614)
- WEEKLY: `yyyy'W'ww` (예: 2025W01)
- MONTHLY: `yyyyMM` (예: 202501)

## 🏗️ 아키텍처 설계

### 1. 데이터 플로우

```
┌─────────────────────────────────────────────────────────────────┐
│                     Real-time Ranking (Redis)                   │
│                     DAILY/HOURLY 랭킹 운영                      │
└──────────────────────────┬──────────────────────────────────────┘
                           │ 매일 23:55 (Scheduler)
                           ↓
┌─────────────────────────────────────────────────────────────────┐
│              Daily Persistence (product_rank_daily)             │
│                     일간 랭킹 영구 저장 (TOP 1000)              │
└───────┬──────────────────────────────────────────────┬──────────┘
        │ 매주 일요일 01:00 (Batch)                     │ 매월 1일 02:00 (Batch)
        ↓                                               ↓
┌──────────────────────────┐              ┌────────────────────────┐
│ mv_product_rank_weekly   │              │ mv_product_rank_monthly│
│  주간 TOP 100 집계       │              │  월간 TOP 100 집계     │
└──────────────────────────┘              └────────────────────────┘
```

### 2. Materialized View 전략

**선택 이유**:
- ✅ **쿼리 성능 최적화**: 사전 계산된 집계 데이터로 빠른 조회
- ✅ **스토리지 효율**: TOP 100만 저장 (vs 전체 상품)
- ✅ **집계 방식**: 평균 점수 (결측일에 대한 공정성)
- ✅ **멱등성**: Delete-then-Insert 패턴으로 재실행 안전성

**대안과 비교**:
- ❌ Real-time Aggregation: 복잡한 집계 쿼리로 인한 성능 저하
- ❌ Incremental Update: 복잡한 업데이트 로직, 데이터 정합성 관리 어려움

### 3. Spring Batch 선택 이유

**Chunk-Oriented Processing**:
- Reader/Processor/Writer 패턴으로 명확한 관심사 분리
- Chunk 단위 트랜잭션으로 대용량 데이터 처리
- Retry, Skip, Restart 등 내장 기능

**Job Repository**:
- Job 실행 이력 관리 (BATCH_JOB_EXECUTION)
- 재시도 및 복구 지원

## 📊 성능 고려사항

### 1. Chunk Size 선택 (100)

**근거**:
- 메모리 사용량과 성능의 균형
- TOP 100 저장 시 1 chunk로 처리 완료
- 트랜잭션 범위 최소화

### 2. 인덱스 설계

**조회 최적화**:
- `(ranking_date, rank)`: 날짜별 순위 조회
- `(year_week, rank)`: 주차별 순위 조회
- `(year_month, rank)`: 월별 순위 조회

**멱등성 보장**:
- `(ranking_date, product_id)` UNIQUE
- `(year_week, product_id)` UNIQUE
- `(year_month, product_id)` UNIQUE

### 3. 스케줄링 시간 분리

| Job | 시간 | 목적 |
|-----|------|------|
| Daily Persistence | 23:55 | Redis → DB 저장 |
| Weekly Aggregation | 일요일 01:00 | 7일 집계 |
| Monthly Aggregation | 1일 02:00 | 월간 집계 |

## 🚀 배포 및 운영

### 1. Spring Batch 메타데이터 테이블

```yaml
spring:
  batch:
    jdbc:
      initialize-schema: never  # prod
      table-prefix: BATCH_
```

**테이블 목록**:
- `BATCH_JOB_INSTANCE`
- `BATCH_JOB_EXECUTION`
- `BATCH_JOB_EXECUTION_PARAMS`
- `BATCH_STEP_EXECUTION`
- `BATCH_STEP_EXECUTION_CONTEXT`
- `BATCH_JOB_EXECUTION_CONTEXT`

### 2. 모니터링 포인트

**Job 실행 이력**:
```sql
SELECT job_name, start_time, end_time, status, exit_code
FROM BATCH_JOB_EXECUTION
WHERE job_name IN ('weeklyRankingAggregationJob', 'monthlyRankingAggregationJob')
ORDER BY start_time DESC
LIMIT 10;
```

**실패한 Job 조회**:
```sql
SELECT * FROM BATCH_JOB_EXECUTION
WHERE status = 'FAILED'
ORDER BY start_time DESC;
```

### 3. 재시도 전략

**JobLauncher 수동 실행**:
```kotlin
val jobParameters = JobParametersBuilder()
    .addString("targetDate", LocalDate.now().toString())
    .addLong("timestamp", System.currentTimeMillis()) // 재실행을 위한 고유값
    .toJobParameters()

jobLauncher.run(weeklyRankingAggregationJob, jobParameters)
```

## 🔍 체크리스트 검증

### Spring Batch
- [x] Spring Batch Job을 작성하고, 파라미터 기반으로 동작시킬 수 있다
  - `targetDate`, `targetYearMonth` 파라미터 활용
  - JobLauncher를 통한 동적 실행
- [x] Chunk Oriented Processing (Reader/Processor/Writer) 기반의 배치 처리를 구현했다
  - Reader: 일간 데이터 조회 및 집계
  - Processor: 엔티티 변환
  - Writer: DB 저장
- [x] 집계 결과를 저장할 Materialized View의 구조를 설계하고 올바르게 적재했다
  - `mv_product_rank_weekly`, `mv_product_rank_monthly` 테이블
  - TOP 100만 저장 (스토리지 효율)
  - 평균 점수 기반 집계

### Ranking API
- [x] API가 일간, 주간, 월간 랭킹을 제공하며 조회해야 하는 형태에 따라 적절한 데이터를 기반으로 랭킹을 제공한다
  - DAILY/HOURLY: Redis 조회
  - WEEKLY/MONTHLY: DB 조회
  - TimeWindow enum 확장으로 일관된 API 제공

## 📝 주요 설계 결정사항

1. **모듈 선택**: `commerce-streamer` (이미 ranking scheduler 보유)
2. **데이터 소스**: Redis daily ranking → DB 영구 저장 → 주간/월간 집계
3. **저장 전략**: Materialized View 테이블에 TOP 100만 저장
4. **집계 방식**: 평균 점수 (결측일에 대한 공정성)
5. **Chunk Size**: 100 (메모리와 성능 균형)
6. **멱등성**: Delete-then-Insert 패턴으로 재실행 안전성 보장
7. **Spring Boot 4.0.1 호환**: `@EnableBatchProcessing` 제거, Package 변경 대응

## 🛠️ 기술 스택

- **Spring Batch 6.0.1** (Spring Boot 4.0.1)
- **Spring Data JPA**
- **MySQL** (Materialized View 테이블)
- **Redis** (실시간 랭킹)
- **Kotlin 2.3.0**

## 📚 참고 문서

- [Spring Batch 6.0 Migration Guide](https://github.com/spring-projects/spring-batch/wiki/Spring-Batch-6.0-Migration-Guide)
- [Spring Batch Reference Documentation](https://docs.spring.io/spring-batch/reference/)
- `.docs/_architecture/4-erd.md`: ERD 설계 (랭킹 테이블 추가)
