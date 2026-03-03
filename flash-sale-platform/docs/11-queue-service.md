# 11. Queue Service 구현

> **한 줄 요약**: Redis Sorted Set 기반 대기열로, 선착순 시스템의 진입점을 헥사고날 아키텍처로 구현

---

## 왜 대기열이 필요한가?

### 문제: 10만 명이 동시에 접속하면?

```
10만 동시 요청 → 서버 직접 처리
├── DB 커넥션 풀 소진
├── CPU/Memory 과부하
└── 서비스 전체 장애 (Cascading Failure)
```

### 해결: 대기열로 유량 제어

```
10만 동시 요청 → 대기열 진입 (빠름, Redis)
                    ↓
                순서대로 처리 (초당 1000명씩)
                    ↓
                구매 페이지 진입
```

- **서버 보호**: 동시 처리량을 서버가 감당 가능한 수준으로 조절
- **공정성**: 먼저 온 사람이 먼저 처리 (FIFO)
- **사용자 경험**: "현재 42번째입니다" 같은 실시간 피드백 제공

---

## 아키텍처 개요

```
┌────────────────────────────────────────────────────────┐
│                    Adapter (In)                        │
│  QueueController                                      │
│  POST /api/queues/{saleEventId}/enter                 │
│  GET  /api/queues/{saleEventId}/position?userId=...   │
└──────────────────────┬─────────────────────────────────┘
                       │
┌──────────────────────▼─────────────────────────────────┐
│                    Port (In)                           │
│  EnqueueUserUseCase    GetQueuePositionUseCase         │
│  EnqueueCommand        PositionQuery                   │
│  EnqueueResult         PositionResult                  │
└──────────────────────┬─────────────────────────────────┘
                       │
┌──────────────────────▼─────────────────────────────────┐
│                   Application                          │
│  EnqueueUserService   GetQueuePositionService          │
└──────────────────────┬─────────────────────────────────┘
                       │
┌──────────────────────▼─────────────────────────────────┐
│                    Port (Out)                          │
│  QueuePort                                            │
│    add(entry): Boolean                                │
│    getPosition(saleEventId, userId): Long?            │
└──────────────────────┬─────────────────────────────────┘
                       │
┌──────────────────────▼─────────────────────────────────┐
│                   Adapter (Out)                        │
│  RedisQueueAdapter                                    │
│    ZADD → add()                                       │
│    ZRANK → getPosition()                              │
└────────────────────────────────────────────────────────┘
```

---

## 도메인 모델

### QueueEntry — 대기열 항목 Value Object

```kotlin
data class QueueEntry(
    val saleEventId: String,   // 어떤 판매 이벤트의 대기열인지
    val userId: String,        // 누가 대기 중인지
    val enteredAt: Instant,    // 언제 진입했는지 (= Redis score)
)
```

**왜 `position`이 없는가?**
- 순번은 Redis에서 실시간으로 계산되는 동적 값
- 앞 사람이 빠지면 내 순번도 바뀜
- 저장하면 매번 갱신해야 하므로 비효율적

### QueueError — sealed interface

```kotlin
sealed interface QueueError {
    data class AlreadyEnqueued(val userId: String, val saleEventId: String) : QueueError
    data class NotFound(val userId: String, val saleEventId: String) : QueueError
}
```

**왜 sealed interface인가?**

```kotlin
// when에서 모든 케이스를 다루지 않으면 컴파일 에러
fun toErrorResponse(error: QueueError): ResponseEntity<Any> =
    when (error) {
        is QueueError.AlreadyEnqueued -> ...  // 필수
        is QueueError.NotFound -> ...         // 필수
        // else 불필요 — 새 에러 추가 시 컴파일러가 누락 알려줌
    }
```

