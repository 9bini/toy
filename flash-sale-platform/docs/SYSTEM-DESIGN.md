# Flash Sale Platform - System Design Document

## 1. 시스템 아키텍처 개요 (System Architecture Overview)

### 1.1 시스템 목표
- 100K 동시 접속, 1,000개 한정 상품 선착순 구매 시스템
- 데이터 정합성 보장 (재고 초과판매 방지)
- 고가용성 및 장애 격리

### 1.2 기술 스택
| 영역 | 기술 | 용도 |
|------|------|------|
| Language | Kotlin 1.9+ | 코루틴 기반 비동기 처리 |
| Framework | Spring Boot 3.x + WebFlux | Reactive 웹 서버 |
| Cache/Queue | Redis 7.4 | 대기열, 재고, Rate Limiting, 분산 락 |
| Messaging | Kafka 3.8 (KRaft) | 서비스 간 이벤트 스트리밍 |
| Database | PostgreSQL 16 | 주문/결제 영속 데이터 (R2DBC) |
| Proxy | Nginx 1.27 | L7 리버스 프록시, Rate Limiting |
| Monitoring | Prometheus + Grafana | 메트릭 수집 및 대시보드 |

### 1.3 서비스 구성도

```
                         ┌─────────────────────────────────────────────┐
                         │                  Client                     │
                         └──────────────────┬──────────────────────────┘
                                            │
                                            ▼
                         ┌─────────────────────────────────────────────┐
                         │           Nginx (L7 Reverse Proxy)          │
                         │  • Rate Limiting (IP-based)                 │
                         │  • Load Balancing (upstream)                │
                         │  • SSE Proxy (long-lived connections)       │
                         └──────────────────┬──────────────────────────┘
                                            │
                                            ▼
                         ┌─────────────────────────────────────────────┐
                         │         Gateway Service (:8080)             │
                         │  • Rate Limiting (Token Bucket, Redis Lua)  │
                         │  • API Routing (WebClient)                  │
                         │  • Request Validation                       │
                         └──────────────────┬──────────────────────────┘
                                            │
              ┌─────────────────────────────┼───────────────────────────┐
              │                             │                           │
              ▼                             ▼                           ▼
┌──────────────────────┐  ┌──────────────────────┐  ┌───────────────────────┐
│  Queue Service       │  │  Order Service       │  │  Notification Service │
│  (:8081)             │  │  (:8082)             │  │  (:8084)              │
│  • 대기열 진입/조회  │  │  • 재고 차감 (Lua)   │  │  • SSE 실시간 알림    │
│  • Redis Sorted Set  │  │  • 주문 생성 (R2DBC) │  │  • Kafka Consumer     │
│  [구현 완료 ✅]       │  │  • Kafka Producer    │  │                       │
└──────────────────────┘  └──────────┬───────────┘  └───────────────────────┘
                                     │
                                     ▼ Kafka: order.placed
                          ┌──────────────────────┐
                          │  Payment Service     │
                          │  (:8083)             │
                          │  • 결제 처리          │
                          │  • Saga 보상 트랜잭션 │
                          │  • Kafka Consumer    │
                          └──────────────────────┘
```

### 1.4 전체 요청 흐름 (Happy Path)

```
1. Client → Nginx → Gateway
   │  Rate Limiting 통과 확인 (Nginx L7 + Gateway Token Bucket)
   │
2. Gateway → Queue Service: POST /api/queues/{saleEventId}/enter
   │  대기열 진입 (Redis Sorted Set, score = server timestamp)
   │  응답: 대기 순번 (position)
   │
3. Client ← SSE: 순번 도달 알림 (Notification Service → Client)
   │
4. Client → Gateway → Order Service: POST /api/orders
   │  ① Redis Lua Script로 재고 원자적 차감
   │  ② PostgreSQL에 주문 저장 (R2DBC)
   │  ③ Kafka로 OrderPlacedEvent 발행
   │
5. Kafka: order.placed → Payment Service
   │  ① 결제 처리 (외부 API 호출 시뮬레이션)
   │  ② 성공 → Kafka: payment.completed
   │  ③ 실패 → Kafka: payment.failed → 보상 트랜잭션 (재고 복원 + 주문 취소)
   │
6. Kafka: payment.completed → Order Service
   │  주문 상태 → COMPLETED 업데이트
   │
7. Kafka: payment.completed → Notification Service
   │  SSE로 결제 완료 알림 전송
```

### 1.5 이벤트 흐름 (Kafka Event Flow)

