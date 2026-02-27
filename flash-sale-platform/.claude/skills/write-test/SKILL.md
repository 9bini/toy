---
name: write-test
description: 단위 테스트, 통합 테스트, 성능 테스트를 작성합니다. Kotest + MockK + Testcontainers 패턴을 따릅니다.
argument-hint: [test-type unit|integration|performance] [target-class-or-feature]
---

$ARGUMENTS 테스트를 작성하세요.

## 테스트 유형별 가이드

### unit (단위 테스트)
- **프레임워크**: Kotest BehaviorSpec (Given/When/Then) 또는 FunSpec
- **모킹**: MockK
- **위치**: `src/test/kotlin/` (소스와 동일 패키지)
- **대상**: UseCase, Domain 로직
- **규칙**: 외부 의존성 모두 모킹, 빠르게 실행

```kotlin
class PlaceOrderUseCaseTest : BehaviorSpec({
    val stockPort = mockk<StockPort>()
    val orderPort = mockk<OrderPort>()
    val sut = PlaceOrderUseCase(stockPort, orderPort)

    // ✅ 매 테스트 전 Mock 초기화 필수
    beforeEach { clearAllMocks() }

    Given("재고가 충분할 때") {
        // ✅ suspend fun에는 반드시 coEvery (every 아님!)
        coEvery { stockPort.verify(any()) } returns StockResult.Available(100)

        When("주문을 요청하면") {
            val result = sut.execute(command)

            Then("주문이 성공한다") {
                result shouldBe OrderResult.Success
            }

            Then("재고 검증이 호출되었다") {
                // ✅ suspend fun 검증은 coVerify
                coVerify { stockPort.verify(any()) }
            }
        }
    }
})
```

**FunSpec 코루틴 지원:**
```kotlin
class SimpleTest : FunSpec({
    // ✅ FunSpec은 코루틴 자동 지원 — runTest 불필요
    test("비동기 작업 테스트") {
        val result = asyncService.fetchData()  // suspend fun 직접 호출 가능
        result shouldBe expectedData
    }
})
```

### integration (통합 테스트)
- **프레임워크**: Kotest + Spring Boot Test
- **인프라**: Testcontainers 2.0 (Redis, Kafka, PostgreSQL)
- **위치**: `src/test/kotlin/.../integration/`
- **대상**: Adapter, 전체 흐름
- **규칙**: 실제 인프라 사용, 격리된 환경

```kotlin
@SpringBootTest
class OrderIntegrationTest : BehaviorSpec({
    // Testcontainers로 Redis, Kafka 자동 시작 (IntegrationTestBase 상속)
    Given("상품 재고가 100개일 때") {
        // Redis에 재고 설정
        When("동시에 50명이 주문하면") {
            // 코루틴으로 동시 요청
            Then("50개가 정확히 차감된다") {
                // 원자성 검증
            }
        }
    }
})
```

**Testcontainers + DynamicPropertySource 패턴:**
```kotlin
companion object {
    @DynamicPropertySource
    @JvmStatic
    fun properties(registry: DynamicPropertyRegistry) {
        registry.add("spring.r2dbc.url") { "r2dbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/test" }
        registry.add("spring.redis.host") { redis.host }
        registry.add("spring.redis.port") { redis.getMappedPort(6379) }
    }
}
```

### performance (성능/동시성 테스트)
- **위치**: `tests/performance/`
- **대상**: 동시성 시나리오, 부하 테스트
- **규칙**: 지표 측정 및 기록

## MockK 치트시트

| 작업 | 일반 함수 | suspend 함수 |
|------|---------|-------------|
| 스터빙 | `every { } returns` | `coEvery { } returns` |
| 검증 | `verify { }` | `coVerify { }` |
| 예외 | `every { } throws` | `coEvery { } throws` |
| Unit | `every { } just Runs` | `coEvery { } just Runs` |
| 다중 | `every { } returnsMany` | `coEvery { } returnsMany` |

## Assertion 치트시트

| 검증 | 코드 |
|------|------|
| 같은지 | `a shouldBe b` |
| 다른지 | `a shouldNotBe b` |
| 타입 | `a shouldBeInstanceOf<T>()` |
| 포함 | `list shouldContain item` |
| 크기 | `list shouldHaveSize N` |
| 예외 | `shouldThrow<E> { }` |
| null | `value.shouldNotBeNull()` |
| 범위 | `number shouldBeInRange 1..100` |

## 필수 사항
- 테스트 작성 후 반드시 실행: `./gradlew test` 또는 특정 테스트
- 실패 시 원인 분석 후 수정
- 엣지 케이스 반드시 포함:
  - null, 빈 값, 경계값
  - 동시성 (여러 코루틴 동시 실행)
  - 타임아웃
  - 네트워크 실패
- **clearAllMocks()**: `beforeEach`에서 매번 호출
- **coEvery / coVerify**: suspend fun에는 반드시 co- 접두사
- **Testcontainers**: Docker 실행 필수, `@DynamicPropertySource`로 포트 바인딩
