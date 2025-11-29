# 좋아요 순 정렬 하나 추가했다가 서버가 죽을 뻔한 이야기

**TL;DR**: "인기 상품 순으로 보여주세요"라는 요구사항 하나 추가했다가, 10만건 데이터에서 쿼리가 1초씩 걸리는 걸 보고 충격받았다. "LEFT JOIN + GROUP BY + COUNT면 되겠지"라고 생각했는데, **EXPLAIN 분석 결과를 보고 전체 테이블 스캔**을 하고 있다는 걸 깨달았다. DB 정규화는 제대로 했는데 왜 이렇게 느릴까? 고민 끝에 Product 테이블에 likeCount를 직접 넣는 비정규화를 선택했고, 복합 인덱스를 설계하고, Redis 캐시까지 적용했더니 **99% 이상 빨라졌다**.

## "인기 상품 순으로 보여주세요"

### 처음 마주한 요구사항

Round 4에서 동시성 문제를 해결하고 나니 자신감이 생겼다. "이제 기본은 다 되었다"고 생각했다.

그때 새로운 요구사항이 들어왔다:

> "사용자들이 가장 많이 좋아요를 누른 상품 순으로 보여주세요."

"뭐 어렵겠어?" 이미 Product와 Like 테이블이 정규화되어 있었으니까.

**테이블 구조:**

| Product 테이블 | Like 테이블 |
|---------------|------------|
| id (PK) | id (PK) |
| name | user_id |
| price | product_id (FK) |
| brand_id (FK) | |

**관계:** Product 1 : N Like

"LEFT JOIN + GROUP BY + COUNT로 하면 되겠네!" 바로 구현했다.

```kotlin
@Query("""
    SELECT p FROM Product p
    LEFT JOIN Like l ON l.productId = p.id
    GROUP BY p
    ORDER BY COUNT(l) DESC
""")
fun findAllOrderByLikeCount(pageable: Pageable): Page<Product>
```

로컬에서 돌려보니 잘 작동했다. 테스트 데이터 100개로는 아무 문제 없었다.

### 충격적인 결과

"실제 환경에서는 데이터가 많을 텐데..." 성능 테스트를 위해 10만건의 데이터를 생성했다.

```bash
./gradlew :apps:commerce-api:test --tests "ProductDataGenerator"
```

- 브랜드: 100개
- 상품: 100,000개
- 좋아요: 약 20,000개

그리고 API를 호출했다.

```bash
time curl "http://localhost:8080/api/v1/products?sort=likes_desc&page=0&size=20"
```

**응답 시간: ~1000ms**

"1초?! 단 20개 상품을 보여주는데?"

### EXPLAIN으로 들여다보니

무슨 일이 일어나고 있는지 확인하기 위해 쿼리를 분석했다.

```sql
EXPLAIN SELECT p.*
FROM products p
LEFT JOIN likes l ON l.product_id = p.id
GROUP BY p.id
ORDER BY COUNT(l.id) DESC
LIMIT 20;
```

결과를 보고 얼어붙었다:

#### 📊 쿼리 실행 계획 (AS-IS)

```
🔴 1단계: 전체 Product 테이블 스캔 (100,000 rows)
    ↓
🔴 2단계: LEFT JOIN (임시 테이블 생성)
    ↓
🔴 3단계: GROUP BY + COUNT 계산
    ↓
🔴 4단계: 파일 정렬 (Filesort)
    ↓
✅ 5단계: 상위 20개 반환
```

| 항목 | 값 | 의미 |
|------|-----|------|
| type | **ALL** | 전체 테이블 스캔 🔴 |
| rows | **100,000** | 읽어야 할 행 수 |
| Extra | **Using filesort, Using temporary** | 임시 테이블 + 파일 정렬 🔴 |
| 실행 시간 | **~1000ms** | 매우 느림 |

**10만건 전체를 스캔하고, 임시 테이블을 만들고, 파일 정렬까지 수행**하고 있었다.

"JOIN + GROUP BY + COUNT가 이렇게 느릴 줄은..."

처음 알았다. 정규화된 구조가 항상 빠른 건 아니라는 것을.

## "정규화를 깨야 하나?"

### 내부의 갈등

데이터베이스 수업에서 배운 건 분명했다. "정규화는 중복을 제거하고 무결성을 보장한다."