```
┌──────────────┐     order.placed      ┌──────────────────┐
│ Order Service│ ──────────────────────▶│ Payment Service  │
│              │                        │                  │
│              │◀─ payment.completed ───│                  │
│              │◀─ payment.failed ──────│                  │
└──────┬───────┘                        └──────────────────┘
       │                                        │
       │  order.completed                       │ payment.completed
       │  order.cancelled                       │ payment.failed
       ▼                                        ▼
┌──────────────────────────────────────────────────────────┐
│                  Notification Service                     │
│  • order.completed  → "주문 완료" SSE 전송               │
│  • order.cancelled  → "주문 취소" SSE 전송               │
│  • payment.completed → "결제 완료" SSE 전송              │
│  • payment.failed   → "결제 실패" SSE 전송               │
│  • notification.send-requested → 범용 알림 전송          │
└──────────────────────────────────────────────────────────┘
```

### 1.6 Saga 패턴 (Order-Payment 보상 트랜잭션)

```
[Happy Path]
Order Service                          Payment Service
    │                                       │
    ├── 재고 차감 (Redis Lua)               │
    ├── 주문 생성 (status=PENDING)          │
    ├── Kafka: order.placed ──────────────▶ │
    │                                       ├── 결제 처리
    │                                       ├── 결제 저장 (status=COMPLETED)
    │   ◀── Kafka: payment.completed ───────┤
    ├── 주문 업데이트 (status=COMPLETED)    │
    │                                       │

[Compensation Path - 결제 실패]
Order Service                          Payment Service
    │                                       │
    ├── 재고 차감 (Redis Lua)               │
    ├── 주문 생성 (status=PENDING)          │
    ├── Kafka: order.placed ──────────────▶ │
    │                                       ├── 결제 처리 실패
    │                                       ├── 결제 저장 (status=FAILED)
    │   ◀── Kafka: payment.failed ─────────┤
    ├── 주문 업데이트 (status=CANCELLED)    │
    ├── 재고 복원 (Redis Lua)               │
    ├── Kafka: stock.restored              │
    │                                       │
```

### 1.7 서비스 간 통신 방식

| 호출 방향 | 방식 | 이유 |
|-----------|------|------|
| Gateway → Queue/Order | **동기** (WebClient) | 클라이언트 응답이 필요 |
| Order → Payment | **비동기** (Kafka) | 결제는 비동기 처리, Saga 보상 지원 |
| Payment → Order | **비동기** (Kafka) | 결과 콜백, 느슨한 결합 |
| \* → Notification | **비동기** (Kafka) | Fire-and-forget 알림 |
| Notification → Client | **SSE** (Server-Sent Events) | 실시간 푸시 |

---

## 2. Gateway Service 상세 설계

### 2.1 역할
- **Rate Limiting**: Redis Token Bucket 알고리즘 (Lua Script)
- **API Routing**: 하위 서비스로 요청 라우팅 (WebClient)
- **Request Validation**: 공통 입력 검증

### 2.2 Domain Model

```kotlin
// domain/RateLimitResult.kt
data class RateLimitResult(
    val allowed: Boolean,
    val remainingTokens: Long,
    val retryAfterMs: Long,
)

// domain/GatewayError.kt
sealed interface GatewayError {
    /** Token Bucket에 토큰이 부족하여 요청이 거부됨 */
    data class RateLimitExceeded(
        val clientId: String,
        val retryAfterMs: Long,
    ) : GatewayError

    /** 하위 서비스 호출 시 타임아웃 발생 */
    data class ServiceTimeout(
        val serviceName: String,
        val timeoutMs: Long,
    ) : GatewayError

    /** 하위 서비스가 비정상 응답 반환 */
    data class ServiceUnavailable(
        val serviceName: String,
        val statusCode: Int,
    ) : GatewayError
}
```

### 2.3 Port 설계

**Port In (UseCase)**:
```kotlin
// application/port/in/CheckRateLimitUseCase.kt
interface CheckRateLimitUseCase {
    /** Rate Limit 확인. 허용이면 Success, 초과면 Failure */
    suspend fun execute(command: RateLimitCommand): Result<RateLimitResult, GatewayError>
}

data class RateLimitCommand(
    val clientId: String,  // IP + User-Agent hash
    val requestPath: String,
)
```

**Port Out**:
```kotlin
// application/port/out/RateLimitPort.kt
interface RateLimitPort {
    /** Token Bucket에서 토큰 1개 소비 시도 */
    suspend fun tryConsume(clientId: String): RateLimitResult
}
```

### 2.4 Adapter 설계

