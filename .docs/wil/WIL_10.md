# Weekly I Learned 10 (실무 배치가 30분 걸리는데 어떻게 하죠?)

## "금감원 자료 추출이 실패했습니다"

이번 주는 **Spring Batch를 배우면서 실무의 고객/회원 정합성 검증 배치를 개선**했다. 빗썸에서 금감원(FIU) 규제 대응을 위한 데이터 정합성 체크 배치를 만들어야 했는데, 처음 구현한 배치가 **30분**이나 걸렸다.

"@Scheduled로 간단히 만들면 되지 않아?"라고 생각했는데... 완전히 다른 세계였다.

**6백만 고객과 천5십만 회원 데이터**를 처리하는 건 내가 생각한 것보다 훨씬 어려웠다.

## 실무 문제: 금감원 자료 추출 시 정합성 오류

### 배경

빗썸 운영팀에서 급하게 연락이 왔다.

> "금감원(FIU)에 제출하는 자료 추출하는데, 회원 데이터 정합성이 안 맞아서 오류가 나요. 고객 확인이 안 되면 서비스 이용이 막히는데, 이거 빨리 해결해야 합니다."

**문제:**
- 회원 이메일 중복 (레거시 시스템 때문)
- 고객 실명번호 중복 (과거 계정 미정리)
- 고객의 회원계정 부재 (데이터 정합성 깨짐)
- 주계정 중복
- 고객 CI 중복

**영향:**
- 고객 확인 실패 → 서비스 이용 불가
- 금감원 규제 대응 실패 → 법적 리스크
- 나쁜 사용자 경험

**"정합성을 미리 체크하는 배치를 만들어야겠구나"**

### 요구사항

규제팀 팀장님이 요구사항을 설명해주셨다.

> "매일 밤 신규 및 수정된 고객 정보를 대상으로 정합성을 체크해주세요. 문제가 있으면 Slack으로 알림 보내서 운영팀이 바로 조치할 수 있게요. 그리고 **서비스 영향은 없어야 합니다**."

**체크 항목:**
1. 회원 이메일 중복 체크 (6백만 건)
2. 고객 실명번호 중복 체크 (천5십만 건)
3. 고객의 회원계정 존재 여부 체크
4. 주계정 중복 체크
5. 고객 CI(Connecting Information) 중복 체크

**제약:**
- **실서비스에 영향 없이** (락 최소화)
- 매일 밤 실행 (새벽 2시)
- 빠르게 처리 (새벽 작업 시간 제한)

## 첫 번째 시도: @Scheduled로 구현

Spring Batch를 배우기 전이라, `@Scheduled`로 간단히 구현했다.

```kotlin
@Component
class CustomerValidationScheduler(
    private val memberRepository: MemberRepository,
    private val customerRepository: CustomerRepository,
) {
    @Scheduled(cron = "0 0 2 * * *")  // 매일 새벽 2시
    @Transactional
    fun validateCustomerIntegrity() {
        logger.info("고객 정합성 체크 시작")

        // 1. 모든 회원 조회
        val members = memberRepository.findAll()  // 6백만 건
        val emailGroups = members.groupBy { it.email }

        // 이메일 중복 체크
        emailGroups.filter { it.value.size > 1 }.forEach { (email, duplicates) ->
            logger.warn("이메일 중복: $email, count=${duplicates.size}")
            sendSlackAlert("이메일 중복", email, duplicates.map { it.id })
        }

        // 2. 모든 고객 조회
        val customers = customerRepository.findAll()  // 천5십만 건
        val ssnGroups = customers.groupBy { it.ssn }

        // 실명번호 중복 체크
        ssnGroups.filter { it.value.size > 1 }.forEach { (ssn, duplicates) ->
            logger.warn("실명번호 중복: $ssn, count=${duplicates.size}")
            sendSlackAlert("실명번호 중복", ssn, duplicates.map { it.id })
        }

        // 3. 고객의 회원계정 존재 여부 체크
        customers.forEach { customer ->
            val memberExists = members.any { it.id == customer.memberId }
            if (!memberExists) {
                logger.warn("회원계정 부재: customerId=${customer.id}")
                sendSlackAlert("회원계정 부재", customer.id.toString(), emptyList())
            }
        }

        logger.info("고객 정합성 체크 완료")
    }
}
```

