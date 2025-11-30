# 성능 테스트 가이드

## 1. 테스트 데이터 생성

### 데이터 생성 스크립트 실행

```bash
# 1. MySQL 실행 확인
docker ps | grep mysql

# 2. 데이터 생성 테스트 실행 (약 5-10분 소요)
./gradlew :apps:commerce-api:test --tests "com.loopers.domain.product.ProductDataGenerator"
```

### 생성되는 데이터

- 브랜드: 100개
- 유저: 1,000명
- 상품: 100,000개
- 재고: 100,000개
- 좋아요: 약 20,000개 (상품의 20%에 대해 0~50명이 좋아요)

## 2. 성능 측정

### AS-IS vs TO-BE 비교

#### AS-IS (개선 전)

- 좋아요 수 정렬: LEFT JOIN + GROUP BY + COUNT 사용
- 인덱스: 없음
- 캐시: 없음

#### TO-BE (개선 후)

- 좋아요 수 정렬: Product.likeCount 컬럼 사용 (비정규화)
- 인덱스:
  - `idx_brand_id_like_count`: (brand_id, like_count DESC)
  - `idx_brand_id_price`: (brand_id, price_amount)
  - `idx_like_count`: (like_count DESC)
- 캐시:
  - 상품 상세: 10분 TTL
  - 상품 목록: 5분 TTL

### EXPLAIN 분석

#### 1. 브랜드 필터 + 좋아요 순 정렬

```sql
-- AS-IS (개선 전)
EXPLAIN SELECT p.*
FROM products p
LEFT JOIN likes l ON l.product_id = p.id
WHERE p.brand_id = 1
GROUP BY p.id
ORDER BY COUNT(l.id) DESC
LIMIT 20;
```

예상 결과:

- type: ALL (전체 테이블 스캔)
- rows: 100,000+
- Extra: Using filesort, Using temporary

```sql
-- TO-BE (개선 후)
EXPLAIN SELECT p.*
FROM products p
WHERE p.brand_id = 1
ORDER BY p.like_count DESC
LIMIT 20;
```

예상 결과:

- type: ref
- key: idx_brand_id_like_count
- rows: 수백~수천 (브랜드별 상품 수에 따라)
- Extra: Using index

#### 2. 전체 상품 좋아요 순 정렬

```sql
-- AS-IS (개선 전)
EXPLAIN SELECT p.*
FROM products p
LEFT JOIN likes l ON l.product_id = p.id
GROUP BY p.id
ORDER BY COUNT(l.id) DESC
LIMIT 20;
```

예상 결과:

- type: ALL
- rows: 100,000
- Extra: Using filesort, Using temporary

```sql
-- TO-BE (개선 후)
EXPLAIN SELECT p.*
FROM products p
ORDER BY p.like_count DESC
LIMIT 20;
```

예상 결과:

- type: index
- key: idx_like_count
- rows: 20
- Extra: Using index

#### 3. 브랜드 필터 + 가격 순 정렬

```sql
EXPLAIN SELECT p.*
FROM products p
WHERE p.brand_id = 1
ORDER BY p.price_amount ASC
LIMIT 20;
```

예상 결과:

- type: ref
- key: idx_brand_id_price
- rows: 수백~수천
- Extra: Using index

### 성능 측정 방법

#### 1. 쿼리 실행 시간 측정

```bash
# MySQL에 접속
mysql -u application -p loopers

# 쿼리 실행 시간 측정
SET profiling = 1;

-- 쿼리 실행
SELECT p.* FROM products p WHERE p.brand_id = 1 ORDER BY p.like_count DESC LIMIT 20;

-- 실행 시간 확인
SHOW PROFILES;

-- 상세 분석
SHOW PROFILE FOR QUERY 1;
```

#### 2. API 응답 시간 측정

```bash
# 상품 목록 조회 (브랜드 필터 + 좋아요 순)
time curl "http://localhost:8080/api/v1/products?brandId=1&sort=likes_desc&page=0&size=20"

# 상품 상세 조회
time curl "http://localhost:8080/api/v1/products/1"

# 캐시 적용 확인 (2번째 호출은 더 빠름)
time curl "http://localhost:8080/api/v1/products/1"
```

#### 3. Redis 캐시 확인

```bash
# Redis CLI 접속
redis-cli

# 캐시 키 확인
KEYS product:*

# 특정 캐시 조회
GET product:detail:1

# TTL 확인
TTL product:detail:1
```

## 3. 예상 성능 개선 결과

### 쿼리 실행 시간

| 쿼리 | AS-IS | TO-BE | 개선율 |
|------|-------|-------|--------|
| 브랜드 필터 + 좋아요 순 (10만건) | ~500ms | ~10ms | 98% ↑ |
| 전체 좋아요 순 (10만건) | ~1000ms | ~5ms | 99.5% ↑ |
| 브랜드 필터 + 가격 순 (10만건) | ~300ms | ~10ms | 96.7% ↑ |

### API 응답 시간

| API | 1차 호출 (캐시 없음) | 2차 호출 (캐시 있음) | 개선율 |
|-----|---------------------|---------------------|--------|
| 상품 목록 조회 | ~100ms | ~5ms | 95% ↑ |
| 상품 상세 조회 | ~50ms | ~3ms | 94% ↑ |

## 4. 주의사항

### 캐시 무효화

- 좋아요 추가/삭제 시 관련 캐시 자동 삭제
- 상품 상세 캐시: 해당 상품만 삭제
- 상품 목록 캐시: 전체 목록 캐시 삭제 (좋아요 순위 변경)

### 데이터 정합성

- Product.likeCount는 Like 테이블과 동기화
- 동시성 문제 발생 시 재계산 배치 작업 고려

### 인덱스 관리

- 인덱스는 조회 성능을 향상시키지만 쓰기 성능을 저하시킴
- 좋아요 추가/삭제 시 인덱스 업데이트 비용 발생
- 실제 워크로드에 따라 인덱스 조정 필요

## 5. 추가 개선 방안

### Materialized View

- PostgreSQL 사용 시 Materialized View 고려
- 주기적으로 갱신하여 조회 성능 극대화
- 실시간성이 중요하지 않은 경우 적합

### Read Replica

- 읽기 전용 복제본 활용
- 조회 부하 분산
- 쓰기/읽기 분리

### 추가 캐시 전략

- 인기 상품 미리 캐싱 (Warm-up)
- CDN 캐싱 (정적 리소스)
- 캐시 압축 (메모리 절약)
