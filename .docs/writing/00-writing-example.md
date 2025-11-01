# Round1 - TDD

## 가장 기억나는 테스트 1가지

처음엔 당연히 E2E 테스트가 가장 중요할 거라 생각했다. 실제 HTTP 요청을 보내고, 전체 흐름을 검증하니까 "진짜" 테스트처럼 느껴졌다. 근데 막상 개발하다 보니, **가장 자주 돌리고 가장 도움이 된 건 User 도메인의 단위 테스트였다.**

```kotlin
@DisplayName("ID가 영문 및 숫자 10자 이내 형식에 맞지 않으면, User 객체 생성에 실패한다.")
@Test
fun failsToCreateUser_whenUserIdFormatIsInvalid() {
    val invalidUserId = "invalid_id"

    val exception = assertThrows<CoreException> {
        User(
            userId = invalidUserId,
            email = "test@example.com",
            birthDate = "1990-01-01",
            gender = Gender.MALE,
        )
    }

    assertThat(exception.errorType).isEqualTo(ErrorType.BAD_REQUEST)
}
```

### 왜 이게 가장 도움이 되었을까?

**첫 번째 이유는 속도였다.** E2E 테스트는 Spring 컨텍스트를 띄우고, DB를 초기화하고, HTTP 요청을 만드는 데만 수 초가 걸렸다. 근데 이 단위 테스트는 밀리초 만에 끝났다. 코드 수정 → 테스트 실행 → 결과 확인의 사이클이 빨라서, Red → Green → Refactor를 실제로 체감할 수 있었다.

**두 번째는 명확성이었다.** E2E 테스트에서 실패하면 "어디서 터진 거지?"를 찾는 게 일이었다. Controller? Service? Domain? 근데 단위 테스트는 실패하면 정확히 **"User 생성 시 ID 검증 로직"**이 문제라는 걸 바로 알 수 있었다. 이메일 검증, 생년월일 검증, ID 검증을 각각 분리해두니까, 테스트 이름만 봐도 뭐가 깨졌는지 알 수 있었다.

**세 번째는 설계에 도움이 되었다.** 처음엔 이런 검증을 Controller에서 `@Valid`로 하려고 했다. 근데 "User라는 개념이 있으면, User를 만드는 규칙도 User가 알아야 하지 않나?" 싶어서 도메인으로 옮겼다. 그러니까 테스트도 간단해지고, 어디서든 User를 만들 때 이 규칙이 보장되는 구조가 됐다.

지금 생각해보면, **단위 테스트는 "내가 뭘 만들고 있는지" 가장 빠르게 확인할 수 있는 도구**였던 것 같다. E2E는 "완성된 기능이 잘 동작하는지" 검증하는 거고.

## 테스트 가능한 구조를 위한 리팩토링

### 처음엔 Service에 다 때려박았다

포인트 충전 기능을 구현할 때, 처음엔 이렇게 시작했다:

```kotlin
// 초기 버전 (실제 코드는 아님)
class UserService {
    fun chargePoint(userId: String, amount: Long): Long {
        // 1. 검증
        if (amount <= 0) throw Exception("잘못된 금액")

        // 2. 조회
        val user = repository.findByUserId(userId) ?: throw Exception("없는 유저")

        // 3. 로직
        user.point += amount

        // 4. 저장
        repository.save(user)
        return user.point
    }
}
```

"뭐 그럴듯한데?" 싶었다. 근데 테스트를 작성하려니까 문제가 보였다.

**"amount가 0 이하일 때" 테스트를 어떻게 써야 할까?** Service 테스트를 쓰면 DB도 띄워야 하고, repository도 주입해야 한다. 근데 검증하고 싶은 건 그냥 "0 이하면 실패한다"는 간단한 규칙인데, 너무 무겁다.

### 그래서 도메인으로 분리했다

"충전 금액은 양수여야 한다"는 **User의 규칙**이지, Service의 책임이 아니라는 생각이 들었다. 그래서 이렇게 바꿨다:

```kotlin
class User {
    fun chargePoint(amount: Long) {
        if (amount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "충전 금액은 0보다 커야 합니다.")
        }
        this.point += amount
    }
}

class UserService(
    private val userJpaRepository: UserJpaRepository,
) {
    fun chargePoint(userId: String, amount: Long): Long {
        val user = userJpaRepository.findByUserId(userId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 유저입니다.")

        user.chargePoint(amount) // 여기로 위임
        userJpaRepository.save(user)

        return user.point
    }
}
```

이제 **검증 로직은 순수하게 단위 테스트**로 작성할 수 있다:

```kotlin
@Test
fun failsToChargePoint_whenAmountIsZeroOrNegative() {
    val user = User(
        userId = "testuser",
        email = "test@example.com",
        birthDate = "1990-01-01",
        gender = Gender.MALE,
    )

    val exception = assertThrows<CoreException> {
        user.chargePoint(0)
    }

    assertThat(exception.errorType).isEqualTo(ErrorType.BAD_REQUEST)
}
```

Spring도, DB도, Repository도 필요 없다. 그냥 User 객체 하나 만들고 `chargePoint(0)` 호출하면 끝이다. 밀리초 만에 실행된다.

### Service 테스트는 뭘 검증하지?

그럼 Service 통합 테스트는 뭘 검증해야 할까? 고민하다가, **"여러 컴포넌트가 잘 엮여서 동작하는가"**를 확인하는 게 맞다고 생각했다.

```kotlin
@Test
fun failsToChargePoint_whenUserDoesNotExist() {
    val exception = assertThrows<CoreException> {
        userService.chargePoint("nonexistent", 1000L)
    }

    assertThat(exception.errorType).isEqualTo(ErrorType.NOT_FOUND)
}
```

이 테스트는 "존재하지 않는 유저로 충전을 시도하면 NOT_FOUND가 나온다"를 검증한다. 여기서는 실제로 DB를 조회하고, 없으면 예외를 던지는 **흐름 전체**를 확인하는 거다.

### 근데 완벽하진 않다

사실 지금도 조금 애매한 부분이 있다. 예를 들어:

- **User 생성 시 검증(ID, 이메일, 생년월일 형식)**은 도메인에 있다.
- **회원가입 시 중복 체크**는 Service에 있다.

왜? 중복 체크는 DB를 조회해야 알 수 있으니까. 근데 이게 맞는 분리인지는 아직 확신이 없다. "User가 중복되면 안 된다"는 것도 User의 규칙 아닌가? 근데 그걸 도메인에서 어떻게 검증하지?

이런 고민들이 남아있다. 다음 라운드에서 좀 더 명확한 기준을 세워봐야겠다.

## Mock, Stub, Fake 활용과 구분

### 요구사항에 "spy 검증"이 있었는데...

요구사항 문서에 **"회원 가입시 User 저장이 수행된다. (spy 검증)"**이라고 명시되어 있었다. 처음엔 "아, Mockito의 `spy()`를 써야 하는구나" 싶었다.

```kotlin
// 이렇게 작성해야 하나?
val spyRepository = spy(userJpaRepository)
userService.registerUser(...)
verify(spyRepository).save(any())
```

근데 뭔가 이상했다. **내가 진짜 확인하고 싶은 게 뭐지?**

- "`save()` 메서드가 호출되었는가?" (Mock의 관심사)
- "실제로 DB에 저장되었는가?" (상태의 관심사)

고민하다가, 후자가 진짜 중요한 거라는 결론이 나왔다. 그래서 이렇게 작성했다:

```kotlin
@Test
fun savesUser_whenRegisteringUser() {
    userService.registerUser(
        userId = "testuser",
        email = "test@example.com",
        birthDate = "1990-01-01",
        gender = Gender.MALE,
    )

    // spy가 아니라, 실제 DB를 조회해서 확인
    val savedUser = userJpaRepository.findByUserId("testuser")
    assertThat(savedUser).isNotNull
        .extracting("userId", "email", "birthDate", "gender")
        .containsExactly(
            "testuser",
            "test@example.com",
            LocalDate.parse("1990-01-01"),
            Gender.MALE,
        )
}
```

### Mock과 상태 검증의 차이를 실감했다

이번에 처음 깨달은 건데, **Mock은 "행위 검증", 상태 검증은 "결과 검증"**이다.

- `verify(repo).save()`는 **"save가 호출되었나?"**를 본다. 근데 실제로 저장되었는지는 보장 못 한다.
- `findByUserId()`로 다시 조회하는 건 **"실제로 저장되었나?"**를 본다.

물론 Mock도 유용한 순간이 있을 것 같다. 예를 들어:

- 외부 API 호출 같이 실제로 호출하면 안 되는 경우
- 이메일 발송처럼 부수 효과만 있고 반환값이 없는 경우
- "몇 번 호출되었는지"가 중요한 경우 (예: 캐시 히트율 확인)

근데 이번 과제에서는 그런 케이스가 없었다. DB는 H2 인메모리를 쓰니까 빠르고, 실제로 저장된 결과를 확인하는 게 더 확실했다.

### H2는 Fake인가?

Learning 문서를 보면 **Fake는 "실제처럼 동작하는 가짜 구현체"**라고 나온다. 예시로 `InMemoryUserRepository` 같은 걸 직접 만드는 거였다.

