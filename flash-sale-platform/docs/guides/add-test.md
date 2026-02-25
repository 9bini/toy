# 테스트 작성 가이드

> 이 프로젝트에서 사용하는 3가지 테스트 유형을 각각 예제와 함께 설명

---

## 목차

1. [테스트 전략 개요](#1-테스트-전략-개요)
2. [단위 테스트 (Kotest + MockK)](#2-단위-테스트-kotest--mockk)
3. [통합 테스트 (Testcontainers)](#3-통합-테스트-testcontainers)
4. [E2E 테스트 (WebTestClient)](#4-e2e-테스트-webtestclient)
5. [테스트 네이밍/구조 규칙](#5-테스트-네이밍구조-규칙)
6. [자주 쓰는 패턴 모음](#6-자주-쓰는-패턴-모음)
7. [실행 명령어](#7-실행-명령어)

---

## 1. 테스트 전략 개요

| 유형 | 도구 | 대상 | 속도 | Docker 필요 |
|------|------|------|------|------------|
| **단위 테스트** | Kotest + MockK | UseCase, 도메인 로직 | 빠름 (~ms) | 불필요 |
| **통합 테스트** | Kotest + Testcontainers | Adapter (Redis/Kafka/DB) | 중간 (~s) | 필요 |
| **E2E 테스트** | WebTestClient | Controller → 전체 흐름 | 느림 | 필요 |

### 테스트 피라미드

```
        ┌────────────┐
        │  E2E 테스트  │  ← 적게 (핵심 흐름만)
        ├────────────┤
        │ 통합 테스트   │  ← 중간 (어댑터별)
        ├────────────┤
        │ 단위 테스트   │  ← 많이 (비즈니스 로직)
        └────────────┘
```

---

## 2. 단위 테스트 (Kotest + MockK)

### 대상: UseCase (비즈니스 로직)

외부 의존성(Redis, DB, Kafka)은 MockK로 모킹한다.

### 예제: PlaceOrderService 단위 테스트

```kotlin
package com.flashsale.order.application.service

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.domain.Result
import com.flashsale.order.application.port.`in`.PlaceOrderUseCase
import com.flashsale.order.application.port.out.OrderPersistencePort
import com.flashsale.order.application.port.out.StockPort
import com.flashsale.order.domain.error.OrderError
import com.flashsale.order.domain.model.OrderStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class PlaceOrderServiceTest : FunSpec({
    // === Mock 준비 ===
    val stockPort = mockk<StockPort>()
    val orderPersistencePort = mockk<OrderPersistencePort>()
    val timeouts = TimeoutProperties()  // 기본값 사용

    // === 테스트 대상 ===
    val service = PlaceOrderService(stockPort, orderPersistencePort, timeouts)

    // === 공통 커맨드 ===
    val command = PlaceOrderUseCase.Command(
        userId = "user-1",
        productId = "prod-1",
        quantity = 1,
        idempotencyKey = "key-1",
    )

    test("재고가 충분하면 주문이 성공한다") {
        // given: 재고 차감 성공 (남은 99개)
        coEvery { stockPort.decrement("prod-1", 1) } returns 99
        coEvery { orderPersistencePort.save(any()) } answers { firstArg() }

        // when
        val result = service.execute(command)

        // then
        result.isSuccess shouldBe true
        val order = (result as Result.Success).value
        order.userId shouldBe "user-1"
        order.productId shouldBe "prod-1"
        order.status shouldBe OrderStatus.CREATED

        // verify: DB 저장이 1번 호출됨
        coVerify(exactly = 1) { orderPersistencePort.save(any()) }
    }

    test("재고가 부족하면 InsufficientStock 에러를 반환한다") {
        // given: 재고 차감 실패 (-1)
        coEvery { stockPort.decrement("prod-1", 1) } returns -1

        // when
        val result = service.execute(command)

        // then
        result.isFailure shouldBe true
        val error = (result as Result.Failure).error
        error.shouldBeInstanceOf<OrderError.InsufficientStock>()
        error.requested shouldBe 1

        // verify: DB 저장이 호출되지 않음
        coVerify(exactly = 0) { orderPersistencePort.save(any()) }
    }
})
```

### MockK 핵심 사용법

```kotlin
// === 모킹 ===

// suspend fun 모킹
coEvery { port.method(any()) } returns value
coEvery { port.method("specific") } returns specificValue
coEvery { port.method(any()) } throws Exception("에러")
coEvery { port.method(any()) } answers { firstArg() }  // 첫 번째 인자 반환
coEvery { port.voidMethod(any()) } just runs  // Unit 반환 함수

// === 검증 ===

coVerify(exactly = 1) { port.method(any()) }      // 정확히 1번 호출
coVerify(exactly = 0) { port.method(any()) }      // 호출 안 됨
coVerify(atLeast = 1) { port.method(any()) }      // 최소 1번
coVerify { port.method("expected-arg") }           // 특정 인자로 호출
```

---

## 3. 통합 테스트 (Testcontainers)

### 대상: Adapter (실제 Redis/Kafka/DB와 통신)

### IntegrationTestBase 사용

```kotlin
// IntegrationTestBase가 자동으로 관리:
// - Redis (7.4-alpine) 컨테이너
// - Kafka (3.8.1 KRaft) 컨테이너
// - PostgreSQL (16-alpine) 컨테이너
// - @DynamicPropertySource로 연결 정보 주입
```

### 예제: RedisStockAdapter 통합 테스트

```kotlin
package com.flashsale.order.adapter.out.redis

import com.flashsale.common.redis.RedisKeys
import com.flashsale.common.test.IntegrationTestBase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

@SpringBootTest
class RedisStockAdapterTest(
    private val adapter: RedisStockAdapter,
    private val redisTemplate: ReactiveStringRedisTemplate,
) : IntegrationTestBase(), FunSpec({

    beforeEach {
        // 테스트 격리: 관련 Redis 키 초기화
        redisTemplate.delete(RedisKeys.Stock.remaining("prod-1")).awaitSingle()
    }

    test("재고 차감 성공 시 남은 수량을 반환한다") {
        // given: 재고 100개 설정
        redisTemplate.opsForValue()
            .set(RedisKeys.Stock.remaining("prod-1"), "100")
            .awaitSingle()

        // when
        val remaining = adapter.decrement("prod-1", 1)

        // then
        remaining shouldBe 99
    }

    test("재고 부족 시 -1을 반환하고 재고를 차감하지 않는다") {
        // given: 재고 0개
        redisTemplate.opsForValue()
            .set(RedisKeys.Stock.remaining("prod-1"), "0")
            .awaitSingle()

        // when
        val remaining = adapter.decrement("prod-1", 1)

        // then
        remaining shouldBe -1

        // 재고가 변경되지 않았는지 확인
        val current = redisTemplate.opsForValue()
            .get(RedisKeys.Stock.remaining("prod-1"))
            .awaitSingle()
        current shouldBe "0"
    }
})
```

### 예제: R2dbcOrderAdapter 통합 테스트

```kotlin
@SpringBootTest
class R2dbcOrderAdapterTest(
    private val adapter: R2dbcOrderAdapter,
) : IntegrationTestBase(), FunSpec({

    test("주문 저장 + 조회") {
        val order = Order.create("user-1", "prod-1", 2)
        adapter.save(order)

        val found = adapter.findById(order.id)

        found shouldNotBe null
        found!!.userId shouldBe "user-1"
        found.quantity shouldBe 2
        found.status shouldBe OrderStatus.CREATED
    }

    test("상태 업데이트") {
        val order = Order.create("user-1", "prod-1", 1)
        adapter.save(order)

        adapter.updateStatus(order.id, OrderStatus.COMPLETED)

        val found = adapter.findById(order.id)
        found!!.status shouldBe OrderStatus.COMPLETED
    }
})
```

---

## 4. E2E 테스트 (WebTestClient)

### 대상: Controller → UseCase → Adapter 전체 흐름

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderControllerE2ETest(
    private val webTestClient: WebTestClient,
    private val redisTemplate: ReactiveStringRedisTemplate,
) : IntegrationTestBase(), FunSpec({

    beforeEach {
        // 재고 설정
        redisTemplate.opsForValue()
            .set(RedisKeys.Stock.remaining("prod-1"), "100")
            .awaitSingle()
    }

    test("POST /api/orders → 201 Created") {
        val request = mapOf(
            "userId" to "user-1",
            "productId" to "prod-1",
            "quantity" to 1,
            "idempotencyKey" to "key-${System.currentTimeMillis()}",
        )

        webTestClient.post()
            .uri("/api/orders")
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.orderId").isNotEmpty
            .jsonPath("$.status").isEqualTo("CREATED")
            .jsonPath("$.productId").isEqualTo("prod-1")
    }

    test("재고 부족 시 409 Conflict") {
        // 재고 0으로 설정
        redisTemplate.opsForValue()
            .set(RedisKeys.Stock.remaining("prod-1"), "0")
            .awaitSingle()

        val request = mapOf(
            "userId" to "user-1",
            "productId" to "prod-1",
            "quantity" to 1,
            "idempotencyKey" to "key-${System.currentTimeMillis()}",
        )

        webTestClient.post()
            .uri("/api/orders")
            .bodyValue(request)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error").isEqualTo("재고 부족")
    }
})
```

---

## 5. 테스트 네이밍/구조 규칙

### 파일 위치

```
src/test/kotlin/com/flashsale/{service}/
├── application/service/
│   └── PlaceOrderServiceTest.kt        ← 단위 테스트
├── adapter/out/redis/
│   └── RedisStockAdapterTest.kt        ← 통합 테스트
├── adapter/out/persistence/
│   └── R2dbcOrderAdapterTest.kt        ← 통합 테스트
└── adapter/in/web/
    └── OrderControllerE2ETest.kt       ← E2E 테스트
```

### 테스트명 규칙

```kotlin
// ✅ 한국어로 행동을 설명
test("재고가 충분하면 주문이 성공한다")
test("재고가 부족하면 InsufficientStock 에러를 반환한다")
test("중복 이벤트를 수신하면 무시한다")

// ❌ 메서드명만 나열
test("testPlaceOrder")
test("execute success")
```

### Kotest 스타일 선택

```kotlin
// FunSpec (추천 — 간단하고 직관적)
class OrderServiceTest : FunSpec({
    test("테스트명") { ... }
})

// BehaviorSpec (시나리오 기반)
class OrderServiceTest : BehaviorSpec({
    given("재고가 100개일 때") {
        `when`("1개 주문하면") {
            then("주문이 성공한다") { ... }
        }
    }
})
```

---

## 6. 자주 쓰는 패턴 모음

### Assertion

```kotlin
// 기본 비교
value shouldBe expected
value shouldNotBe null

// 타입 확인
result.shouldBeInstanceOf<Result.Success<Order>>()
error.shouldBeInstanceOf<OrderError.InsufficientStock>()

// 컬렉션
list shouldHaveSize 3
list shouldContain element
list.shouldBeEmpty()

// 예외
shouldThrow<IllegalArgumentException> {
    riskyOperation()
}

// 범위
value shouldBeGreaterThan 0
value shouldBeInRange 1..100
```

### 비동기 대기 (eventually)

```kotlin
// Kafka 메시지 처리를 기다릴 때
eventually(5.seconds) {
    val result = repository.findById(orderId)
    result shouldNotBe null
    result!!.status shouldBe OrderStatus.COMPLETED
}
```

### 테스트 격리 (beforeEach)

```kotlin
beforeEach {
    // Redis 초기화
    redisTemplate.delete(RedisKeys.Stock.remaining("prod-1")).awaitSingle()

    // DB 초기화 (필요 시)
    orderRepository.deleteAll()
}
```

### MockK 고급 패턴

```kotlin
// 순차적 반환 (호출할 때마다 다른 값)
coEvery { port.method(any()) } returnsMany listOf(value1, value2, value3)

// 인자 캡처
val slot = slot<Order>()
coEvery { port.save(capture(slot)) } answers { firstArg() }
// 검증
slot.captured.userId shouldBe "user-1"

// any() 제약 조건
coEvery { port.method(match { it.startsWith("user-") }) } returns value
```

---

## 7. 실행 명령어

```bash
# 전체 테스트
./gradlew test

# 특정 서비스 테스트
./gradlew :services:order-service:test

# 특정 테스트 클래스
./gradlew :services:order-service:test --tests "*.PlaceOrderServiceTest"

# 특정 테스트 메서드 (Kotest는 클래스 단위만 필터 가능)
./gradlew :services:order-service:test --tests "*.RedisStockAdapterTest"

# 테스트 리포트 확인
# build/reports/tests/test/index.html
```

### 주의: 통합 테스트는 Docker 필요

```
통합 테스트 실행 전 확인:
1. Docker Desktop이 실행 중인가?
2. Testcontainers가 Docker에 접근할 수 있는가?

에러: "Could not find a valid Docker environment"
→ Docker Desktop을 시작하세요
```