**Adapter Out - Redis Token Bucket (Lua Script)**:
```kotlin
// adapter/out/redis/RedisRateLimitAdapter.kt
@Component
class RedisRateLimitAdapter(
    private val redisScriptExecutor: RedisScriptExecutor,
    private val timeouts: TimeoutProperties,
) : RateLimitPort
```

**Token Bucket Lua Script**:
```lua
-- KEYS[1]: ratelimit:bucket:{clientId}
-- ARGV[1]: max_tokens (bucket capacity)
-- ARGV[2]: refill_rate (tokens per second)
-- ARGV[3]: current_time_ms
local key = KEYS[1]
local max_tokens = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

if tokens == nil then
    tokens = max_tokens
    last_refill = now
end

-- Refill tokens based on elapsed time
local elapsed = (now - last_refill) / 1000.0
local new_tokens = math.min(max_tokens, tokens + elapsed * refill_rate)

if new_tokens >= 1 then
    new_tokens = new_tokens - 1
    redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
    redis.call('EXPIRE', key, math.ceil(max_tokens / refill_rate) + 1)
    return {1, math.floor(new_tokens), 0}  -- allowed, remaining, retry_after
else
    local retry_after = math.ceil((1 - new_tokens) / refill_rate * 1000)
    redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
    return {0, 0, retry_after}  -- denied, 0 remaining, retry_after_ms
end
```

**Adapter In - Gateway Controller**:
```kotlin
// adapter/in/web/GatewayController.kt
@RestController
@RequestMapping("/api")
class GatewayController(
    private val checkRateLimitUseCase: CheckRateLimitUseCase,
    private val webClient: WebClient,
)
```

### 2.5 API 엔드포인트

| Method | Path | 대상 서비스 | 설명 |
|--------|------|------------|------|
| POST | `/api/queues/{saleEventId}/enter` | queue-service | 대기열 진입 |
| GET | `/api/queues/{saleEventId}/position` | queue-service | 대기 순번 조회 |
| POST | `/api/orders` | order-service | 주문 생성 |
| GET | `/api/orders/{orderId}` | order-service | 주문 조회 |
| GET | `/api/notifications/stream` | notification-service | SSE 스트림 |

### 2.6 Rate Limiting 설정

| 파라미터 | 값 | 설명 |
|---------|------|------|
| max_tokens | 10 | Bucket 용량 |
| refill_rate | 5/s | 초당 토큰 충전량 |
| 식별자 | IP + User-Agent hash | 복합 식별 (bypass 방지) |

---

## 3. Order Service 상세 설계

### 3.1 역할
- **재고 차감**: Redis Lua Script로 원자적 차감
- **주문 생성**: PostgreSQL에 주문 영속화 (R2DBC)
- **이벤트 발행**: Kafka로 order.placed 이벤트
- **Saga 보상**: payment.failed 수신 시 재고 복원 + 주문 취소

### 3.2 Domain Model

```kotlin
// domain/Order.kt
data class Order(
    val id: String,              // ULID (IdGenerator)
    val userId: String,
    val saleEventId: String,
    val productId: String,
    val quantity: Int,
    val totalAmount: Long,       // 원 단위
    val status: OrderStatus,
    val idempotencyKey: String,  // 중복 주문 방지
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun create(
            userId: String,
            saleEventId: String,
            productId: String,
            quantity: Int,
            unitPrice: Long,
            idempotencyKey: String,
        ): Order = Order(
            id = IdGenerator.generate(),
            userId = userId,
            saleEventId = saleEventId,
            productId = productId,
            quantity = quantity,
            totalAmount = unitPrice * quantity,
            status = OrderStatus.PENDING,
            idempotencyKey = idempotencyKey,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }
}

// domain/OrderStatus.kt
enum class OrderStatus {
    PENDING,     // 주문 생성됨, 결제 대기 중
    COMPLETED,   // 결제 완료
    CANCELLED,   // 결제 실패로 취소됨
}

// domain/OrderError.kt
sealed interface OrderError {
    /** 상품 재고가 요청 수량보다 부족 */
    data class InsufficientStock(val available: Int, val requested: Int) : OrderError

    /** 동일 사용자가 동일 세일 이벤트에 중복 주문 */
    data class DuplicateOrder(val userId: String, val saleEventId: String) : OrderError

    /** 주문을 찾을 수 없음 */
    data class OrderNotFound(val orderId: String) : OrderError

    /** 재고 차감 시 분산 락 획득 실패 */
    data class LockAcquisitionFailed(val productId: String) : OrderError

    /** 주문 상태 전이가 허용되지 않음 (예: COMPLETED → PENDING) */
    data class InvalidStatusTransition(
        val orderId: String,
        val currentStatus: OrderStatus,
        val targetStatus: OrderStatus,
    ) : OrderError
}
```

