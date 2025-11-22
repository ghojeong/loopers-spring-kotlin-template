# 좋아요 순 정렬 하나 추가했다가 서버가 죽을 뻔한 이야기

**TL;DR**: "인기 상품 순으로 보여주세요"라는 요구사항 하나 추가했다가, 10만건 데이터에서 쿼리가 1초씩 걸리는 걸 보고 충격받았다. "LEFT JOIN + GROUP BY + COUNT면 되겠지"라고 생각했는데, **EXPLAIN 분석 결과를 보고 전체 테이블 스캔**을 하고 있다는 걸 깨달았다. DB 정규화는 제대로 했는데 왜 이렇게 느릴까? 고민 끝에 Product 테이블에 likeCount를 직접 넣는 비정규화를 선택했고, 복합 인덱스를 설계하고, Redis 캐시까지 적용했더니 **99% 이상 빨라졌다**.

## "인기 상품 순으로 보여주세요"

### 처음 마주한 요구사항

Round 4에서 동시성 문제를 해결하고 나니 자신감이 생겼다. "이제 기본은 다 되었다"고 생각했다.

그때 새로운 요구사항이 들어왔다:

> "사용자들이 가장 많이 좋아요를 누른 상품 순으로 보여주세요."

"뭐 어렵겠어?" 이미 Product와 Like 테이블이 정규화되어 있었으니까.

```kotlin
@Entity
@Table(name = "products")
class Product(
    val name: String,
    val price: Price,
    val brand: Brand,
) : BaseEntity()

@Entity
@Table(name = "likes")
class Like(
    val userId: Long,
    val productId: Long,
) : BaseEntity()
```

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

| 항목 | 값 |
|------|-----|
| type | **ALL** (전체 테이블 스캔) |
| rows | **100,000** |
| Extra | **Using filesort, Using temporary** |

**10만건 전체를 스캔하고, 임시 테이블을 만들고, 파일 정렬까지 수행**하고 있었다.

"JOIN + GROUP BY + COUNT가 이렇게 느릴 줄은..."

처음 알았다. 정규화된 구조가 항상 빠른 건 아니라는 것을.

## "정규화를 깨야 하나?"

### 내부의 갈등

데이터베이스 수업에서 배운 건 분명했다. "정규화는 중복을 제거하고 무결성을 보장한다."

지금 구조는 정규화 원칙에 완벽하게 부합했다:

- Product 테이블: 상품 정보만
- Like 테이블: 좋아요 정보만
- 중복 데이터 없음
- 참조 무결성 보장

하지만 현실은 달랐다. **1초씩 걸리는 쿼리를 유저에게 보여줄 수는 없었다.**

"Product 테이블에 likeCount를 직접 넣으면... 빨라지긴 하겠지만..."

고민이 깊어졌다:

- likeCount를 추가하면 **비정규화**다
- Like 테이블과 **데이터 불일치** 가능성
- 좋아요 추가/삭제할 때마다 **두 곳을 업데이트**해야 함

"그래도... 읽기가 쓰기보다 훨씬 많잖아?"

| 상황 | 빈도 |
|------|------|
| 상품 목록 조회 | 초당 수백 번 |
| 좋아요 추가/삭제 | 초당 수 번 |

결정했다. **"읽기 최적화를 선택한다."**

### 비정규화 적용

Product 엔티티에 likeCount를 추가했다.

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

    fun incrementLikeCount() {
        this.likeCount += 1
    }

    fun decrementLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount -= 1
        }
    }
}
```

좋아요 추가 시 likeCount도 함께 업데이트:

```kotlin
@Transactional
fun addLike(userId: Long, productId: Long) {
    if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
        return
    }

    val product = productRepository.findById(productId)
        ?: throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다")

    product.incrementLikeCount()  // 좋아요 수 증가

    val like = Like(userId = userId, productId = productId)
    likeRepository.save(like)
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

```kotlin
@Table(
    name = "products",
    indexes = [
        Index(name = "idx_like_count", columnList = "like_count DESC"),
    ],
)
```

하지만 실제 요구사항을 보니 더 복잡했다:

- 전체 상품 좋아요 순
- **브랜드 필터 + 좋아요 순**
- **브랜드 필터 + 가격 순**

"브랜드 필터가 있으면 어떻게 되지?"

```sql
EXPLAIN SELECT p.*
FROM products p
WHERE p.brand_id = 1
ORDER BY p.like_count DESC
LIMIT 20;
```

여전히 느렸다. 인덱스가 제대로 사용되지 않았다.

