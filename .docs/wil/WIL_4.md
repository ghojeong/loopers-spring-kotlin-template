# Weekly I Learned 4 (Transaction)

수습과제로 진행한 "고객/회원 정합성 체크" 배치 구현을 위해 Transaction 에 대한 이해가 절실함.
전체 회원에 대한 배치였기 떄문에 DB 부하로 인한 실서비스 영향이 있을 수 있었는데,
트랜잭션을 공부해서 JDBC READ_ONLY 옵션으로 락을 방지하고,
JDBC TYPE_FOWARD_ONLY 옵션으로 읽기 혹은 쓰기 트랜잭션에 영향이 없도록 할 수 있었음.

이러한 성과 덕분에 1차 수습평가에서 생존할 수 있었음