지금 구조는 정규화 원칙에 완벽하게 부합했다:

**정규화된 구조의 장단점:**

| 측면 | 장점 ✅ | 단점 ⚠️ |
|------|---------|---------|
| 데이터 무결성 | 자동으로 보장됨 | - |
| 데이터 중복 | 없음 | - |
| 쓰기 성능 | 빠름 | - |
| 읽기 성능 | - | JOIN 필수 |
| 집계 연산 | - | GROUP BY + COUNT 필요 |
| 조회 속도 | - | 매우 느림 (1초) |

하지만 현실은 달랐다. **1초씩 걸리는 쿼리를 유저에게 보여줄 수는 없었다.**

"Product 테이블에 likeCount를 직접 넣으면... 빨라지긴 하겠지만..."

고민이 깊어졌다:

| 고려사항 | 정규화 유지 | 비정규화 |
|---------|------------|---------|
| 데이터 중복 | ✅ 없음 | ⚠️ likeCount 중복 |
| 데이터 일관성 | ✅ 자동 보장 | ⚠️ 수동 관리 필요 |
| 읽기 성능 | 🔴 느림 (JOIN) | ✅ 빠름 |
| 쓰기 성능 | ✅ 빠름 | ⚠️ 약간 느림 |
| 복잡도 | ✅ 낮음 | ⚠️ 높음 |

"그래도... 읽기가 쓰기보다 훨씬 많잖아?"

**워크로드 분석:**

| 작업 | 빈도 | 중요도 |
|------|------|--------|
| 상품 목록 조회 | 초당 수백 번 | 🔥🔥🔥 |
| 좋아요 추가/삭제 | 초당 수 번 | 🔥 |

결정했다. **"읽기 최적화를 선택한다."**

### 비정규화 적용

Product 엔티티에 likeCount를 추가했다.

**변경된 테이블 구조:**

| Product 테이블 (변경) | Like 테이블 |
|---------------------|------------|
| id (PK) | id (PK) |
| name | user_id |
| price | product_id (FK) |
| brand_id (FK) | |
| **like_count** ← 추가! | |

```kotlin
@Entity
@Table(name = "products")
class Product(
    val name: String,
    val price: Price,
    val brand: Brand,
) : BaseEntity() {
    @Column(name = "like_count", nullable = false)
    var likeCount: Long = 0L
        protected set
}
```

이제 쿼리는 단순해졌다:

```kotlin
@Query("""
    SELECT p FROM Product p
    ORDER BY p.likeCount DESC, p.id DESC
""")
fun findAllOrderByLikeCount(pageable: Pageable): Page<Product>
```

JOIN도 없고, GROUP BY도 없고, COUNT도 없다. 그냥 **정렬만** 하면 된다.

### 하지만 여전히 느렸다

다시 API를 호출했다.

```bash
time curl "http://localhost:8080/api/v1/products?sort=likes_desc"
```

**응답 시간: ~500ms**

"어? 빨라지긴 했는데... 여전히 너무 느린데?"

EXPLAIN을 다시 보니:

| 항목 | 값 |
|------|-----|
| type | **ALL** |
| rows | 100,000 |
| Extra | **Using filesort** |

여전히 전체 테이블을 스캔하고 있었다.

"인덱스가 없구나!"

## 인덱스, 제대로 이해하기

### "like_count에 인덱스만 추가하면 되겠지"

처음엔 단순하게 생각했다. 좋아요 순 정렬이니까 like_count에 인덱스를 추가하면 될 거라고.

하지만 실제 요구사항을 보니 더 복잡했다:

- 전체 상품 좋아요 순
- **브랜드 필터 + 좋아요 순**
- **브랜드 필터 + 가격 순**

"브랜드 필터가 있으면 어떻게 되지?"

### 복합 인덱스의 필요성

찾아보니 **복합 인덱스**가 필요했다. 인덱스는 왼쪽부터 순서대로 사용된다.

#### 🔍 인덱스 동작 원리 비교

| 구분 | 잘못된 인덱스 ❌ | 올바른 인덱스 ✅ |
|------|----------------|----------------|
| 인덱스 구성 | `INDEX: like_count` | `INDEX: brand_id, like_count` |
| 쿼리 조건 | `WHERE brand_id = 1`<br/>`ORDER BY like_count` | `WHERE brand_id = 1`<br/>`ORDER BY like_count` |
| 결과 | brand_id 필터를 사용하지 못함 | 완벽하게 사용됨! |
| 성능 | 🔴 느림 | ✅ 빠름 |