배포하고 다음날 아침에 로그를 확인했다.

## 충격: 30분 걸리고 서비스 장애

다음날 아침, Slack에 알림이 폭주하고 있었고, 새벽 2시 ~ 2시 30분 사이에 **서비스 응답 속도가 10배 느려진** 로그가 있었다.

```
[배치 실행 로그]
02:00:00 - 고객 정합성 체크 시작
02:28:43 - 고객 정합성 체크 완료
소요 시간: 28분 43초

[서비스 영향]
02:00 ~ 02:30 - API 응답 시간: 50ms → 500ms (10배)
02:00 ~ 02:30 - DB CPU: 95%
02:00 ~ 02:30 - 메모리 사용량: 4GB → 12GB (OOM 위험)

[문제]
- 이메일 중복 알림: 327건
- 실명번호 중복 알림: 89건
- 회원계정 부재 알림: 1,521건
→ Slack 채널 마비
```

**"이건 큰일났다..."**

CTO님이 당황하셨다.

> "새벽 2시에 서비스가 느려진 이유가 뭐죠? 고객들이 새벽에도 거래하는데 이건 문제입니다."

### 문제 분석

| 문제 | 원인 | 영향 |
|------|------|------|
| 30분 소요 | 전체 데이터 메모리 로드 | 배치 시간 초과 |
| 메모리 8GB 증가 | findAll() 6백만 + 천5십만 건 | OOM 위험 |
| DB CPU 95% | 트랜잭션 락 | 실서비스 느려짐 |
| Slack 폭주 | 모든 이상 건 개별 알림 | 알림 채널 마비 |

**깨달음:**
1. `findAll()`로 전체 데이터를 메모리에 올리니 OOM 위험
2. `@Transactional`이 30분 동안 락을 잡고 있어 서비스 영향
3. 배치 처리 경험이 없어 최적화 방법을 모름

**"대량 데이터 처리는 이렇게 하면 안 되는구나..."**

## Round 10에서 Spring Batch 학습

마침 이번 주 학습 주제가 **Spring Batch**였다.

멘토님이 힌트를 주셨다.

> "대량 데이터 배치는 Spring Batch 쓰는 게 정석이야. **Chunk-Oriented Processing**으로 100개씩 나눠서 처리하면, 메모리도 안전하고 트랜잭션도 짧아져서 서비스 영향이 없어."

### Quest 구현하면서 배운 핵심 개념

Round 10 Quest로 **주간/월간 랭킹 집계 배치**를 만들면서 배웠다:

**1. Chunk-Oriented Processing**
```
Reader → Processor → Writer
  ↓         ↓          ↓
100개    100개      100개 (트랜잭션 커밋)
```

**2. ItemReader / ItemProcessor / ItemWriter**
- Reader: 데이터를 청크 단위로 읽기
- Processor: 데이터 변환/검증
- Writer: 결과 저장

**3. 트랜잭션 관리**
- 청크 단위로 자동 커밋/롤백
- 실패 시 해당 청크만 재시작

**4. 멱등성 보장**
- Delete-then-Insert 패턴
- 재실행해도 안전

**"아, 이걸 실무 배치에 적용하면 되겠다!"**

## 실무 적용: Spring Batch로 리팩토링

### 설계 변경

**Before (30분):**
```
@Scheduled
  ↓
findAll() - 전체 데이터 메모리 로드 (6백만 + 천5십만 건)
  ↓
groupBy() - 메모리에서 그룹핑
  ↓
forEach() - 하나씩 체크
  ↓
30분 소요, 서비스 영향
```