| | sealed interface | sealed class | enum |
|---|---|---|---|
| 상태 보유 | 각 하위 타입이 다른 필드 가능 | 동일 | 불가 |
| 다중 구현 | 가능 | 불가 (단일 상속) | 불가 |
| 인스턴스 | 매번 새로 생성 | 매번 새로 생성 | 싱글턴 |
| 적합한 경우 | 에러 타입처럼 각각 다른 데이터를 가질 때 | 공통 상태가 있을 때 | 상태 없는 상수 열거 |

---

## Redis Sorted Set 동작 원리

### 자료구조: Skip List + Hash Table

```
Redis Sorted Set 내부 구조 (Skip List)

Level 4: HEAD ────────────────────────────────────────→ user-D → NIL
Level 3: HEAD ──────────────→ user-B ─────────────────→ user-D → NIL
Level 2: HEAD → user-A ─────→ user-B ──────→ user-C ──→ user-D → NIL
Level 1: HEAD → user-A ─────→ user-B ──────→ user-C ──→ user-D → NIL
          score: 1708900000001  1708900000002  1708900000005  1708900000010
```

- **Skip List**: score 기준 정렬 유지, O(log N) 삽입/조회
- **Hash Table**: member → score 매핑, O(1) 존재 확인

### ZADD — 대기열 진입

```
ZADD queue:waiting:sale-001 1708900000001 user-001
```

| 항목 | 값 |
|------|-----|
| key | `queue:waiting:sale-001` |
| score | `1708900000001` (진입 시각 밀리초) |
| member | `user-001` |
| 반환값 | 1 (새 멤버) / 0 (기존 멤버) |
| 시간복잡도 | O(log N) |

```kotlin
// RedisQueueAdapter.add()
override suspend fun add(entry: QueueEntry): Boolean =
    withTimeout(timeouts.redisOperation) {
        val key = RedisKeys.Queue.waiting(entry.saleEventId)
        val score = entry.enteredAt.toEpochMilli().toDouble()
        redisTemplate.opsForZSet()
            .add(key, entry.userId, score)
            .awaitSingle()  // Boolean: 새 멤버면 true
    }
```

### ZRANK — 순번 조회

```
ZRANK queue:waiting:sale-001 user-001
→ 0 (0-based index)
→ +1 해서 1-based로 변환
```

| 항목 | 값 |
|------|-----|
| 반환값 | 0-based rank (없으면 nil) |
| 시간복잡도 | O(log N) |

```kotlin
// RedisQueueAdapter.getPosition()
override suspend fun getPosition(saleEventId: String, userId: String): Long? =
    withTimeout(timeouts.redisOperation) {
        val key = RedisKeys.Queue.waiting(saleEventId)
        val rank = redisTemplate.opsForZSet()
            .rank(key, userId)
            .awaitFirstOrNull()  // null: 멤버가 없음
        rank?.plus(1)  // 0-based → 1-based
    }
```

### awaitSingle vs awaitFirstOrNull

| | `awaitSingle()` | `awaitFirstOrNull()` |
|---|---|---|
| Mono가 빈 값일 때 | `NoSuchElementException` | `null` |
| 사용 시점 | 반드시 값이 있을 때 (ZADD는 항상 Boolean 반환) | 값이 없을 수 있을 때 (ZRANK는 멤버 미존재 시 nil) |

---

## UseCase 흐름

### 대기열 진입 (EnqueueUserService)

```
요청: POST /api/queues/sale-001/enter { "userId": "user-001" }

1. QueuePort.add(entry)
   ├── true  → 새로 추가됨
   │   └── 2. QueuePort.getPosition() → position 반환
   │       └── Result.success(EnqueueResult(position = 42))
   └── false → 이미 존재
       └── Result.failure(AlreadyEnqueued)
```