**핵심 원칙**: 필터 조건(WHERE)을 먼저, 정렬 조건(ORDER BY)을 나중에!

최종 인덱스 설계:

```kotlin
@Table(
    name = "products",
    indexes = [
        Index(name = "idx_brand_id_like_count", columnList = "brand_id,like_count DESC"),
        Index(name = "idx_brand_id_price", columnList = "brand_id,price_amount"),
        Index(name = "idx_like_count", columnList = "like_count DESC"),
    ],
)
```

| 인덱스 | 사용 시나리오 | 쿼리 예시 |
|--------|--------------|----------|
| (brand_id, like_count) | 브랜드 필터 + 좋아요 순 | `WHERE brand_id = 1 ORDER BY like_count` |
| (brand_id, price_amount) | 브랜드 필터 + 가격 순 | `WHERE brand_id = 1 ORDER BY price` |
| (like_count) | 전체 상품 좋아요 순 | `ORDER BY like_count` |

### 극적인 성능 개선

다시 EXPLAIN을 실행했다:

#### 📊 쿼리 실행 계획 비교

**AS-IS: 인덱스 없음 🔴**

```
1. 전체 테이블 스캔 (100,000 rows)
   ↓
2. 메모리에 로드
   ↓
3. 파일 정렬
   ↓
4. 20개 반환

⏱️ 실행 시간: ~500ms
```

**TO-BE: 복합 인덱스 ✅**

```
1. 인덱스 스캔 (~1,000 rows)
   ↓
2. 정렬된 상태로 읽기
   ↓
3. 20개 반환

⏱️ 실행 시간: ~10ms
```

**성능 비교 테이블:**

| 항목 | AS-IS (인덱스 없음) | TO-BE (복합 인덱스) | 개선율 |
|------|---------------------|---------------------|--------|
| type | ALL (전체 스캔) 🔴 | **ref** (인덱스) ✅ | - |
| key | NULL | **idx_brand_id_like_count** | - |
| rows | 100,000 | **~1,000** | 98% 감소 |
| Extra | Using filesort | **Using index** | - |
| 실행 시간 | ~500ms | **~10ms** | **98% 개선** |

**98% 성능 향상!**

"인덱스가 이렇게 중요했구나..."

처음 알았다. 인덱스는 단순히 "빠르게 하는 것"이 아니라, **어떻게 설계하느냐**가 중요하다는 것을.

## Redis 캐시 추가하기

### "인덱스로 충분하지 않나?"

10ms면 충분히 빠르다고 생각했다. 하지만 생각해보니:

**캐시 적용 적합성 체크:**

| 조건 | 인기 상품 목록 | 적합도 |
|------|---------------|--------|
| 조회 빈도 | 초당 수백 번 | ✅ 높음 |
| 데이터 변경 빈도 | 초당 수 번 (좋아요 추가) | ✅ 낮음 |
| 실시간성 요구 | 5분 지연 허용 | ✅ 낮음 |
| 동일 요청 반복 | 첫 페이지는 모든 유저가 조회 | ✅ 높음 |
| **결론** | **캐시 적용 적합!** | 🎯 |

"매번 DB에 접근할 필요가 있나?"

캐시를 추가하기로 했다.

### 캐시 아키텍처 설계

#### 📐 Repository 패턴으로 관심사 분리

**아키텍처 구조:**

```
ProductQueryService
    ↓ 의존
ProductCacheRepository (인터페이스)
    ↑ 구현
ProductCacheRepositoryImpl
    ↓ 사용
RedisTemplate
```

**계층별 역할:**

| 계층 | 역할 | 책임 |
|------|------|------|
| ProductQueryService | 비즈니스 로직 | 상품 조회 로직 처리 |
| ProductCacheRepository | 추상화 (인터페이스) | 캐시 작업 정의 (DIP) |
| ProductCacheRepositoryImpl | 구체 구현 | Redis 세부 구현 캡슐화 |
| RedisTemplate | 기술 스택 | 실제 Redis 연산 |