**After (3분):**
```
Spring Batch Job
  ↓
ItemReader - 100개씩 읽기 (커서 기반)
  ↓
ItemProcessor - 정합성 체크 (책임연쇄 패턴)
  ↓
ItemWriter - 이상 건 저장 (배치 알림)
  ↓
3분 소요, 서비스 영향 없음
```

### 구현 1: JobConfig 작성

```kotlin
@Configuration
class CustomerValidationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
) {
    @Bean
    fun customerValidationJob(): Job {
        return JobBuilder("customerValidationJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(emailDuplicateCheckStep())
            .next(ssnDuplicateCheckStep())
            .next(memberAccountCheckStep())
            .build()
    }

    @Bean
    fun emailDuplicateCheckStep(): Step {
        return StepBuilder("emailDuplicateCheckStep", jobRepository)
            .chunk<Member, ValidationResult>(100, transactionManager)
            .reader(memberReader())
            .processor(emailDuplicateProcessor())
            .writer(validationResultWriter())
            .build()
    }
}
```

**핵심 포인트:**
- 청크 크기 100 (메모리와 성능의 균형)
- Step을 분리하여 각 체크 항목 독립적으로 실행
- 실패 시 해당 Step만 재시작 가능

### 구현 2: 커서 기반 ItemReader (중요!)

처음엔 `RepositoryItemReader`를 쓰려 했는데, 페이징 방식이라 성능이 안 좋았다.

```kotlin
// ❌ RepositoryItemReader (페이징)
@Bean
fun memberReader(): RepositoryItemReader<Member> {
    return RepositoryItemReaderBuilder<Member>()
        .repository(memberRepository)
        .methodName("findAll")
        .pageSize(100)
        .build()
}

// 문제: 매번 OFFSET 계산으로 느려짐
// SELECT * FROM member LIMIT 100 OFFSET 0;
// SELECT * FROM member LIMIT 100 OFFSET 100;
// ...
// SELECT * FROM member LIMIT 100 OFFSET 5999900;  ← 매우 느림!
```

**해결: JdbcCursorItemReader (커서 기반)**

```kotlin
@Bean
fun memberReader(): JdbcCursorItemReader<Member> {
    return JdbcCursorItemReaderBuilder<Member>()
        .name("memberReader")
        .dataSource(dataSource)
        .sql("SELECT id, email, created_at, updated_at FROM member WHERE updated_at >= ?")
        .preparedStatementSetter { ps ->
            ps.setTimestamp(1, Timestamp.valueOf(LocalDate.now().minusDays(1).atStartOfDay()))
        }
        .rowMapper { rs, _ ->
            Member(
                id = rs.getLong("id"),
                email = rs.getString("email"),
                createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
                updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
            )
        }
        .fetchSize(100)  // 중요!
        .build()
        .apply {
            // READ_ONLY로 락 방지
            setDataSource(dataSource)
        }
}
```

**왜 커서인가?**

```
페이징:
OFFSET 0     → 빠름
OFFSET 100   → 빠름
OFFSET 10000 → 느려짐
OFFSET 6000000 → 매우 느림 (DB가 6백만 행 건너뛰기)

커서:
1번째 → 빠름
100번째 → 빠름
10000번째 → 빠름
6000000번째 → 빠름 (DB가 커서 위치만 이동)
```

**"커서로 바꾸니까 6배 빨라졌다!"**

### 구현 3: READ_ONLY 트랜잭션 (핵심!)

Round 10에서 배운 건 아니었지만, 실무에서 가장 중요한 최적화였다.

```kotlin
@Bean
fun emailDuplicateCheckStep(): Step {
    return StepBuilder("emailDuplicateCheckStep", jobRepository)
        .chunk<Member, ValidationResult>(100, transactionManager)
        .reader(memberReader())
        .processor(emailDuplicateProcessor())
        .writer(validationResultWriter())
        .transactionAttribute(
            DefaultTransactionAttribute().apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_UNCOMMITTED
                isReadOnly = true  // 중요!
            }
        )
        .build()
}
```

**READ_ONLY의 효과:**