```kotlin
override suspend fun execute(command: EnqueueCommand): Result<EnqueueResult, QueueError> {
    val entry = QueueEntry(
        saleEventId = command.saleEventId,
        userId = command.userId,
        enteredAt = Instant.now(),
    )

    val added = queuePort.add(entry)
    if (!added) {
        return Result.failure(QueueError.AlreadyEnqueued(command.userId, command.saleEventId))
    }

    val position = queuePort.getPosition(command.saleEventId, command.userId)
        ?: return Result.failure(QueueError.NotFound(command.userId, command.saleEventId))

    return Result.success(EnqueueResult(position))
}
```

### 순번 조회 (GetQueuePositionService)

```
요청: GET /api/queues/sale-001/position?userId=user-001

1. QueuePort.getPosition()
   ├── 42L → Result.success(PositionResult(42))
   └── null → Result.failure(NotFound)
```

---

## Result 타입 패턴

### 왜 Exception 대신 Result를 쓰는가?

```kotlin
// ❌ Exception 방식: 어떤 예외가 던져지는지 시그니처에서 안 보임
suspend fun enqueue(command: EnqueueCommand): EnqueueResult  // throws ???

// ✅ Result 방식: 실패 가능성과 에러 타입이 시그니처에 명시
suspend fun execute(command: EnqueueCommand): Result<EnqueueResult, QueueError>
```

| | Exception | Result<T, E> |
|---|---|---|
| 실패 가능성 | 시그니처에 안 보임 | 타입으로 명시 |
| 에러 처리 강제 | try-catch 잊으면 런타임 터짐 | fold/when으로 컴파일 타임 강제 |
| 성능 | 스택 트레이스 생성 비용 | 일반 객체 생성 (가벼움) |
| 적합한 곳 | 예상치 못한 시스템 오류 | 예상 가능한 비즈니스 에러 |

### Controller에서의 사용

```kotlin
// Result.fold()로 성공/실패 분기
return enqueueUserUseCase.execute(command).fold(
    onSuccess = { result ->
        ResponseEntity.status(HttpStatus.CREATED).body(
            QueuePositionResponse(saleEventId, request.userId, result.position),
        )
    },
    onFailure = { error -> toErrorResponse(error) },
)
```

---

## withTimeout — 코루틴 타임아웃

### 왜 Redis 호출에 타임아웃을 걸까?

```
정상: Redis 응답 ~1ms
장애: Redis 네트워크 지연 → 응답 안 옴 → 코루틴 무한 대기 → 스레드 풀 고갈 → 전체 서비스 장애

withTimeout(100ms): Redis가 100ms 안에 응답 안 하면 TimeoutCancellationException 발생
→ 빠르게 실패하여 다른 요청 처리 가능 (Fail-Fast 전략)
```

### withTimeout 내부 동작

```kotlin
withTimeout(timeouts.redisOperation) {  // 100ms
    redisTemplate.opsForZSet()
        .add(key, userId, score)
        .awaitSingle()
}
```

1. 코루틴이 `withTimeout` 블록에 진입
2. 내부적으로 `delay(100ms)` 후 취소하는 Job을 스케줄링
3. `awaitSingle()`에서 suspension point 발생 (코루틴이 일시 중단)
4. Redis 응답이 100ms 안에 오면 → 정상 반환, 취소 Job은 폐기
5. 100ms 초과 시 → `TimeoutCancellationException` 발생

**핵심**: `withTimeout`은 스레드를 블로킹하지 않고, 코루틴 취소 메커니즘을 활용

---

## 테스트 전략

### 테스트 피라미드

```
          ┌──────────┐
          │ Controller│  ← WebTestClient 단위 테스트 (Spring Context 없이)
          │   Test    │
          └────┬─────┘
       ┌───────▼───────┐
       │  Service Test  │  ← MockK로 Port mock, 비즈니스 로직만 검증
       └───────┬───────┘
    ┌──────────▼──────────┐
    │ Redis Adapter Test   │  ← Testcontainers로 실제 Redis 사용
    └─────────────────────┘
```

### 단위 테스트: MockK + Kotest DescribeSpec