**Domain Events**:
```kotlin
// domain/event/OrderPlacedEvent.kt
data class OrderPlacedEvent(
    override val aggregateId: String,     // orderId
    override val eventType: String = "order.placed",
    override val occurredAt: Instant = Instant.now(),
    override val eventId: String = IdGenerator.generate(),
    val userId: String,
    val productId: String,
    val quantity: Int,
    val totalAmount: Long,
    val saleEventId: String,
) : DomainEvent

// domain/event/OrderCancelledEvent.kt
data class OrderCancelledEvent(
    override val aggregateId: String,     // orderId
    override val eventType: String = "order.cancelled",
    override val occurredAt: Instant = Instant.now(),
    override val eventId: String = IdGenerator.generate(),
    val userId: String,
    val productId: String,
    val quantity: Int,
    val reason: String,
) : DomainEvent

// domain/event/OrderCompletedEvent.kt
data class OrderCompletedEvent(
    override val aggregateId: String,     // orderId
    override val eventType: String = "order.completed",
    override val occurredAt: Instant = Instant.now(),
    override val eventId: String = IdGenerator.generate(),
    val userId: String,
) : DomainEvent
```

### 3.3 Port 설계

**Port In (UseCase)**:
```kotlin
// application/port/in/PlaceOrderUseCase.kt
interface PlaceOrderUseCase {
    suspend fun execute(command: PlaceOrderCommand): Result<OrderResult, OrderError>
}

data class PlaceOrderCommand(
    val userId: String,
    val saleEventId: String,
    val productId: String,
    val quantity: Int,
    val idempotencyKey: String,
)

data class OrderResult(
    val orderId: String,
    val status: OrderStatus,
    val position: Long?,  // 대기열 순번 (optional)
)

// application/port/in/GetOrderUseCase.kt
interface GetOrderUseCase {
    suspend fun execute(query: GetOrderQuery): Result<Order, OrderError>
}

data class GetOrderQuery(
    val orderId: String,
    val userId: String,  // IDOR 방지: 소유자 확인
)
```

**Port Out**:
```kotlin
// application/port/out/StockPort.kt
interface StockPort {
    /** 재고를 원자적으로 차감. 성공 시 남은 재고 반환 */
    suspend fun decrement(productId: String, quantity: Int): Result<Int, OrderError>

    /** 재고 복원 (보상 트랜잭션) */
    suspend fun restore(productId: String, quantity: Int): Boolean
}

// application/port/out/OrderPersistencePort.kt
interface OrderPersistencePort {
    suspend fun save(order: Order): Order
    suspend fun findById(orderId: String): Order?
    suspend fun findByUserIdAndSaleEventId(userId: String, saleEventId: String): Order?
    suspend fun updateStatus(orderId: String, status: OrderStatus): Boolean
}

// application/port/out/OrderEventPort.kt
interface OrderEventPort {
    suspend fun publishOrderPlaced(event: OrderPlacedEvent)
    suspend fun publishOrderCancelled(event: OrderCancelledEvent)
    suspend fun publishOrderCompleted(event: OrderCompletedEvent)
}
```

### 3.4 Adapter 설계

**Adapter Out - Redis Stock (Lua Script)**:
```kotlin
// adapter/out/redis/RedisStockAdapter.kt
@Component
class RedisStockAdapter(
    private val redisScriptExecutor: RedisScriptExecutor,
    private val timeouts: TimeoutProperties,
) : StockPort
```

**재고 차감 Lua Script**:
```lua
-- KEYS[1]: stock:remaining:{productId}
-- ARGV[1]: quantity to decrement
local key = KEYS[1]
local quantity = tonumber(ARGV[1])

local current = tonumber(redis.call('GET', key))
if current == nil then
    return {-1, 0}  -- stock key not found
end

if current < quantity then
    return {0, current}  -- insufficient stock, return current stock
end

local remaining = redis.call('DECRBY', key, quantity)
return {1, remaining}  -- success, remaining stock
```

**재고 복원 Lua Script**:
```lua
-- KEYS[1]: stock:remaining:{productId}
-- ARGV[1]: quantity to restore
local key = KEYS[1]
local quantity = tonumber(ARGV[1])
local remaining = redis.call('INCRBY', key, quantity)
return remaining
```

