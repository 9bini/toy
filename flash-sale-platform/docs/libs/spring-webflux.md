# Spring WebFlux

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [Controller 작성법](#4-controller-작성법)
5. [WebClient (HTTP 클라이언트)](#5-webclient-http-클라이언트)
6. [SSE (Server-Sent Events)](#6-sse-server-sent-events)
7. [이 프로젝트에서의 활용](#7-이-프로젝트에서의-활용)
8. [자주 하는 실수 / 주의사항](#8-자주-하는-실수--주의사항)
9. [정리 / 한눈에 보기](#9-정리--한눈에-보기)
10. [더 알아보기](#10-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

Spring MVC의 **논블로킹 버전**. 소수의 스레드로 대량의 동시 요청을 처리하는 리액티브 웹 프레임워크.

### 비유: 식당 운영 방식

**Spring MVC (전통 식당)**:
- 종업원(스레드) 1명이 손님 1명 전담
- 손님 200명 = 종업원 200명 필요
- 종업원이 주방에서 기다리는 동안 다른 손님 응대 못함

**Spring WebFlux (스마트 식당)**:
- 종업원(스레드) 8명이 손님 전부 응대
- 주문 넣고 → 다른 손님 → 요리 나오면 서빙
- 손님 10,000명도 8명으로 처리 가능

---

## 2. 왜 필요한가?

### 상세 비교

| | Spring MVC | Spring WebFlux |
|---|---|---|
| 서버 엔진 | **Tomcat** (서블릿) | **Netty** (이벤트 루프) |
| I/O 모델 | 블로킹 (요청당 스레드 1개) | 논블로킹 (이벤트 기반) |
| 기본 스레드 수 | 200 | **CPU 코어 수 (~8)** |
| 동시 10만 접속 | 불가능 (스레드 부족) | **가능** |
| 프로그래밍 | 동기식 (직관적) | 리액티브/코루틴 |
| DB 드라이버 | JDBC (블로킹) | R2DBC (논블로킹) |
| HTTP 클라이언트 | RestTemplate | WebClient |

### 이 프로젝트에서 WebFlux를 선택한 이유

1. **10만 동시 접속**: 선착순 한정판매 → 순간적으로 대량 트래픽
2. **SSE 지원**: 대기열 실시간 알림에 장시간 연결 필요
3. **리소스 효율**: 8개 스레드로 수만 요청 처리 → 서버 비용 절감

---

## 3. 핵심 개념

### 3.1 Netty (서버 엔진)

Tomcat과 달리, **이벤트 루프** 기반으로 동작한다.

```
Tomcat (스레드 풀):
  요청 1 → [스레드 1: 처리 중... DB 대기... 응답]
  요청 2 → [스레드 2: 처리 중... DB 대기... 응답]
  요청 3 → [스레드 3: 처리 중... DB 대기... 응답]
  (스레드가 DB 대기하는 동안 아무것도 못함)

Netty (이벤트 루프):
  이벤트 루프 스레드:
    요청 1 수신 → DB 쿼리 시작 → (반납)
    요청 2 수신 → DB 쿼리 시작 → (반납)
    DB 응답 1 도착 → 요청 1 응답 전송
    요청 3 수신 → ...
    DB 응답 2 도착 → 요청 2 응답 전송
  (1개 스레드가 여러 요청을 번갈아 처리)
```

### 3.2 Reactor (Mono / Flux)

WebFlux의 기반 라이브러리. 비동기 데이터 스트림을 다루는 타입.

**Mono\<T\>**: 0 또는 1개의 값

```kotlin
// DB에서 주문 1건 조회 → 결과가 0 또는 1개
val order: Mono<Order> = orderRepository.findById(id)
```

**Flux\<T\>**: 0 ~ N개의 값 (스트림)

```kotlin
// DB에서 주문 목록 조회 → 결과가 N개
val orders: Flux<Order> = orderRepository.findByUserId(userId)
```

### 코루틴과의 관계

이 프로젝트에서는 Reactor를 직접 쓰지 않고, **Kotlin 코루틴으로 변환**하여 사용한다.

```kotlin
// ❌ Reactor 직접 사용 (콜백 지옥 느낌)
orderRepository.findById(id)
    .flatMap { order ->
        paymentRepository.findByOrderId(order.id)
            .map { payment -> OrderDetail(order, payment) }
    }
    .subscribe()

// ✅ 코루틴으로 변환 (동기 코드처럼 읽기 쉬움)
val order = orderRepository.findById(id).awaitSingle()
val payment = paymentRepository.findByOrderId(order.id).awaitSingle()
return OrderDetail(order, payment)
```

### 3.3 Reactor 주요 연산자

```kotlin
// map: 값 변환
mono.map { order -> order.toResponse() }

// flatMap: 비동기 변환 (Mono를 반환하는 경우)
mono.flatMap { order -> paymentRepo.findByOrderId(order.id) }

// filter: 조건 필터링
flux.filter { order -> order.status == "PENDING" }

// switchIfEmpty: 값이 없을 때 대체
mono.switchIfEmpty(Mono.error(NotFoundException()))

// onErrorResume: 에러 시 대체 값
mono.onErrorResume { Mono.just(defaultValue) }
```

---

## 4. Controller 작성법

### suspend fun 사용 (권장)

```kotlin
@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val placeOrderUseCase: PlaceOrderUseCase,
) {
    // suspend fun → WebFlux가 자동으로 코루틴 실행
    @PostMapping
    suspend fun placeOrder(
        @RequestBody request: OrderRequest
    ): ResponseEntity<OrderResponse> {
        val result = placeOrderUseCase.execute(request)
        return result.fold(
            onSuccess = { ResponseEntity.ok(it.toResponse()) },
            onFailure = { ResponseEntity.badRequest().build() }
        )
    }

    // suspend + nullable → Mono처럼 0 or 1
    @GetMapping("/{id}")
    suspend fun getOrder(@PathVariable id: String): ResponseEntity<OrderResponse> {
        val order = orderService.findById(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(order.toResponse())
    }
}
```

### Flow 반환 (스트림)

```kotlin
@GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun streamOrders(): Flow<OrderEvent> = flow {
    // Flow를 반환 → WebFlux가 자동으로 SSE 스트림 변환
    while (true) {
        emit(getLatestEvent())
        delay(1.seconds)
    }
}
```

---

## 5. WebClient (HTTP 클라이언트)

RestTemplate의 논블로킹 대체. 다른 서비스를 HTTP로 호출할 때 사용.

```kotlin
@Component
class PaymentWebClient(
    private val webClient: WebClient,
) {
    suspend fun requestPayment(request: PaymentRequest): PaymentResponse {
        return webClient
            .post()
            .uri("/api/payments")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(PaymentResponse::class.java)
            .awaitSingle()    // Mono → suspend 변환
    }
}
```

---

## 6. SSE (Server-Sent Events)

서버가 클라이언트에 **단방향으로 실시간 이벤트**를 보내는 기술.

```
HTTP (일반):      요청 → 응답 → 연결 종료
WebSocket:        양방향 실시간 통신
SSE:              서버 → 클라이언트 단방향 실시간  ← 이 프로젝트

SSE가 적합한 경우: 대기열 순위 알림 (서버→클라이언트 단방향)
```

```kotlin
@GetMapping(
    "/queue/events",
    produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
)
fun queueEvents(
    @RequestParam saleEventId: String,
    @RequestParam userId: String,
): Flow<ServerSentEvent<String>> = flow {
    while (true) {
        val position = queueService.getPosition(saleEventId, userId)
        emit(
            ServerSentEvent.builder<String>()
                .event("queue-update")
                .data("""{"position": $position}""")
                .build()
        )
        delay(2.seconds)
    }
}
```

---

## 7. 이 프로젝트에서의 활용

### 의존성

```kotlin
// common/infrastructure/build.gradle.kts
api("org.springframework.boot:spring-boot-starter-webflux")
```

모든 서비스가 `common:infrastructure`를 의존 → 전부 WebFlux 사용.

### WebFilter (요청 필터)

모든 요청에 공통 처리 (로깅, 인증 등).

```kotlin
@Component
class RequestLoggingFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val start = System.currentTimeMillis()
        return chain.filter(exchange).doFinally {
            val elapsed = System.currentTimeMillis() - start
            logger.info { "${exchange.request.method} ${exchange.request.path} ${elapsed}ms" }
        }
    }
}
```

---

## 8. 자주 하는 실수 / 주의사항

### 블로킹 코드 사용

```kotlin
// ❌ Thread.sleep → 이벤트 루프 스레드를 블로킹
Thread.sleep(1000)
// ✅ delay
delay(1000)

// ❌ JDBC (블로킹)
val result = jdbcTemplate.queryForObject(...)
// ✅ R2DBC (논블로킹)
val result = r2dbcRepository.findById(id).awaitSingle()

// ❌ RestTemplate (블로킹)
val response = restTemplate.getForObject(url, String::class.java)
// ✅ WebClient (논블로킹)
val response = webClient.get().uri(url).retrieve().bodyToMono<String>().awaitSingle()
```

### Reactor + 코루틴 혼용 주의

```kotlin
// ❌ subscribe() 호출 → 구조적 동시성 깨짐
mono.subscribe { println(it) }

// ✅ await로 변환
val result = mono.awaitSingle()
println(result)
```

---

## 9. 정리 / 한눈에 보기

### MVC vs WebFlux 최종 비교

| 항목 | Spring MVC | Spring WebFlux |
|------|-----------|----------------|
| 서버 | Tomcat | Netty |
| 스레드 | 200 (요청당 1) | ~8 (이벤트 루프) |
| I/O | 블로킹 | 논블로킹 |
| DB | JDBC | R2DBC |
| HTTP 클라이언트 | RestTemplate | WebClient |
| 반환 타입 | `T`, `List<T>` | `Mono<T>`, `Flux<T>` / `suspend`, `Flow` |
| 적합한 경우 | 일반 웹 앱 | **대량 동시 접속, SSE** |

### Reactor ↔ 코루틴 변환 치트시트

| Reactor | 코루틴 | 변환 |
|---------|--------|------|
| `Mono<T>` | `suspend fun(): T` | `.awaitSingle()` |
| `Mono<T?>` | `suspend fun(): T?` | `.awaitSingleOrNull()` |
| `Flux<T>` | `Flow<T>` | `.asFlow()` |
| `Mono<Void>` | `suspend fun()` | `.awaitFirstOrNull()` |

---

## 10. 더 알아보기

- [Spring WebFlux 공식 문서](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [Project Reactor 공식 문서](https://projectreactor.io/docs/core/release/reference/)
