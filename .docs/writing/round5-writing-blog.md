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

    /**
     * 좋아요 수를 직접 설정합니다.
     * 주의: Redis와 DB 동기화 시에만 사용해야 합니다.
     */
    internal fun setLikeCount(count: Long) {
        this.likeCount = maxOf(0, count)
    }
}
```

~~좋아요 추가 시 likeCount도 함께 업데이트:~~ *(이 방식은 동시성 문제가 있어 후에 개선됩니다)*

```kotlin
@Transactional
fun addLike(userId: Long, productId: Long) {
    if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
        return
    }

    val product = productRepository.findById(productId)
        ?: throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다")

    product.incrementLikeCount()  // ⚠️ 동시성 문제 발생!

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

**초기에는 RedisTemplate을 직접 사용했습니다:**

```kotlin
@Service
class ProductQueryService(
    private val productRepository: ProductRepository,
    private val stockRepository: StockRepository,
    private val redisTemplate: RedisTemplate<String, String>,  // ❌ 직접 사용
    private val objectMapper: ObjectMapper,
) {
    fun getProductDetail(productId: Long): ProductDetailData {
        val cacheKey = "$PRODUCT_DETAIL_CACHE_PREFIX$productId"

        // Redis 직접 접근
        val cached = redisTemplate.opsForValue().get(cacheKey)
        if (cached != null) {
            return objectMapper.readValue(cached)
        }

        // ... DB 조회 및 캐시 저장
    }
}
```

하지만 **Service가 Redis 구현 세부사항에 의존**하는 문제가 있었습니다.

**Repository 패턴으로 리팩토링:**

```kotlin
// ProductCacheRepository.kt - Redis 작업을 캡슐화
@Repository
class ProductCacheRepository(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    fun <T> get(cacheKey: String, typeReference: TypeReference<T>): T? =
        runCatching {
            redisTemplate.opsForValue().get(cacheKey)?.let { cached ->
                objectMapper.readValue(cached, typeReference)
            }
        }.onFailure { e ->
            logger.error("Failed to read from Redis cache: cacheKey=$cacheKey", e)
        }.getOrNull()

    fun <T> set(cacheKey: String, data: T, ttl: Duration) {
        runCatching {
            val cacheValue = objectMapper.writeValueAsString(data)
            redisTemplate.opsForValue().set(cacheKey, cacheValue, ttl)
        }.onFailure { e ->
            logger.error("Failed to write to Redis cache: cacheKey=$cacheKey", e)
        }
    }

    fun buildProductDetailCacheKey(productId: Long): String =
        "$PRODUCT_DETAIL_CACHE_PREFIX$productId"
}

// ProductQueryService.kt - Repository를 통한 간접 접근
@Service
class ProductQueryService(
    private val productRepository: ProductRepository,
    private val stockRepository: StockRepository,
    private val productCacheRepository: ProductCacheRepository,  // ✅ Repository 사용
) {
    fun getProductDetail(productId: Long): ProductDetailData {
        val cacheKey = productCacheRepository.buildProductDetailCacheKey(productId)

        // 1. Redis에서 먼저 조회 (Repository를 통해)
        val cached = productCacheRepository.get(
            cacheKey,
            object : TypeReference<ProductDetailData>() {}
        )
        if (cached != null) {
            return cached
        }

        // 2. DB 조회
        val product = productRepository.findById(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다")
        val stock = stockRepository.findByProductId(productId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "재고 정보를 찾을 수 없습니다")
        val productDetailData = ProductDetailData(product, stock)

        // 3. Redis에 캐시 저장 (Repository를 통해)
        productCacheRepository.set(cacheKey, productDetailData, PRODUCT_DETAIL_TTL)

        return productDetailData
    }
}
```

**Repository 패턴의 장점:**

- **관심사 분리**: Service는 비즈니스 로직에 집중, Redis 세부사항은 Repository가 담당
- **테스트 용이성**: ProductCacheRepository를 모킹하여 Service 테스트 가능
- **변경 용이성**: Redis 구현을 변경해도 Service 코드는 변경 불필요

**인터페이스/구현체 분리로 DIP 적용:**

Repository 패턴을 더욱 개선하여 인터페이스와 구현체를 분리했습니다. 이는 **의존성 역전 원칙(DIP, Dependency Inversion Principle)**을 따르는 것입니다.