그런데 H2 DB도 생각해보면 Fake의 일종이다:
- 프로덕션에서는 MySQL이나 PostgreSQL을 쓸 텐데
- 테스트에서는 H2를 쓰니까, "실제처럼 동작하는 가짜"인 셈이다
- 단, 직접 구현한 게 아니라 이미 만들어진 도구를 쓰는 것뿐

처음엔 "Fake를 써야 하나? InMemoryRepository를 만들어야 하나?" 고민했는데, H2가 이미 충분히 그 역할을 하고 있었다.

### 그래서 내 기준은?

| 상황 | 내가 선택한 방법 | 이유 |
|---|---|---|
| 도메인 로직 테스트 | 진짜 객체 | Spring도 DB도 필요 없음 |
| 통합 테스트 (DB 접근) | H2 인메모리 DB | 실제 JPA처럼 동작하고 빠름 |
| 외부 API 호출 | (아직 경험 안 함) | Mock이나 Stub을 써야 할 듯? |

**아직 Mock을 써야 하는 명확한 상황을 경험하지 못했다.** 이론적으로는 알겠는데, 실제로 "아, 여기선 Mock이 필요하네"라고 느낀 적이 없다. 다음 라운드에서 외부 API 연동 같은 게 나오면 그때 제대로 써볼 수 있을 것 같다.

## TDD 어려웠던 점

### 1. "테스트를 먼저 쓴다"는 게 생각보다 어려웠다

Learning 문서에서 TDD는 **Red → Green → Refactor** 순서로 진행한다고 했다. "실패하는 테스트를 먼저 쓰고, 통과시키고, 개선한다." 이론은 간단했다.

근데 막상 회원가입 E2E 테스트를 작성하려니까:

```kotlin
// 테스트를 먼저 쓰려면...
@Test
fun returnsCreatedUser_whenRegistrationIsSuccessful() {
    val request = UserV1Dto.RegisterUserRequest(...) // 이 DTO가 없는데?

    val response = testRestTemplate.exchange(
        "/api/v1/users", // 이 엔드포인트가 없는데?
        HttpMethod.POST,
        HttpEntity(request),
        ??? // 응답 타입도 모르는데?
    )
}
```

**테스트를 먼저 쓰려면 API 설계가 먼저 필요했다.** 엔드포인트 경로, 요청/응답 DTO 구조를 어느 정도 정해야 테스트를 작성할 수 있었다.

그래서 실제로는:
1. Controller, DTO 스켈레톤 만들기 (컴파일은 되게)
2. E2E 테스트 작성 (Red)
3. Service, Domain 구현 (Green)
4. 리팩토링 (Refactor)

순서로 진행했다. Learning 문서에서 말한 **TLD(Test Last Development)**에 가까웠다. "계층 설계가 먼저 필요한 상황에서는 TLD가 더 자연스럽다"고 되어 있었는데, 딱 그 상황이었다.

**근데 도메인 단위 테스트는 달랐다.** User 객체의 `chargePoint()` 메서드 같은 건, 테스트를 먼저 쓰는 게 자연스러웠다:

```kotlin
// 1. 테스트 먼저 (Red)
@Test
fun failsToChargePoint_whenAmountIsZeroOrNegative() {
    val user = User(...)
    assertThrows<CoreException> { user.chargePoint(0) }
}

// 2. 구현 (Green)
class User {
    fun chargePoint(amount: Long) {
        if (amount <= 0) throw CoreException(...)
        this.point += amount
    }
}
```

**결론: "무조건 테스트 먼저"는 아닌 것 같다.** 계층 설계는 스켈레톤부터, 도메인 로직은 테스트부터가 자연스러웠다.

### 2. E2E 테스트, 생각보다 손이 많이 갔다

가장 번거로웠던 게 **필수 필드 누락 테스트**였다:

```kotlin
@Test
fun returnsBadRequest_whenGenderIsMissing() {
    // data class를 쓰면 gender가 필수라 누락시킬 수가 없다
    // 그래서 raw JSON 문자열로...
    val requestJson = """
        {
            "userId": "testuser",
            "email": "test@example.com",
            "birthDate": "1990-01-01"
        }
    """

    val headers = HttpHeaders()
    headers.set("Content-Type", "application/json")

    val response = testRestTemplate.exchange(
        ENDPOINT_REGISTER_USER,
        HttpMethod.POST,
        HttpEntity(requestJson, headers),
        String::class.java,
    )
}
```

"gender를 빼고 요청을 보내면 400이 나온다"는 간단한 테스트인데, 코드는 생각보다 복잡했다. JSON 문자열을 직접 써야 하고, 헤더도 수동으로 설정해야 하고.