**Repository 패턴의 장점:**

| 측면 | 장점 |
|------|------|
| 관심사 분리 | Service는 비즈니스 로직, Repository는 Redis 담당 |
| 테스트 용이성 | Repository를 모킹하여 Service 단위 테스트 가능 |
| 변경 용이성 | Redis → Memcached 교체 시 Service 코드 변경 불필요 |
| DIP 적용 | 인터페이스에 의존하여 구체 구현으로부터 독립 |

### 캐시 동작 흐름

**캐시 조회 프로세스:**

1. **Client → Service**: 상품 목록 조회 요청 (브랜드1, 좋아요순)
2. **Service → Cache Repository**: 캐시 조회 (`product:list:brand:1:sort:likes_desc:page:0`)

**✅ 캐시 HIT 경로:**
- Cache Repository → Service: 캐시 데이터 반환
- Service → Client: **응답 (~5ms)**

**❌ 캐시 MISS 경로:**
- Cache Repository → Service: null 반환
- Service → DB: 쿼리 실행
- DB → Service: 데이터 반환
- Service → Cache Repository: 캐시 저장 (TTL 5분)
- Service → Client: **응답 (~100ms)**

핵심 구현 코드만 간단하게:

```kotlin
fun getProductDetail(productId: Long): ProductDetailData {
    val cacheKey = productCacheRepository.buildProductDetailCacheKey(productId)

    // 1. 캐시 조회
    val cached = productCacheRepository.get(cacheKey, ProductDetailData::class)
    if (cached != null) return cached

    // 2. DB 조회
    val product = productRepository.findById(productId) ?: throw NotFoundException()
    val productDetailData = ProductDetailData(product, stock)

    // 3. 캐시 저장
    productCacheRepository.set(cacheKey, productDetailData, Duration.ofMinutes(10))

    return productDetailData
}
```

### 캐시 키 설계

상품 목록은 여러 조건의 조합이다:

**캐시 키 구성 요소:**

- 브랜드: `brand:1` 또는 `brand:all`
- 정렬 방식: `sort:likes_desc` 또는 `sort:price_asc`
- 페이지 번호: `page:0`, `page:1`, ...
- 페이지 크기: `size:20`

**예시:**
- `product:list:brand:1:sort:likes_desc:page:0:size:20`
- `product:list:brand:all:sort:price_asc:page:1:size:20`

이제 Redis CLI에서 키를 보면 **한눈에 무슨 데이터인지** 알 수 있다.

### 캐시 무효화 전략

좋아요를 추가하면 캐시를 지워야 한다. 안 그러면 **좋아요 수가 업데이트되지 않는다.**

**캐시 무효화 프로세스:**

```
1. 좋아요 추가
   ↓
2. DB에 Like 저장
   ↓
3. Redis 카운트 증가 (INCR)
   ↓
4. 캐시 삭제
   ├─ 상품 상세: product:detail:123
   └─ 상품 목록: product:list:* (전체 삭제)
```

처음엔 걱정했다. "목록 캐시를 **전체 삭제**하는 게 너무 과하지 않나?"

하지만:

| 고려사항 | 판단 |
|---------|------|
| 좋아요 빈도 | 초당 수백 건 조회 vs 수 건의 좋아요 |
| TTL | 5분이므로 어차피 곧 만료 |
| 구현 복잡도 | 특정 목록만 삭제하려면 복잡도가 크게 증가 |

**결론**: 단순함을 택하자. KISS 원칙이다.

### 성능 측정

캐시 적용 전후를 비교했다:

```bash
# 1차 호출 (캐시 없음)
time curl "http://localhost:8080/api/v1/products?brandId=1&sort=likes_desc"
# → ~100ms

# 2차 호출 (캐시 있음)
time curl "http://localhost:8080/api/v1/products?brandId=1&sort=likes_desc"
# → ~5ms
```

**성능 비교:**

| 상태 | 응답 시간 | 개선율 |
|------|----------|--------|
| 캐시 없음 (DB 쿼리) | ~100ms | - |
| 캐시 있음 (Redis) | ~5ms | **95% ↑** |

**95% 성능 향상!**

## 좋아요 카운트의 동시성 문제

### "어? 좋아요 수가 이상한데?"

성능 최적화를 마치고 뿌듯해하던 중, 테스트 중 이상한 현상을 발견했다.