```kotlin
class EnqueueUserServiceTest : DescribeSpec({
    val queuePort = mockk<QueuePort>()
    val sut = EnqueueUserService(queuePort)

    describe("execute") {
        val command = EnqueueCommand(saleEventId = "sale-001", userId = "user-001")

        context("새로운 사용자가 대기열에 진입하면") {
            it("position을 반환한다") {
                coEvery { queuePort.add(any()) } returns true
                coEvery { queuePort.getPosition("sale-001", "user-001") } returns 1L

                val result = sut.execute(command)

                result.shouldBeInstanceOf<Result.Success<*>>()
                (result as Result.Success).value.position shouldBe 1L
            }
        }

        context("이미 대기열에 있는 사용자가 재진입하면") {
            it("AlreadyEnqueued 에러를 반환한다") {
                coEvery { queuePort.add(any()) } returns false

                val result = sut.execute(command)

                result.shouldBeInstanceOf<Result.Failure<*>>()
                (result as Result.Failure).error.shouldBeInstanceOf<QueueError.AlreadyEnqueued>()
            }
        }
    }
})
```

**`coEvery` vs `every`**
- `coEvery`: `suspend fun`을 mock할 때 사용 (코루틴 함수)
- `every`: 일반 함수를 mock할 때 사용

### 통합 테스트: Testcontainers + companion object 패턴

```kotlin
@SpringBootTest
class RedisQueueAdapterTest(
    private val adapter: RedisQueueAdapter,
    private val redisTemplate: ReactiveStringRedisTemplate,
) : DescribeSpec({
    extensions(SpringExtension)  // Spring DI 연동

    beforeEach {
        // 테스트 격리: 각 테스트 전에 키 삭제
        redisTemplate.delete("queue:waiting:test-sale").block()
    }

    describe("add") {
        it("새 사용자를 추가하면 true를 반환한다") {
            val entry = QueueEntry("test-sale", "user-001", Instant.now())
            adapter.add(entry) shouldBe true
        }
    }
}) {
    // Testcontainers 싱글턴을 companion object로 관리
    companion object : IntegrationTestBase()
}
```

**왜 `companion object : IntegrationTestBase()`인가?**
- Kotlin은 다중 클래스 상속이 불가 → `DescribeSpec`과 `IntegrationTestBase`를 동시에 상속할 수 없음
- `companion object`는 JVM에서 static 영역 → `@DynamicPropertySource`가 정상 동작
- 컨테이너는 JVM 프로세스에서 1번만 생성되어 모든 테스트가 공유 (속도 최적화)

### 컨트롤러 테스트: WebTestClient.bindToController()

```kotlin
class QueueControllerTest : DescribeSpec({
    val enqueueUserUseCase = mockk<EnqueueUserUseCase>()
    val getQueuePositionUseCase = mockk<GetQueuePositionUseCase>()
    val controller = QueueController(enqueueUserUseCase, getQueuePositionUseCase)
    val webTestClient = WebTestClient.bindToController(controller).build()

    describe("POST /api/queues/{saleEventId}/enter") {
        it("정상 진입 시 201 Created와 position을 반환한다") {
            coEvery { enqueueUserUseCase.execute(any()) } returns
                Result.success(EnqueueResult(position = 1))

            webTestClient.post()
                .uri("/api/queues/sale-001/enter")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"userId": "user-001"}""")
                .exchange()
                .expectStatus().isCreated
                .expectBody()
                .jsonPath("$.position").isEqualTo(1)
        }
    }
})
```

**`bindToController()` vs `@WebFluxTest`**
- `bindToController()`: Spring Context 없이 컨트롤러만 테스트 (빠름, 의존성 직접 주입)
- `@WebFluxTest`: Spring Context 로드 (필터, 인터셉터, 글로벌 에러 핸들러 등 포함)

---

## API 스펙

### POST /api/queues/{saleEventId}/enter

대기열 진입

**Request:**
```json
{
    "userId": "user-001"
}
```

