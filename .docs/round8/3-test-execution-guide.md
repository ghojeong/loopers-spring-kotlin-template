# Round 8: 테스트 실행 가이드

## 테스트 개요

commerce-streamer 애플리케이션의 Kafka Consumer 관련 테스트는 다음과 같이 구성되어 있습니다:

1. **도메인 로직 단위 테스트** - Kafka 없이 실행 가능
2. **Kafka Consumer E2E 통합 테스트** - Kafka 인프라 필요

## 1. 도메인 로직 단위 테스트

### 테스트 파일

- `EventHandledTest.kt` - 이벤트 처리 기록 도메인 로직 테스트
- `ProductMetricsTest.kt` - 상품 메트릭 집계 도메인 로직 테스트

### 실행 방법

Kafka 인프라 없이 실행 가능합니다.

```bash
# 모든 단위 테스트 실행
./gradlew :apps:commerce-streamer:test

# EventHandledTest만 실행
./gradlew :apps:commerce-streamer:test --tests "EventHandledTest"

# ProductMetricsTest만 실행
./gradlew :apps:commerce-streamer:test --tests "ProductMetricsTest"

# 특정 테스트 메서드만 실행
./gradlew :apps:commerce-streamer:test --tests "EventHandledTest.EventHandled를 생성할 수 있다"
```

### 테스트 커버리지

**EventHandledTest (3개 테스트):**
- ✅ EventHandled 생성
- ✅ handledBy 커스터마이징
- ✅ 유니크 제약 조건 확인

**ProductMetricsTest (8개 테스트):**
- ✅ ProductMetrics 생성
- ✅ 좋아요 수 증가/감소
- ✅ 조회 수 증가
- ✅ 판매량/판매 금액 증가/감소
- ✅ 0일 때 감소 시 0 유지

---

## 2. Kafka Consumer E2E 통합 테스트

### 테스트 파일

- `KafkaConsumerE2ETest.kt` - Kafka Consumer 전체 플로우 통합 테스트

### 사전 준비

#### Step 1: Kafka 인프라 시작

```bash
cd docker
docker-compose -f infra-compose.yml up -d kafka kafka-ui
```

**확인:**
- Kafka: `localhost:19092`
- Kafka UI: http://localhost:9099

#### Step 2: 환경 변수 설정

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:19092
```

#### Step 3: commerce-streamer 실행 (백그라운드)

테스트 시 Consumer가 메시지를 처리해야 하므로, 애플리케이션을 먼저 실행해야 합니다.

```bash
# 별도 터미널에서 실행
./gradlew :apps:commerce-streamer:bootRun
```

또는

```bash
# 백그라운드로 실행
./gradlew :apps:commerce-streamer:bootRun &
```

### 실행 방법

```bash
# 모든 E2E 테스트 실행
./gradlew :apps:commerce-streamer:test --tests "KafkaConsumerE2ETest"

# 특정 테스트만 실행
./gradlew :apps:commerce-streamer:test --tests "KafkaConsumerE2ETest.Consumer가 LikeAddedEvent를 수신하여 ProductMetrics를 업데이트한다"
```

### 테스트 시나리오

**KafkaConsumerE2ETest (7개 테스트):**

1. **Kafka 연결 확인**
   - KafkaTemplate 빈 생성 여부 확인

2. **LikeAddedEvent 처리**
   - Kafka로 LikeAddedEvent 전송
   - Consumer가 메시지 수신
   - ProductMetrics의 likeCount 증가 확인

3. **LikeRemovedEvent 처리**
   - 좋아요 추가 후 제거
   - likeCount 감소 확인

4. **OrderCreatedEvent 처리**
   - 주문 생성 이벤트 전송
   - 판매량 및 판매 금액 집계 확인

5. **멱등성 보장**
   - 같은 메시지 3번 전송
   - ProductMetrics는 1번만 증가
   - EventHandled 테이블에 1번만 기록

6. **여러 상품 동시 처리**
   - 하나의 주문에 여러 상품 포함
   - 각 상품별로 판매량 집계 확인

7. **알 수 없는 이벤트 무시**
   - 잘못된 형식의 이벤트 전송
   - 에러 없이 무시되는지 확인

---

## 3. Kafka 없이 테스트 실행

Kafka가 실행 중이지 않아도 테스트는 실패하지 않고 skip됩니다.

```bash
# Kafka 없이 테스트 실행
./gradlew :apps:commerce-streamer:test

# 출력 예시:
# ⚠️  Kafka가 실행 중이지 않습니다. 테스트를 건너뜁니다.
#    Kafka 실행: cd docker && docker-compose -f infra-compose.yml up -d kafka
#    환경 변수: export KAFKA_BOOTSTRAP_SERVERS=localhost:19092
```

**동작 원리:**
- `@Autowired(required = false)`로 KafkaTemplate 주입
- KafkaTemplate이 null이면 테스트 early return
- 빌드는 성공하지만 일부 테스트는 skip

---

## 4. 전체 빌드 및 테스트

### 빌드 + 포맷 + 테스트

```bash
# 전체 빌드 (ktlint + 테스트 포함)
./gradlew :apps:commerce-streamer:build