동시에 여러 명이 같은 상품에 좋아요를 누르면 **likeCount가 정확히 증가하지 않는** 문제였다.

#### ⚠️ Read-Modify-Write 문제

**동시성 문제 시나리오:**

| 시점 | Thread A | Thread B | DB 상태 |
|------|----------|----------|---------|
| 초기 | - | - | likeCount = 100 |
| T1 | DB 조회: 100 | DB 조회: 100 | likeCount = 100 |
| T2 | 계산: 100 + 1 = 101 | 계산: 100 + 1 = 101 | likeCount = 100 |
| T3 | UPDATE: 101 | UPDATE: 101 | likeCount = 101 |
| **결과** | - | - | **😱 2번 증가해야 하는데 1번만!** |

"성능은 빨라졌는데 정확하지 않으면 무슨 소용이지?"

### Redis Atomic 연산으로 해결

고민 끝에 **Redis의 INCR/DECR 명령어**를 사용하기로 했다.

Redis의 INCR/DECR은 **원자적(atomic) 연산**이다. 동시에 여러 스레드가 호출해도 안전하다.

#### ✅ Atomic 연산의 마법

**Atomic 연산 시나리오:**

| 시점 | Thread A | Thread B | Redis 상태 |
|------|----------|----------|-----------|
| 초기 | - | - | likeCount = 100 |
| T1 | Redis INCR (Atomic) | - | likeCount = 101 |
| T2 | 결과 반환: 101 | Redis INCR (Atomic) | likeCount = 102 |
| T3 | - | 결과 반환: 102 | likeCount = 102 |
| **결과** | - | - | **✅ 2번 증가 정확히 반영!** |

하지만 단순한 INCR/DECR로는 부족했다:
- **키가 없을 때**: DB에서 초기값을 가져와야 함
- **동시 초기화**: 여러 스레드가 동시에 초기화하면 경합 발생
- **0 이하 방지**: 감소 시 음수가 되면 안 됨

**Lua 스크립트로 원자적 연산 보장:**

```kotlin
// 핵심 로직만 간단하게
private val INCREMENT_IF_EXISTS_SCRIPT = RedisScript.of(
    """
    local current = redis.call('GET', KEYS[1])
    if current == false then
        return -1  -- 키가 없음을 표시
    end
    redis.call('INCR', KEYS[1])
    return tonumber(current) + 1
    """.trimIndent(),
    Long::class.java,
)

fun increment(productId: Long): Long {
    // 1단계: 키가 존재하면 바로 증가
    val result = redisRepository.incrementIfExists(productId)
    if (result != null && result != -1L) return result

    // 2단계: 키가 없으면 DB에서 초기화 후 증가
    val initialValue = productRepository.findById(productId)?.likeCount ?: 0L
    return redisRepository.initAndIncrement(productId, initialValue)
}
```

### "그럼 DB는 언제 업데이트하나?"

**스케줄러로 주기적 동기화:**

**데이터 동기화 프로세스:**

1. **즉시 (0초)**: 사용자가 좋아요 클릭 → Redis INCR
2. **실시간**: 다른 사용자가 최신값 조회 → Redis에서 읽기 ✅
3. **5분마다**: 스케줄러가 Redis → DB 동기화
   - Redis에서 모든 카운트 조회 (`SCAN product:like:count:*`)
   - 각 상품마다 DB UPDATE
4. **5분 이후**: DB 쿼리도 최신값 반영 완료

| 시점 | 동작 | 데이터 위치 |
|------|------|------------|
| 즉시 | 고객이 좋아요 클릭 | Redis에 반영 |
| 실시간 | 고객이 최신값 조회 | Redis에서 읽기 |
| 5분마다 | 배치 동기화 | DB에 반영 |

- **고객 조회**: Redis에서 항상 최신값 (실시간) ⚡
- **DB 반영**: 5분마다 배치 동기화 (지연 허용) 📊

### Redis 장애 시에는?

"Redis가 죽으면 어떻게 하지?"

**비관적 락을 사용한 Fallback 구현:**

