# 9. 테스트 전략

> **한 줄 요약**: Kotest(BDD 스타일 테스트) + MockK(모킹) + Testcontainers(실제 인프라 통합 테스트)

---

## 이 프로젝트의 테스트 도구

| 도구 | 역할 | 대응하는 Java 도구 |
|------|------|------------------|
| **Kotest** | 테스트 프레임워크 | JUnit 5 |
| **MockK** | 모킹 라이브러리 | Mockito |
| **Testcontainers** | 실제 컨테이너로 통합 테스트 | 직접 Docker 관리 |
| **kotlinx-coroutines-test** | 코루틴 테스트 유틸리티 | - |

---

## 1. Kotest (테스트 프레임워크)

### JUnit과 뭐가 다른가?

```kotlin
// JUnit 스타일
@Test
fun `재고가 부족하면 에러를 반환한다`() {
    // given
    val stock = 0
    // when
    val result = service.placeOrder(request)
    // then
    assertEquals(false, result.isSuccess)
}

// Kotest 스타일 (더 읽기 쉽고 Kotlin에 최적화)
class PlaceOrderUseCaseTest : FunSpec({
    test("재고가 부족하면 InsufficientStock 에러를 반환한다") {
        // given
        coEvery { stockPort.getRemaining("product-1") } returns 0

        // when
        val result = useCase.execute(command)

        // then
        result.isFailure shouldBe true
    }
})
```

### Kotest의 다양한 테스트 스타일

```kotlin
// FunSpec: 가장 많이 사용하는 스타일 (이 프로젝트 기본)
class OrderTest : FunSpec({
    test("주문 생성 테스트") { ... }
    test("주문 취소 테스트") { ... }
})

// BehaviorSpec: Given-When-Then 스타일
class OrderBehaviorTest : BehaviorSpec({
    given("재고가 10개인 상품") {
        `when`("5개를 주문하면") {
            then("주문이 성공하고 재고가 5개 남는다") { ... }
        }
        `when`("11개를 주문하면") {
            then("재고 부족 에러가 발생한다") { ... }
        }
    }
})

// DescribeSpec: describe-it 스타일 (RSpec과 유사)
class OrderDescribeTest : DescribeSpec({
    describe("PlaceOrderUseCase") {
        it("정상 주문을 처리한다") { ... }
        it("재고 부족 시 에러를 반환한다") { ... }
    }
})
```

### Kotest Assertion (검증)

```kotlin
// shouldBe: 동등 비교
result.isSuccess shouldBe true
order.status shouldBe OrderStatus.COMPLETED

// shouldContain: 포함 여부
errors shouldContain OrderError.InsufficientStock(0, 1)

// shouldThrow: 예외 발생 확인
shouldThrow<TimeoutCancellationException> {
    withTimeout(1.milliseconds) { delay(1.seconds) }
}

// shouldBeNull / shouldNotBeNull
val order = orderRepository.findById("non-existent")
order.shouldBeNull()

// Collection assertions
orders shouldHaveSize 3
orders.shouldContainAll(order1, order2, order3)
```

---

## 2. MockK (모킹 라이브러리)

### 왜 모킹이 필요한가?

UseCase를 테스트할 때, 실제 Redis/Kafka/DB가 필요하면 테스트가 느려지고 불안정해집니다.
**Mock(가짜 객체)**를 만들어 의존성을 대체합니다.

### 기본 사용법

```kotlin
class PlaceOrderUseCaseTest : FunSpec({
    // 모의 객체 생성
    val stockPort = mockk<StockPort>()
    val eventPublisher = mockk<OrderEventPublisher>()
    val useCase = PlaceOrderUseCaseImpl(stockPort, eventPublisher)

    test("재고가 충분하면 주문이 성공한다") {
        // coEvery: suspend fun의 반환값 지정
        coEvery { stockPort.getRemaining("product-1") } returns 10
        coEvery { stockPort.decrement("product-1", 1) } returns true
        coEvery { eventPublisher.publishOrderPlaced(any()) } just Runs

        val result = useCase.execute(PlaceOrderCommand("product-1", 1))

        result.isSuccess shouldBe true

        // coVerify: suspend fun 호출 확인
        coVerify(exactly = 1) { stockPort.decrement("product-1", 1) }
        coVerify(exactly = 1) { eventPublisher.publishOrderPlaced(any()) }
    }

    test("재고 부족 시 차감하지 않고 에러를 반환한다") {
        coEvery { stockPort.getRemaining("product-1") } returns 0

        val result = useCase.execute(PlaceOrderCommand("product-1", 1))

        result.isFailure shouldBe true
        // 재고 차감이 호출되지 않았음을 확인
        coVerify(exactly = 0) { stockPort.decrement(any(), any()) }
    }
})
```

### MockK 핵심 함수

| 함수 | 용도 | 코루틴 버전 |
|------|------|-----------|
| `every { ... } returns value` | 반환값 설정 | `coEvery { ... } returns value` |
| `every { ... } throws Exception()` | 예외 발생 | `coEvery { ... } throws Exception()` |
| `every { ... } just Runs` | 반환값 없음 (Unit) | `coEvery { ... } just Runs` |
| `verify { ... }` | 호출 검증 | `coVerify { ... }` |
| `verify(exactly = N) { ... }` | N번 호출 확인 | `coVerify(exactly = N) { ... }` |
| `any()` | 아무 값이나 매칭 | - |
| `slot<T>()` | 인수 캡처 | - |