**Adapter Out - R2DBC Order Persistence**:
```kotlin
// adapter/out/persistence/R2dbcOrderAdapter.kt
@Component
class R2dbcOrderAdapter(
    private val orderRepository: OrderRepository,
    private val timeouts: TimeoutProperties,
) : OrderPersistencePort
```

**Adapter Out - Kafka Event Publisher**:
```kotlin
// adapter/out/kafka/KafkaOrderEventAdapter.kt
@Component
class KafkaOrderEventAdapter(
    private val eventPublisher: EventPublisher,
) : OrderEventPort
```

**Adapter In - Kafka Consumer (Saga 보상)**:
```kotlin
// adapter/in/kafka/PaymentResultConsumer.kt
@Component
class PaymentResultConsumer(
    private val completeOrderUseCase: CompleteOrderUseCase,
    private val cancelOrderUseCase: CancelOrderUseCase,
    private val idempotencyExecutor: IdempotencyExecutor,
)
```

**Adapter In - Controller**:
```kotlin
// adapter/in/web/OrderController.kt
@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val placeOrderUseCase: PlaceOrderUseCase,
    private val getOrderUseCase: GetOrderUseCase,
)
```

### 3.5 API 엔드포인트

| Method | Path | Request | Response | Status |
|--------|------|---------|----------|--------|
| POST | `/api/orders` | `PlaceOrderRequest` | `OrderResponse` | 201 Created |
| GET | `/api/orders/{orderId}` | `userId` (header) | `OrderResponse` | 200 OK |

**Request/Response DTO**:
```kotlin
// adapter/in/web/OrderRequest.kt
data class PlaceOrderRequest(
    @field:NotBlank val userId: String,
    @field:NotBlank val saleEventId: String,
    @field:NotBlank val productId: String,
    @field:Min(1) @field:Max(5) val quantity: Int,
    @field:NotBlank val idempotencyKey: String,
)

// adapter/in/web/OrderResponse.kt
data class OrderResponse(
    val orderId: String,
    val status: String,
    val totalAmount: Long,
    val createdAt: String,
)
```

### 3.6 DB Schema (Flyway Migration)

```sql
-- V1__create_orders_table.sql
CREATE TABLE orders (
    id              VARCHAR(26) PRIMARY KEY,  -- ULID
    user_id         VARCHAR(255) NOT NULL,
    sale_event_id   VARCHAR(255) NOT NULL,
    product_id      VARCHAR(255) NOT NULL,
    quantity        INT NOT NULL CHECK (quantity > 0),
    total_amount    BIGINT NOT NULL CHECK (total_amount >= 0),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_orders_user_sale_event UNIQUE (user_id, sale_event_id)
);

CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_sale_event_id ON orders (sale_event_id);
CREATE INDEX idx_orders_status ON orders (status);
```

### 3.7 핵심 비즈니스 로직 (PlaceOrderService)

```
PlaceOrderService.execute(command):
  1. 멱등성 확인: idempotencyKey로 중복 주문 방지
  2. 중복 주문 확인: userId + saleEventId로 이미 주문했는지 확인
  3. 재고 차감: stockPort.decrement() — Redis Lua Script (atomic)
  4. 주문 생성: orderPersistencePort.save() — PostgreSQL (R2DBC)
  5. 이벤트 발행: orderEventPort.publishOrderPlaced() — Kafka
  6. (실패 시) 재고 복원 + 주문 취소 처리
```

---

## 4. Payment Service 상세 설계

### 4.1 역할
- **결제 처리**: 외부 결제 API 호출 (시뮬레이션)
- **Saga 보상**: 결제 실패 시 payment.failed 이벤트 발행
- **멱등성**: 동일 주문에 대한 중복 결제 방지

### 4.2 Domain Model

```kotlin
// domain/Payment.kt
data class Payment(
    val id: String,            // ULID
    val orderId: String,
    val userId: String,
    val amount: Long,
    val status: PaymentStatus,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun create(orderId: String, userId: String, amount: Long): Payment = Payment(
            id = IdGenerator.generate(),
            orderId = orderId,
            userId = userId,
            amount = amount,
            status = PaymentStatus.PENDING,
            failureReason = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }
}

// domain/PaymentStatus.kt
enum class PaymentStatus {
    PENDING,     // 결제 대기
    PROCESSING,  // 결제 처리 중
    COMPLETED,   // 결제 완료
    FAILED,      // 결제 실패
}

// domain/PaymentError.kt
sealed interface PaymentError {
    /** 동일 주문에 대한 결제가 이미 존재 */
    data class DuplicatePayment(val orderId: String) : PaymentError

    /** 외부 결제 API 호출 시 타임아웃 */
    data class PaymentTimeout(val orderId: String, val timeoutMs: Long) : PaymentError

    /** 외부 결제 API가 결제 거부 (잔액 부족 등) */
    data class PaymentRejected(val orderId: String, val reason: String) : PaymentError

    /** 결제를 찾을 수 없음 */
    data class PaymentNotFound(val paymentId: String) : PaymentError
}
```

