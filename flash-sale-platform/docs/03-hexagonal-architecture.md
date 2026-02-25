# 3. Hexagonal Architecture (헥사고날 아키텍처)

> **한 줄 요약**: 비즈니스 로직을 기술적 세부사항(DB, Redis, Kafka)으로부터 격리하는 설계 패턴

---

## 왜 필요한가?

### 흔한 문제: 비즈니스 로직과 기술이 뒤섞임

```kotlin
// ❌ 모든 것이 한 곳에 섞여 있는 코드
@Service
class OrderService(
    private val redisTemplate: ReactiveStringRedisTemplate, // Redis 종속
    private val kafkaTemplate: KafkaTemplate<String, String>, // Kafka 종속
    private val r2dbcEntityTemplate: R2dbcEntityTemplate, // DB 종속
) {
    suspend fun placeOrder(request: OrderRequest): Order {
        // Redis Lua Script 직접 호출
        val script = "if redis.call('get', KEYS[1]) >= ARGV[1] then ..."
        redisTemplate.execute(RedisScript.of(script)).awaitSingle()

        // DB 직접 조작
        r2dbcEntityTemplate.insert(OrderEntity(...)).awaitSingle()

        // Kafka 직접 발행
        kafkaTemplate.send("flashsale.order.placed", ...).asDeferred().await()

        return order
    }
}
```

**문제점**:
- Redis를 Memcached로 바꾸면? → 비즈니스 로직 전체를 수정해야 함
- 테스트하려면? → Redis, Kafka, DB 전부 필요
- 비즈니스 로직이 어디있는지? → 기술 코드에 묻혀서 안 보임

### 헥사고날 아키텍처의 해결

```
        ┌──────────────────────────────────────┐
        │            Adapter (In)              │
        │      Controller (WebFlux)            │
        └──────────────┬───────────────────────┘
                       │ 호출
        ┌──────────────▼───────────────────────┐
        │           Port (In)                  │
        │      UseCase Interface               │
        └──────────────┬───────────────────────┘
                       │ 구현
        ┌──────────────▼───────────────────────┐
        │         Application                  │
        │      UseCase Implementation          │
        │      (순수 비즈니스 로직)              │
        └──────────────┬───────────────────────┘
                       │ 의존
        ┌──────────────▼───────────────────────┐
        │          Port (Out)                  │
        │      Output Port Interface           │
        └──────────────┬───────────────────────┘
                       │ 구현
        ┌──────────────▼───────────────────────┐
        │          Adapter (Out)               │
        │   Redis / Kafka / DB 구현체           │
        └──────────────────────────────────────┘
```

---

## 핵심 개념

### 1. Port (포트) = 인터페이스

비즈니스 로직이 외부와 소통하는 **계약**입니다. 기술 세부사항을 노출하지 않습니다.

```kotlin
// Port In (들어오는 요청을 정의)
interface PlaceOrderUseCase {
    suspend fun execute(command: PlaceOrderCommand): Result<Order, OrderError>
}

// Port Out (나가는 요청을 정의 - "무엇을"만 정의, "어떻게"는 숨김)
interface StockPort {
    suspend fun getRemaining(productId: String): Int
    suspend fun decrement(productId: String, quantity: Int): Boolean
}

interface OrderEventPublisher {
    suspend fun publishOrderPlaced(event: OrderPlacedEvent)
}
```

**핵심**: 포트에는 Redis, Kafka, DB 같은 기술 용어가 등장하지 않습니다.

### 2. Adapter (어댑터) = 구현체

포트 인터페이스를 **특정 기술**로 구현한 클래스입니다.

```kotlin
// Adapter Out: Redis로 재고 관리 구현
@Component
class RedisStockAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) : StockPort {
    override suspend fun getRemaining(productId: String): Int =
        redisTemplate.opsForValue()
            .get(RedisKeys.Stock.remaining(productId))
            .awaitSingleOrNull()?.toInt() ?: 0

    override suspend fun decrement(productId: String, quantity: Int): Boolean {
        // Redis Lua Script으로 원자적 차감
        // ...
    }
}

// Adapter Out: Kafka로 이벤트 발행 구현
@Component
class KafkaOrderEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : OrderEventPublisher {
    override suspend fun publishOrderPlaced(event: OrderPlacedEvent) {
        kafkaTemplate.send(KafkaTopics.Order.PLACED, event.orderId, event.toJson())
            .asDeferred().await()
    }
}
```

**명명 규칙**: 어댑터 클래스명에 기술 스택을 포함 → `Redis`StockAdapter, `Kafka`OrderEventPublisher

### 3. UseCase (유스케이스) = 비즈니스 로직