```
Before (READ_WRITE):
트랜잭션 시작 → SELECT ... FOR UPDATE (락 획득)
  ↓
다른 트랜잭션 대기 (실서비스 영향)
  ↓
트랜잭션 커밋 → 락 해제

After (READ_ONLY):
트랜잭션 시작 → SELECT ... (락 없음)
  ↓
다른 트랜잭션 정상 동작 (서비스 영향 없음)
  ↓
트랜잭션 커밋
```

**DB 부하 비교:**
```
Before: DB CPU 95% (락으로 인한 대기)
After: DB CPU 15% (락 없음)
```

**"READ_ONLY 하나로 서비스 영향이 사라졌다!"**

### 구현 4: 책임연쇄 패턴으로 확장성 확보

정합성 체크 로직이 계속 추가될 수 있어서, **책임연쇄 디자인 패턴**을 적용했다.

```kotlin
interface ValidationStrategy {
    fun validate(member: Member): ValidationResult?
    fun setNext(next: ValidationStrategy): ValidationStrategy
}

class EmailDuplicateValidator(
    private val memberRepository: MemberRepository
) : ValidationStrategy {
    private var next: ValidationStrategy? = null

    override fun validate(member: Member): ValidationResult? {
        // 이메일 중복 체크
        val duplicates = memberRepository.findByEmail(member.email)

        return if (duplicates.size > 1) {
            ValidationResult(
                type = ValidationType.EMAIL_DUPLICATE,
                memberId = member.id,
                message = "이메일 중복: ${member.email}",
                metadata = duplicates.map { it.id }.toString()
            )
        } else {
            next?.validate(member)  // 다음 체크로 넘김
        }
    }

    override fun setNext(next: ValidationStrategy): ValidationStrategy {
        this.next = next
        return next
    }
}

// Processor에서 사용
@Bean
fun emailDuplicateProcessor(): ItemProcessor<Member, ValidationResult> {
    val chain = EmailDuplicateValidator(memberRepository)
        .setNext(AccountStatusValidator())
        .setNext(CiDuplicateValidator())

    return ItemProcessor { member ->
        chain.validate(member)  // 체인 시작
    }
}
```

**장점:**
- 새로운 체크 로직 추가 시 기존 코드 수정 없음
- 각 Validator 독립적으로 테스트 가능
- 순서 변경 용이

**"나중에 체크 항목 추가되어도 확장하기 쉽다!"**

### 구현 5: 배치 알림 최적화

처음엔 이상 건마다 Slack 알림을 보내서 채널이 마비됐다.

```kotlin
// ❌ Before: 개별 알림 (1,937건)
@Bean
fun validationResultWriter(): ItemWriter<ValidationResult> {
    return ItemWriter { results ->
        results.forEach { result ->
            slackClient.sendAlert(result)  // 1,937번 호출!
        }
    }
}

// ✅ After: 배치 알림 (요약)
@Bean
fun validationResultWriter(): ItemWriter<ValidationResult> {
    return ItemWriter { results ->
        if (results.isEmpty()) return@ItemWriter

        // DB에 저장
        validationResultRepository.saveAll(results)

        // 타입별로 그룹핑하여 요약 알림
        val summary = results.groupBy { it.type }
            .map { (type, items) ->
                "$type: ${items.size}건"
            }
            .joinToString("\n")

        slackClient.sendAlert(
            title = "고객 정합성 체크 결과",
            message = summary,
            detailUrl = "https://admin.bithumb.com/validation-results"
        )
    }
}
```

**결과:**
- Slack 알림: 1,937건 → 1건 (요약)
- 상세 내용은 어드민 페이지에서 확인
- 운영팀 업무 효율 대폭 향상

## 실전 투입 결과

### 성능 개선

```
[배치 실행 시간]
Before: 28분 43초
After: 2분 51초
개선: 10배 빨라짐 ✅

[메모리 사용량]
Before: 4GB → 12GB (피크)
After: 4GB → 5GB (피크)
개선: 메모리 안정화 ✅

[DB 부하]
Before: CPU 95%
After: CPU 15%
개선: 실서비스 영향 없음 ✅

[처리량]
- 회원 6백만 건: 2분 10초
- 고객 천5십만 건: 41초
- 총: 2분 51초

[Slack 알림]
Before: 1,937건 (개별)
After: 1건 (요약)
```