**Domain Events**:
```kotlin
// domain/event/PaymentCompletedEvent.kt
data class PaymentCompletedEvent(
    override val aggregateId: String,     // paymentId
    override val eventType: String = "payment.completed",
    override val occurredAt: Instant = Instant.now(),
    override val eventId: String = IdGenerator.generate(),
    val orderId: String,
    val userId: String,
    val amount: Long,
) : DomainEvent

// domain/event/PaymentFailedEvent.kt
data class PaymentFailedEvent(
    override val aggregateId: String,     // paymentId
    override val eventType: String = "payment.failed",
    override val occurredAt: Instant = Instant.now(),
    override val eventId: String = IdGenerator.generate(),
    val orderId: String,
    val userId: String,
    val amount: Long,
    val reason: String,
) : DomainEvent
```

### 4.3 Port 설계

**Port In (UseCase)**:
```kotlin
// application/port/in/ProcessPaymentUseCase.kt
interface ProcessPaymentUseCase {
    suspend fun execute(command: ProcessPaymentCommand): Result<Payment, PaymentError>
}

data class ProcessPaymentCommand(
    val orderId: String,
    val userId: String,
    val amount: Long,
    val eventId: String,  // 멱등성 키로 사용
)
```

**Port Out**:
```kotlin
// application/port/out/PaymentPersistencePort.kt
interface PaymentPersistencePort {
    suspend fun save(payment: Payment): Payment
    suspend fun findByOrderId(orderId: String): Payment?
    suspend fun updateStatus(paymentId: String, status: PaymentStatus, failureReason: String?): Boolean
}

// application/port/out/PaymentGatewayPort.kt
interface PaymentGatewayPort {
    /** 외부 결제 API 호출 (시뮬레이션) */
    suspend fun processPayment(orderId: String, amount: Long): Result<PaymentGatewayResult, PaymentError>
}

data class PaymentGatewayResult(
    val transactionId: String,
    val approved: Boolean,
)

// application/port/out/PaymentEventPort.kt
interface PaymentEventPort {
    suspend fun publishPaymentCompleted(event: PaymentCompletedEvent)
    suspend fun publishPaymentFailed(event: PaymentFailedEvent)
}
```

### 4.4 Adapter 설계

**Adapter In - Kafka Consumer (order.placed 수신)**:
```kotlin
// adapter/in/kafka/OrderPlacedConsumer.kt
@Component
class OrderPlacedConsumer(
    private val processPaymentUseCase: ProcessPaymentUseCase,
    private val idempotencyExecutor: IdempotencyExecutor,
) {
    @KafkaListener(topics = [KafkaTopics.Order.PLACED], groupId = "payment-service")
    suspend fun onOrderPlaced(record: ConsumerRecord<String, OrderPlacedEvent>) {
        // 멱등성 보장: eventId 기반 중복 처리 방지
        idempotencyExecutor.executeOnce(
            key = RedisKeys.Order.idempotencyKey("payment:${record.value().eventId}")
        ) {
            processPaymentUseCase.execute(
                ProcessPaymentCommand(
                    orderId = record.value().aggregateId,
                    userId = record.value().userId,
                    amount = record.value().totalAmount,
                    eventId = record.value().eventId,
                )
            )
        }
    }
}
```

**Adapter Out - Payment Gateway (시뮬레이션)**:
```kotlin
// adapter/out/external/SimulatedPaymentGatewayAdapter.kt
@Component
class SimulatedPaymentGatewayAdapter(
    private val timeouts: TimeoutProperties,
) : PaymentGatewayPort {
    // 시뮬레이션: 90% 확률로 성공, 10% 실패
}
```

**Adapter Out - R2DBC Persistence**:
```kotlin
// adapter/out/persistence/R2dbcPaymentAdapter.kt
@Component
class R2dbcPaymentAdapter(
    private val paymentRepository: PaymentRepository,
    private val timeouts: TimeoutProperties,
) : PaymentPersistencePort
```