```
increment() 호출
    ↓
Redis 연결 확인
    ↓
    ├─ ✅ Redis 정상
    │   ├─ Redis INCR 사용
    │   ├─ Atomic 연산으로 안전하게 증가
    │   └─ 성공 (매우 빠름 🚀)
    │
    └─ ❌ Redis 장애
        ├─ Redis 장애 감지
        ├─ DB Fallback 실행
        ├─ 비관적 락으로 조회
        ├─ likeCount 증가
        ├─ UPDATE & COMMIT
        └─ 성공 (느리지만 안전 ⚠️)
```

| 상황 | 동작 | 동시성 보장 | 성능 |
|------|------|------------|------|
| Redis 정상 | INCR/DECR 사용 | ✅ Atomic 연산 | 🚀 매우 빠름 |
| Redis 장애 | DB 비관적 락 사용 | ✅ PESSIMISTIC_WRITE | ⚠️ 느리지만 안전 |

### 트레이드오프

| 장점 | 단점 |
|------|------|
| 🚀 **성능**: Redis 메모리 연산, 매우 빠름 | ⏱️ **지연**: DB 반영은 최대 5분 지연 |
| ✅ **동시성**: Atomic 연산으로 안전 | 🔧 **복잡도**: 동기화 로직 관리 필요 |
| 📊 **확장성**: DB 부하 분산 | 💾 **의존성**: Redis 인프라 추가 |
| 🛡️ **안정성**: Redis 장애 시 자동 fallback | - |

"실시간성이 필요한가?"를 먼저 물어야 한다.

좋아요 수는 1-2개 차이는 유저가 신경 쓰지 않는다. 하지만 1초 걸리는 페이지는 바로 느낀다.

## 결과: AS-IS vs TO-BE

### 전체 성능 개선 요약

#### 📊 쿼리 실행 시간

| 쿼리 유형 | AS-IS | TO-BE | 개선율 |
|----------|-------|-------|--------|
| 브랜드 필터 + 좋아요 순 | ~500ms | ~10ms | **98% ↑** |
| 전체 좋아요 순 | ~1000ms | ~5ms | **99.5% ↑** |
| 브랜드 필터 + 가격 순 | ~300ms | ~10ms | **96.7% ↑** |

#### 📊 API 응답 시간

| API | 1차 호출 (캐시 없음) | 2차 호출 (캐시 있음) | 개선율 |
|-----|---------------------|---------------------|--------|
| 상품 목록 조회 | ~100ms | ~5ms | **95% ↑** |
| 상품 상세 조회 | ~50ms | ~3ms | **94% ↑** |

#### 🎯 개선 과정 요약

| 단계 | 방법 | 실행 시간 | 개선율 |
|------|------|----------|--------|
| **초기 상태** | JOIN + GROUP BY | ~1000ms | - |
| ⬇️ | | | |
| **1단계: 비정규화** | likeCount 추가 | ~500ms | 50% 개선 |
| ⬇️ | | | |
| **2단계: 복합 인덱스** | 인덱스 스캔 | ~10ms | 98% 개선 |
| ⬇️ | | | |
| **3단계: Redis 캐시** | 최종 | ~5ms | 99.5% 개선 |

"1초 걸리던 쿼리가 5ms로..."

## 배운 것들

### 1. 정규화는 만능이 아니다

데이터베이스 수업에서는 "정규화가 정답"이라고 배웠다. 무결성을 보장하고, 중복을 제거한다고.

하지만 **실무에서는 워크로드가 중요**하다는 걸 배웠다.

**워크로드 기반 설계 판단:**

```
워크로드 분석
    ├─ 읽기 >> 쓰기
    │   ├─ 비정규화로 조회 최적화
    │   ├─ 쓰기 시 정합성 관리 필요
    │   └─ 예: 상품 목록 조회 (초당 수백 건)
    │
    ├─ 쓰기 >> 읽기
    │   ├─ 정규화 유지
    │   ├─ 데이터 무결성 자동 보장
    │   └─ 인덱스로 성능 개선
    │
    └─ 균형적
        └─ 상황에 따라 판단
```

"정규화 vs 비정규화"가 아니라 **"상황에 맞는 선택"**이었다.

### 2. 인덱스는 설계하는 것이다

"인덱스를 추가하면 빨라진다"는 막연히 알고 있었다.

하지만 더 중요한 건:

| 질문 | 중요도 |
|------|--------|
| 어떤 컬럼에 인덱스를 걸까? | ⭐⭐⭐ |
| 복합 인덱스는 어떤 순서로? | ⭐⭐⭐⭐⭐ |
| WHERE 절과 ORDER BY 절의 관계는? | ⭐⭐⭐⭐ |

**인덱스는 단순히 추가하는 게 아니라, 쿼리 패턴을 분석해서 설계**해야 한다는 걸 배웠다.

`EXPLAIN`은 나의 친구가 되었다.

### 3. 캐시는 복잡도를 증가시킨다

캐시를 도입하면:

| 고려사항 | 영향 |
|---------|------|
| **무효화 로직** | 관리 복잡도 증가 |
| **데이터 불일치** | 가능성 존재 |
| **디버깅** | 캐시 때문에 최신 데이터 안 보임 |

하지만 그럼에도 **캐시는 가성비가 가장 좋은 성능 개선 방법** 중 하나다.

특히:

**캐시 적용 조건:**
- ✅ 읽기가 쓰기보다 훨씬 많을 때
- ✅ 데이터가 자주 변하지 않을 때
- ✅ 동일한 요청이 반복될 때

→ **상품 목록 조회에 완벽하게 부합** 🎯

### 4. TTL 전략의 중요성

처음엔 "캐시는 오래 보관할수록 좋다"고 생각했다.

하지만:

| TTL | 장점 | 단점 |
|-----|------|------|
| 너무 길게 (1시간+) | 캐시 히트율 높음 | 최신 데이터 반영 느림 |
| 적절하게 (5-10분) | 균형적 | - |
| 너무 짧게 (1분 미만) | 최신 데이터 빠른 반영 | 캐시 효과 낮음 |

현재 설정:

| 캐시 대상 | TTL | 이유 |
|----------|-----|------|
| 상품 상세 | 10분 | 상품 정보는 자주 변경되지 않음 |
| 상품 목록 | 5분 | 좋아요 변경이 반영되어야 하지만 실시간일 필요는 없음 |

**"실시간이어야 하는가?"**를 먼저 고민해야 한다.

좋아요 수는 1-2개 차이는 유저가 신경 쓰지 않는다. 하지만 페이지가 느린 건 바로 느낀다.

### 5. 명시적 제어의 가치

`@Cacheable`을 쓰면 간단하다. 하지만 **무슨 일이 일어나는지 명확하지 않다.**

RedisTemplate을 직접 사용하니:
- 캐시 키가 **정확히 뭔지** 안다
- TTL이 **언제 만료되는지** 안다
- 무효화가 **정확히 언제 일어나는지** 안다

디버깅할 때, 장애 상황에서, **정확히 알고 있다는 것**은 엄청난 가치가 있다.

## 한계와 개선 방향

### Redis-DB 동기화 지연

Redis와 DB 간에 **최대 5분의 지연**이 있다.

**데이터 동기화 타임라인:**

| 시점 | 이벤트 | 상태 |
|------|--------|------|
| 0초 | 좋아요 클릭 | Redis INCR (likeCount = 101) |
| 1초 | 다른 유저 조회 | Redis에서 101 확인 ✅ |
| ... | ... | ... |
| 5분 | 스케줄러 실행 | DB UPDATE (likeCount = 101) |
| 5분+ | DB 쿼리 | DB 쿼리도 최신값 반영 |

이로 인해:

| 영향받는 기능 | 영향 | 해결 방법 |
|-------------|------|----------|
| DB 기반 쿼리 | 5분 전 데이터 기준 | 동기화 API 제공 |
| 분석/리포트 | 실시간 통계 부정확 | 별도 집계 테이블 운영 |
| Redis 초기화 | 앱 재시작 시 DB에서 로드 | 캐시 워밍 적용 |

현재는 "5분 지연은 사용자 경험에 큰 영향 없음"으로 판단했다.

### 인덱스 비용

인덱스는 **조회 성능을 향상시키지만 쓰기 성능을 저하**시킨다.

**인덱스 업데이트 오버헤드:**

```
상품 업데이트 발생
    ↓
3개의 인덱스 업데이트 필요
    ├─ idx_brand_id_like_count
    ├─ idx_brand_id_price
    └─ idx_like_count
```

만약 쓰기가 훨씬 많은 워크로드라면?

- 인덱스를 줄이거나
- 비동기로 인덱스를 업데이트하거나
- 파티셔닝을 고려해야 한다