**"드디어 새벽 2시에도 서비스가 안정적이다!"**

### 실제 발견한 정합성 이슈

배치를 돌리고 실제로 많은 정합성 문제를 발견했다.

**사례 1: 이메일 중복 (327건)**
- 원인: 레거시 시스템 통합 시 데이터 정리 미흡
- 조치: 동일 인물 확인 후 과거 계정 비활성화

**사례 2: 실명번호 중복 (89건)**
- 원인: 과거 계정 삭제 시 고객 데이터만 남음
- 조치: 과거 계정 완전 제거

**사례 3: 회원계정 부재 (1,521건)**
- 원인: 회원 탈퇴 시 고객 데이터 미삭제
- 조치: 고객 강제 탈퇴 처리

**"배치가 없었으면 금감원 검사 때 큰일날 뻔했다..."**

## Round 10에서 배운 걸 실무에 적용하며 깨달은 것

### 1. Chunk-Oriented Processing의 위력

**Quest:**
- 주간 랭킹: 7일치 데이터를 100개씩 나눠서 집계
- 메모리 안전, 트랜잭션 짧음

**실무:**
- 6백만 + 천5십만 건을 100개씩 나눠서 체크
- OOM 방지, 실서비스 영향 없음

**"청크 단위로 나누는 게 대량 데이터의 핵심이다"**

### 2. 커서 vs 페이징

Quest에서는 페이징도 괜찮았다 (TOP 100만 저장하니까).

하지만 실무에서는:
```
페이징: OFFSET 6백만 → 느림
커서: 포인터 이동 → 빠름
```

**"대량 데이터는 무조건 커서다"**

### 3. READ_ONLY 트랜잭션의 중요성

Quest에서는 Writer에서만 데이터를 쓰니 문제없었다.

실무에서는:
- 배치가 30분 돌면서 락을 잡고 있음
- 실서비스 API가 느려짐

**해결:**
- READ_ONLY로 락 방지
- 실서비스 영향 없음

**"조회만 하는 배치는 반드시 READ_ONLY"**

### 4. 멱등성 보장

Quest에서 배운 Delete-then-Insert 패턴:

```kotlin
// 주간 랭킹
repository.deleteByYearWeek(yearWeek)
repository.saveAll(rankings)

// 실무 (정합성 결과)
validationResultRepository.deleteByCheckDate(today)
validationResultRepository.saveAll(results)
```

**"재실행해도 안전하다"**

### 5. 책임연쇄 패턴의 확장성

Quest에서는 Reader/Processor/Writer만 배웠다.

실무에서는:
- 정합성 체크 로직이 계속 추가됨
- 책임연쇄 패턴으로 확장성 확보

**"디자인 패턴이 실무에서 빛난다"**

### 6. 배치 알림 전략

Quest에서는 로그만 찍었다.

실무에서는:
- 개별 알림 → Slack 마비
- 요약 알림 → 효율적

**"사용자 관점에서 생각해야 한다"**

## 사후보고서

성공적으로 배포하고 보고서를 작성했다:

> **고객/회원 정합성 검증 배치 개선 성과**
>
> **배경**: 금감원 규제 대응을 위한 데이터 정합성 사전 체크 필요
>
> **개선 내용**:
> 1. Spring Batch Chunk-Oriented Processing 적용 → **배치 시간 30분 → 3분 (10배 개선)**
> 2. 커서 기반 데이터 순회 → **메모리 사용량 안정화** (12GB → 5GB)
> 3. READ_ONLY 트랜잭션 → **실서비스 영향 없음** (DB CPU 95% → 15%)
> 4. 책임연쇄 패턴 → **정합성 로직 확장성 확보**
> 5. 배치 알림 최적화 → **Slack 알림 1,937건 → 1건**
>
> **결과**:
> - 6백만 고객 + 천5십만 회원 안정적 처리
> - 금감원 규제 리스크 사전 관리
> - 실서비스 무중단 운영
>
> **발견 및 조치**:
> - 이메일 중복 327건 해결
> - 실명번호 중복 89건 해결
> - 회원계정 부재 1,521건 해결

