# 새 API 엔드포인트 추가 가이드

> order-service의 **"주문 생성 API"**를 예제로 Step-by-Step 따라하기

---

## 목차

1. [전체 흐름 미리보기](#1-전체-흐름-미리보기)
2. [Step 1: 도메인 모델 정의](#step-1-도메인-모델-정의)
3. [Step 2: 아웃바운드 포트 정의](#step-2-아웃바운드-포트-정의)
4. [Step 3: 인바운드 포트 정의](#step-3-인바운드-포트-정의-usecase)
5. [Step 4: 유스케이스 구현](#step-4-유스케이스-구현)
6. [Step 5: 어댑터 구현](#step-5-어댑터-구현)
7. [Step 6: 컨트롤러](#step-6-컨트롤러)
8. [Step 7: DB 마이그레이션](#step-7-db-마이그레이션)
9. [Step 8: 테스트](#step-8-테스트)
10. [체크리스트](#체크리스트)

---

## 1. 전체 흐름 미리보기

```
생성할 파일:
services/order-service/src/main/kotlin/com/flashsale/order/
├── domain/
│   ├── model/Order.kt                              ← Step 1
│   └── error/OrderError.kt                         ← Step 1
├── application/
│   ├── port/out/StockPort.kt                       ← Step 2
│   ├── port/out/OrderPersistencePort.kt            ← Step 2
│   ├── port/in/PlaceOrderUseCase.kt                ← Step 3
│   └── service/PlaceOrderService.kt                ← Step 4
├── adapter/
│   ├── out/redis/RedisStockAdapter.kt              ← Step 5
│   ├── out/persistence/
│   │   ├── OrderEntity.kt                          ← Step 5
│   │   ├── OrderRepository.kt                      ← Step 5
│   │   └── R2dbcOrderAdapter.kt                    ← Step 5
│   └── in/web/
│       ├── OrderController.kt                      ← Step 6
│       ├── PlaceOrderRequest.kt                    ← Step 6
│       └── OrderResponse.kt                        ← Step 6
└── src/main/resources/db/migration/
    └── V1__create_orders_table.sql                 ← Step 7
```

---

## Step 1: 도메인 모델 정의

> 규칙: 외부 의존성 없는 순수 Kotlin. Spring 어노테이션 금지.

### domain/model/Order.kt

```kotlin
package com.flashsale.order.domain.model

import com.flashsale.common.domain.IdGenerator
import java.time.Instant

data class Order(
    val id: String,
    val userId: String,
    val productId: String,
    val quantity: Int,
    val status: OrderStatus,
    val createdAt: Instant,
) {
    companion object {
        /** 팩토리 메서드: 새 주문 생성 */
        fun create(
            userId: String,
            productId: String,
            quantity: Int,
        ) = Order(
            id = IdGenerator.generate(),
            userId = userId,
            productId = productId,
            quantity = quantity,
            status = OrderStatus.CREATED,
            createdAt = Instant.now(),
        )
    }
}

enum class OrderStatus {
    CREATED,          // 생성됨
    STOCK_RESERVED,   // 재고 확보됨
    PAYMENT_PENDING,  // 결제 대기
    COMPLETED,        // 완료
    CANCELLED,        // 취소됨
}
```

### domain/error/OrderError.kt

```kotlin
package com.flashsale.order.domain.error

/** 주문 처리 중 발생할 수 있는 에러 타입 */
sealed interface OrderError {
    /** 상품을 찾을 수 없을 때 */
    data class ProductNotFound(val productId: String) : OrderError

    /** 상품 재고가 요청 수량보다 부족할 때 */
    data class InsufficientStock(val available: Int, val requested: Int) : OrderError

    /** 분산 락 획득 실패 (동시 요청 과다) */
    data class LockAcquisitionFailed(val productId: String) : OrderError

    /** 중복 주문 (멱등성 키 충돌) */
    data class DuplicateOrder(val idempotencyKey: String) : OrderError
}
```

---

## Step 2: 아웃바운드 포트 정의

> 규칙: 기술 세부사항(Redis, DB)을 노출하지 않는 인터페이스.

### application/port/out/StockPort.kt

```kotlin
package com.flashsale.order.application.port.out

/** 재고 관리 (기술 세부사항 노출 금지 — Redis인지, DB인지 모름) */
interface StockPort {
    /** 현재 재고 조회. null이면 상품 없음 */
    suspend fun getRemaining(productId: String): Int?

    /** 재고 원자적 차감. 성공 시 남은 수량, 실패 시 -1 */
    suspend fun decrement(productId: String, quantity: Int): Long
}
```

### application/port/out/OrderPersistencePort.kt

```kotlin
package com.flashsale.order.application.port.out

import com.flashsale.order.domain.model.Order

/** 주문 영속화 (기술 세부사항 노출 금지 — R2DBC인지, JPA인지 모름) */
interface OrderPersistencePort {
    suspend fun save(order: Order): Order
    suspend fun findById(orderId: String): Order?
    suspend fun updateStatus(orderId: String, status: com.flashsale.order.domain.model.OrderStatus)
}
```

---

## Step 3: 인바운드 포트 정의 (UseCase)

> 규칙: 입력(Command)과 출력(Result) 타입을 함께 정의.

### application/port/in/PlaceOrderUseCase.kt

```kotlin
package com.flashsale.order.application.port.`in`

import com.flashsale.common.domain.Result
import com.flashsale.order.domain.error.OrderError
import com.flashsale.order.domain.model.Order

interface PlaceOrderUseCase {
    /** 주문 생성 커맨드 */
    data class Command(
        val userId: String,
        val productId: String,
        val quantity: Int,
        val idempotencyKey: String,
    )

    suspend fun execute(command: Command): Result<Order, OrderError>
}
```

---

## Step 4: 유스케이스 구현

> 규칙: Port만 의존, Result<T,E> 반환, withTimeout 적용.

### application/service/PlaceOrderService.kt

```kotlin
package com.flashsale.order.application.service

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.domain.Result
import com.flashsale.common.logging.Log
import com.flashsale.common.logging.MdcKeys
import com.flashsale.common.logging.withMdc
import com.flashsale.order.application.port.`in`.PlaceOrderUseCase
import com.flashsale.order.application.port.out.OrderPersistencePort
import com.flashsale.order.application.port.out.StockPort
import com.flashsale.order.domain.error.OrderError
import com.flashsale.order.domain.model.Order
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service

@Service
class PlaceOrderService(
    private val stockPort: StockPort,
    private val orderPersistencePort: OrderPersistencePort,
    private val timeouts: TimeoutProperties,
) : PlaceOrderUseCase {
    companion object : Log

    override suspend fun execute(
        command: PlaceOrderUseCase.Command,
    ): Result<Order, OrderError> =
        withMdc(MdcKeys.USER_ID, command.userId, MdcKeys.PRODUCT_ID, command.productId) {
            logger.info { "주문 생성 시작: productId=${command.productId}, qty=${command.quantity}" }

            // 1. 재고 확인 + 차감 (Redis Lua Script — 원자적)
            val remaining = withTimeout(timeouts.redisLuaScript) {
                stockPort.decrement(command.productId, command.quantity)
            }

            if (remaining < 0) {
                logger.warn { "재고 부족: productId=${command.productId}" }
                return@withMdc Result.failure(
                    OrderError.InsufficientStock(available = 0, requested = command.quantity),
                )
            }

            // 2. 주문 생성 + DB 저장
            val order = Order.create(
                userId = command.userId,
                productId = command.productId,
                quantity = command.quantity,
            )

            val savedOrder = withTimeout(timeouts.dbQuery) {
                orderPersistencePort.save(order)
            }

            logger.info { "주문 생성 완료: orderId=${savedOrder.id}" }
            Result.success(savedOrder)
        }
}
```

---

## Step 5: 어댑터 구현

### adapter/out/redis/RedisStockAdapter.kt

```kotlin
package com.flashsale.order.adapter.out.redis

import com.flashsale.common.redis.RedisKeys
import com.flashsale.order.application.port.out.StockPort
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

@Component
class RedisStockAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) : StockPort {

    // Lua Script: 재고 조회 + 비교 + 차감을 원자적으로 수행
    private val decrementScript = RedisScript.of<Long>(
        """
        local remaining = tonumber(redis.call('GET', KEYS[1]) or '0')
        if remaining >= tonumber(ARGV[1]) then
            redis.call('DECRBY', KEYS[1], ARGV[1])
            return remaining - tonumber(ARGV[1])
        else
            return -1
        end
        """.trimIndent(),
        Long::class.java,
    )

    override suspend fun getRemaining(productId: String): Int? =
        redisTemplate.opsForValue()
            .get(RedisKeys.Stock.remaining(productId))
            .awaitSingleOrNull()
            ?.toInt()

    override suspend fun decrement(productId: String, quantity: Int): Long =
        redisTemplate.execute(
            decrementScript,
            listOf(RedisKeys.Stock.remaining(productId)),
            listOf(quantity.toString()),
        ).awaitSingle()
}
```

### adapter/out/persistence/OrderEntity.kt

```kotlin
package com.flashsale.order.adapter.out.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("orders")
data class OrderEntity(
    @Id val id: String,
    val userId: String,
    val productId: String,
    val quantity: Int,
    val status: String,
    val createdAt: Instant,
)
```

### adapter/out/persistence/OrderRepository.kt

```kotlin
package com.flashsale.order.adapter.out.persistence

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface OrderRepository : CoroutineCrudRepository<OrderEntity, String> {
    @Modifying
    @Query("UPDATE orders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String): Int
}
```

### adapter/out/persistence/R2dbcOrderAdapter.kt

```kotlin
package com.flashsale.order.adapter.out.persistence

import com.flashsale.order.application.port.out.OrderPersistencePort
import com.flashsale.order.domain.model.Order
import com.flashsale.order.domain.model.OrderStatus
import org.springframework.stereotype.Component

@Component
class R2dbcOrderAdapter(
    private val orderRepository: OrderRepository,
) : OrderPersistencePort {

    override suspend fun save(order: Order): Order {
        val entity = OrderEntity(
            id = order.id,
            userId = order.userId,
            productId = order.productId,
            quantity = order.quantity,
            status = order.status.name,
            createdAt = order.createdAt,
        )
        orderRepository.save(entity)
        return order
    }

    override suspend fun findById(orderId: String): Order? =
        orderRepository.findById(orderId)?.toDomain()

    override suspend fun updateStatus(orderId: String, status: OrderStatus) {
        orderRepository.updateStatus(orderId, status.name)
    }

    private fun OrderEntity.toDomain() = Order(
        id = id,
        userId = userId,
        productId = productId,
        quantity = quantity,
        status = OrderStatus.valueOf(status),
        createdAt = createdAt,
    )
}
```

---

## Step 6: 컨트롤러

### adapter/in/web/PlaceOrderRequest.kt

```kotlin
package com.flashsale.order.adapter.`in`.web

data class PlaceOrderRequest(
    val userId: String,
    val productId: String,
    val quantity: Int,
    val idempotencyKey: String,
)
```

### adapter/in/web/OrderResponse.kt

```kotlin
package com.flashsale.order.adapter.`in`.web

import java.time.Instant

data class OrderResponse(
    val orderId: String,
    val status: String,
    val productId: String,
    val quantity: Int,
    val createdAt: Instant,
)
```

### adapter/in/web/OrderController.kt

```kotlin
package com.flashsale.order.adapter.`in`.web

import com.flashsale.common.domain.fold
import com.flashsale.order.application.port.`in`.PlaceOrderUseCase
import com.flashsale.order.domain.error.OrderError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val placeOrderUseCase: PlaceOrderUseCase,
) {
    @PostMapping
    suspend fun placeOrder(
        @RequestBody request: PlaceOrderRequest,
    ): ResponseEntity<Any> {
        val command = PlaceOrderUseCase.Command(
            userId = request.userId,
            productId = request.productId,
            quantity = request.quantity,
            idempotencyKey = request.idempotencyKey,
        )

        return placeOrderUseCase.execute(command).fold(
            onSuccess = { order ->
                ResponseEntity.status(HttpStatus.CREATED).body(
                    OrderResponse(
                        orderId = order.id,
                        status = order.status.name,
                        productId = order.productId,
                        quantity = order.quantity,
                        createdAt = order.createdAt,
                    ),
                )
            },
            onFailure = { error ->
                when (error) {
                    is OrderError.InsufficientStock ->
                        ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(mapOf("error" to "재고 부족", "available" to error.available))

                    is OrderError.ProductNotFound ->
                        ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(mapOf("error" to "상품 없음"))

                    is OrderError.LockAcquisitionFailed ->
                        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(mapOf("error" to "잠시 후 재시도"))

                    is OrderError.DuplicateOrder ->
                        ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(mapOf("error" to "중복 주문"))
                }
            },
        )
    }
}
```

### 에러 → HTTP 상태코드 매핑 참조

| 에러 타입 | HTTP 상태코드 | 설명 |
|----------|-------------|------|
| 정상 | 201 Created | 리소스 생성 성공 |
| 리소스 없음 | 404 Not Found | 상품/주문 없음 |
| 비즈니스 충돌 | 409 Conflict | 재고 부족, 중복 주문 |
| 서비스 불가 | 503 Service Unavailable | 락 실패, 서킷 오픈 |
| 요청 오류 | 400 Bad Request | 잘못된 요청 파라미터 |

---

## Step 7: DB 마이그레이션

### src/main/resources/db/migration/V1__create_orders_table.sql

```sql
CREATE TABLE orders (
    id          VARCHAR(26)  PRIMARY KEY,  -- ULID (26자)
    user_id     VARCHAR(26)  NOT NULL,
    product_id  VARCHAR(26)  NOT NULL,
    quantity    INTEGER      NOT NULL CHECK (quantity > 0),
    status      VARCHAR(20)  NOT NULL DEFAULT 'CREATED',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- 조회 성능을 위한 인덱스
    CONSTRAINT orders_status_check CHECK (
        status IN ('CREATED', 'STOCK_RESERVED', 'PAYMENT_PENDING', 'COMPLETED', 'CANCELLED')
    )
);

-- 사용자별 주문 조회
CREATE INDEX idx_orders_user_id ON orders (user_id);

-- 상태별 조회 (관리자용)
CREATE INDEX idx_orders_status ON orders (status);

-- 생성일시 역순 (최신 주문 조회)
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);
```

### Flyway 파일 네이밍 규칙

```
V{버전}__{설명}.sql

예시:
V1__create_orders_table.sql      ← 첫 번째 마이그레이션
V2__add_payment_id_column.sql    ← 두 번째
V3__create_payments_table.sql    ← 세 번째

주의:
- V 뒤 숫자는 순차적이어야 함
- __ (언더스코어 2개) 필수
- 한 번 적용된 파일은 절대 수정 금지
```

---

## Step 8: 테스트

### 단위 테스트: PlaceOrderService

```kotlin
class PlaceOrderServiceTest : FunSpec({
    val stockPort = mockk<StockPort>()
    val orderPersistencePort = mockk<OrderPersistencePort>()
    val timeouts = TimeoutProperties()
    val service = PlaceOrderService(stockPort, orderPersistencePort, timeouts)

    test("재고가 충분하면 주문이 생성된다") {
        // given
        coEvery { stockPort.decrement("prod-1", 1) } returns 99
        coEvery { orderPersistencePort.save(any()) } answers { firstArg() }

        // when
        val result = service.execute(
            PlaceOrderUseCase.Command("user-1", "prod-1", 1, "key-1"),
        )

        // then
        result.isSuccess shouldBe true
        coVerify(exactly = 1) { orderPersistencePort.save(any()) }
    }

    test("재고가 부족하면 InsufficientStock 에러를 반환한다") {
        coEvery { stockPort.decrement("prod-1", 1) } returns -1

        val result = service.execute(
            PlaceOrderUseCase.Command("user-1", "prod-1", 1, "key-1"),
        )

        result.isFailure shouldBe true
        (result as Result.Failure).error shouldBe OrderError.InsufficientStock(0, 1)
    }
})
```

### 통합 테스트: RedisStockAdapter

```kotlin
@SpringBootTest
class RedisStockAdapterTest : IntegrationTestBase(), FunSpec({
    val adapter = autowired<RedisStockAdapter>()
    val redisTemplate = autowired<ReactiveStringRedisTemplate>()

    beforeEach {
        // 테스트 전 Redis 초기화
        redisTemplate.delete(RedisKeys.Stock.remaining("prod-1")).awaitSingle()
    }

    test("재고가 충분하면 차감 후 남은 수량을 반환한다") {
        // given: 재고 100개 설정
        redisTemplate.opsForValue()
            .set(RedisKeys.Stock.remaining("prod-1"), "100")
            .awaitSingle()

        // when
        val remaining = adapter.decrement("prod-1", 1)

        // then
        remaining shouldBe 99
    }

    test("재고가 부족하면 -1을 반환한다") {
        redisTemplate.opsForValue()
            .set(RedisKeys.Stock.remaining("prod-1"), "0")
            .awaitSingle()

        val remaining = adapter.decrement("prod-1", 1)

        remaining shouldBe -1
    }
})
```

---

## 체크리스트

```bash
# 1. 코드 스타일 검사
./gradlew :services:order-service:ktlintCheck

# 2. 컴파일
./gradlew :services:order-service:compileKotlin

# 3. 테스트 실행 (docker compose 필요)
./gradlew :services:order-service:test

# 4. 전체 빌드
./gradlew :services:order-service:build
```

### 자가 점검

- [ ] domain에 Spring 어노테이션이 없는가?
- [ ] Port 인터페이스에 기술 세부사항(Redis, R2DBC)이 노출되지 않는가?
- [ ] 모든 suspend fun에 withTimeout이 적용되었는가?
- [ ] sealed interface로 에러 타입이 정의되었는가?
- [ ] Result<T, E>로 성공/실패가 처리되는가?
- [ ] Flyway 마이그레이션 파일이 올바른 경로에 있는가?
- [ ] 단위 테스트 + 통합 테스트가 모두 작성되었는가?