**Adapter Out - Kafka Event Publisher**:
```kotlin
// adapter/out/kafka/KafkaPaymentEventAdapter.kt
@Component
class KafkaPaymentEventAdapter(
    private val eventPublisher: EventPublisher,
) : PaymentEventPort
```

### 4.5 DB Schema (Flyway Migration)

```sql
-- V1__create_payments_table.sql
CREATE TABLE payments (
    id              VARCHAR(26) PRIMARY KEY,  -- ULID
    order_id        VARCHAR(26) NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    amount          BIGINT NOT NULL CHECK (amount > 0),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_payments_order_id UNIQUE (order_id)
);

CREATE INDEX idx_payments_user_id ON payments (user_id);
CREATE INDEX idx_payments_status ON payments (status);
```

### 4.6 핵심 비즈니스 로직 (ProcessPaymentService)

```
ProcessPaymentService.execute(command):
  1. 중복 결제 확인: orderId로 이미 결제가 존재하는지 확인
  2. Payment 엔티티 생성 (status=PENDING)
  3. DB 저장 (status=PROCESSING)
  4. 외부 결제 API 호출 (withTimeout: 3s)
  5-a. 성공 → status=COMPLETED, Kafka: payment.completed 발행
  5-b. 실패 → status=FAILED, Kafka: payment.failed 발행
```

---

## 5. Notification Service 상세 설계

### 5.1 역할
- **SSE (Server-Sent Events)**: 클라이언트에 실시간 알림 전송
- **Kafka Consumer**: 여러 토픽의 이벤트를 수신하여 알림으로 변환
- **알림 채널**: SSE (실시간), 향후 Push/Email 확장 가능

### 5.2 Domain Model

```kotlin
// domain/Notification.kt
data class Notification(
    val id: String,           // ULID
    val userId: String,
    val type: NotificationType,
    val title: String,
    val message: String,
    val metadata: Map<String, String>,  // orderId, amount 등 추가 정보
    val createdAt: Instant,
)

// domain/NotificationType.kt
enum class NotificationType {
    ORDER_COMPLETED,    // 주문 완료
    ORDER_CANCELLED,    // 주문 취소
    PAYMENT_COMPLETED,  // 결제 완료
    PAYMENT_FAILED,     // 결제 실패
    QUEUE_TURN_ARRIVED, // 대기열 순번 도달
}

// domain/NotificationError.kt
sealed interface NotificationError {
    /** 해당 사용자의 SSE 연결이 없음 */
    data class UserNotConnected(val userId: String) : NotificationError

    /** SSE 전송 중 연결이 끊어짐 */
    data class ConnectionLost(val userId: String) : NotificationError
}
```

### 5.3 Port 설계

**Port In (UseCase)**:
```kotlin
// application/port/in/SendNotificationUseCase.kt
interface SendNotificationUseCase {
    suspend fun execute(command: SendNotificationCommand): Result<Unit, NotificationError>
}

data class SendNotificationCommand(
    val userId: String,
    val type: NotificationType,
    val title: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
)

// application/port/in/SubscribeNotificationUseCase.kt
interface SubscribeNotificationUseCase {
    /** SSE 스트림을 구독 (Flow 반환) */
    fun subscribe(userId: String): Flow<Notification>
}
```

**Port Out**:
```kotlin
// application/port/out/NotificationSenderPort.kt
interface NotificationSenderPort {
    /** SSE로 알림 전송 */
    suspend fun send(userId: String, notification: Notification): Boolean

    /** SSE 연결 등록 */
    fun register(userId: String): Flow<Notification>

    /** SSE 연결 해제 */
    fun unregister(userId: String)
}
```

### 5.4 Adapter 설계

**Adapter In - Kafka Consumers**:
```kotlin
// adapter/in/kafka/OrderEventConsumer.kt
@Component
class OrderEventConsumer(
    private val sendNotificationUseCase: SendNotificationUseCase,
    private val idempotencyExecutor: IdempotencyExecutor,
) {
    @KafkaListener(topics = [KafkaTopics.Order.COMPLETED])
    suspend fun onOrderCompleted(record: ConsumerRecord<String, OrderCompletedEvent>) { ... }

    @KafkaListener(topics = [KafkaTopics.Order.CANCELLED])
    suspend fun onOrderCancelled(record: ConsumerRecord<String, OrderCancelledEvent>) { ... }
}

// adapter/in/kafka/PaymentEventConsumer.kt
@Component
class PaymentEventConsumer(
    private val sendNotificationUseCase: SendNotificationUseCase,
    private val idempotencyExecutor: IdempotencyExecutor,
) {
    @KafkaListener(topics = [KafkaTopics.Payment.COMPLETED])
    suspend fun onPaymentCompleted(record: ConsumerRecord<String, PaymentCompletedEvent>) { ... }

    @KafkaListener(topics = [KafkaTopics.Payment.FAILED])
    suspend fun onPaymentFailed(record: ConsumerRecord<String, PaymentFailedEvent>) { ... }
}
```