**Response (201 Created):**
```json
{
    "saleEventId": "sale-001",
    "userId": "user-001",
    "position": 42
}
```

**Response (409 Conflict — 중복 진입):**
```json
{
    "code": "ALREADY_ENQUEUED",
    "message": "이미 대기열에 진입한 사용자입니다"
}
```

### GET /api/queues/{saleEventId}/position?userId={userId}

순번 조회

**Response (200 OK):**
```json
{
    "saleEventId": "sale-001",
    "userId": "user-001",
    "position": 42
}
```

**Response (404 Not Found):**
```json
{
    "code": "NOT_FOUND",
    "message": "대기열에서 사용자를 찾을 수 없습니다"
}
```

---

## 파일 구조

```
services/queue-service/src/main/kotlin/com/flashsale/queue/
├── domain/
│   ├── QueueEntry.kt           # 대기열 항목 Value Object
│   └── QueueError.kt           # 에러 sealed interface
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── EnqueueUserUseCase.kt      # 진입 UseCase 인터페이스
│   │   │   └── GetQueuePositionUseCase.kt  # 조회 UseCase 인터페이스
│   │   └── out/
│   │       └── QueuePort.kt               # Redis 어댑터 인터페이스
│   └── service/
│       ├── EnqueueUserService.kt          # 진입 비즈니스 로직
│       └── GetQueuePositionService.kt     # 조회 비즈니스 로직
└── adapter/
    ├── in/web/
    │   ├── QueueController.kt     # REST API 엔드포인트
    │   ├── QueueRequest.kt        # 요청 DTO
    │   └── QueueResponse.kt       # 응답 DTO
    └── out/redis/
        └── RedisQueueAdapter.kt   # Redis Sorted Set 구현
```

---

## 면접 질문

### Q1. 왜 Redis Sorted Set을 대기열에 사용했나요?

**A:** 선착순 시스템에서 대기열의 핵심 요구사항은 (1) 진입 순서 보장, (2) 순번 조회, (3) 중복 방지입니다.

Redis Sorted Set은 score(진입 시각 밀리초)로 자동 정렬되어 FIFO를 보장하고, `ZRANK`로 O(log N)에 순번을 조회할 수 있으며, Set 특성상 같은 member의 중복 삽입이 자동으로 방지됩니다. List를 사용하면 순번 조회에 O(N)이 걸리고 중복 체크를 별도로 해야 합니다.

내부 구조는 Skip List + Hash Table로, Skip List가 score 기준 정렬된 순회를 O(log N)에 제공하고, Hash Table이 member → score 매핑을 O(1)에 제공합니다.

### Q2. sealed interface와 sealed class의 차이는?

**A:** 둘 다 제한된 타입 계층을 정의하여 `when` 식에서 컴파일 타임 완전성 검사를 지원합니다. 차이점은:

- `sealed class`: 단일 상속만 가능. 공통 상태(프로퍼티)를 상위 클래스에 정의 가능
- `sealed interface`: 다중 구현 가능. 하위 타입이 다른 클래스를 상속하면서 동시에 이 인터페이스를 구현 가능

`QueueError`는 `AlreadyEnqueued`와 `NotFound`가 서로 다른 필드를 갖고, 공통 상태가 없으므로 `sealed interface`가 적합합니다.

### Q3. `awaitSingle()`과 `awaitFirstOrNull()`은 왜 구분해서 사용하나요?

**A:** 두 함수 모두 Reactor의 `Mono`를 코루틴의 `suspend fun`으로 변환하지만, 빈 Mono에 대한 처리가 다릅니다.

- `awaitSingle()`: Mono가 반드시 값을 방출할 때 사용. 빈 Mono면 `NoSuchElementException` 발생. Redis `ZADD`는 항상 Boolean을 반환하므로 이것을 사용
- `awaitFirstOrNull()`: Mono가 비어있을 수 있을 때 사용. 빈 Mono면 `null` 반환. Redis `ZRANK`는 멤버가 없으면 nil을 반환하므로 이것을 사용

