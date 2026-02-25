# 2. Spring WebFlux (리액티브 웹 프레임워크)

> **한 줄 요약**: 스레드를 블로킹하지 않고 요청을 처리하는 Spring의 비동기 웹 프레임워크

---

## Spring MVC vs Spring WebFlux

### Spring MVC (전통적 방식)

```
요청 1 → [스레드 1] → DB 조회(대기 50ms) → 응답
요청 2 → [스레드 2] → DB 조회(대기 50ms) → 응답
요청 3 → [스레드 3] → DB 조회(대기 50ms) → 응답
...
요청 200 → [스레드 200] → 스레드 풀 고갈! → 나머지 요청 대기
```

- 요청 1개 = 스레드 1개 점유
- 기본 스레드 풀: 200개 → 200개 동시 요청까지만 처리
- I/O 대기 중에도 스레드를 점유 → **낭비**

### Spring WebFlux (리액티브 방식)

```
요청 1 → [스레드 1] → DB 요청 보냄 → 스레드 반납 → (다른 요청 처리)
요청 2 → [스레드 1] → DB 요청 보냄 → 스레드 반납 → (다른 요청 처리)
                     ...DB 응답 도착...
         [스레드 1] → 요청 1 응답 생성 → 전송
         [스레드 1] → 요청 2 응답 생성 → 전송
```

- CPU 코어 수만큼의 스레드로 수만 개의 동시 요청 처리 가능
- I/O 대기 중 스레드를 반납 → **효율적**

---

## 왜 이 프로젝트에서 WebFlux를 쓰는가?

**10만 동시 접속** 환경에서:
- Spring MVC: 10만 개 스레드 필요 → 불가능
- Spring WebFlux: ~16개 스레드 (CPU 코어 수)로 10만 요청 처리 → 가능

---

## 핵심 개념

### 1. Reactor의 Mono와 Flux

WebFlux의 기반인 Project Reactor는 두 가지 핵심 타입을 제공합니다.

```kotlin
// Mono: 0 또는 1개의 값 (단일 응답)
val mono: Mono<Order> = orderRepository.findById(orderId)

// Flux: 0~N개의 값 (스트림/컬렉션)
val flux: Flux<Order> = orderRepository.findAll()
```

### 2. Kotlin Coroutines과의 통합

Kotlin에서는 Reactor의 Mono/Flux 대신 **코루틴**을 직접 사용할 수 있습니다.
`kotlinx-coroutines-reactor` 라이브러리가 둘을 연결해줍니다.

```kotlin
// Reactor 방식 (Java 스타일 - 복잡)
fun getOrder(orderId: String): Mono<Order> {
    return orderRepository.findById(orderId)
        .flatMap { order ->
            paymentRepository.findById(order.paymentId)
                .map { payment -> order.copy(payment = payment) }
        }
}

// Kotlin 코루틴 방식 (이 프로젝트 스타일 - 직관적)
suspend fun getOrder(orderId: String): Order {
    val order = orderRepository.findById(orderId) // awaitSingle 내부 호출
    val payment = paymentRepository.findById(order.paymentId)
    return order.copy(payment = payment)
}
```

### 3. WebFlux Controller

```kotlin
@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val placeOrderUseCase: PlaceOrderUseCase,
) {
    // suspend fun으로 선언하면 WebFlux가 코루틴으로 실행
    @PostMapping
    suspend fun placeOrder(@RequestBody request: OrderRequest): ResponseEntity<OrderResponse> {
        val result = placeOrderUseCase.execute(request)
        return result.fold(
            onSuccess = { ResponseEntity.ok(it.toResponse()) },
            onFailure = { ResponseEntity.badRequest().body(it.toErrorResponse()) }
        )
    }
}
```

### 4. SSE (Server-Sent Events)

WebFlux는 서버에서 클라이언트로 실시간 이벤트를 보내는 SSE를 자연스럽게 지원합니다.

```kotlin
@GetMapping("/api/queue/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun streamQueueEvents(
    @RequestParam saleEventId: String,
    @RequestParam userId: String,
): Flow<ServerSentEvent<QueueEvent>> = flow {
    // Flow: Kotlin 코루틴의 비동기 스트림
    while (true) {
        val position = queueService.getPosition(saleEventId, userId)
        emit(ServerSentEvent.builder(QueueEvent(position)).build())
        delay(1.seconds)
    }
}
```

---

## 이 프로젝트에서의 활용

### Reactive Redis

```kotlin
// ReactiveStringRedisTemplate: 논블로킹 Redis 클라이언트
@Component
class RedisStockAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) {
    // awaitSingleOrNull(): Mono를 코루틴의 suspend로 변환
    suspend fun getRemaining(productId: String): Int =
        redisTemplate.opsForValue()
            .get(RedisKeys.Stock.remaining(productId))
            .awaitSingleOrNull()
            ?.toInt() ?: 0
}
```

### Reactive Kafka

```kotlin
// KafkaTemplate: 논블로킹 Kafka Producer
@Component
class KafkaOrderEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    suspend fun publishOrderPlaced(event: OrderPlacedEvent) {
        kafkaTemplate
            .send(KafkaTopics.Order.PLACED, event.orderId, event.toJson())
            .asDeferred()  // ListenableFuture → Deferred (코루틴)
            .await()
    }
}
```

### Reactive R2DBC

```kotlin
// R2DBC: 논블로킹 DB 드라이버
interface OrderRepository : ReactiveCrudRepository<OrderEntity, String> {
    // Spring Data R2DBC가 구현 자동 생성
    fun findByUserId(userId: String): Flux<OrderEntity>
}

// 코루틴에서 사용 시
suspend fun findOrders(userId: String): List<Order> =
    orderRepository.findByUserId(userId)
        .asFlow()           // Flux → Flow (코루틴 스트림)
        .map { it.toDomain() }
        .toList()           // Flow → List
```

---

## Reactor ↔ Coroutines 변환 치트시트

| Reactor | Coroutines | 설명 |
|---------|-----------|------|
| `Mono<T>` | `suspend fun(): T` | 단일 값 |
| `Flux<T>` | `Flow<T>` | 값의 스트림 |
| `mono.awaitSingle()` | - | Mono → 코루틴 값 (null 불가) |
| `mono.awaitSingleOrNull()` | - | Mono → 코루틴 값 (null 가능) |
| `flux.asFlow()` | - | Flux → Flow |
| `flow.asFlux()` | - | Flow → Flux |
| `mono { ... }` | - | 코루틴 블록 → Mono |
| `flux { ... }` | - | 코루틴 블록 → Flux |

---

## Spring MVC에서 넘어올 때 주의점

| Spring MVC | Spring WebFlux | 이유 |
|-----------|---------------|------|
| `@Controller` | `@RestController` + `suspend fun` | 코루틴 지원 |
| JDBC | R2DBC | 논블로킹 DB 드라이버 |
| `RestTemplate` | `WebClient` | 논블로킹 HTTP 클라이언트 |
| `Thread.sleep()` | `delay()` | 스레드 블로킹 방지 |
| `HttpServletRequest` | `ServerWebExchange` | 리액티브 요청 객체 |
| Tomcat (기본) | Netty (기본) | 논블로킹 서버 엔진 |
| Servlet Filter | `WebFilter` | 리액티브 필터 |

---

## 더 알아보기

- **Spring WebFlux 공식 문서**: [Spring Framework - WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- **Project Reactor 공식**: [projectreactor.io](https://projectreactor.io/)
- **이 프로젝트 의존성**: `spring-boot-starter-webflux` (common/infrastructure에서 관리)
