# Weekly I Learned 6 (Failure-Ready System)

신규로 추가된 인프라에 방화벽 설정을 제대로 하지 않아서 장애가 나는 일이 발생했음.
7분동안 일어난 장애는 뉴스에 실릴 정도였고 이로인해서 CTO 님께서 이에 관한 대응책을 요구해오심.
신규 인프라 연결에 실패했을 경우, 기존 인프라와 연결만 되었다면 전사적인 장애로 전파되지 않았음.
Circuit Break 와 Fallback 의 중요성을 깨달음
Circuit Break 가 Open 되어 과거 인프라와 연결되는 로직이 있기만 했어도 괜찮았을 듯.