```kotlin
// Domain Layer: domain/product/ProductCacheRepository.kt (인터페이스)
interface ProductCacheRepository {
    fun <T> get(cacheKey: String, typeReference: TypeReference<T>): T?
    fun <T> set(cacheKey: String, data: T, ttl: Duration)
    fun delete(cacheKey: String)
    fun deleteByPattern(pattern: String)
    fun buildProductDetailCacheKey(productId: Long): String
    fun buildProductListCacheKey(brandId: Long?, sort: String, pageNumber: Int, pageSize: Int): String
    fun getProductListCachePattern(): String
}

// Infrastructure Layer: infrastructure/product/ProductCacheRepositoryImpl.kt (구현체)
@Component
class ProductCacheRepositoryImpl(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : ProductCacheRepository {
    override fun <T> get(cacheKey: String, typeReference: TypeReference<T>): T? =
        runCatching {
            redisTemplate.opsForValue().get(cacheKey)?.let { cached ->
                objectMapper.readValue(cached, typeReference)
            }
        }.onFailure { e ->
            logger.error("Failed to read from Redis cache: cacheKey=$cacheKey", e)
        }.getOrNull()
    // ... 나머지 구현
}
```

**DIP의 장점:**

- **도메인 독립성**: Domain Layer는 Redis 구현 기술에 의존하지 않음
- **유연한 구현 교체**: Redis를 Memcached나 다른 캐시로 교체해도 Domain Layer는 변경 불필요
- **테스트 격리**: 인터페이스를 모킹하여 Domain Service를 독립적으로 테스트 가능
- **명확한 책임**: 인터페이스는 "무엇을" 정의하고, 구현체는 "어떻게"를 정의

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

**초기 구현 (RedisTemplate 직접 사용):**

```kotlin
@Transactional
fun addLike(userId: Long, productId: Long) {
    // ... 좋아요 추가 로직 ...

    evictProductCache(productId)  // 캐시 무효화
}

private fun evictProductCache(productId: Long) {
    // 상품 상세 캐시 삭제
    redisTemplate.delete("product:detail:$productId")  // ❌ 직접 접근

    // 상품 목록 캐시 전체 삭제
    val keys = redisTemplate.keys("product:list:*")  // ❌ 직접 접근
    if (keys.isNotEmpty()) {
        redisTemplate.delete(keys)
    }
}
```

**Repository 패턴으로 리팩토링:**