### 복합 인덱스의 필요성

찾아보니 **복합 인덱스**가 필요했다. 인덱스는 왼쪽부터 순서대로 사용된다.

**잘못된 인덱스:**

```sql
INDEX (like_count)
WHERE brand_id = 1 ORDER BY like_count  ← brand_id 필터를 못 씀
```

**올바른 인덱스:**

```sql
INDEX (brand_id, like_count)
WHERE brand_id = 1 ORDER BY like_count  ← 완벽하게 사용됨
```

"필터 조건(WHERE)을 먼저, 정렬 조건(ORDER BY)을 나중에!"

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

| 인덱스 | 사용 시나리오 |
|--------|--------------|
| (brand_id, like_count) | 브랜드 필터 + 좋아요 순 정렬 |
| (brand_id, price_amount) | 브랜드 필터 + 가격 순 정렬 |
| (like_count) | 전체 상품 좋아요 순 정렬 |

### 극적인 성능 개선

다시 EXPLAIN을 실행했다:

```sql
EXPLAIN SELECT p.*
FROM products p
WHERE p.brand_id = 1
ORDER BY p.like_count DESC
LIMIT 20;
```

| 항목 | AS-IS | TO-BE |
|------|-------|-------|
| type | ALL | **ref** |
| key | NULL | **idx_brand_id_like_count** |
| rows | 100,000 | **~1,000** (브랜드별) |
| Extra | Using filesort | **Using index** |
| 실행 시간 | ~500ms | **~10ms** |

**98% 성능 향상!**

"인덱스가 이렇게 중요했구나..."

처음 알았다. 인덱스는 단순히 "빠르게 하는 것"이 아니라, **어떻게 설계하느냐**가 중요하다는 것을.

## Redis 캐시 추가하기

### "인덱스로 충분하지 않나?"

10ms면 충분히 빠르다고 생각했다. 하지만 생각해보니:

- 인기 상품 목록은 **모든 유저가 본다**
- 같은 페이지를 **수백 명이 동시에 조회**한다
- 데이터는 **자주 변하지 않는다**

"매번 DB에 접근할 필요가 있나?"

캐시를 추가하기로 했다.

### "@Cacheable vs RedisTemplate"

Spring의 `@Cacheable`을 쓸까 고민했다. 간단하고 빠르니까.

```kotlin
@Cacheable("products")
fun findProducts(...): Page<Product>
```

하지만 불안했다:

- 캐시가 **언제 저장**되는지 정확히 알고 싶다
- 캐시가 **언제 무효화**되는지 명확히 제어하고 싶다
- 디버깅할 때 **무슨 일이 일어나는지** 보고 싶다

"실무에서 캐시 버그는 심각한 장애로 이어질 수 있다."

**RedisTemplate을 직접 사용**하기로 했다.

### 명시적 캐시 구현

```kotlin
@Service
class ProductQueryService(
    private val productRepository: ProductRepository,
    private val stockRepository: StockRepository,
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val PRODUCT_DETAIL_CACHE_PREFIX = "product:detail:"
        private const val PRODUCT_LIST_CACHE_PREFIX = "product:list:"
        private val PRODUCT_DETAIL_TTL = Duration.ofMinutes(10)
        private val PRODUCT_LIST_TTL = Duration.ofMinutes(5)
    }

    fun getProductDetail(productId: Long): ProductDetailData {
        val cacheKey = "$PRODUCT_DETAIL_CACHE_PREFIX$productId"

        // 1. Redis에서 먼저 조회
        val cached = redisTemplate.opsForValue().get(cacheKey)
        if (cached != null) {
            return objectMapper.readValue(cached)
        }

        // 2. DB 조회
        val product = productRepository.findById(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다")
        val stock = stockRepository.findByProductId(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "재고 정보를 찾을 수 없습니다")
        val productDetailData = ProductDetailData(product, stock)

        // 3. Redis에 캐시 저장 (10분 TTL)
        val cacheValue = objectMapper.writeValueAsString(productDetailData)
        redisTemplate.opsForValue().set(cacheKey, cacheValue, PRODUCT_DETAIL_TTL)

        return productDetailData
    }
}
```

이제 **정확히 무슨 일이 일어나는지** 알 수 있다:

1. 어떤 키로 저장되는지 (`product:detail:123`)
2. 언제 만료되는지 (10분)
3. 어떤 데이터가 저장되는지 (JSON 직렬화)

### 캐시 키 설계의 중요성

상품 목록은 여러 조건의 조합이다:

- 브랜드 필터 (있을 수도, 없을 수도)
- 정렬 방식 (likes_desc, price_asc, ...)
- 페이지 번호 (0, 1, 2, ...)

"키를 어떻게 설계해야 중복도 없고 명확할까?"

```kotlin
private fun buildProductListCacheKey(
    brandId: Long?,
    sort: String,
    pageable: Pageable
): String {
    val brand = brandId ?: "all"
    val prefix = PRODUCT_LIST_CACHE_PREFIX
    return "${prefix}brand:$brand:sort:$sort:page:${pageable.pageNumber}:size:${pageable.pageSize}"
}
```

예시:

- `product:list:brand:1:sort:likes_desc:page:0:size:20`
- `product:list:brand:all:sort:price_asc:page:1:size:20`

이제 Redis CLI에서 키를 보면 **한눈에 무슨 데이터인지** 알 수 있다.

### 캐시 무효화는 어떻게?

좋아요를 추가하면 캐시를 지워야 한다. 안 그러면 **좋아요 수가 업데이트되지 않는다.**

```kotlin
@Transactional
fun addLike(userId: Long, productId: Long) {
    // ... 좋아요 추가 로직 ...

    evictProductCache(productId)  // 캐시 무효화
}

private fun evictProductCache(productId: Long) {
    // 상품 상세 캐시 삭제
    redisTemplate.delete("product:detail:$productId")

    // 상품 목록 캐시 전체 삭제 (좋아요 순위 변경)
    val keys = redisTemplate.keys("product:list:*")
    if (keys.isNotEmpty()) {
        redisTemplate.delete(keys)
    }
}
```

처음엔 걱정했다. "목록 캐시를 **전체 삭제**하는 게 너무 과하지 않나?"

하지만:

1. 좋아요는 빈번하지 않다 (초당 수백 건 조회 vs 수 건의 좋아요)
2. 목록 캐시는 5분 TTL이므로 **어차피 곧 만료**된다
3. 특정 목록만 삭제하려면 **복잡도가 크게 증가**한다

"단순함을 택하자." KISS 원칙이다.

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

**95% 성능 향상!**

Redis 캐시를 확인해보니:

```bash
$ redis-cli
127.0.0.1:6379> KEYS product:*
1) "product:list:brand:1:sort:likes_desc:page:0:size:20"
2) "product:detail:1"

127.0.0.1:6379> TTL product:detail:1
(integer) 587  # 10분 = 600초
```

"캐시가 정확히 설계대로 작동하고 있구나!"

## 결과: AS-IS vs TO-BE

### 쿼리 실행 시간

| 쿼리 | AS-IS | TO-BE | 개선율 |
|------|-------|-------|--------|
| 브랜드 필터 + 좋아요 순 | ~500ms | ~10ms | **98% ↑** |
| 전체 좋아요 순 | ~1000ms | ~5ms | **99.5% ↑** |
| 브랜드 필터 + 가격 순 | ~300ms | ~10ms | **96.7% ↑** |

### API 응답 시간

| API | 1차 호출 (캐시 없음) | 2차 호출 (캐시 있음) | 개선율 |
|-----|---------------------|---------------------|--------|
| 상품 목록 조회 | ~100ms | ~5ms | **95% ↑** |
| 상품 상세 조회 | ~50ms | ~3ms | **94% ↑** |

"1초 걸리던 쿼리가 5ms로..."

## 배운 것들

### 1. 정규화는 만능이 아니다

데이터베이스 수업에서는 "정규화가 정답"이라고 배웠다. 무결성을 보장하고, 중복을 제거한다고.

하지만 **실무에서는 워크로드가 중요**하다는 걸 배웠다.

읽기가 많은 서비스라면:

- 비정규화로 **조회 성능을 극대화**
- 쓰기 시 정합성 유지 로직 추가
- 필요하면 배치로 재계산

"정규화 vs 비정규화"가 아니라 **"상황에 맞는 선택"**이었다.

### 2. 인덱스는 설계하는 것이다

"인덱스를 추가하면 빨라진다"는 막연히 알고 있었다.

하지만:

- 어떤 컬럼에 인덱스를 걸까?
- 복합 인덱스는 어떤 순서로?
- WHERE 절과 ORDER BY 절의 관계는?

**인덱스는 단순히 추가하는 게 아니라, 쿼리 패턴을 분석해서 설계**해야 한다는 걸 배웠다.

`EXPLAIN`은 나의 친구가 되었다.

