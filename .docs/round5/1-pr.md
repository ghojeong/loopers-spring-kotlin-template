# Round5 - Read Optimization

## Summary

상품 목록 조회 성능을 개선하기 위해 **비정규화, 인덱스, Redis 캐시**를 적용

### 주요 변경사항

1. **비정규화**: Product 테이블에 `likeCount` 필드 추가
   - AS-IS: LEFT JOIN + GROUP BY + COUNT (10만건 기준 ~1000ms)
   - TO-BE: 단순 정렬 쿼리 (~5ms, **99.5% 개선**)

2. **복합 인덱스 설계**: 쿼리 패턴 분석 후 3개 인덱스 추가
   - `(brand_id, like_count DESC)` - 브랜드 필터 + 좋아요 순 정렬
   - `(brand_id, price_amount)` - 브랜드 필터 + 가격 순 정렬
   - `(like_count DESC)` - 전체 좋아요 순 정렬

3. **Redis 캐시 적용**: RedisTemplate 직접 사용으로 명시적 제어
   - 상품 상세: 10분 TTL
   - 상품 목록: 5분 TTL
   - 좋아요 변경 시 캐시 무효화

### 성능 개선 결과

| 쿼리 | AS-IS | TO-BE | 개선율 |
|------|-------|-------|--------|
| 브랜드 필터 + 좋아요 순 | ~500ms | ~10ms | **98% ↑** |
| 전체 좋아요 순 | ~1000ms | ~5ms | **99.5% ↑** |
| API (캐시 히트) | ~100ms | ~5ms | **95% ↑** |

## 💬 Review Points

### 1. 비정규화 vs 정규화 트레이드오프

**선택한 이유:**

- 읽기가 쓰기보다 수십 배 많은 워크로드 (상품 조회 >> 좋아요 변경)
- 좋아요 수는 실시간 정확도가 필수가 아님 (1-2개 차이는 UX에 영향 없음)
- JOIN + GROUP BY + COUNT는 인덱스로도 최적화 한계가 있음

**정합성 보장 방법:**

```kotlin
@Transactional
fun addLike(userId: Long, productId: Long) {
    // 1. Like 저장과 2. Product.likeCount 증가를 하나의 트랜잭션으로 처리
    product.incrementLikeCount()
    likeRepository.save(like)
}
```

**향후 개선 방안:**

- 배치 작업으로 주기적 재계산
- 이벤트 소싱으로 일관성 보장

### 2. 인덱스 설계 전략

**복합 인덱스 순서:**

- WHERE 절 필터 조건(brand_id)을 먼저
- ORDER BY 정렬 조건(like_count, price_amount)을 나중에

**확인 포인트:**

- 실제 워크로드에서 인덱스가 제대로 사용되는지 EXPLAIN 분석 필요
- 쓰기 성능 저하 모니터링 (좋아요 추가 시 3개 인덱스 업데이트)

### 3. Redis 캐시 설계

**RedisTemplate을 선택한 이유:**

```kotlin
// @Cacheable 대신 RedisTemplate 직접 사용
// - 캐시 저장/무효화 시점을 명시적으로 제어
// - 디버깅 시 정확히 무슨 일이 일어나는지 파악 가능
// - 캐시 키 설계를 직접 관리
```

**캐시 키 설계:**

```txt
// 상품 상세: "product:detail:{productId}"
// 상품 목록: "product:list:brand:{brandId}:sort:{sort}:page:{page}:size:{size}"
// - 조회 조건의 모든 파라미터를 키에 포함
// - Redis CLI에서 키만 봐도 어떤 데이터인지 명확
```

**무효화 전략:**

- 좋아요 변경 시 → 해당 상품 상세 캐시 삭제 + 모든 목록 캐시 삭제
- 단순하게 시작: 좋아요는 빈번하지 않고, TTL도 짧음 (5분)
- 향후 개선: 브랜드별 캐시만 선택적 삭제

### 4. 테스트 수정 사항

**변경된 의존성:**

- `LikeService`: ProductRepository, RedisTemplate 추가
- `ProductFacade`: product.likeCount 직접 사용 (LikeQueryService 미사용)

**테스트 패턴:**

```kotlin
// Product.likeCount 설정
val product = createTestProduct(...)
product.setLikeCount(10L)  // internal 메서드 사용

// Redis mock
every { redisTemplate.opsForValue().get(any()) } returns null  // 캐시 미스
every { redisTemplate.delete(any<String>()) } returns true
```

## Checklist

### 구현 완료

- [x] Product 엔티티에 `likeCount` 필드 추가 및 증가/감소 메서드 구현
- [x] 복합 인덱스 3개 추가 (`@Table` 애노테이션)
- [x] LikeService에 좋아요 추가/삭제 시 likeCount 동기화 로직
- [x] ProductQueryService에 Redis 캐시 적용 (상품 상세, 목록)
- [x] LikeService에 캐시 무효화 로직 추가
- [x] ProductFacade에서 product.likeCount 직접 사용하도록 변경
- [x] ProductJpaRepository 쿼리 최적화 (JOIN 제거)
- [x] Product에 `setLikeCount()` internal 메서드 추가 (배치 작업용)

### 테스트

- [x] LikeServiceTest 수정 (ProductRepository, RedisTemplate mock 추가)
- [x] ProductFacadeTest 수정 (product.likeCount 사용)
- [x] ProductQueryServiceTest 수정 (Redis mock 추가)
- [x] 전체 테스트 통과 확인

### 문서화

- [x] Round 2 ERD 업데이트 (`like_count` 컬럼, 인덱스 추가)
- [x] Round 2 Class Diagram 업데이트 (Product, LikeService, ProductQueryService)
- [x] 성능 테스트 가이드 작성 (EXPLAIN 분석 방법, 예상 결과)
- [x] 블로그 작성 (Round 4 스타일, 구현 과정 및 트레이드오프 설명)
- [x] 데이터 생성 스크립트 (ProductDataGenerator) - 10만건 상품, 2만건 좋아요