**그리고 `ParameterizedTypeReference`도 처음엔 헷갈렸다:**

```kotlin
val responseType = object : ParameterizedTypeReference<ApiResponse<UserV1Dto.UserResponse>>() {}
val response = testRestTemplate.exchange(..., responseType)
```

"왜 `ApiResponse<UserV1Dto.UserResponse>::class.java`는 안 되고 이렇게 써야 하지?" 찾아보니 제네릭 타입 소거(Type Erasure) 때문이라는데... 이해는 했지만 번거롭긴 했다.

### 3. 테스트 격리, 생각보다 비용이 크더라

처음엔 `@AfterEach`로 테스트마다 DB를 깨끗하게 정리했다:

```kotlin
@AfterEach
fun tearDown() {
    databaseCleanUp.truncateAllTables()
}
```

근데 테스트가 늘어나니까 실행 시간도 같이 늘어났다. 테스트 10개면 DB 초기화도 10번. 단위 테스트의 "밀리초 만에 실행된다"는 느낌이 전혀 없었다.

**대안을 찾아봤지만 뾰족한 수가 없었다:**
- `@DirtiesContext`? 더 느림
- `@Transactional`로 롤백? E2E 테스트에서는 트랜잭션 컨텍스트가 다름
- 테스트마다 다른 userId 쓰기? 데이터가 계속 쌓여서 부담

결국 "테스트 격리는 비용이 든다"는 걸 받아들였다. 단위 테스트를 많이 쓰고, 통합/E2E는 꼭 필요한 것만 쓰는 게 답인 것 같다.

### 4. assertAll, 언제 써야 할까?

이것도 계속 고민이다. 예를 들어 이 테스트:

```kotlin
@Test
fun returnsCreatedUser_whenRegistrationIsSuccessful() {
    val response = testRestTemplate.exchange(...)

    assertAll(
        { assertThat(response.statusCode.is2xxSuccessful).isTrue() },
        { assertThat(response.body?.data?.userId).isEqualTo("testuser") },
        { assertThat(response.body?.data?.email).isEqualTo("test@example.com") },
        { assertThat(response.body?.data?.gender).isEqualTo(Gender.MALE) },
        { assertThat(response.body?.data?.point).isEqualTo(0L) },
    )
}
```

`assertAll`을 쓰면 **모든 검증을 한 번에 실행**한다. 첫 번째가 실패해도 나머지도 확인한다. 근데 그게 장점이자 단점이다:

- 장점: 한 번에 뭐가 잘못됐는지 다 보임
- 단점: 실패 메시지가 여러 개 나오면 오히려 헷갈림

**아직도 명확한 기준이 없다.** 지금은 "엔티티 전체 상태를 확인할 때만" 쓰는데, 맞는 건지 모르겠다.

### 5. TDD의 본질은 "순서"가 아니었다

이번에 깨달은 게, **TDD의 가치는 "테스트를 먼저 쓰는 것" 자체가 아니라, "테스트 가능한 구조를 만드는 것"**이었다.

도메인 로직을 Entity에 넣고, Service는 흐름만 제어하고, 외부 의존성은 주입받고. 이런 구조가 되니까 테스트가 자연스럽게 작성됐다.

반대로, 처음에 Service에 다 때려박았을 때는 테스트를 나중에 써도 어려웠다.

**"테스트를 먼저 쓰면 좋은 설계가 나온다"는 말을 이해했다.** 테스트를 작성하려고 하니까, 자연스럽게 "이건 분리해야겠다"는 생각이 들었다. 테스트가 설계를 유도하는 느낌?

---

## 다음 라운드에서 해보고 싶은 것

1. **외부 API 연동이 있으면 Mock을 제대로 써보고 싶다.** 이번엔 Mock이 필요한 상황이 없어서, 이론만 알고 실전 경험은 부족하다.

2. **테스트 격리를 더 효율적으로 할 방법**을 찾고 싶다. DB 초기화 비용을 줄일 수 있는 전략이 있을 것 같다.

3. **중복 검증 같은 걸 도메인에서 할 방법**이 있을까? 지금은 Service에 있는데, 이게 맞는 건지 확신이 없다.

4. **TDD를 "자연스럽게" 하고 싶다.** 지금은 "테스트를 써야지!" 하고 의식적으로 쓰는 느낌인데, 그냥 습관처럼 되면 좋겠다.

이번 라운드는 테스트가 뭔지, 왜 쓰는지, 어떻게 쓰는지를 체감하는 시간이었다. 완벽하진 않지만, 확실히 이전보다는 "테스트 가능한 코드"가 뭔지 알게 된 것 같다.
