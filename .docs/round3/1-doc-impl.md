# Round3 doc

Round 3의 도메인 모델링과 레이어드 아키텍처 원칙을 바탕으로 4가지 문서를 개선했습니다.

## 주요 개선 사항

## 1. 1-requirements.md

- ✅ 도메인 모델링 원칙 섹션 추가 (Entity, VO, Domain Service 구분)
- ✅ 레이어별 책임 명시 (Interfaces → Application → Domain ←
Infrastructure)
- ✅ 각 요구사항에 도메인 객체 책임 섹션 추가
  - 어떤 Entity가 어떤 책임을 가지는지 명확히 정의
  - Repository Interface가 Domain Layer에 위치한다는 것을 명시

## 2. 2-sequence-diagrams.md

- ✅ BC/Facade 패턴 → 레이어드 아키텍처로 재구성
- ✅ 모든 시퀀스 다이어그램을 4개 레이어 관점으로 재작성
  - Controller → ApplicationService → DomainService → Repository 흐름
- ✅ Repository Interface는 Domain Layer에, 구현체는 Infrastructure
Layer에
- ✅ Domain Service의 역할 명확화 (여러 도메인 객체 협력 조율)
- ✅ Entity의 책임 강조 (Stock.decrease(), Point.deduct(),
Order.isOwnedBy())
- ✅ 설계 원칙 섹션을 레이어드 아키텍처 + DIP 관점으로 재작성

## 3. 3-class-diagram.md

- ✅ 클래스 다이어그램에 <>, <> 스테레오타입 추가
- ✅ Order에 <> 표시
- ✅ Facade 섹션 제거 → Repository Interface + Domain Service 섹션으로
교체
- ✅ Repository Interface 코드 예시 추가 (Domain Layer와 Infrastructure
Layer 분리)
- ✅ Domain Service 코드 예시 추가 (OrderService, ProductQueryService,
LikeService)
- ✅ 패키지 구조를 레이어드 아키텍처로 재구성
- ✅ 레이어 간 의존성 다이어그램 추가 (DIP 강조)

## 4. 4-erd.md

- ✅ 도메인 모델과 ERD의 관계 섹션 추가
  - Entity → 테이블 매핑
  - Value Object → 컬럼 매핑 (임베드)
  - Aggregate → 테이블 관계
- ✅ 모든 테이블 설명에 도메인 모델 매핑 정보 추가
- ✅ VO가 어떻게 저장되는지 설명 추가
  - Price VO → price_amount, price_currency 컬럼
  - Money VO → balance, currency 또는 total_amount, currency 컬럼

핵심 변경점

Before (Round 2): BC와 Facade 중심의 설계
After (Round 3): 레이어드 아키텍처 + DIP + 도메인 모델링 중심의 설계

이제 문서들이 Round 3의 학습 목표인 도메인 모델링, Entity/VO/Domain 
Service 구분, 레이어드 아키텍처 + DIP, 테스트 가능한 구조를 명확히
반영하고 있습니다.