```kotlin
// ProductCacheRepository.kt에 무효화 메서드 추가
@Repository
class ProductCacheRepository(/* ... */) {
    fun delete(cacheKey: String) {
        redisTemplate.delete(cacheKey)
    }

    fun deleteByPattern(pattern: String) {
        val keys = scanKeys(pattern)
        if (keys.isNotEmpty()) {
            redisTemplate.delete(keys)
        }
    }

    fun buildProductDetailCacheKey(productId: Long): String =
        "$PRODUCT_DETAIL_CACHE_PREFIX$productId"

    fun getProductListCachePattern(): String =
        "$PRODUCT_LIST_CACHE_PREFIX*"

    private fun scanKeys(pattern: String): Set<String> {
        val keys = mutableSetOf<String>()
        redisTemplate.execute { connection ->
            val scanOptions = ScanOptions.scanOptions()
                .match(pattern)
                .count(100)
                .build()

            connection.scan(scanOptions).use { cursor ->
                while (cursor.hasNext()) {
                    keys.add(String(cursor.next()))
                }
            }
        }
        return keys
    }
}

// LikeService.kt - Repository를 통한 캐시 무효화
@Service
class LikeService(
    private val likeRepository: LikeRepository,
    private val productRepository: ProductRepository,
    private val productLikeCountService: ProductLikeCountService,
    private val productCacheRepository: ProductCacheRepository,  // ✅ Repository 사용
) {
    @Transactional
    fun addLike(userId: Long, productId: Long) {
        // ... 좋아요 추가 로직 ...

        evictProductCache(productId)  // 캐시 무효화
    }

    private fun evictProductCache(productId: Long) {
        // 상품 상세 캐시 삭제 (Repository를 통해)
        val detailCacheKey = productCacheRepository.buildProductDetailCacheKey(productId)
        productCacheRepository.delete(detailCacheKey)

        // 상품 목록 캐시 삭제 (Repository를 통해)
        val listCachePattern = productCacheRepository.getProductListCachePattern()
        productCacheRepository.deleteByPattern(listCachePattern)
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

## 좋아요 카운트의 동시성 문제

### "어? 좋아요 수가 이상한데?"

성능 최적화를 마치고 뿌듯해하던 중, 테스트 중 이상한 현상을 발견했다.

동시에 여러 명이 같은 상품에 좋아요를 누르면 **likeCount가 정확히 증가하지 않는** 문제였다.

```kotlin
// 현재 구현 (문제 있음)
fun incrementLikeCount() {
    this.likeCount += 1  // Read-Modify-Write 패턴
}
```

**시나리오:**
```
Thread A: likeCount = 100 읽음
Thread B: likeCount = 100 읽음
Thread A: likeCount = 101로 UPDATE
Thread B: likeCount = 101로 UPDATE (102가 되어야 하는데!)
→ 결과: 2번의 좋아요가 1번만 반영됨
```

"성능은 빨라졌는데 정확하지 않으면 무슨 소용이지?"

### Redis Atomic 연산으로 해결

고민 끝에 **Redis의 INCR/DECR 명령어**를 사용하기로 했다.

Redis의 INCR/DECR은 **원자적(atomic) 연산**이다. 동시에 여러 스레드가 호출해도 안전하다.

하지만 단순한 INCR/DECR로는 부족했다:
- **키가 없을 때**: DB에서 초기값을 가져와야 함
- **동시 초기화**: 여러 스레드가 동시에 초기화하면 경합 발생
- **0 이하 방지**: 감소 시 음수가 되면 안 됨

**Lua 스크립트로 원자적 연산 보장 + Repository 패턴 적용 + DIP:**

먼저 인터페이스와 구현체를 분리하여 DIP를 적용합니다:

```kotlin
// Domain Layer: domain/product/ProductLikeCountRedisRepository.kt (인터페이스)
interface ProductLikeCountRedisRepository {
    fun incrementIfExists(productId: Long): Long?
    fun initAndIncrement(productId: Long, initialValue: Long): Long
    fun decrementIfPositive(productId: Long): Long?
    fun initAndDecrementIfPositive(productId: Long, initialValue: Long): Long
    fun get(productId: Long): Long?
    fun setIfAbsent(productId: Long, value: Long): Boolean
    fun getAfterSetIfAbsent(productId: Long): Long?
    fun getAllKeys(): Set<String>?
    fun extractProductId(key: String): Long?

    companion object {
        const val KEY_NOT_FOUND = -1L
    }
}