# 포맷 적용 후 빌드
./gradlew :apps:commerce-streamer:ktlintFormat :apps:commerce-streamer:build
```

### 테스트만 실행

```bash
# 단위 테스트만 (Kafka 불필요)
./gradlew :apps:commerce-streamer:test --tests "*Test"

# E2E 테스트만 (Kafka 필요)
./gradlew :apps:commerce-streamer:test --tests "KafkaConsumerE2ETest"
```

### 테스트 리포트 확인

```bash
# 테스트 실행 후 리포트 확인
open apps/commerce-streamer/build/reports/tests/test/index.html
```

---

## 5. 트러블슈팅

### 문제: Kafka 연결 실패

**증상:**
```
org.apache.kafka.common.errors.TimeoutException: Topic catalog-events not present in metadata after 60000 ms.
```

**해결:**
1. Kafka가 실행 중인지 확인
   ```bash
   docker ps | grep kafka
   ```

2. 환경 변수 확인
   ```bash
   echo $KAFKA_BOOTSTRAP_SERVERS
   # 출력: localhost:19092
   ```

3. Kafka 재시작
   ```bash
   cd docker
   docker-compose -f infra-compose.yml restart kafka
   ```

### 문제: Consumer가 메시지를 처리하지 않음

**증상:**
- 테스트에서 timeout 발생
- ProductMetrics가 업데이트되지 않음

**해결:**
1. commerce-streamer 애플리케이션이 실행 중인지 확인
   ```bash
   ./gradlew :apps:commerce-streamer:bootRun
   ```

2. Consumer Group 확인
   ```bash
   # Kafka UI에서 확인: http://localhost:9099
   # Consumers → commerce-streamer-consumer-group
   ```

3. 토픽 메시지 확인
   ```bash
   # Kafka UI에서 확인: http://localhost:9099
   # Topics → catalog-events → Messages
   ```

### 문제: 멱등성 테스트 실패

**증상:**
- 중복 메시지가 여러 번 처리됨
- EventHandled 테이블에 중복 기록

**해결:**
1. DB 초기화
   ```bash
   # 테스트 DB 초기화
   ./gradlew :apps:commerce-streamer:clean
   ```

2. EventHandled 테이블 확인
   ```sql
   SELECT * FROM event_handled
   WHERE aggregate_type = 'Product' AND aggregate_id = 400
   ORDER BY created_at DESC;
   ```

---

## 6. CI/CD 환경에서 테스트

### GitHub Actions 예시

```yaml
name: Test commerce-streamer

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    services:
      kafka:
        image: confluentinc/cp-kafka:latest
        ports:
          - 19092:19092
        env:
          KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
          KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:19092

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'

      - name: Run tests
        env:
          KAFKA_BOOTSTRAP_SERVERS: localhost:19092
        run: |
          ./gradlew :apps:commerce-streamer:test
```

---

## 7. 테스트 요약

### 빠른 시작 (권장)

```bash
# 1. Kafka 시작
cd docker && docker-compose -f infra-compose.yml up -d kafka

# 2. 환경 변수 설정
export KAFKA_BOOTSTRAP_SERVERS=localhost:19092

# 3. commerce-streamer 실행 (백그라운드)
./gradlew :apps:commerce-streamer:bootRun &

# 4. 테스트 실행
./gradlew :apps:commerce-streamer:test

# 5. 종료
pkill -f commerce-streamer
cd docker && docker-compose -f infra-compose.yml down
```

### 단위 테스트만 (Kafka 불필요)

```bash
./gradlew :apps:commerce-streamer:test --tests "*Test"
```

### E2E 테스트만 (Kafka 필요)

```bash
# Kafka 시작 후
export KAFKA_BOOTSTRAP_SERVERS=localhost:19092
./gradlew :apps:commerce-streamer:bootRun &
./gradlew :apps:commerce-streamer:test --tests "KafkaConsumerE2ETest"
```

---

## 8. 추가 참고 사항

### 테스트 격리

각 테스트는 다른 productId를 사용하여 격리됩니다:
- 100L: LikeAddedEvent 테스트
- 200L: LikeRemovedEvent 테스트
- 300L: OrderCreatedEvent 테스트
- 400L: 멱등성 테스트
- 500L, 600L: 여러 상품 동시 처리 테스트

### 대기 시간 설정

- 기본 대기 시간: 10초 (`await().atMost(10, TimeUnit.SECONDS)`)
- 폴링 간격: 500ms
- Kafka 전송 타임아웃: 5초

느린 환경에서는 대기 시간을 늘려야 할 수 있습니다.

### 로그 확인

```bash
# 상세 로그 확인
./gradlew :apps:commerce-streamer:test --info

# 디버그 로그 확인
./gradlew :apps:commerce-streamer:test --debug
```