### 3. 캐시는 복잡도를 증가시킨다

캐시를 도입하면:

- **무효화 로직**을 관리해야 한다
- 캐시와 DB의 **불일치 가능성**이 생긴다
- 디버깅이 어려워진다 (캐시 때문에 최신 데이터가 안 보임)

하지만 그럼에도 **캐시는 가성비가 가장 좋은 성능 개선 방법** 중 하나다.

특히:

- 읽기가 쓰기보다 훨씬 많을 때
- 데이터가 자주 변하지 않을 때
- 동일한 요청이 반복될 때

### 4. TTL 전략의 중요성

처음엔 "캐시는 오래 보관할수록 좋다"고 생각했다.

하지만:

- 너무 길면 → 최신 데이터가 반영 안 됨
- 너무 짧으면 → 캐시 효과가 없음

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

### 정합성 문제

Product.likeCount와 Like 테이블의 count가 **항상 일치한다고 보장할 수 없다.**

동시성 버그, 네트워크 장애 등으로 어긋날 수 있다.

만약 정합성이 중요하다면:

1. 주기적으로 **배치 작업**으로 likeCount 재계산
2. **이벤트 소싱**으로 일관성 보장
3. **Materialized View** 활용

지금은 "좋아요 수가 1-2개 차이나는 것보다 빠른 응답이 중요"하다고 판단했다.

### 인덱스 비용

인덱스는 **조회 성능을 향상시키지만 쓰기 성능을 저하**시킨다.

좋아요를 추가할 때마다 **3개의 인덱스를 업데이트**해야 한다.

만약 쓰기가 훨씬 많은 워크로드라면?

- 인덱스를 줄이거나
- 비동기로 인덱스를 업데이트하거나
- 파티셔닝을 고려해야 한다

### 캐시 무효화 전략

지금은 좋아요가 변경되면 **모든 목록 캐시를 삭제**한다.

좋아요가 빈번해지면:

- 캐시가 자주 비워져서 효과가 떨어진다
- 더 정교한 무효화 전략이 필요하다

개선 방향:

- 해당 브랜드의 캐시만 삭제
- Write-Through 캐시 (쓰기 시 캐시도 업데이트)
- 이벤트 기반 비동기 무효화

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

- **쓰기와 읽기를 분리**
- 조회 부하 분산
- 메인 DB 부담 감소

```kotlin
@Transactional(readOnly = true)
fun findProducts(...) {
    // Read Replica로 자동 라우팅
}
```

### 3. APM 도구로 실제 워크로드 분석

지금은 추측과 테스트로 최적화했다. 하지만 **실제 운영 환경**에서는:

- 어떤 쿼리가 가장 느린가?
- 어떤 API가 가장 자주 호출되는가?
- 캐시 히트율은 얼마나 되는가?

DataDog, New Relic 같은 APM 도구로 **실제 데이터 기반 최적화**를 하고 싶다.

## 마치며

### "정규화가 항상 옳은가?"

이번 라운드를 통해 이 질문에 대한 답을 얻었다.

**"상황에 따라 다르다."**

- 읽기가 많으면 → 비정규화로 조회 최적화
- 정합성이 중요하면 → 정규화 유지, 인덱스로 성능 개선
- 실시간성이 필요하면 → 캐시 TTL을 짧게
- 데이터가 안정적이면 → 캐시 TTL을 길게

"은탄환은 없다." 다만 **트레이드오프를 이해하고 선택**하는 것뿐이다.

### "LEFT JOIN + GROUP BY + COUNT면 되겠지"

처음엔 간단해 보였다. 정규화되어 있고, JOIN 하나 추가하면 될 것 같았다.

하지만 10만건 데이터에서 1초씩 걸리는 걸 보고 깨달았다.

**"돌아간다"와 "빠르게 돌아간다"는 완전히 다르다.**

Round 4에서 "안전하게 돌아간다"를 배웠다면, Round 5에서는 **"빠르게 돌아간다"**를 배웠다.

### 다음은

이제 기본적인 CRUD는 안전하고 빠르게 작동한다.

하지만 여전히 궁금한 게 많다:

- 분산 환경에서는 어떻게 캐시를 관리할까?
- 트래픽이 폭증하면 어떻게 대응할까?
- 데이터가 1000만건이 되면?

다음 라운드에서는 이런 것들을 고민해보고 싶다.

"좋아요 순 정렬 하나"에서 시작해서, 비정규화, 인덱스, 캐시까지 배웠다.

성능 최적화는 이제 시작일 뿐이다.