// Infrastructure Layer: infrastructure/product/ProductLikeCountRedisRepositoryImpl.kt (구현체)
@Component
class ProductLikeCountRedisRepositoryImpl(
    private val redisTemplate: RedisTemplate<String, String>,
) : ProductLikeCountRedisRepository {
    companion object {
        private const val LIKE_COUNT_KEY_PREFIX = "product:like:count:"
        const val KEY_NOT_FOUND = -1L

        /**
         * Redis에서 원자적으로 증가하는 Lua 스크립트 (키가 존재하는 경우)
         */
        private val INCREMENT_IF_EXISTS_SCRIPT = RedisScript.of(
            """
            local current = redis.call('GET', KEYS[1])
            if current == false then
                return -1
            end
            redis.call('INCR', KEYS[1])
            return tonumber(current) + 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * Redis에서 원자적으로 초기화 후 증가하는 Lua 스크립트
         */
        private val INIT_AND_INCREMENT_SCRIPT = RedisScript.of(
            """
            local exists = redis.call('EXISTS', KEYS[1])
            if exists == 0 then
                redis.call('SET', KEYS[1], ARGV[1])
            end
            redis.call('INCR', KEYS[1])
            local result = redis.call('GET', KEYS[1])
            return tonumber(result)
            """.trimIndent(),
            Long::class.java,
        )

        // ... 다른 Lua 스크립트들 ...
    }

    fun incrementIfExists(productId: Long): Long? {
        val key = buildKey(productId)
        return redisTemplate.execute(INCREMENT_IF_EXISTS_SCRIPT, listOf(key))
    }

    fun initAndIncrement(productId: Long, initialValue: Long): Long {
        val key = buildKey(productId)
        return redisTemplate.execute(
            INIT_AND_INCREMENT_SCRIPT,
            listOf(key),
            initialValue.toString(),
        ) ?: 0L
    }

    // ... 다른 메서드들 ...

    private fun buildKey(productId: Long): String = "$LIKE_COUNT_KEY_PREFIX$productId"
}

// ProductLikeCountService.kt - Repository를 통한 Redis 접근
@Service
class ProductLikeCountService(
    private val productLikeCountRedisRepository: ProductLikeCountRedisRepository,  // ✅ Repository 사용
    private val productRepository: ProductRepository,
) {
    /**
     * 좋아요 수를 원자적으로 증가시킵니다.
     */
    fun increment(productId: Long): Long {
        // 1단계: 키가 존재하면 바로 증가 (Repository를 통해)
        val result = productLikeCountRedisRepository.incrementIfExists(productId)
        if (result != null && result != ProductLikeCountRedisRepository.KEY_NOT_FOUND) {
            return result
        }

        // 2단계: 키가 없으면 DB에서 초기값을 가져와 초기화 후 증가
        val initialValue = productRepository.findById(productId)?.likeCount ?: 0L
        return productLikeCountRedisRepository.initAndIncrement(productId, initialValue)
    }

    /**
     * 좋아요 수를 원자적으로 감소시킵니다.
     */
    fun decrement(productId: Long): Long {
        // 1단계: 키가 존재하면 바로 감소 (Repository를 통해)
        val result = productLikeCountRedisRepository.decrementIfPositive(productId)
        if (result != null && result != ProductLikeCountRedisRepository.KEY_NOT_FOUND) {
            return result
        }

        // 2단계: 키가 없으면 DB에서 초기값을 가져와 초기화 후 감소
        val initialValue = productRepository.findById(productId)?.likeCount ?: 0L
        return productLikeCountRedisRepository.initAndDecrementIfPositive(productId, initialValue)
    }
}
```

**Repository 패턴 + DIP의 장점:**

- **Lua 스크립트 캡슐화**: Redis 세부 구현을 Repository에 격리
- **테스트 용이성**: ProductLikeCountRedisRepository를 모킹하여 Service 단위 테스트 가능
- **재사용성**: 다른 Service에서도 동일한 Repository 사용 가능
- **도메인 독립성**: Domain Layer는 Redis 구현 기술에 의존하지 않음 (인터페이스만 의존)
- **유연한 구현 교체**: Redis 구현을 다른 기술로 교체해도 Domain Service는 변경 불필요

좋아요 서비스에서 사용:

```kotlin
@Transactional
fun addLike(userId: Long, productId: Long) {
    if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
        return
    }

    if (!productRepository.existsById(productId)) {
        throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다: $productId")
    }

    val like = Like(userId = userId, productId = productId)
    likeRepository.save(like)

    // Redis에서 원자적으로 증가 (Lua 스크립트 사용)
    productLikeCountService.increment(productId)

    evictProductCache(productId)
}

@Transactional
fun removeLike(userId: Long, productId: Long) {
    if (!productRepository.existsById(productId)) {
        throw CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다: $productId")
    }

    // 삭제 시도 및 삭제된 행 수 확인
    val deletedCount = likeRepository.deleteByUserIdAndProductId(userId, productId)

    // 실제로 삭제된 경우에만 Redis 감소 및 캐시 무효화
    if (deletedCount > 0) {
        // Redis에서 원자적으로 감소 (Lua 스크립트 사용, 0 이하 방지)
        productLikeCountService.decrement(productId)

        evictProductCache(productId)
    }
}
```

### "그럼 DB는 언제 업데이트하나요?"

**스케줄러로 주기적 동기화 (비관적 락 사용):**

```kotlin
@Component
class ProductLikeCountSyncScheduler(
    private val redisTemplate: RedisTemplate<String, String>,
    private val productRepository: ProductRepository,
) {
    @Scheduled(fixedDelay = 300000) // 5분마다
    @Transactional
    fun syncLikeCountsToDatabase() {
        val keys = redisTemplate.keys("product:like:count:*") ?: return

        keys.forEach { key ->
            val productId = key.removePrefix("product:like:count:").toLongOrNull()
            val redisCount = redisTemplate.opsForValue().get(key)?.toLongOrNull()

            if (productId != null && redisCount != null) {
                // DB 업데이트 (비관적 락 사용)
                val product = productRepository.findByIdWithLock(productId)
                if (product != null) {
                    product.setLikeCount(redisCount)
                    productRepository.save(product)
                }
            }
        }
    }
}
```

**데이터 흐름:**

```
고객이 좋아요 클릭
  ↓
Redis INCR (즉시 반영) ← 고객은 실시간으로 최신값 확인 가능
  ↓
... 5분 경과 ...
  ↓
Scheduler가 Redis → DB 동기화
```

- **고객 조회**: Redis에서 항상 최신값 (실시간)
- **DB 반영**: 5분마다 배치 동기화 (지연 허용)

### Redis 장애 시에는?

"Redis가 죽으면 어떻게 하지?"

**비관적 락을 사용한 Fallback 구현:**

```kotlin
@Transactional
fun increment(productId: Long): Long {
    try {
        val key = getLikeCountKey(productId)

        // 1단계: 키가 존재하면 바로 증가 (대부분의 경우)
        val result = redisTemplate.execute(INCREMENT_IF_EXISTS_SCRIPT, listOf(key))
        if (result != null && result != -1L) {
            return result
        }

        // 2단계: 키가 없으면 DB에서 초기값을 가져와 초기화 후 증가
        val initialValue = productRepository.findById(productId)?.likeCount ?: 0L
        return redisTemplate.execute(
            INIT_AND_INCREMENT_SCRIPT,
            listOf(key),
            initialValue.toString(),
        ) ?: 0L
    } catch (e: RedisConnectionFailureException) {
        logger.warn("Redis 장애, DB fallback 사용")
        return fallbackToDbIncrement(productId)
    }
}

private fun fallbackToDbIncrement(productId: Long): Long {
    // 비관적 락으로 동시성 보장
    val product = productRepository.findByIdWithLock(productId)
        ?: throw IllegalArgumentException("Product not found")

    val newCount = product.likeCount + 1
    product.setLikeCount(newCount)
    productRepository.save(product)

    return newCount
}
```

**비관적 락 적용:**

```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :id")
fun findByIdWithLock(@Param("id") id: Long): Product?
```

| 상황 | 동작 | 동시성 보장 |
|------|------|------------|
| Redis 정상 | INCR/DECR 사용 | ✅ Atomic 연산 |
| Redis 장애 | DB 비관적 락 사용 | ✅ PESSIMISTIC_WRITE |

### 트레이드오프

**장점:**
- 🚀 **성능**: Redis 메모리 연산, 매우 빠름
- ✅ **동시성**: Atomic 연산으로 안전
- 📊 **확장성**: DB 부하 분산
- 🛡️ **안정성**: Redis 장애 시 자동 fallback

**단점:**
- ⏱️ **지연**: DB 반영은 최대 5분 지연
- 🔧 **복잡도**: 동기화 로직 관리 필요
- 💾 **의존성**: Redis 인프라 추가

"실시간성이 필요한가?"를 먼저 물어야 한다.

좋아요 수는 1-2개 차이는 유저가 신경 쓰지 않는다. 하지만 1초 걸리는 페이지는 바로 느낀다.

### 추가 개선: 동시성 이슈 완전 해결

초기 구현에서는 몇 가지 동시성 이슈가 남아있었다:

**문제 1: `initializeFromDatabase`의 경합 조건**

여러 스레드가 동시에 Redis 키를 초기화하면 나중 스레드가 먼저 증가된 값을 덮어쓸 수 있었다:

```kotlin
// 문제가 있는 초기 구현
private fun initializeFromDatabase(productId: Long, key: String): Long {
    val likeCount = getFromDatabase(productId)
    redisTemplate.opsForValue().set(key, likeCount.toString())  // 무조건 덮어씀 ❌
    return likeCount
}
```

**시나리오:**
```
Thread A: DB에서 likeCount = 5 읽음
Thread B: DB에서 likeCount = 5 읽음, Redis에 SET하고 INCR → 6
Thread A: Redis에 SET (5로 덮어씀) ❌
→ 결과: Thread B의 증가가 손실됨
```

**해결: `setIfAbsent` 사용**

```kotlin
// 개선된 구현
private fun initializeFromDatabase(productId: Long, key: String): Long {
    val likeCount = getFromDatabase(productId)
    redisTemplate.opsForValue().setIfAbsent(key, likeCount.toString())  // 키가 없을 때만 설정 ✅
    return redisTemplate.opsForValue().get(key)?.toLongOrNull() ?: likeCount
}
```

이제 먼저 도착한 스레드만 키를 설정하고, 나중 스레드는 이미 설정된 값을 사용한다.

**문제 2: `decrement`에서 첫 unlike 이벤트 손실**

Redis 키가 없을 때 `decrement`를 호출하면 0을 반환하여 첫 번째 unlike 이벤트가 무시되었다:

```kotlin
// 문제가 있는 초기 구현
private val DECREMENT_IF_POSITIVE_SCRIPT = RedisScript.of(
    """
    local current = redis.call('GET', KEYS[1])
    if current == false then
        return 0  // ❌ DB에 likeCount가 있어도 무시됨
    end
    // ...
    """.trimIndent()
)
```

**시나리오:**
```
DB: Product(likeCount = 3)
Redis: 키 없음
User: Unlike 요청
→ 결과: 0 반환 (실제로는 3 → 2가 되어야 함) ❌
```

**해결: `increment`와 동일한 패턴 적용**

```kotlin
// 개선된 구현
private val DECREMENT_IF_POSITIVE_SCRIPT = RedisScript.of(
    """
    local current = redis.call('GET', KEYS[1])
    if current == false then
        return -1  // ✅ 키 없음을 명시적으로 표시
    end
    // ...
    """.trimIndent()
)

fun decrement(productId: Long): Long {
    val key = getLikeCountKey(productId)

    // 1단계: 키가 존재하면 바로 감소
    val result = redisTemplate.execute(DECREMENT_IF_POSITIVE_SCRIPT, listOf(key))
    if (result != null && result != -1L) {
        return result
    }

    // 2단계: 키가 없으면 DB에서 초기화 후 감소 ✅
    val initialValue = productRepository.findById(productId)?.likeCount ?: 0L
    return redisTemplate.execute(
        INIT_AND_DECREMENT_IF_POSITIVE_SCRIPT,
        listOf(key),
        initialValue.toString(),
    ) ?: 0L
}
```

이제 `decrement`도 `increment`와 동일하게 키가 없으면 DB에서 초기화 후 감소한다.

**검증: 통합 테스트**

```kotlin
@Test
fun decrementShouldInitializeFromDatabaseWhenKeyIsMissing() {
    // given: DB에 likeCount가 3인 상태, Redis 키 없음
    product.setLikeCount(3L)
    assertThat(redisTemplate.hasKey(key)).isFalse()

    // when: 첫 번째 decrement 요청
    val result = productLikeCountService.decrement(productId)

    // then: DB의 초기값(3)에서 1을 뺀 2가 반환
    assertThat(result).isEqualTo(2L)
    assertThat(redisTemplate.opsForValue().get(key)?.toLongOrNull()).isEqualTo(2L)
}
```

**개선 효과:**

| 상황 | 개선 전 | 개선 후 |
|------|---------|---------|
| 동시 초기화 | 나중 스레드가 증가값 덮어씀 ❌ | `setIfAbsent`로 첫 스레드만 설정 ✅ |
| 첫 unlike (키 없음) | 0 반환하여 이벤트 손실 ❌ | DB에서 초기화 후 감소 ✅ |
| 키 없을 때 동작 | increment만 초기화 가능 | increment/decrement 모두 가능 ✅ |

이제 모든 동시성 이슈가 해결되어 **원자성과 정합성이 완벽히 보장**된다.

## 한계와 개선 방향

### Redis-DB 동기화 지연

Redis와 DB 간에 **최대 5분의 지연**이 있다.

이로 인해:

1. **DB 기반 쿼리**: 좋아요순 정렬은 5분 전 데이터 기준
2. **분석/리포트**: 실시간 통계는 부정확할 수 있음
3. **Redis 초기화**: 앱 재시작 시 DB에서 로드

해결 방법:

- **즉시 동기화 필요 시**: 동기화 API 제공
- **정확한 통계 필요 시**: 별도 집계 테이블 운영
- **Redis 캐시 워밍**: 시작 시 인기 상품만 미리 로드

현재는 "5분 지연은 사용자 경험에 큰 영향 없음"으로 판단했다.

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
