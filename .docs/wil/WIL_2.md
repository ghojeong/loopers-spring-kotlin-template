# Weekly I Learned 2 (Docs)

요구사항 파악을 위해 다음 문서를 읽음
1. 회원 테이블의 ERD
2. 회원가입/인증/고객확인 Flow Chart
3. 기존 회원 API 클래스의 인터페이스와 객체설계
4. 고객의 상태 흐름도

구현을 위해 다음을 설계
1. Spring Batch 에서 Reader, Processor, Writer 를 어떻게 구현할지 객체 설계 및 Flow Chart
2. 어떤 테이블을 어떻게 읽고, 결과를 어떻게 저장할 것인지 ERD 다이어그램. Oracle 의 Degree Of Parallelism 을 활용하기 때문에 테이블 별 병렬처리 가능한 수준 및 병목 조사
3. 배치를 언제 돌릴 것이고 스케쥴표 작성과, 그로 인해 실서비스에 갈 수 있는 영향도 분석