CTO님이 "신입이 Spring Batch를 이렇게 실무에 잘 적용한 건 처음"이라고 칭찬해주셨다.

규제팀 팀장님도 만족하셨다.

> "이제 금감원 검사 때 걱정 없겠어요. 매일 정합성을 체크하니까 문제를 사전에 발견할 수 있네요."

## 이번 주 배운 핵심

### 1. Quest로 배우고 실무에 적용

**Quest (주간/월간 랭킹):**
- Chunk-Oriented Processing
- ItemReader/Processor/Writer
- Delete-then-Insert 멱등성

**실무 (정합성 검증):**
- 동일한 패턴 적용
- 추가: 커서, READ_ONLY, 책임연쇄 패턴

**"배운 걸 바로 실무에 써먹으니 확실히 이해된다"**

### 2. 대량 데이터는 Spring Batch

```
@Scheduled + findAll():
- 전체 메모리 로드 → OOM
- 긴 트랜잭션 → 서비스 영향
- 30분 소요

Spring Batch:
- 청크 단위 처리 → 메모리 안전
- 짧은 트랜잭션 → 서비스 영향 없음
- 3분 소요
```

**"대량 데이터는 절대 findAll() 쓰면 안 된다"**

### 3. 커서 기반 처리

```
페이징 (OFFSET):
OFFSET 6백만 → 매우 느림 (DB가 6백만 건 건너뛰기)

커서:
포인터 이동 → 빠름
```

**"실무에서는 커서가 필수다"**

### 4. READ_ONLY의 중요성

```
READ_WRITE:
배치 30분 → 락 30분 → 서비스 느려짐

READ_ONLY:
배치 3분 → 락 없음 → 서비스 정상
```

**"조회 배치는 무조건 READ_ONLY"**

### 5. 확장성을 고려한 설계

처음엔 if-else로 정합성 체크 로직을 작성하려 했다.

```kotlin
// ❌ 확장성 없음
if (이메일 중복) { ... }
else if (실명번호 중복) { ... }
else if (회원계정 부재) { ... }
// 새로운 체크 추가 시 기존 코드 수정
```

**책임연쇄 패턴:**
```kotlin
// ✅ 확장성 있음
EmailValidator()
  .setNext(SsnValidator())
  .setNext(AccountValidator())
// 새로운 체크 추가 시 체인에 추가만
```

**"실무는 계속 변한다. 확장성이 중요하다"**

### 6. 사용자 관점의 알림

```
개별 알림 (1,937건):
운영팀 "너무 많아서 못 보겠어요"

요약 알림 (1건):
운영팀 "한눈에 파악되고 좋아요"
```

**"기술만이 아니라 사용자 경험도 중요하다"**

## 다음 주 계획

정합성 검증 배치는 성공적으로 끝났다. 다음엔:

1. **실시간 정합성 체크** - 고객 등록 시점에 바로 체크
2. **자동 복구** - 단순 이슈는 자동으로 수정
3. **대시보드** - 정합성 지표 시각화
4. **히스토리 추적** - 정합성 변화 추이 분석

이번 주를 통해 깨달았다. **Quest로 개념을 배우고, 실무에 바로 적용하니 확실히 내 것이 된다**는 것을. 그리고 가장 중요한 건:

**"배운 건 쓸모없다고 생각하지 말고, 실무에 어떻게 적용할지 고민하는 게 진짜 학습이다"**

Spring Batch를 Quest로 배우고, 빗썸 정합성 배치에 적용하니, 30분짜리 배치가 3분으로 줄었고, 서비스 영향도 사라졌다. 그리고 금감원 규제 리스크도 관리할 수 있게 됐다.

**학습과 실무의 완벽한 연결.**
