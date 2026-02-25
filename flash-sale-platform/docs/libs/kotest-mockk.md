# Kotest + MockK + Testcontainers

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [Kotest: 테스트 프레임워크](#3-kotest-테스트-프레임워크)
4. [MockK: 모킹 라이브러리](#4-mockk-모킹-라이브러리)
5. [Testcontainers: 통합 테스트](#5-testcontainers-통합-테스트)
6. [이 프로젝트에서의 활용](#6-이-프로젝트에서의-활용)
7. [자주 하는 실수 / 주의사항](#7-자주-하는-실수--주의사항)
8. [정리 / 한눈에 보기](#8-정리--한눈에-보기)
9. [더 알아보기](#9-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

- **Kotest**: Kotlin 네이티브 테스트 프레임워크 (JUnit 대체)
- **MockK**: Kotlin 네이티브 모킹 라이브러리 (Mockito 대체)
- **Testcontainers**: 테스트 시 Docker 컨테이너(Redis, Kafka, PostgreSQL)를 자동 실행

---

## 2. 왜 필요한가?

### 단위 테스트 (Kotest + MockK)

외부 의존성(DB, Redis) 없이 **비즈니스 로직만** 검증한다.

```kotlin
// DB 없이 주문 로직 테스트
class PlaceOrderUseCaseTest : FunSpec({
    val stockPort = mockk<StockPort>()           // 가짜 재고 포트
    val useCase = PlaceOrderUseCase(stockPort)

    test("재고가 있으면 주문 성공") {
        coEvery { stockPort.getRemaining("prod-1") } returns 10  // 재고 10개 설정
        useCase.execute(orderRequest) shouldBeSuccess { ... }
    }
})
```

### 통합 테스트 (Testcontainers)

실제 DB, Redis를 Docker로 띄워서 **전체 흐름**을 검증한다.

```kotlin
// 진짜 Redis, PostgreSQL로 테스트
class OrderIntegrationTest : IntegrationTestBase() {
    test("주문 → 재고 차감 → 결제 → 완료") {
        // 실제 Redis에 재고 설정
        // 실제 API 호출
        // 실제 DB에서 결과 확인
    }
}
```

---

## 3. Kotest: 테스트 프레임워크

### 3.1 테스트 스타일

Kotest는 여러 스타일을 제공한다. 이 프로젝트에서는 **FunSpec**과 **BehaviorSpec**을 사용.

#### FunSpec (가장 단순)

```kotlin
class OrderServiceTest : FunSpec({

    test("주문 생성 성공") {
        val order = orderService.create(request)
        order.status shouldBe OrderStatus.PENDING
    }

    test("재고 부족 시 실패") {
        shouldThrow<InsufficientStockException> {
            orderService.create(noStockRequest)
        }
    }
})
```

#### BehaviorSpec (Given-When-Then)

```kotlin
class OrderServiceTest : BehaviorSpec({

    given("재고가 10개인 상품") {
        // setup

        `when`("1개 주문하면") {
            val result = orderService.create(request)

            then("주문이 성공한다") {
                result.isSuccess shouldBe true
            }

            then("재고가 9개로 줄어든다") {
                stockService.getRemaining(productId) shouldBe 9
            }
        }

        `when`("11개 주문하면") {
            then("재고 부족 에러가 발생한다") {
                shouldThrow<InsufficientStockException> {
                    orderService.create(bigRequest)
                }
            }
        }
    }
})
```

### 3.2 Assertion (검증) API

```kotlin
// 동등성
result shouldBe expected
result shouldNotBe unexpected

// 타입
result shouldBeInstanceOf<Order>()

// 컬렉션
list shouldContain item
list shouldHaveSize 3
list.shouldBeEmpty()

// 문자열
str shouldStartWith "prefix"
str shouldContain "keyword"

// 숫자
number shouldBeGreaterThan 0
number shouldBeInRange 1..100

// Null
value.shouldNotBeNull()
value.shouldBeNull()

// 예외
shouldThrow<IllegalArgumentException> { riskyCall() }
shouldNotThrow<Exception> { safeCall() }

// Result (Kotlin Result)
result.shouldBeSuccess()
result.shouldBeFailure()
```

### 3.3 코루틴 테스트

```kotlin
class AsyncServiceTest : FunSpec({

    test("비동기 작업 테스트") {
        // FunSpec은 코루틴을 자동 지원 (runTest 불필요)
        val result = asyncService.fetchData()   // suspend fun 직접 호출 가능
        result shouldBe expectedData
    }
})
```

---

## 4. MockK: 모킹 라이브러리

### 4.1 Mock이란?

**가짜 객체**. 실제 구현 없이 원하는 동작을 시뮬레이션한다.

```
실제: orderService.create() → DB 접근 → Kafka 발행 → 복잡한 처리
Mock: orderService.create() → 내가 정한 값 반환 (DB, Kafka 불필요)
```

### 4.2 기본 사용법

```kotlin
// Mock 생성
val stockPort = mockk<StockPort>()

// 동작 정의 (Stubbing)
every { stockPort.getRemaining("prod-1") } returns 10
// "getRemaining이 호출되면 10을 반환해라"

// 코루틴 함수는 coEvery
coEvery { stockPort.getRemaining("prod-1") } returns 10

// 테스트 실행
val result = useCase.execute(request)
result.shouldBeSuccess()

// 호출 검증 (Verification)
verify { stockPort.getRemaining("prod-1") }
// "getRemaining이 호출되었는지 확인"

// 코루틴 함수는 coVerify
coVerify { stockPort.getRemaining("prod-1") }
```

### 4.3 Stubbing 패턴

```kotlin
// 값 반환
every { mock.method() } returns value

// 예외 발생
every { mock.method() } throws IllegalArgumentException("에러")

// 호출마다 다른 값
every { mock.method() } returnsMany listOf(1, 2, 3)
// 첫 번째 호출: 1, 두 번째: 2, 세 번째: 3

// 인자에 따라 다른 반환
every { mock.method(1) } returns "one"
every { mock.method(2) } returns "two"

// 아무 인자나 매칭
every { mock.method(any()) } returns "anything"

// Unit 반환 (void)
every { mock.voidMethod() } just Runs
```

### 4.4 Verification 패턴

```kotlin
// 호출되었는지
verify { mock.method() }

// 정확히 N번 호출
verify(exactly = 2) { mock.method() }

// 호출되지 않았는지
verify(exactly = 0) { mock.method() }

// 호출 순서 검증
verifyOrder {
    mock.first()
    mock.second()
}
```

### 4.5 코루틴 전용 API

```kotlin
// suspend fun 스터빙
coEvery { suspendMock.method() } returns value

// suspend fun 검증
coVerify { suspendMock.method() }

// suspend fun 예외
coEvery { suspendMock.method() } throws TimeoutException()
```

---

## 5. Testcontainers: 통합 테스트

### 5.1 Testcontainers란?

테스트 코드에서 **Docker 컨테이너를 자동으로 시작/종료**하는 라이브러리.
실제 Redis, Kafka, PostgreSQL을 사용하여 통합 테스트 수행.

```
테스트 시작
  ↓ Testcontainers가 Docker 컨테이너 시작
  ↓ Redis (:49152), Kafka (:49153), PostgreSQL (:49154)  ← 랜덤 포트
  ↓ 테스트 실행 (실제 인프라에 연결)
  ↓ 테스트 완료
  ↓ Docker 컨테이너 자동 종료
```

### 5.2 기본 사용법

```kotlin
class IntegrationTest {

    companion object {
        val redis = GenericContainer("redis:7.4-alpine")
            .withExposedPorts(6379)

        init {
            redis.start()
        }
    }

    // redis.host → "localhost"
    // redis.getMappedPort(6379) → 49152 (랜덤)
}
```

### 5.3 이 프로젝트의 testFixtures

공통 테스트 설정을 `common/infrastructure/testFixtures`에 정의하여 재사용한다.

```kotlin
// common/infrastructure/src/testFixtures/.../IntegrationTestBase.kt
abstract class IntegrationTestBase {
    companion object {
        val redis = GenericContainer("redis:7.4-alpine").withExposedPorts(6379)
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"))
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        init {
            redis.start()
            kafka.start()
            postgres.start()
        }
    }
}
```

서비스 테스트에서 상속하여 사용:

```kotlin
// order-service 통합 테스트
class OrderIntegrationTest : IntegrationTestBase() {
    // redis, kafka, postgres가 이미 실행됨
}
```

---

## 6. 이 프로젝트에서의 활용

### 의존성

```kotlin
// build.gradle.kts (루트 subprojects)
testImplementation(rootProject.libs.bundles.kotest)   // runner + assertions + property
testImplementation(rootProject.libs.mockk)
testImplementation(rootProject.libs.kotest.extensions.spring)

// common/infrastructure/build.gradle.kts (testFixtures)
testFixturesApi(libs.testcontainers.core)
testFixturesApi(libs.testcontainers.kafka)
testFixturesApi(libs.testcontainers.postgresql)
testFixturesApi(libs.testcontainers.r2dbc)
testFixturesApi(libs.redis.testcontainers)
```

### 테스트 실행

```bash
# 전체 테스트
./gradlew test

# 특정 서비스만
./gradlew :services:order-service:test

# 특정 테스트 클래스
./gradlew test --tests "*.PlaceOrderUseCaseTest"
```

---

## 7. 자주 하는 실수 / 주의사항

### Mock 초기화 누락

```kotlin
// ❌ 이전 테스트의 스터빙이 남아 있을 수 있음
class MyTest : FunSpec({
    val mock = mockk<Service>()

    // ✅ 매 테스트 전 초기화
    beforeEach { clearAllMocks() }

    test("테스트 1") { ... }
    test("테스트 2") { ... }
})
```

### coEvery vs every

```kotlin
// ❌ suspend fun에 every 사용 → 에러
every { suspendFun() } returns value

// ✅ suspend fun에는 coEvery
coEvery { suspendFun() } returns value
```

### Testcontainers Docker 미설치

```
// Docker Desktop이 실행 중이어야 함
// 없으면: "Could not find a valid Docker environment" 에러
```

---

## 8. 정리 / 한눈에 보기

### 도구별 역할

| 도구 | 역할 | 대체 |
|------|------|------|
| Kotest | 테스트 프레임워크 | JUnit |
| MockK | 모킹/스터빙 | Mockito |
| Testcontainers | Docker 기반 통합 테스트 | H2 (인메모리 DB) |

### MockK 치트시트

| 작업 | 일반 함수 | suspend 함수 |
|------|---------|-------------|
| 스터빙 | `every { } returns` | `coEvery { } returns` |
| 검증 | `verify { }` | `coVerify { }` |
| 예외 | `every { } throws` | `coEvery { } throws` |

### Kotest Assertion 치트시트

| 검증 | 코드 |
|------|------|
| 같은지 | `a shouldBe b` |
| 다른지 | `a shouldNotBe b` |
| 포함 | `list shouldContain item` |
| 크기 | `list shouldHaveSize N` |
| 예외 | `shouldThrow<E> { }` |
| null 아닌지 | `value.shouldNotBeNull()` |

---

## 9. 더 알아보기

- [Kotest 공식 문서](https://kotest.io/docs/framework/framework.html)
- [MockK 공식 문서](https://mockk.io/)
- [Testcontainers 공식 문서](https://www.testcontainers.org/)