### 인수 캡처

```kotlin
test("발행된 이벤트의 내용을 검증한다") {
    val eventSlot = slot<OrderPlacedEvent>()
    coEvery { eventPublisher.publishOrderPlaced(capture(eventSlot)) } just Runs
    coEvery { stockPort.getRemaining(any()) } returns 10
    coEvery { stockPort.decrement(any(), any()) } returns true

    useCase.execute(PlaceOrderCommand("product-1", 3))

    // 캡처된 이벤트 검증
    eventSlot.captured.productId shouldBe "product-1"
    eventSlot.captured.quantity shouldBe 3
}
```

---

## 3. Testcontainers (통합 테스트)

### 왜 필요한가?

단위 테스트는 Mock으로 충분하지만, **실제 Redis/Kafka/PostgreSQL과의 연동**을 검증하려면
실제 인프라가 필요합니다. Testcontainers는 Docker 컨테이너를 자동으로 띄우고 내려줍니다.

### 작동 방식

```
테스트 시작
  └── Testcontainers가 Docker 컨테이너 자동 시작
      ├── Redis (redis:7.4-alpine)
      ├── Kafka (apache/kafka:3.8.1)
      └── PostgreSQL (postgres:16-alpine)
  └── 테스트 실행 (실제 인프라에 연결)
  └── 테스트 종료 → 컨테이너 자동 정리
```

### 사용 예시

```kotlin
@SpringBootTest
class OrderIntegrationTest : FunSpec() {

    companion object {
        // Testcontainers: Redis 컨테이너
        val redis = GenericContainer("redis:7.4-alpine")
            .withExposedPorts(6379)

        // Testcontainers: PostgreSQL 컨테이너
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("flashsale_test")
            .withUsername("test")
            .withPassword("test")

        init {
            redis.start()
            postgres.start()
        }

        // Spring에 컨테이너 연결 정보 전달
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.firstMappedPort }
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/flashsale_test"
            }
        }
    }

    @Autowired lateinit var orderUseCase: PlaceOrderUseCase
    @Autowired lateinit var redisTemplate: ReactiveStringRedisTemplate

    init {
        test("실제 Redis에서 재고 차감이 정확히 동작한다") {
            // 실제 Redis에 재고 설정
            redisTemplate.opsForValue()
                .set("stock:remaining:product-1", "100")
                .awaitSingle()

            // 실제 주문 실행
            val result = orderUseCase.execute(
                PlaceOrderCommand("product-1", 1)
            )

            result.isSuccess shouldBe true

            // 실제 Redis에서 재고 확인
            val remaining = redisTemplate.opsForValue()
                .get("stock:remaining:product-1")
                .awaitSingle()
            remaining shouldBe "99"
        }
    }
}
```

### 이 프로젝트의 testFixtures

공통 Testcontainers 설정을 `common/infrastructure`의 `testFixtures`에서 공유합니다.

```kotlin
// common/infrastructure/build.gradle.kts
plugins {
    `java-test-fixtures`  // testFixtures 지원
}

dependencies {
    testFixturesApi(libs.testcontainers.core)
    testFixturesApi(libs.testcontainers.kafka)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.redis.testcontainers)
}
```

---

## 4. 코루틴 테스트

```kotlin
class TimeoutTest : FunSpec({
    test("withTimeout이 만료되면 예외가 발생한다") {
        shouldThrow<TimeoutCancellationException> {
            withTimeout(100.milliseconds) {
                delay(1.seconds) // 1초 대기 → 100ms에 타임아웃
            }
        }
    }

    test("runTest로 가상 시간 사용") {
        runTest {
            // runTest 안에서는 delay가 즉시 실행됨 (가상 시간)
            val start = currentTime
            delay(10.seconds)
            val elapsed = currentTime - start
            elapsed shouldBe 10_000 // 실제로는 즉시 완료
        }
    }
})
```

---

## 테스트 피라미드

```
        /  E2E 테스트  \          ← 적음, 느림, 비쌈
       / 통합 테스트     \        ← Testcontainers
      / 단위 테스트        \      ← Kotest + MockK (가장 많이)
```

| 테스트 종류 | 도구 | 대상 | 속도 |
|-----------|------|------|------|
| **단위 테스트** | Kotest + MockK | UseCase, Domain | 빠름 (~ms) |
| **통합 테스트** | Testcontainers | Adapter (Redis, Kafka, DB) | 느림 (~s) |
| **E2E 테스트** | Docker Compose | 전체 시스템 | 매우 느림 |

---

## 테스트 실행 명령어

```bash
# 전체 테스트
./gradlew test

# 특정 서비스 테스트
./gradlew :services:order-service:test

# 특정 테스트 클래스
./gradlew :services:order-service:test --tests "*.PlaceOrderUseCaseTest"

# 테스트 결과 확인
# build/reports/tests/test/index.html
```

---

## 더 알아보기

- **Kotest 공식**: [kotest.io](https://kotest.io/)
- **MockK 공식**: [mockk.io](https://mockk.io/)
- **Testcontainers**: [testcontainers.org](https://testcontainers.org/)
- **이 프로젝트 의존성**: `build.gradle.kts` 루트의 `testImplementation` 블록