**Adapter Out - SSE Notification Sender**:
```kotlin
// adapter/out/sse/SseNotificationAdapter.kt
@Component
class SseNotificationAdapter : NotificationSenderPort {
    // ConcurrentHashMap<userId, MutableSharedFlow<Notification>>
    // 사용자별 SSE 연결을 인메모리로 관리
    private val connections = ConcurrentHashMap<String, MutableSharedFlow<Notification>>()
}
```

**Adapter In - SSE Controller**:
```kotlin
// adapter/in/web/NotificationController.kt
@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val subscribeNotificationUseCase: SubscribeNotificationUseCase,
) {
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun subscribe(@RequestParam userId: String): Flow<ServerSentEvent<Notification>> {
        return subscribeNotificationUseCase.subscribe(userId)
            .map { notification ->
                ServerSentEvent.builder(notification)
                    .id(notification.id)
                    .event(notification.type.name)
                    .build()
            }
    }
}
```

### 5.5 API 엔드포인트

| Method | Path | Request | Response | 설명 |
|--------|------|---------|----------|------|
| GET | `/api/notifications/stream?userId={userId}` | - | SSE Stream | 실시간 알림 구독 |

### 5.6 SSE 연결 관리

```
- 사용자별 MutableSharedFlow를 인메모리 관리
- 연결 타임아웃: 5분 (TimeoutProperties.sseConnection)
- Heartbeat: 30초마다 ping 이벤트 전송 (연결 유지)
- 클라이언트 재연결 시 lastEventId 기반으로 놓친 이벤트 재전송 (선택사항)
```

---

## 6. 구현 우선순위 (Implementation Priority)

| 순서 | 서비스 | 이유 |
|------|--------|------|
| 1 | **order-service** | 핵심 비즈니스 로직 (재고 차감, 주문 생성, Kafka 발행) |
| 2 | **payment-service** | Saga 패턴, Kafka Consumer/Producer |
| 3 | **notification-service** | SSE, 여러 Kafka Consumer |
| 4 | **gateway** | Rate Limiting, API 라우팅 (인프라 성격) |

각 서비스는 다음 순서로 구현:
1. Domain (Entity, VO, Error)
2. Port Out → Port In
3. UseCase (비즈니스 로직)
4. Adapter Out (Redis/Kafka/DB)
5. Adapter In (Controller/Kafka Consumer)
6. Config
7. Unit Test → Integration Test

---

## 7. 비기능 요구사항 설계

### 7.1 동시성/정합성
- 재고 차감: Redis Lua Script (atomic, no race condition)
- 분산 락: Redisson (DistributedLockExecutor)
- 멱등성: IdempotencyExecutor (Redis SET NX + TTL 24h)
- 중복 주문 방지: DB UNIQUE 제약 (user_id + sale_event_id)

### 7.2 타임아웃
- Redis: 100ms (단순), 200ms (Lua Script)
- Kafka: 1s (produce)
- DB: 2s (query), 5s (transaction)
- 외부 결제 API: 3s
- SSE: 5분

### 7.3 에러 복구
- Kafka Consumer: 3회 재시도 후 DLQ 이동
- 결제 실패: Saga 보상 트랜잭션 (재고 복원 + 주문 취소)
- 서비스 장애: Circuit Breaker 패턴 (선택사항)

### 7.4 모니터링
- Prometheus 메트릭: 주문/결제 성공률, 재고 차감 시간, 대기열 길이
- Grafana 대시보드: 서비스별 RPS, 에러율, 응답시간

---

## Change Summary
- 전체 시스템 아키텍처 흐름도 (서비스 구성, 요청 흐름, 이벤트 흐름)
- Saga 보상 트랜잭션 설계 (Order-Payment)
- Gateway Service: Rate Limiting (Token Bucket Lua Script), API Routing
- Order Service: 재고 차감 (Lua Script), 주문 CRUD, Kafka 이벤트, DB 스키마
- Payment Service: 결제 처리, Saga 보상, Kafka Consumer/Producer, DB 스키마
- Notification Service: SSE 실시간 알림, 다중 Kafka Consumer
- 구현 우선순위 및 비기능 요구사항