### 캐시 무효화 전략

지금은 좋아요가 변경되면 **모든 목록 캐시를 삭제**한다.

좋아요가 빈번해지면:

| 문제 | 개선 방향 |
|------|----------|
| 캐시가 자주 비워져서 효과 감소 | 해당 브랜드의 캐시만 삭제 |
| 조회할 때마다 캐시 MISS | Write-Through 캐시 (쓰기 시 캐시도 업데이트) |
| 동기적 캐시 무효화로 쓰기 성능 저하 | 이벤트 기반 비동기 무효화 |

## 다음에 시도해보고 싶은 것

### 1. Materialized View

Materialized View를 사용하면 **쿼리 결과를 물리적으로 저장**할 수 있다.

```sql
CREATE MATERIALIZED VIEW product_with_like_count AS
SELECT p.*, COUNT(l.id) as like_count
FROM products p
LEFT JOIN likes l ON l.product_id = p.id
GROUP BY p.id;
```

주기적으로 갱신하면:
- 조회 성능 극대화
- 비정규화 없이도 빠른 조회
- 정합성은 갱신 주기에 따라 조절

### 2. Read Replica

읽기 전용 복제본을 활용하면:

**Read Replica 아키텍처:**

```
Client 요청
    ↓
요청 타입 분기
    ├─ Write → Main DB
    └─ Read → Read Replica 1, 2
              (Main DB에서 복제)
```

- **쓰기와 읽기를 분리**
- 조회 부하 분산
- 메인 DB 부담 감소

### 3. APM 도구로 실제 워크로드 분석

지금은 추측과 테스트로 최적화했다. 하지만 **실제 운영 환경**에서는:

| 궁금한 것 | APM으로 확인 가능 |
|----------|-----------------|
| 어떤 쿼리가 가장 느린가? | ✅ |
| 어떤 API가 가장 자주 호출되는가? | ✅ |
| 캐시 히트율은 얼마나 되는가? | ✅ |
| 병목 지점은 어디인가? | ✅ |

DataDog, New Relic 같은 APM 도구로 **실제 데이터 기반 최적화**를 하고 싶다.

## 마치며

### "정규화가 항상 옳은가?"

이번 라운드를 통해 이 질문에 대한 답을 얻었다.

**"상황에 따라 다르다."**

**워크로드 분석 기반 의사결정:**

| 조건 | 선택 | 이유 |
|------|------|------|
| 읽기 >> 쓰기 | 비정규화로 조회 최적화 | 읽기 성능 극대화 |
| 정합성이 중요 | 동기화 로직 강화 | 데이터 일관성 보장 |
| 정합성 덜 중요 | 배치로 재계산 | 구현 단순화 |
| 쓰기 >> 읽기 | 정규화 유지 | 데이터 무결성 자동 보장 |
| 균형적 | 상황에 따라 | 트레이드오프 고려 |

"은탄환은 없다." 다만 **트레이드오프를 이해하고 선택**하는 것뿐이다.

### "LEFT JOIN + GROUP BY + COUNT면 되겠지"

처음엔 간단해 보였다. 정규화되어 있고, JOIN 하나 추가하면 될 것 같았다.

하지만 10만건 데이터에서 1초씩 걸리는 걸 보고 깨달았다.

**"돌아간다"와 "빠르게 돌아간다"는 완전히 다르다.**

Round 4에서 "안전하게 돌아간다"를 배웠다면, Round 5에서는 **"빠르게 돌아간다"**를 배웠다.

### 다음은

이제 기본적인 CRUD는 안전하고 빠르게 작동한다.

하지만 여전히 궁금한 게 많다:

**다음 단계:**

- **분산 환경**
  - 캐시 클러스터링
  - Redis Sentinel
  - Redis Cluster

- **트래픽 대응**
  - 로드 밸런싱
  - 오토 스케일링
  - 서킷 브레이커

- **대용량 데이터**
  - 파티셔닝
  - 샤딩
  - 아카이빙

다음 라운드에서는 이런 것들을 고민해보고 싶다.

"좋아요 순 정렬 하나"에서 시작해서, 비정규화, 인덱스, 캐시까지 배웠다.

성능 최적화는 이제 시작일 뿐이다. 🚀