### Q4. withTimeout은 어떻게 동작하나요? 스레드를 블로킹하지 않나요?

**A:** `withTimeout`은 코루틴의 구조화된 동시성(Structured Concurrency) 메커니즘을 활용합니다.

1. `withTimeout(100ms)` 호출 시 내부적으로 자식 코루틴 Job을 생성하고, 100ms 후 취소하는 타이머를 등록
2. 블록 내에서 `awaitSingle()` 같은 suspension point에서 코루틴이 중단(suspend)됨 — **스레드는 반환**됨
3. Redis 응답이 100ms 안에 오면 코루틴 재개, 타이머 취소
4. 100ms 초과 시 코루틴의 Job이 취소되고 `TimeoutCancellationException` 발생

스레드를 블로킹하는 `Thread.sleep()`과 달리, 코루틴의 중단/재개 메커니즘으로 동작하므로 소수의 스레드로 수만 개의 동시 요청을 처리할 수 있습니다.

### Q5. 헥사고날 아키텍처에서 Port와 Adapter의 역할은?

**A:** Port는 비즈니스 로직이 외부 세계와 소통하는 "계약(인터페이스)"이고, Adapter는 그 계약의 구체적인 "구현체"입니다.

- **Port In** (`EnqueueUserUseCase`): 외부 → 애플리케이션 진입점. Controller가 호출
- **Port Out** (`QueuePort`): 애플리케이션 → 외부 기술. Service가 의존하되, 구현 세부사항은 모름
- **Adapter In** (`QueueController`): Port In을 HTTP로 노출
- **Adapter Out** (`RedisQueueAdapter`): Port Out을 Redis로 구현

이점: Service는 `QueuePort` 인터페이스만 알고, Redis인지 Memcached인지 모름 → 기술 교체 시 Adapter만 바꾸면 됨. 테스트에서는 MockK로 Port를 mock하여 비즈니스 로직만 검증 가능.

### Q6. Result 타입을 쓰면 Exception 대비 어떤 장점이 있나요?

**A:** 세 가지 핵심 장점이 있습니다.

1. **타입 안전성**: `Result<EnqueueResult, QueueError>` 시그니처만 보고 어떤 에러가 발생할 수 있는지 알 수 있음. Exception은 시그니처에 드러나지 않음
2. **처리 강제**: `fold()`나 `when`으로 반드시 성공/실패를 처리해야 컴파일됨. Exception은 catch를 잊으면 런타임에 터짐
3. **성능**: 일반 데이터 클래스 생성이므로 스택 트레이스 생성 비용이 없음. Exception 생성 시 `fillInStackTrace()`가 호출되어 비용이 큼 (선착순 시스템에서 "이미 진입" 같은 빈번한 에러에 중요)

적합한 분리: 비즈니스 에러(재고 부족, 중복 진입)는 Result로, 시스템 오류(DB 연결 실패, 네트워크 장애)는 Exception으로 처리.

### Q7. Testcontainers에서 companion object 패턴을 쓰는 이유는?

**A:** 두 가지 제약을 동시에 해결합니다.

1. **Kotlin 단일 상속 제약**: `DescribeSpec`(Kotest 테스트 프레임워크)과 `IntegrationTestBase`(Testcontainers 설정)를 동시에 상속할 수 없음. `companion object : IntegrationTestBase()`로 Testcontainers 설정을 static 영역에 위임
2. **JVM 싱글턴 패턴**: `companion object`는 JVM에서 static 초기화 → 클래스 로드 시 1번만 실행. `@DynamicPropertySource`로 등록한 프로퍼티가 Spring Context에 주입됨. 여러 테스트 클래스가 있어도 컨테이너는 1번만 생성

이를 통해 테스트 실행 시간을 최소화합니다 (컨테이너 시작은 수 초 소요, 매 테스트마다 재시작하면 매우 느림).