```kotlin
// 순수 비즈니스 로직 - 기술 종속성 없음
@Component
class PlaceOrderUseCaseImpl(
    private val stockPort: StockPort,          // 인터페이스만 의존
    private val orderEventPublisher: OrderEventPublisher, // 인터페이스만 의존
) : PlaceOrderUseCase {

    override suspend fun execute(command: PlaceOrderCommand): Result<Order, OrderError> {
        // 1. 재고 확인 (Redis인지 DB인지 모름 - 알 필요도 없음)
        val remaining = stockPort.getRemaining(command.productId)
        if (remaining < command.quantity) {
            return Result.failure(OrderError.InsufficientStock(remaining, command.quantity))
        }

        // 2. 재고 차감
        val decremented = stockPort.decrement(command.productId, command.quantity)
        if (!decremented) {
            return Result.failure(OrderError.StockDecrementFailed)
        }

        // 3. 이벤트 발행 (Kafka인지 RabbitMQ인지 모름)
        val order = Order.create(command)
        orderEventPublisher.publishOrderPlaced(OrderPlacedEvent(order))

        return Result.success(order)
    }
}
```

---

## 이 프로젝트의 패키지 구조

```
com.flashsale.{service-name}/
├── adapter/
│   ├── in/web/          # Adapter In: Controller (WebFlux)
│   │   └── OrderController.kt
│   └── out/             # Adapter Out: 기술 구현체
│       ├── RedisStockAdapter.kt
│       ├── KafkaOrderEventPublisher.kt
│       └── R2dbcOrderRepository.kt
├── application/
│   ├── port/in/         # Port In: UseCase 인터페이스
│   │   └── PlaceOrderUseCase.kt
│   ├── port/out/        # Port Out: Output Port 인터페이스
│   │   ├── StockPort.kt
│   │   └── OrderEventPublisher.kt
│   └── service/         # UseCase 구현체
│       └── PlaceOrderUseCaseImpl.kt
├── domain/              # 도메인 모델 (외부 의존성 없음)
│   ├── Order.kt
│   ├── OrderStatus.kt
│   └── OrderError.kt
└── config/              # Spring 설정
    └── OrderServiceConfig.kt
```

### 의존성 방향 (가장 중요한 규칙!)

```
Adapter In → Port In → UseCase → Port Out ← Adapter Out
                          ↓
                       Domain
```

- **Domain**은 아무것도 의존하지 않음 (순수 Kotlin)
- **UseCase**는 Port(인터페이스)만 의존
- **Adapter**는 Port를 구현하지만, UseCase는 Adapter를 모름
- 화살표 방향: 항상 안쪽(Domain)을 향함

---

## 이 아키텍처의 이점

### 1. 테스트가 쉬워진다

```kotlin
// UseCase 테스트: Redis나 Kafka 없이도 테스트 가능
class PlaceOrderUseCaseTest : FunSpec({
    val stockPort = mockk<StockPort>()
    val eventPublisher = mockk<OrderEventPublisher>()
    val useCase = PlaceOrderUseCaseImpl(stockPort, eventPublisher)

    test("재고 부족 시 InsufficientStock 에러 반환") {
        // 가짜(Mock) 포트로 테스트
        coEvery { stockPort.getRemaining("product-1") } returns 0

        val result = useCase.execute(PlaceOrderCommand("product-1", 1))

        result.isFailure shouldBe true
    }
})
```

### 2. 기술 교체가 쉬워진다

```kotlin
// Redis → Memcached로 교체 시: Adapter만 새로 만들면 됨
@Component
class MemcachedStockAdapter : StockPort {
    override suspend fun getRemaining(productId: String): Int { /* Memcached 구현 */ }
    override suspend fun decrement(productId: String, quantity: Int): Boolean { /* ... */ }
}
// UseCase 코드는 한 줄도 바꿀 필요 없음!
```

### 3. 비즈니스 로직이 명확하게 보인다

UseCase 코드만 읽으면 비즈니스 흐름이 한눈에 보입니다.
"재고 확인 → 재고 차감 → 이벤트 발행" — Redis나 Kafka에 대한 지식 없이도 이해 가능.

---

## 자주 하는 실수

### 1. Port에 기술 세부사항 노출

```kotlin
// ❌ Redis를 포트에 노출
interface StockPort {
    suspend fun executeRedisLuaScript(script: String): Boolean
}

// ✅ 비즈니스 의도만 표현
interface StockPort {
    suspend fun decrement(productId: String, quantity: Int): Boolean
}
```

### 2. UseCase에서 직접 기술 라이브러리 사용

```kotlin
// ❌ UseCase에서 Redis 직접 사용
class PlaceOrderUseCaseImpl(
    private val redisTemplate: ReactiveStringRedisTemplate, // 직접 의존!
)

// ✅ Port를 통해서만 접근
class PlaceOrderUseCaseImpl(
    private val stockPort: StockPort, // 인터페이스만 의존
)
```

### 3. Domain에 프레임워크 어노테이션

```kotlin
// ❌ Domain 엔티티에 Spring/JPA 어노테이션
@Entity
@Table(name = "orders")
data class Order(
    @Id val id: String,
)

// ✅ Domain은 순수 Kotlin (어노테이션은 Adapter 레이어에서)
data class Order(
    val id: String,
    val status: OrderStatus,
)
```

---

## 더 알아보기

- **원저**: Alistair Cockburn의 "Hexagonal Architecture" (Ports and Adapters)
- **이 프로젝트 관련**: `CLAUDE.md`의 "패키지 구조" 및 "구현 순서 가이드" 참고
- **구현 순서**: Domain → Port Out → Port In → UseCase → Adapter Out → Adapter In → Config → Test
